package com.alike.masal.data

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

data class TvApp(val pkg: String, val label: String, val banner: ImageBitmap, val isBanner: Boolean)

object Apps {
    fun load(context: Context): List<TvApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .mapNotNull { ri ->
                runCatching {
                    val ai = ri.activityInfo
                    val banner = ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm)
                    val drawable = banner ?: ai.loadIcon(pm)
                    val bmp = if (banner != null) drawable.toBitmap(320, 180) else drawable.toBitmap(160, 160)
                    TvApp(ai.packageName, ri.loadLabel(pm).toString(), bmp.asImageBitmap(), banner != null)
                }.getOrNull()
            }
            .let { list -> systemSettings(context)?.takeIf { s -> list.none { it.pkg == s.pkg } }?.let { list + it } ?: list }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * The device's own settings app. On TV it has no launcher entry, and without the stock launcher
     * (Masal as home screen) it would otherwise be hard to reach.
     */
    private fun systemSettings(context: Context): TvApp? = runCatching {
        val pm = context.packageManager
        val ai = pm.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.activityInfo ?: return null
        val icon = ai.loadIcon(pm).toBitmap(160, 160)
        TvApp(ai.packageName, SettingsLabel, icon.asImageBitmap(), false)
    }.getOrNull()

    fun openSystemSettings(context: Context) {
        runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    const val SettingsLabel = "Android settings"

    fun launch(context: Context, pkg: String) {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
            // The settings app tile: no launcher entry, opened through its settings action.
            ?: Intent(Settings.ACTION_SETTINGS).setPackage(pkg).takeIf { pm.resolveActivity(it, 0) != null }
            ?: return
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
