package org.telegram.configuration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class ConfigurationRepository(
    private val api: ConfigurationApi,
    private val timeoutMillis: Long = 10_000L
) {
    private val refreshMutex = Mutex()
    private val mutableConfiguration = MutableStateFlow(ConfigurationResponse())
    val configuration = mutableConfiguration.asStateFlow()

    internal fun reset() {
        mutableConfiguration.value = ConfigurationResponse()
    }

    /** Re-check while the app runs: only a successful answer replaces the current value (no flicker, no reset). */
    suspend fun poll(deviceLanguage: String, appVersion: String, appVersionCode: String) {
        if (!refreshMutex.tryLock()) return
        try {
            val response = withTimeoutOrNull(timeoutMillis) {
                api.getConfiguration(deviceLanguage, appVersion, appVersionCode)
            }
            if (response != null) mutableConfiguration.value = response
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            // keep the last value
        } finally {
            refreshMutex.unlock()
        }
    }

    suspend fun refresh(deviceLanguage: String, appVersion: String, appVersionCode: String) {
        refreshMutex.withLock {
            mutableConfiguration.value = ConfigurationResponse()
            try {
                mutableConfiguration.value = withTimeoutOrNull(timeoutMillis) {
                    api.getConfiguration(deviceLanguage, appVersion, appVersionCode)
                } ?: ConfigurationResponse()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableConfiguration.value = ConfigurationResponse()
            }
        }
    }
}
