package com.aharou.feature.plugin.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.plugin.domain.npm.NpmPackageMetadata
import com.aharou.feature.plugin.domain.npm.NpmRegistryError
import com.aharou.feature.plugin.domain.npm.NpmRegistryException
import com.aharou.feature.plugin.domain.npm.NpmRegistryRepository
import com.aharou.feature.plugin.domain.npm.NpmSearchPage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class NpmLoadStatus { IDLE, LOADING, READY, ERROR }

internal data class NpmPackageCoordinate(val name: String, val version: String)

internal data class NpmMarketUiState(
    val query: String = "",
    val activeQuery: String = "",
    val page: NpmSearchPage? = null,
    val requestedFrom: Int = 0,
    val searchStatus: NpmLoadStatus = NpmLoadStatus.IDLE,
    val searchError: NpmRegistryError? = null,
    val selected: NpmPackageCoordinate? = null,
    val detail: NpmPackageMetadata? = null,
    val detailStatus: NpmLoadStatus = NpmLoadStatus.IDLE,
    val detailError: NpmRegistryError? = null,
) {
    val canSearch: Boolean get() = query.trim().length in 1..NPM_QUERY_LIMIT && searchStatus != NpmLoadStatus.LOADING
    val canGoBack: Boolean get() = searchStatus == NpmLoadStatus.READY && (page?.from ?: 0) > 0
    val canGoForward: Boolean get() = searchStatus == NpmLoadStatus.READY && page?.nextFrom != null
}

internal const val NPM_QUERY_LIMIT = 200
private const val NPM_PAGE_SIZE = 20

internal interface NpmMarketSource {
    suspend fun search(query: String, from: Int, size: Int): NpmSearchPage
    suspend fun packageVersion(packageName: String, exactVersion: String): NpmPackageMetadata
}

/** Both operations return display-only metadata; no installer is available to this state owner. */
internal class NpmMarketStateOwner(
    private val source: NpmMarketSource,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(NpmMarketUiState())
    val state = mutableState.asStateFlow()
    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var searchGeneration = 0L
    private var detailGeneration = 0L

    fun query(value: String) {
        if (value == state.value.query) return
        searchGeneration++
        searchJob?.cancel()
        dismissDetails()
        val tooLong = value.trim().length > NPM_QUERY_LIMIT
        mutableState.value = NpmMarketUiState(
            query = value,
            searchStatus = if (tooLong) NpmLoadStatus.ERROR else NpmLoadStatus.IDLE,
            searchError = if (tooLong) NpmRegistryError.INVALID_INPUT else null,
        )
    }

    fun search() {
        if (state.value.searchStatus == NpmLoadStatus.LOADING) return
        val query = state.value.query.trim()
        if (query.length !in 1..NPM_QUERY_LIMIT) {
            mutableState.update { it.copy(searchStatus = NpmLoadStatus.ERROR, searchError = NpmRegistryError.INVALID_INPUT) }
            return
        }
        dismissDetails()
        mutableState.update { NpmMarketUiState(query = it.query, activeQuery = query) }
        loadPage(0)
    }

    fun previous() {
        if (state.value.canGoBack) loadPage((state.value.page!!.from - NPM_PAGE_SIZE).coerceAtLeast(0))
    }

    fun next() {
        if (state.value.canGoForward) loadPage(state.value.page!!.nextFrom!!)
    }

    fun retrySearch() {
        if (state.value.searchStatus == NpmLoadStatus.ERROR && state.value.activeQuery.isNotEmpty()) {
            loadPage(state.value.requestedFrom)
        }
    }

    private fun loadPage(from: Int) {
        val query = state.value.activeQuery
        val generation = ++searchGeneration
        searchJob?.cancel()
        dismissDetails()
        mutableState.update { it.copy(requestedFrom = from, searchStatus = NpmLoadStatus.LOADING, searchError = null) }
        searchJob = scope.launch {
            try {
                val page = source.search(query, from, NPM_PAGE_SIZE)
                if (generation == searchGeneration) {
                    mutableState.update { it.copy(page = page, searchStatus = NpmLoadStatus.READY) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == searchGeneration) {
                    mutableState.update { it.copy(searchStatus = NpmLoadStatus.ERROR, searchError = error.registryError()) }
                }
            }
        }
    }

    fun select(metadata: NpmPackageMetadata) {
        val name = metadata.name?.takeIf { it.isNotBlank() } ?: return
        val version = metadata.version?.takeIf { it.isNotBlank() } ?: return
        loadDetails(NpmPackageCoordinate(name, version))
    }

    fun retryDetails() {
        if (state.value.detailStatus == NpmLoadStatus.ERROR) state.value.selected?.let(::loadDetails)
    }

    private fun loadDetails(coordinate: NpmPackageCoordinate) {
        val generation = ++detailGeneration
        detailJob?.cancel()
        mutableState.update {
            it.copy(selected = coordinate, detail = null, detailStatus = NpmLoadStatus.LOADING, detailError = null)
        }
        detailJob = scope.launch {
            try {
                val detail = source.packageVersion(coordinate.name, coordinate.version)
                if ((detail.name != null && detail.name != coordinate.name) ||
                    (detail.version != null && detail.version != coordinate.version)) {
                    throw NpmRegistryException(NpmRegistryError.INVALID_RESPONSE)
                }
                if (generation == detailGeneration) {
                    mutableState.update { it.copy(detail = detail, detailStatus = NpmLoadStatus.READY) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == detailGeneration) {
                    mutableState.update { it.copy(detailStatus = NpmLoadStatus.ERROR, detailError = error.registryError()) }
                }
            }
        }
    }

    fun dismissDetails() {
        detailGeneration++
        detailJob?.cancel()
        mutableState.update { it.copy(selected = null, detail = null, detailStatus = NpmLoadStatus.IDLE, detailError = null) }
    }

    private fun Exception.registryError(): NpmRegistryError =
        (this as? NpmRegistryException)?.error ?: NpmRegistryError.NETWORK
}

@HiltViewModel
internal class NpmMarketViewModel @Inject constructor(repository: NpmRegistryRepository) : ViewModel() {
    private val owner = NpmMarketStateOwner(object : NpmMarketSource {
        override suspend fun search(query: String, from: Int, size: Int) = repository.search(query, from, size)
        override suspend fun packageVersion(packageName: String, exactVersion: String) =
            repository.packageVersion(packageName, exactVersion)
    }, viewModelScope)

    val state = owner.state
    fun query(value: String) = owner.query(value)
    fun search() = owner.search()
    fun previous() = owner.previous()
    fun next() = owner.next()
    fun retrySearch() = owner.retrySearch()
    fun select(metadata: NpmPackageMetadata) = owner.select(metadata)
    fun retryDetails() = owner.retryDetails()
    fun dismissDetails() = owner.dismissDetails()
}

internal enum class NpmMetadataField { NAME, VERSION, DESCRIPTION, LICENSE, REPOSITORY, DEPRECATED, INTEGRITY }

internal fun npmMetadataFields(metadata: NpmPackageMetadata): List<Pair<NpmMetadataField, String?>> = listOf(
    NpmMetadataField.NAME to npmDisplayText(metadata.name),
    NpmMetadataField.VERSION to npmDisplayText(metadata.version),
    NpmMetadataField.DESCRIPTION to npmDisplayText(metadata.description),
    NpmMetadataField.LICENSE to npmDisplayText(metadata.license),
    NpmMetadataField.REPOSITORY to npmDisplayText(metadata.repository),
    NpmMetadataField.DEPRECATED to npmDisplayText(metadata.deprecated),
    NpmMetadataField.INTEGRITY to npmDisplayText(metadata.integrity),
)

internal fun npmDisplayText(value: String?): String? {
    if (value == null) return null
    // Bound work before filtering, preserve complete surrogate pairs, and remove bidi overrides.
    val prefix = value.take(2000).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
    val text = prefix.filterNot {
        (it.isISOControl() && it != '\n' && it != '\t') || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069'
    }.takeIf { it.isNotBlank() } ?: return null
    return if (value.length > prefix.length) "$text…" else text
}
