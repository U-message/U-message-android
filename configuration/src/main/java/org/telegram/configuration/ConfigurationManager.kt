package org.telegram.configuration

import android.content.Context
import android.content.res.Resources
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.os.ConfigurationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArraySet

object ConfigurationManager {
    private val repository = ConfigurationRepository(
        createConfigurationApi("https://umessage-premium-server.vercel.app/")
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listeners = CopyOnWriteArraySet<Runnable>()
    private var refreshJob: Job? = null
    val configuration = repository.configuration

    init {
        scope.launch {
            configuration.collect {
                listeners.forEach { listener ->
                    try {
                        listener.run()
                    } catch (exception: Exception) {
                        android.util.Log.w("Configuration", "Settings observer failed", exception)
                    }
                }
            }
        }
    }

    @JvmStatic
    fun isExtendedCacheEnabled(): Boolean = !configuration.value.stable_release

    // Called from Application.onCreate or another main-thread app-start hook.
    @JvmStatic
    fun refresh(context: Context) {
        val applicationContext = context.applicationContext
        refreshJob?.cancel()
        repository.reset()
        refreshJob = scope.launch {
            try {
                val deviceLanguage = ConfigurationCompat.getLocales(
                    Resources.getSystem().configuration
                )[0]?.toLanguageTag() ?: "und"
                val packageInfo = applicationContext.packageManager.getPackageInfo(
                    applicationContext.packageName, 0
                )
                repository.refresh(
                    deviceLanguage,
                    packageInfo.versionName ?: "unknown",
                    PackageInfoCompat.getLongVersionCode(packageInfo).toString()
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                repository.reset()
            }
        }
    }

    private var pollJob: Job? = null

    /** Polled with the remote feature switches, so Safe Mode turned on in the admin panel applies within seconds. */
    @JvmStatic
    fun poll(context: Context) {
        if (refreshJob?.isActive == true || pollJob?.isActive == true) return
        val applicationContext = context.applicationContext
        pollJob = scope.launch {
            try {
                val deviceLanguage = ConfigurationCompat.getLocales(
                    Resources.getSystem().configuration
                )[0]?.toLanguageTag() ?: "und"
                val packageInfo = applicationContext.packageManager.getPackageInfo(
                    applicationContext.packageName, 0
                )
                repository.poll(
                    deviceLanguage,
                    packageInfo.versionName ?: "unknown",
                    PackageInfoCompat.getLongVersionCode(packageInfo).toString()
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                // keep the last value
            }
        }
    }

    @JvmStatic
    fun addListener(listener: Runnable) {
        listeners.add(listener)
    }

    @JvmStatic
    fun removeListener(listener: Runnable) {
        listeners.remove(listener)
    }
}
