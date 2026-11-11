package org.telegram.configuration

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

/** Supply existing settings content; extended content is absent in standard mode. */
@Composable
fun ConfigurationSettings(
    modifier: Modifier = Modifier,
    configurationFlow: StateFlow<ConfigurationResponse> = ConfigurationManager.configuration,
    localDataContent: @Composable () -> Unit = {},
    standardContent: @Composable () -> Unit,
    extendedCacheContent: @Composable () -> Unit
) {
    val configuration by configurationFlow.collectAsStateWithLifecycle()
    Column(modifier) {
        standardContent()
        // Transparency and deletion stay available in restricted mode too.
        localDataContent()
        if (!configuration.stable_release) {
            extendedCacheContent()
        }
    }
}
