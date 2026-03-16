package com.kubedroid.feature.settings.domain.model

data class AppPreferences(
    val theme: AppTheme = AppTheme.System,
    val defaultNamespace: String = "default",
    val logBufferSize: LogBufferSize = LogBufferSize.Size5000,
    val metricsRefreshSeconds: Int = 15,
    val biometricLockEnabled: Boolean = false,
    val cacheEnabled: Boolean = true,
)

enum class AppTheme {
    System,
    Light,
    Dark,
    OledBlack,
}

enum class LogBufferSize(val lines: Int) {
    Size1000(1000),
    Size5000(5000),
    Size10000(10000),
}
