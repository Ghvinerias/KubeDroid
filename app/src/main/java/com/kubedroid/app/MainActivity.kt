package com.kubedroid.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import androidx.fragment.app.FragmentActivity
import com.kubedroid.app.navigation.AppNavGraph
import com.kubedroid.app.navigation.LocalScopedTokenCache
import com.kubedroid.app.navigation.Routes
import com.kubedroid.app.navigation.ScopedTokenCache
import com.kubedroid.app.ui.theme.KubeDroidTheme
import com.kubedroid.core.security.AppLockManager
import com.kubedroid.core.ui.AppLockScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject
    lateinit var appLockManager: AppLockManager

    private val scopedTokenCache = ScopedTokenCache()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(appLockManager)
        lifecycle.addObserver(scopedTokenCache)
        enableEdgeToEdge()
        setContent {
            val preferencesViewModel: MainViewModel = hiltViewModel()
            val themePreference by preferencesViewModel.theme.collectAsStateWithLifecycle()
            val biometricLockEnabled by preferencesViewModel.biometricLockEnabled.collectAsStateWithLifecycle()
            val navController = rememberNavController()
            var isLockRequired by remember { mutableStateOf(appLockManager.requiresUnlock()) }
            var previousBiometricLockEnabled by remember { mutableStateOf<Boolean?>(null) }

            LifecycleEventEffect(event = Lifecycle.Event.ON_START) {
                if (biometricLockEnabled) {
                    isLockRequired = appLockManager.requiresUnlock()
                }
            }
            LaunchedEffect(biometricLockEnabled) {
                val wasEnabled = previousBiometricLockEnabled
                if (!biometricLockEnabled) {
                    appLockManager.markUnlocked()
                } else if (wasEnabled == false) {
                    appLockManager.markLocked()
                }
                isLockRequired = if (biometricLockEnabled) {
                    appLockManager.requiresUnlock()
                } else {
                    false
                }
                previousBiometricLockEnabled = biometricLockEnabled
            }

            DisposableEffect(navController) {
                val destinationListener = NavController.OnDestinationChangedListener { _, destination, _ ->
                    val isUnauthenticated = destination.route in unauthenticatedRoutes
                    scopedTokenCache.onSessionStateChanged(!isUnauthenticated)
                }
                navController.addOnDestinationChangedListener(destinationListener)
                onDispose {
                    navController.removeOnDestinationChangedListener(destinationListener)
                }
            }

            KubeDroidTheme(theme = themePreference) {
                CompositionLocalProvider(LocalScopedTokenCache provides scopedTokenCache) {
                    if (biometricLockEnabled && isLockRequired) {
                        AppLockScreen(
                            logoPainter = painterResource(id = R.drawable.ic_launcher_logo),
                            onUnlocked = {
                                appLockManager.markUnlocked()
                                isLockRequired = false
                            },
                        )
                    } else {
                        AppNavGraph(
                            navController = navController,
                            startDestination = Routes.ENTRY_GATE,
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        lifecycle.removeObserver(appLockManager)
        lifecycle.removeObserver(scopedTokenCache)
        super.onDestroy()
    }

    private companion object {
        val unauthenticatedRoutes = setOf(
            Routes.EntryGate.root,
            Routes.Onboarding.root,
        )
    }
}
