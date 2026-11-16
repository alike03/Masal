package com.alike.masal.data

import android.content.Context
import android.content.Intent
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
            .sortedBy { it.label.lowercase() }
    }

    fun launch(context: Context, pkg: String) {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
