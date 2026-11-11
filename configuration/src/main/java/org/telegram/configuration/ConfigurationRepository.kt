package org.telegram.configuration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

class ConfigurationRepository(
    private val api: ConfigurationApi,
    private val timeoutMillis: Long = 10_000L
) {
    private val refreshMutex = Mutex()
    private val mutableConfiguration = MutableStateFlow(ConfigurationResponse())
    val configuration = mutableConfiguration.asStateFlow()

    /**
     * Only a successful answer replaces the current value. Errors, timeouts and bad payloads keep it, so the
     * app never switches features off (or back on) because of a network problem. Overlapping calls are skipped.
     */
    suspend fun refresh() {
        if (!refreshMutex.tryLock()) return
        try {
            val response = withTimeoutOrNull(timeoutMillis) { api.getConfiguration() }
            if (response != null) mutableConfiguration.value = response
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            // keep the last value
        } finally {
            refreshMutex.unlock()
        }
    }
}
