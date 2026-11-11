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

    @Test fun `sends device context to existing endpoint`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"stable_release":false}"""))
        repository.refresh("uz-UZ", "1.0.9", "109")
        assertFalse(repository.configuration.value.stable_release)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/api/settings?route=features", request.path)
        assertEquals("uz-UZ", request.getHeader("Accept-Language"))
        assertEquals("1.0.9", request.getHeader("X-App-Version"))
        assertEquals("109", request.getHeader("X-App-Version-Code"))
        assertEquals("no-cache, no-store", request.getHeader("Cache-Control"))
    }

    @Test fun `invalid payloads never grant permission`() = runBlocking {
        for (body in listOf(
            "{}", "null", "[]", "", "not-json",
            """{"stable_release":true}""",
            """{"stable_release":null}""",
            """{"stable_release":"false"}""",
            """{"stable_release":0}""",
            """{"stable_release":{}}"""
        )) {
            server.enqueue(MockResponse().setBody("""{"stable_release":false}"""))
            repository.refresh("uz", "1.0.9", "109")
            assertFalse(repository.configuration.value.stable_release)
            server.enqueue(MockResponse().setBody(body))
            repository.refresh("uz", "1.0.9", "109")
            assertTrue("Must restrict: $body", repository.configuration.value.stable_release)
        }
    }

    @Test fun `http errors and empty responses never grant permission`() = runBlocking {
        for (code in listOf(204, 301, 401, 403, 500)) {
            server.enqueue(MockResponse().setResponseCode(code))
            repository.refresh("uz", "1.0.9", "109")
            assertTrue("Must restrict HTTP $code", repository.configuration.value.stable_release)
        }
    }
}
