package org.telegram.configuration

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ConfigurationApiTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: ConfigurationRepository

    @Before fun prepare() {
        server = MockWebServer()
        server.start()
        repository = ConfigurationRepository(createConfigurationApi(server.url("/").toString()))
    }

    @After fun finish() { server.shutdown() }

    @Test fun `sends no device context`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"stable_release":true}"""))
        repository.refresh()
        assertTrue(repository.configuration.value.safeMode)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/api/settings?route=features", request.path)
        assertNull(request.getHeader("X-App-Version"))
        assertNull(request.getHeader("X-App-Version-Code"))
        assertEquals("no-cache, no-store", request.getHeader("Cache-Control"))
    }

    @Test fun `only a literal true turns safe mode on`() = runBlocking {
        for (body in listOf(
            "{}", "null", "[]",
            """{"stable_release":false}""",
            """{"stable_release":null}""",
            """{"stable_release":"true"}""",
            """{"stable_release":1}""",
            """{"stable_release":{}}"""
        )) {
            server.enqueue(MockResponse().setBody(body))
            repository.refresh()
            assertFalse("Must stay off: $body", repository.configuration.value.safeMode)
        }
    }

    @Test fun `http errors keep the current state`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"stable_release":true}"""))
        repository.refresh()
        for (code in listOf(204, 301, 401, 403, 500)) {
            server.enqueue(MockResponse().setResponseCode(code))
            repository.refresh()
            assertTrue("Must keep state on HTTP $code", repository.configuration.value.safeMode)
        }
    }
}
