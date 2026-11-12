package com.aharou.feature.plugin.presentation

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.R
import com.aharou.feature.plugin.domain.market.OperitAsset
import com.aharou.feature.plugin.domain.market.OperitCatalog
import com.aharou.feature.plugin.domain.market.OperitEntry
import com.aharou.feature.plugin.domain.market.OperitMarketRepository
import com.aharou.feature.plugin.domain.market.OperitPage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class OperitMarketState(
    val type: String = "all", val query: String = "", val page: Int = 1,
    val result: OperitPage? = null, val loading: Boolean = false,
    val busy: Boolean = false, val choosing: Boolean = false,
    @param:StringRes val notice: Int? = null,
)

@HiltViewModel
internal class OperitMarketViewModel @Inject constructor(
    private val repository: OperitMarketRepository,
) : ViewModel() {
    private val mutable = MutableStateFlow(OperitMarketState())
    val state = mutable.asStateFlow()
    private var loadJob: Job? = null
    private var operation: Job? = null
    private var generation = 0L
    private var pending: Pair<OperitEntry, OperitAsset?>? = null

    init { load() }

    fun query(value: String) { mutable.update { it.copy(query = value) } }
    fun type(value: String) {
        if (mutable.value.busy || value !in OperitCatalog.types) return
        mutable.update { it.copy(type = value, page = 1, result = null) }
        load()
    }
    fun page(offset: Int) {
        val state = mutable.value
        if (state.busy || state.loading || (offset > 0 && state.result?.hasNext != true)) return
        mutable.update { it.copy(page = (it.page + offset).coerceAtLeast(1)) }
        load()
    }
    fun load() {
        val ticket = ++generation
        loadJob?.cancel()
        val request = mutable.value
        mutable.update { it.copy(loading = true, result = null, notice = null) }
        loadJob = viewModelScope.launch {
            try {
                val result = repository.page(request.type, request.page)
                if (ticket == generation) mutable.update { it.copy(result = result) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                coroutineContext.ensureActive()
                if (ticket == generation) mutable.update { it.copy(notice = R.string.operit_load_failed) }
            } finally {
                if (ticket == generation) mutable.update { it.copy(loading = false) }
            }
        }
    }

    fun canDownload(entry: OperitEntry) = repository.canDownload(entry)
    fun canImport(entry: OperitEntry, asset: OperitAsset?) = repository.canImport(entry, asset)

    fun chooseDownload(entry: OperitEntry, asset: OperitAsset?): String? {
        if (mutable.value.busy || !canDownload(entry)) return null
        pending = entry to asset
        mutable.update { it.copy(busy = true, choosing = true, notice = null) }
        return OperitMarketRepository.safeName(asset?.assetName ?: "${entry.title}-source.zip")
    }

    fun documentChosen(uri: Uri?) {
        val choice = pending
        pending = null
        if (uri == null || choice == null) {
            mutable.update { it.copy(busy = false, choosing = false) }
            return
        }
        mutable.update { it.copy(choosing = false) }
        operate {
            repository.save(choice.first, choice.second, uri)
            R.string.operit_saved
        }
    }

    fun importSkill(entry: OperitEntry, asset: OperitAsset?) {
        if (mutable.value.busy || !canImport(entry, asset)) return
        mutable.update { it.copy(busy = true, notice = null) }
        operate {
            val report = repository.importSkill(entry, asset)
            when {
                report.fatal != null || report.imported.isEmpty() -> R.string.operit_import_failed
                report.failures.isNotEmpty() -> R.string.operit_import_partial
                else -> R.string.operit_imported
            }
        }
    }

    private fun operate(action: suspend () -> Int) {
        operation = viewModelScope.launch {
            try {
                val notice = action()
                mutable.update { it.copy(notice = notice) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                coroutineContext.ensureActive()
                mutable.update { it.copy(notice = R.string.operit_operation_failed) }
            } finally {
                mutable.update { it.copy(busy = false, choosing = false) }
                operation = null
            }
        }
    }

    fun cancel() {
        if (mutable.value.choosing) return // SAF owns its own cancel action until its result arrives.
        operation?.cancel()
    }
}
