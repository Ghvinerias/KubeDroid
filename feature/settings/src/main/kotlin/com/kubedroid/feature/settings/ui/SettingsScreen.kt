package com.kubedroid.feature.settings.ui

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kubedroid.feature.settings.R
import com.kubedroid.feature.settings.domain.model.AppTheme
import com.kubedroid.feature.settings.domain.model.LogBufferSize

private val metricsRefreshOptionsSeconds = listOf(5, 15, 30, 60)

@Composable
fun SettingsRoute(
    viewModel: SettingsViewModel,
    onExportLogsClick: () -> Unit,
    onOpenSourceLicensesClick: () -> Unit,
    onDeployManifestClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    SettingsScreen(
        state = state,
        onThemeSelected = viewModel::onThemeSelected,
        onDefaultNamespaceChanged = viewModel::onDefaultNamespaceChanged,
        onLogBufferSizeSelected = viewModel::onLogBufferSizeSelected,
        onMetricsIntervalSelected = viewModel::onMetricsRefreshSecondsSelected,
        onBiometricLockChanged = viewModel::onBiometricLockChanged,
        onClearCache = viewModel::clearCache,
        onExportLogsClick = onExportLogsClick,
        onOpenSourceLicensesClick = onOpenSourceLicensesClick,
        onDeployManifestClick = onDeployManifestClick,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onThemeSelected: (AppTheme) -> Unit,
    onDefaultNamespaceChanged: (String) -> Unit,
    onLogBufferSizeSelected: (LogBufferSize) -> Unit,
    onMetricsIntervalSelected: (Int) -> Unit,
    onBiometricLockChanged: (Boolean) -> Unit,
    onClearCache: () -> Unit,
    onExportLogsClick: () -> Unit,
    onOpenSourceLicensesClick: () -> Unit,
    onDeployManifestClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cacheSize = Formatter.formatShortFileSize(context, state.cacheSizeBytes)
    var isClearCacheConfirmationVisible by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.settings_title)) },
                actions = {
                    TextButton(onClick = onDeployManifestClick) {
                        Text(text = stringResource(id = R.string.settings_deploy_manifest_action))
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                PreferenceCategoryHeader(title = stringResource(id = R.string.settings_appearance_title))
            }
            items(AppTheme.entries) { theme ->
                ThemePreferenceItem(
                    theme = theme,
                    selected = state.preferences.theme == theme,
                    onClick = { onThemeSelected(theme) },
                )
            }

            item {
                PreferenceCategoryHeader(title = stringResource(id = R.string.settings_behaviour_title))
            }
            item {
                ListItem(
                    headlineContent = {
                        Text(text = stringResource(id = R.string.settings_default_namespace_title))
                    },
                    supportingContent = {
                        OutlinedTextField(
                            value = state.preferences.defaultNamespace,
                            onValueChange = onDefaultNamespaceChanged,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = {
                                Text(text = stringResource(id = R.string.settings_default_namespace_label))
                            },
                        )
                    },
                )
            }
            item {
                ChoiceChipPreferenceRow(
                    title = stringResource(id = R.string.settings_log_buffer_title),
                    selectedValue = state.preferences.logBufferSize,
                    options = LogBufferSize.entries,
                    optionLabel = { value ->
                        stringResource(id = R.string.settings_log_buffer_option, value.lines)
                    },
                    onSelected = onLogBufferSizeSelected,
                )
            }
            item {
                ChoiceChipPreferenceRow(
                    title = stringResource(id = R.string.settings_metrics_interval_title),
                    selectedValue = state.preferences.metricsRefreshSeconds,
                    options = metricsRefreshOptionsSeconds,
                    optionLabel = { seconds ->
                        stringResource(id = R.string.settings_metrics_interval_option, seconds)
                    },
                    onSelected = onMetricsIntervalSelected,
                )
            }

            item {
                PreferenceCategoryHeader(title = stringResource(id = R.string.settings_security_title))
            }
            item {
                ListItem(
                    headlineContent = {
                        Text(text = stringResource(id = R.string.settings_biometric_lock_title))
                    },
                    supportingContent = {
                        Text(text = stringResource(id = R.string.settings_biometric_lock_summary))
                    },
                    trailingContent = {
                        Switch(
                            checked = state.preferences.biometricLockEnabled,
                            onCheckedChange = onBiometricLockChanged,
                        )
                    },
                )
            }

            item {
                PreferenceCategoryHeader(title = stringResource(id = R.string.settings_data_title))
            }
            item {
                ListItem(
                    headlineContent = {
                        Text(text = stringResource(id = R.string.settings_clear_cache_title))
                    },
                    supportingContent = {
                        Text(text = stringResource(id = R.string.settings_clear_cache_summary, cacheSize))
                    },
                    trailingContent = {
                        FilterChip(
                            selected = false,
                            enabled = !state.isClearingCache,
                            onClick = { isClearCacheConfirmationVisible = true },
                            label = {
                                Text(text = stringResource(id = R.string.settings_clear_cache_action))
                            },
                        )
                    },
                )
            }
            item {
                ActionPreferenceRow(
                    title = stringResource(id = R.string.settings_export_logs_title),
                    summary = stringResource(id = R.string.settings_export_logs_summary),
                    onClick = onExportLogsClick,
                )
            }
            item {
                ActionPreferenceRow(
                    title = stringResource(id = R.string.settings_deploy_manifest_title),
                    summary = stringResource(id = R.string.settings_deploy_manifest_summary),
                    onClick = onDeployManifestClick,
                )
            }

            item {
                PreferenceCategoryHeader(title = stringResource(id = R.string.settings_about_title))
            }
            item {
                InfoPreferenceRow(
                    title = stringResource(id = R.string.settings_version_title),
                    value = state.appVersion,
                )
            }
            item {
                InfoPreferenceRow(
                    title = stringResource(id = R.string.settings_build_title),
                    value = state.appBuildNumber,
                )
            }
            item {
                ActionPreferenceRow(
                    title = stringResource(id = R.string.settings_open_source_licenses_title),
                    summary = stringResource(id = R.string.settings_open_source_licenses_summary),
                    onClick = onOpenSourceLicensesClick,
                )
            }
        }
    }

    if (isClearCacheConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { isClearCacheConfirmationVisible = false },
            title = {
                Text(text = stringResource(id = R.string.settings_clear_cache_confirm_title))
            },
            text = {
                Text(text = stringResource(id = R.string.settings_clear_cache_confirm_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        isClearCacheConfirmationVisible = false
                        onClearCache()
                    },
                ) {
                    Text(text = stringResource(id = R.string.settings_clear_cache_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { isClearCacheConfirmationVisible = false }) {
                    Text(text = stringResource(id = R.string.settings_clear_cache_cancel_action))
                }
            },
        )
    }
}

@Composable
private fun ThemePreferenceItem(
    theme: AppTheme,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Text(text = theme.displayLabel())
        },
        supportingContent = {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = theme.icon(),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = MaterialTheme.shapes.small,
                            ),
                    )
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(
                                color = MaterialTheme.colorScheme.secondary,
                                shape = MaterialTheme.shapes.small,
                            ),
                    )
                    Box(
                        modifier = Modifier
                            .height(12.dp)
                            .width(64.dp)
                            .background(
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                shape = MaterialTheme.shapes.small,
                            ),
                    )
                }
            }
        },
        trailingContent = {
            if (selected) {
                Icon(
                    imageVector = Icons.Default.RadioButtonChecked,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun <T> ChoiceChipPreferenceRow(
    title: String,
    selectedValue: T,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onSelected: (T) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(text = title) },
            supportingContent = {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.forEach { option ->
                        FilterChip(
                            selected = option == selectedValue,
                            onClick = { onSelected(option) },
                            label = { Text(text = optionLabel(option)) },
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun ActionPreferenceRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(text = title) },
        supportingContent = { Text(text = summary) },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
            )
        },
    )
}

@Composable
private fun InfoPreferenceRow(
    title: String,
    value: String,
) {
    ListItem(
        headlineContent = { Text(text = title) },
        trailingContent = {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
private fun PreferenceCategoryHeader(title: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun AppTheme.displayLabel(): String {
    return when (this) {
        AppTheme.System -> stringResource(id = R.string.settings_theme_system)
        AppTheme.Light -> stringResource(id = R.string.settings_theme_light)
        AppTheme.Dark -> stringResource(id = R.string.settings_theme_dark)
        AppTheme.OledBlack -> stringResource(id = R.string.settings_theme_oled_black)
    }
}

private fun AppTheme.icon(): ImageVector = when (this) {
    AppTheme.System -> Icons.Default.BrightnessAuto
    AppTheme.Light -> Icons.Default.LightMode
    AppTheme.Dark -> Icons.Default.Bedtime
    AppTheme.OledBlack -> Icons.Default.Bedtime
}
