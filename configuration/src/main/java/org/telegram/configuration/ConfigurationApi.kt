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
import retrofit2.http.Header
import retrofit2.http.Headers
import java.util.concurrent.TimeUnit

data class ConfigurationResponse(val stable_release: Boolean = true)

// Only a literal JSON boolean false can enable extended configuration.
internal class ConfigurationAdapter : TypeAdapter<ConfigurationResponse>() {
    override fun read(reader: JsonReader): ConfigurationResponse {
        val document = JsonParser.parseReader(reader)
        val value = if (document.isJsonObject) document.asJsonObject.get("stable_release") else null
        val stable = value == null || !value.isJsonPrimitive ||
            !value.asJsonPrimitive.isBoolean || value.asBoolean
        return ConfigurationResponse(stable)
    }

    override fun write(writer: JsonWriter, value: ConfigurationResponse?) {
        writer.beginObject()
        writer.name("stable_release").value(value?.stable_release ?: true)
        writer.endObject()
    }
}

interface ConfigurationApi {
    @Headers("Cache-Control: no-cache, no-store")
    @GET("api/settings?route=features")
    suspend fun getConfiguration(
        @Header("Accept-Language") deviceLanguage: String,
        @Header("X-App-Version") appVersion: String,
        @Header("X-App-Version-Code") appVersionCode: String
    ): ConfigurationResponse
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
