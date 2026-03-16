package com.kubedroid.feature.widget

import android.content.Context

internal object WidgetUiPreferences {
    private const val PREFS_NAME = "widget_ui_preferences"
    private const val KEY_WIDGET_CLUSTER_PREFIX = "widget_cluster_"

    fun setWidgetCluster(
        context: Context,
        appWidgetId: Int,
        clusterName: String,
    ) {
        prefs(context).edit()
            .putString(KEY_WIDGET_CLUSTER_PREFIX + appWidgetId, clusterName)
            .apply()
    }

    fun getWidgetCluster(
        context: Context,
        appWidgetId: Int,
    ): String? {
        return prefs(context).getString(KEY_WIDGET_CLUSTER_PREFIX + appWidgetId, null)
    }

    fun clearWidgetCluster(
        context: Context,
        appWidgetId: Int,
    ) {
        prefs(context).edit()
            .remove(KEY_WIDGET_CLUSTER_PREFIX + appWidgetId)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
