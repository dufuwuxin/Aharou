// SPDX-License-Identifier: AGPL-3.0-only
package com.aharou.feature.sandbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.agent.domain.binary.SomcpAnalysisService
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject

data class SomcpPreviewState(val loading: Boolean = false, val report: JsonObject? = null,
    val error: String? = null, val action: String = "overview", val offset: Int = 0, val query: String = "")

@HiltViewModel
class SomcpPreviewViewModel @Inject constructor(private val service: SomcpAnalysisService) : ViewModel() {
    private val mutable = MutableStateFlow(SomcpPreviewState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var epoch = 0L

    fun load(file: File, action: String = "overview", offset: Int = 0, query: String = "", general: Boolean = false) {
        job?.cancel()
        val request = ++epoch
        mutable.value = SomcpPreviewState(loading = true, action = action, offset = offset, query = query)
        job = viewModelScope.launch {
            try {
                val report = withTimeout(30_000) {
                    if (general) service.inspectSelectedFile(file, action, offset, 50, query)
                    else service.analyzeSelectedFile(file, action, offset, 50, query)
                }
                if (request == epoch) mutable.value = mutable.value.copy(loading = false, report = report)
            } catch (_: TimeoutCancellationException) {
                if (request == epoch) mutable.value = mutable.value.copy(loading = false, error = "ELF_ANALYSIS_TIMEOUT")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (request == epoch) mutable.value = mutable.value.copy(loading = false,
                    error = failure.message ?: "ELF_ANALYSIS_FAILED")
            }
        }
    }

    fun cancel() { ++epoch; job?.cancel(); job = null }
}
