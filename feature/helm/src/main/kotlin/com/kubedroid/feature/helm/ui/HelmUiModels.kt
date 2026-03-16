package com.kubedroid.feature.helm.ui

import androidx.annotation.StringRes
import com.kubedroid.feature.helm.R

data class HelmReleaseUiModel(
    val name: String,
    val chart: String,
    val version: String,
    val status: HelmReleaseStatus,
    val lastDeployedEpochMillis: Long?,
)

enum class HelmReleaseStatus(
    @StringRes val labelResId: Int,
) {
    DEPLOYED(R.string.helm_release_status_deployed),
    FAILED(R.string.helm_release_status_failed),
    PENDING(R.string.helm_release_status_pending),
    SUPERSEDED(R.string.helm_release_status_superseded),
    UNINSTALLED(R.string.helm_release_status_uninstalled),
    UNINSTALLING(R.string.helm_release_status_uninstalling),
    UNKNOWN(R.string.helm_release_status_unknown),
}

data class HelmRevisionUiModel(
    val revision: Int,
    val status: HelmReleaseStatus,
    val description: String,
    val deployedAtEpochMillis: Long?,
)

enum class HelmDetailTab(
    @StringRes val titleResId: Int,
) {
    INFO(R.string.helm_detail_tab_info),
    HISTORY(R.string.helm_detail_tab_history),
    MANIFEST(R.string.helm_detail_tab_manifest),
}
