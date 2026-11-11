package org.telegram.configuration

import kotlinx.coroutines.CoroutineScope
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

    /** True while the admin has Safe Mode on (for everyone): store-sensitive features are switched off. */
    @JvmStatic
    fun isSafeModeActive(): Boolean = configuration.value.safeMode

    private var refreshJob: Job? = null

    /** On app start, on return to the foreground and with the remote feature switches' poll. No device data is sent. */
    @JvmStatic
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch { repository.refresh() }
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
