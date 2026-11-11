package org.telegram.configuration

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConfigurationRepositoryTest {
    private class Api(var response: suspend () -> ConfigurationResponse) : ConfigurationApi {
        override suspend fun getConfiguration(
            deviceLanguage: String, appVersion: String, appVersionCode: String
        ) = response()
    }

    @Test fun `starts restricted and applies server state`() = runTest {
        val api = Api { ConfigurationResponse(false) }
        val repository = ConfigurationRepository(api)
        assertTrue(repository.configuration.value.stable_release)
        repository.refresh("uz-UZ", "1.0.9", "109")
        assertFalse(repository.configuration.value.stable_release)
        api.response = { ConfigurationResponse(true) }
        repository.refresh("uz-UZ", "1.0.9", "109")
        assertTrue(repository.configuration.value.stable_release)
    }

    @Test fun `network failure replaces previous permission`() = runTest {
        val api = Api { ConfigurationResponse(false) }
        val repository = ConfigurationRepository(api)
        repository.refresh("uz", "1.0.9", "109")
        api.response = { throw IOException("offline") }
        repository.refresh("uz", "1.0.9", "109")
        assertTrue(repository.configuration.value.stable_release)
    }

    @Test fun `refresh is restricted while waiting and timeout stays restricted`() = runTest {
        val api = Api { ConfigurationResponse(false) }
        val repository = ConfigurationRepository(api, timeoutMillis = 100)
        repository.refresh("uz", "1.0.9", "109")
        api.response = { delay(1_000); ConfigurationResponse(false) }
        val job = launch { repository.refresh("uz", "1.0.9", "109") }
        runCurrent()
        assertTrue(repository.configuration.value.stable_release)
        job.join()
        assertTrue(repository.configuration.value.stable_release)
    }

    @Test fun `cancellation propagates and leaves restricted state`() = runTest {
        val api = Api { throw CancellationException("cancelled") }
        val repository = ConfigurationRepository(api)
        try {
            repository.refresh("uz", "1.0.9", "109")
            fail("Expected cancellation")
        } catch (expected: CancellationException) {
            assertTrue(repository.configuration.value.stable_release)
        }
    }

    @Test fun `concurrent refreshes cannot apply stale result last`() = runTest {
        val pending = CompletableDeferred<Unit>()
        var calls = 0
        val api = Api {
            if (++calls == 1) {
                pending.await()
                ConfigurationResponse(false)
            } else ConfigurationResponse(true)
        }
        val repository = ConfigurationRepository(api)
        val first = launch { repository.refresh("uz", "1.0.9", "109") }
        runCurrent()
        val second = launch { repository.refresh("uz", "1.0.9", "109") }
        pending.complete(Unit)
        first.join()
        second.join()
        assertTrue(repository.configuration.value.stable_release)
    }
}
