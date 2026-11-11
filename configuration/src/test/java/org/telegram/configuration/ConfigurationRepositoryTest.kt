package org.telegram.configuration

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConfigurationRepositoryTest {
    private class Api(var response: suspend () -> ConfigurationResponse) : ConfigurationApi {
        override suspend fun getConfiguration() = response()
    }

    @Test fun `starts with the app as shipped and applies the admin switch`() = runTest {
        val api = Api { ConfigurationResponse(true) }
        val repository = ConfigurationRepository(api)
        assertFalse(repository.configuration.value.safeMode)
        repository.refresh()
        assertTrue(repository.configuration.value.safeMode)
        api.response = { ConfigurationResponse(false) }
        repository.refresh()
        assertFalse(repository.configuration.value.safeMode)
    }

    @Test fun `network failure keeps the last answer`() = runTest {
        val api = Api { ConfigurationResponse(true) }
        val repository = ConfigurationRepository(api)
        repository.refresh()
        api.response = { throw IOException("offline") }
        repository.refresh()
        assertTrue(repository.configuration.value.safeMode)
    }

    @Test fun `timeout keeps the last answer`() = runTest {
        val api = Api { delay(1_000); ConfigurationResponse(true) }
        val repository = ConfigurationRepository(api, timeoutMillis = 100)
        repository.refresh()
        assertFalse(repository.configuration.value.safeMode)
    }

    @Test fun `cancellation propagates`() = runTest {
        val repository = ConfigurationRepository(Api { throw CancellationException("cancelled") })
        try {
            repository.refresh()
            fail("Expected cancellation")
        } catch (expected: CancellationException) {
            assertFalse(repository.configuration.value.safeMode)
        }
    }
}
