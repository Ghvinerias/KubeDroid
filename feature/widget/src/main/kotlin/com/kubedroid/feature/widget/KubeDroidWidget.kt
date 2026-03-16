package com.kubedroid.feature.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.text.format.DateFormat
import android.view.View
import android.widget.RemoteViews
import com.kubedroid.feature.settings.ClusterHealth
import java.util.Date

class KubeDroidWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { widgetId ->
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    override fun onReceive(
        context: Context,
        intent: android.content.Intent,
    ) {
        super.onReceive(context, intent)
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, KubeDroidWidget::class.java)
            val ids = manager.getAppWidgetIds(component)
            onUpdate(context, manager, ids)
        }
    }

    override fun onDeleted(
        context: Context,
        appWidgetIds: IntArray,
    ) {
        super.onDeleted(context, appWidgetIds)
        appWidgetIds.forEach { widgetId ->
            WidgetUiPreferences.clearWidgetCluster(context, widgetId)
        }
    }

    companion object {
        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
        ) {
            val clusterName = WidgetUiPreferences.getWidgetCluster(context, appWidgetId)
            val state = clusterName?.let {
                WidgetSyncPreferences.loadSnapshot(
                    context = context,
                    clusterName = it,
                )
            }

            val remoteViews = RemoteViews(context.packageName, R.layout.widget_kubedroid)
            val health = state?.nodeStatus
                ?.let { runCatching { ClusterHealth.valueOf(it) }.getOrNull() }
                ?: ClusterHealth.Unreachable

            val clusterLabel = state?.clusterName
                ?: context.getString(R.string.widget_cluster_not_selected)
            remoteViews.setTextViewText(R.id.widget_cluster_name, clusterLabel)

            val podLabel = context.getString(
                R.string.widget_pod_count,
                state?.podCount ?: 0,
            )
            remoteViews.setTextViewText(R.id.widget_pod_count, podLabel)

            val warningCount = state?.warningCount ?: 0
            if (warningCount > 0) {
                remoteViews.setViewVisibility(R.id.widget_warning_badge, View.VISIBLE)
                remoteViews.setTextViewText(
                    R.id.widget_warning_badge,
                    context.getString(R.string.widget_warning_badge, warningCount),
                )
            } else {
                remoteViews.setViewVisibility(R.id.widget_warning_badge, View.GONE)
            }

            val lastUpdatedText = if (state != null) {
                val timeFormatter = DateFormat.getTimeFormat(context)
                context.getString(
                    R.string.widget_last_updated,
                    timeFormatter.format(Date(state.lastUpdated.toEpochMilli())),
                )
            } else {
                context.getString(R.string.widget_last_updated_unknown)
            }
            remoteViews.setTextViewText(R.id.widget_last_updated, lastUpdatedText)

            remoteViews.setTextColor(R.id.widget_health_indicator, context.getColor(health.toColorRes()))

            appWidgetManager.updateAppWidget(appWidgetId, remoteViews)
        }

        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, KubeDroidWidget::class.java)
            val ids = manager.getAppWidgetIds(component)
            ids.forEach { widgetId ->
                updateWidget(context, manager, widgetId)
            }
        }

        private fun ClusterHealth.toColorRes(): Int {
            return when (this) {
                ClusterHealth.Healthy -> R.color.widget_health_healthy
                ClusterHealth.Degraded -> R.color.widget_health_degraded
                ClusterHealth.Unreachable -> R.color.widget_health_unreachable
            }
        }

    }
}
