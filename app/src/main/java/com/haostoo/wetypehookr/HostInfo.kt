package com.haostoo.wetypehookr

import android.content.Context
import android.content.pm.PackageManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 宿主应用的元信息查询（版本号、安装状态），供宿主设置页展示。 */
object HostInfo {

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    /** 例如 `3.5.3 (3530)`；查询失败返回 null（界面显示“未知”）。 */
    fun versionText(context: Context, hostPackage: String): String? = runCatching {
        val info = context.packageManager.getPackageInfo(hostPackage, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "${info.versionName ?: "未知"} ($code)"
    }.getOrNull()

    fun isInstalled(context: Context, hostPackage: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(hostPackage, 0)
        true
    }.getOrDefault(false)

    fun formatTime(millis: Long): String = timeFormat.format(Date(millis))
}
