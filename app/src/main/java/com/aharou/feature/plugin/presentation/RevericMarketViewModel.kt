package com.aharou.feature.plugin.presentation

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.R
import com.aharou.feature.plugin.domain.market.RevericCatalog
import com.aharou.feature.plugin.domain.market.RevericEntry
import com.aharou.feature.plugin.domain.market.RevericFailure
import com.aharou.feature.plugin.domain.market.RevericMarketException
import com.aharou.feature.plugin.domain.market.RevericMarketRepository
import com.aharou.feature.plugin.domain.market.RevericVersion
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class RevericMarketState(
    val entries: List<RevericEntry> = emptyList(), val loaded: Boolean = false,
    val loadAttempted: Boolean = false,
    val query: String = "", val loading: Boolean = false, val busy: Boolean = false,
    val choosing: Boolean = false, val notice: Int? = null, val requestToken: String? = null,
)

@HiltViewModel
internal class RevericMarketViewModel @Inject constructor(
    private val repository: RevericMarketRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private data class Pending(
        val token: String, val phase: String, val pluginId: String, val version: String,
        val downloadUrl: String, val size: Long, val sha256: String,
    ) {
        fun fields() = arrayListOf(token, phase, pluginId, version, downloadUrl, size.toString(), sha256)
    }
    private val hadSavedRequest = savedStateHandle.contains(PENDING_KEY)
    private val restored = runCatching {
        savedStateHandle.get<ArrayList<String>>(PENDING_KEY)?.let(::decode)
    }.getOrNull()
    private var pending = restored?.takeIf { it.phase == "choosing" }
    private var activeToken = pending?.token
    private var needsCatalogRefresh = pending != null
    private val mutable = MutableStateFlow(RevericMarketState(
        busy = pending != null, choosing = pending != null, requestToken = pending?.token,
        notice = if (hadSavedRequest && pending == null) R.string.reveric_selection_lost else null,
    ))
    val state = mutable.asStateFlow()
    private var operation: Job? = null

    init { if (pending == null) savedStateHandle.remove<ArrayList<String>>(PENDING_KEY) }

    fun query(value: String) { mutable.update { it.copy(query = value.take(200)) } }

    fun load() {
        if (mutable.value.loading || mutable.value.busy) return
        mutable.update { it.copy(loading = true, loadAttempted = true,
            notice = it.notice.takeIf { notice -> notice == R.string.reveric_selection_lost }) }
        viewModelScope.launch {
            try {
                val entries = repository.load()
                mutable.update { it.copy(entries = entries, loaded = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                coroutineContext.ensureActive()
                mutable.update { it.copy(notice = notice(failure)) }
            } finally { mutable.update { it.copy(loading = false) } }
        }
    }

    suspend fun loadMagpie(bundled: Boolean) = repository.loadMagpie(bundled)

    fun chooseDownload(entry: RevericEntry, version: RevericVersion): String? {
        val current = mutable.value
        if (current.busy || current.loading || entry !in current.entries || version !in entry.versions ||
            !RevericCatalog.canDownload(version)) return null
        val request = Pending(UUID.randomUUID().toString(), "choosing", entry.id, version.version,
            version.url, version.size, version.sha256)
        if (runCatching { decode(request.fields()) }.isFailure) {
            mutable.update { it.copy(notice = R.string.reveric_selection_lost) }
            return null
        }
        needsCatalogRefresh = false
        pending = request
        activeToken = request.token
        persist(request)
        mutable.update { it.copy(busy = true, choosing = true, notice = null, requestToken = request.token) }
        return "${entry.id}-${version.version}".replace(Regex("[^A-Za-z0-9._-]"), "_").take(100) + ".zip"
    }

    fun documentChosen(token: String?, uri: Uri?) {
        val request = pending
        if (token == null || request == null || token != request.token || token != activeToken) {
            if (!mutable.value.busy) mutable.update { it.copy(notice = R.string.reveric_selection_lost) }
            return
        }
        pending = null
        if (uri == null) {
            finish(token, R.string.reveric_cancelled)
            return
        }
        val refreshCatalog = needsCatalogRefresh
        persist(request.copy(phase = "downloading"))
        mutable.update { it.copy(choosing = false) }
        operation = viewModelScope.launch {
            var result = R.string.reveric_failed
            try {
                val entries = if (refreshCatalog) {
                    mutable.update { it.copy(loadAttempted = true) }
                    try { repository.load() } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        result = R.string.reveric_selection_lost
                        return@launch
                    }
                } else mutable.value.entries
                coroutineContext.ensureActive()
                if (token != activeToken) return@launch
                if (refreshCatalog) mutable.update { it.copy(entries = entries, loaded = true) }
                val current = entries.singleOrNull { it.id == request.pluginId }?.versions
                    ?.singleOrNull { it.version == request.version }
                if (current == null || current.url != request.downloadUrl || current.size != request.size ||
                    current.sha256 != request.sha256 || !RevericCatalog.canDownload(current)) {
                    result = R.string.reveric_selection_lost
                    return@launch
                }
                val saved = repository.save(current, uri)
                coroutineContext.ensureActive()
                if (saved) result = R.string.reveric_saved
            } catch (cancelled: CancellationException) {
                result = R.string.reveric_cancelled
                throw cancelled
            } catch (failure: Exception) {
                coroutineContext.ensureActive()
                result = notice(failure)
            } finally { finish(token, result) }
        }
    }

    fun cancel() {
        if (mutable.value.choosing) return
        val token = activeToken ?: return
        val previous = operation
        finish(token, R.string.reveric_cancelled)
        previous?.cancel()
    }

    private fun finish(token: String, result: Int) {
        if (token != activeToken) return
        activeToken = null
        pending = null
        operation = null
        savedStateHandle.remove<ArrayList<String>>(PENDING_KEY)
        mutable.update { it.copy(busy = false, choosing = false, requestToken = null, notice = result) }
    }

    private fun persist(request: Pending) {
        savedStateHandle[PENDING_KEY] = request.fields()
    }

    private fun decode(values: ArrayList<String>): Pending {
        val limits = listOf(36, 11, 128, 128, 2048, 8, 64)
        require(values.size == limits.size && values.zip(limits).all { (value, limit) ->
            value.isNotBlank() && value.length <= limit
        })
        require(values[0].length == 36)
        UUID.fromString(values[0])
        require(values[1] == "choosing" || values[1] == "downloading")
        val size = values[5].toLong()
        require(size in 1..RevericCatalog.MAX_DOWNLOAD && Regex("[a-fA-F0-9]{64}").matches(values[6]))
        return Pending(values[0], values[1], values[2], values[3], values[4], size, values[6])
    }

    private fun notice(failure: Exception): Int = when ((failure as? RevericMarketException)?.reason) {
        RevericFailure.UNKNOWN_SCHEMA -> R.string.reveric_unknown_schema
        RevericFailure.INVALID_CATALOG -> R.string.reveric_invalid_catalog
        RevericFailure.TOO_LARGE -> R.string.reveric_too_large
        RevericFailure.SIZE_MISMATCH -> R.string.reveric_size_mismatch
        RevericFailure.HASH_MISMATCH -> R.string.reveric_hash_mismatch
        RevericFailure.UNSAFE_URL -> R.string.reveric_unsafe_url
        RevericFailure.REDIRECT -> R.string.reveric_redirect
        RevericFailure.DOCUMENT -> R.string.reveric_document_failed
        else -> R.string.reveric_failed
    }

    companion object { internal const val PENDING_KEY = "reveric.pendingDownload" }
}
