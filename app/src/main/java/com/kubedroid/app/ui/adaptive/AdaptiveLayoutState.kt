package com.kubedroid.app.ui.adaptive

import androidx.activity.ComponentActivity
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import kotlinx.coroutines.flow.Flow

data class AdaptiveLayoutState(
    val isCompactWidth: Boolean,
    val isTwoPanePreferred: Boolean,
    val hingePadding: Dp,
)

@Composable
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
fun rememberAdaptiveLayoutState(): AdaptiveLayoutState {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    if (activity == null) {
        return AdaptiveLayoutState(
            isCompactWidth = true,
            isTwoPanePreferred = false,
            hingePadding = 0.dp,
        )
    }

    val windowSizeClass = calculateWindowSizeClass(activity)
    val isCompact = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Compact

    val layoutInfoFlow: Flow<WindowLayoutInfo> = remember(activity) {
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity)
    }
    val layoutInfo = layoutInfoFlow.collectAsStateWithLifecycle(
        initialValue = WindowLayoutInfo(emptyList()),
    ).value

    val foldingFeature = layoutInfo.displayFeatures
        .filterIsInstance<FoldingFeature>()
        .firstOrNull()
    val density = LocalDensity.current
    val hingePadding = remember(foldingFeature, density) {
        if (foldingFeature?.isSeparating == true &&
            foldingFeature.orientation == FoldingFeature.Orientation.VERTICAL
        ) {
            with(density) { (foldingFeature.bounds.width() / 2f).toDp() }
        } else {
            0.dp
        }
    }

    return AdaptiveLayoutState(
        isCompactWidth = isCompact,
        isTwoPanePreferred = !isCompact,
        hingePadding = hingePadding,
    )
}
