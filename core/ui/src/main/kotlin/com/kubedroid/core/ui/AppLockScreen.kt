package com.kubedroid.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.kubedroid.core.security.BiometricHelper
import kotlinx.coroutines.launch

@Composable
fun AppLockScreen(
    logoPainter: Painter,
    onUnlocked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val biometricHelper = remember { BiometricHelper() }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = logoPainter,
                contentDescription = stringResource(id = R.string.app_lock_logo_content_description),
                modifier = Modifier.size(96.dp),
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = {
                    val activity = context as? FragmentActivity
                    if (activity == null) {
                        errorMessage = context.getString(R.string.app_lock_error_unavailable_context)
                        return@Button
                    }

                    scope.launch {
                        errorMessage = null
                        when (
                            val authResult = biometricHelper.authenticate(
                                activity = activity,
                                promptConfig = BiometricHelper.PromptConfig(
                                    title = context.getString(R.string.app_lock_prompt_title),
                                    subtitle = context.getString(R.string.app_lock_prompt_subtitle),
                                    cancelText = context.getString(R.string.app_lock_prompt_cancel),
                                ),
                            )
                        ) {
                            is BiometricHelper.AuthResult.Success -> onUnlocked()
                            is BiometricHelper.AuthResult.Failed -> {
                                errorMessage = context.getString(R.string.app_lock_error_failed)
                            }

                            is BiometricHelper.AuthResult.Error -> {
                                errorMessage = authResult.message
                            }

                            is BiometricHelper.AuthResult.Unavailable -> {
                                errorMessage = context.getString(R.string.app_lock_error_unavailable)
                            }
                        }
                    }
                },
            ) {
                Text(text = stringResource(id = R.string.app_lock_unlock_button))
            }
            errorMessage?.let { message ->
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
