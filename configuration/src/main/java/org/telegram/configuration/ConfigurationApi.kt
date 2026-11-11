package org.telegram.configuration

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Headers
import java.util.concurrent.TimeUnit

/**
 * Safe Mode: an emergency off-switch the admin flips for EVERY user at once. It never depends on who asks
 * (no country, version or language targeting), and it can only turn features off: the app as shipped is
 * the default, and an unknown or failed answer never changes it.
 */
data class ConfigurationResponse(val safeMode: Boolean = false)

// Wire field "stable_release" is kept for installed versions. Only a literal JSON true turns Safe Mode on.
internal class ConfigurationAdapter : TypeAdapter<ConfigurationResponse>() {
    override fun read(reader: JsonReader): ConfigurationResponse {
        val document = JsonParser.parseReader(reader)
        val value = if (document.isJsonObject) document.asJsonObject.get("stable_release") else null
        val on = value != null && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean && value.asBoolean
        return ConfigurationResponse(on)
    }

    override fun write(writer: JsonWriter, value: ConfigurationResponse?) {
        writer.beginObject()
        writer.name("stable_release").value(value?.safeMode ?: false)
        writer.endObject()
    }
}

interface ConfigurationApi {
    @Headers("Cache-Control: no-cache, no-store")
    @GET("api/settings?route=features")
    suspend fun getConfiguration(): ConfigurationResponse
}

internal fun createConfigurationApi(baseUrl: String): ConfigurationApi {
    val gson = GsonBuilder()
        .registerTypeAdapter(ConfigurationResponse::class.java, ConfigurationAdapter())
        .create()
    val client = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    return Retrofit.Builder()
        .baseUrl(baseUrl.trimEnd('/') + "/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
        .create(ConfigurationApi::class.java)
}
