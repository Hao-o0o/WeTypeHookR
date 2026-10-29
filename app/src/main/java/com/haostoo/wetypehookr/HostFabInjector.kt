package com.haostoo.wetypehookr

import android.animation.ValueAnimator
import android.app.Activity
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.highcapable.yukihookapi.hook.factory.registerModuleActivities
import kotlin.math.hypot
import kotlin.math.min

/**
 * 在受支持输入法的「设置主 Activity」上注入一个可拖动的 FAB。
 *
 * - 只在白名单里的设置主页面注入，不全局 Hook 所有 Activity；
 * - 用 View 的 tag 去重，Activity 重建后不会叠加多个；
 * - 拖动与点击分开判定；松手吸附到最近侧边，位置写宿主的 `ui.json`；
 * - 点击直接 `startActivity` 到 [SettingsActivity]（模块 Activity 已在宿主内注册）。
 */
object HostFabInjector {

    private const val TAG = "WETYPE_FAB"
    private const val VIEW_TAG = "haostoo_host_fab_v1"

    fun isTargetSettingsActivity(hostPackage: String, activityClass: String): Boolean =
        HostCatalog.settingsActivity(hostPackage) == activityClass

    fun attach(activity: Activity, hostPackage: String, ui: SettingsModel.UiState) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        if (decor.findViewWithTag<View>(VIEW_TAG) != null) return

        val resources = activity.resources
        val size = dp(resources, 56f)
        val fab = HostFabView(activity)
        fab.tag = VIEW_TAG
        fab.isClickable = true
        fab.setOnClickListener { launchSettings(activity) }

        decor.addView(fab, ViewGroup.LayoutParams(size, size))
        decor.post {
            placeInitially(decor, fab, size, ui)
            bindTouch(decor, fab, size, ui, resources)
        }
    }

    private fun placeInitially(
        decor: ViewGroup,
        fab: View,
        size: Int,
        ui: SettingsModel.UiState,
    ) {
        val maxX = (decor.width - size).coerceAtLeast(0)
        val maxY = (decor.height - size).coerceAtLeast(0)
        fab.translationX = if (ui.fabX in 0f..1f) ui.fabX * maxX else maxX.toFloat()
        fab.translationY = if (ui.fabY in 0f..1f) ui.fabY * maxY else maxY * 0.62f
    }

    private fun bindTouch(
        decor: ViewGroup,
        fab: View,
        size: Int,
        ui: SettingsModel.UiState,
        resources: Resources,
    ) {
        val touchSlop = dp(resources, 8f).toFloat()
        var startX = 0f
        var startY = 0f
        var downRawX = 0f
        var downRawY = 0f
        var moved = false

        fab.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = view.translationX
                    startY = view.translationY
                    downRawX = event.rawX
                    downRawY = event.rawY
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!moved && hypot(dx, dy) > touchSlop) moved = true
                    if (moved) {
                        val maxX = (decor.width - size).coerceAtLeast(0).toFloat()
                        val maxY = (decor.height - size).coerceAtLeast(0).toFloat()
                        view.translationX = (startX + dx).coerceIn(0f, maxX)
                        view.translationY = (startY + dy).coerceIn(0f, maxY)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        snapToEdge(decor, view, size, ui)
                    } else {
                        view.performClick()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (moved) snapToEdge(decor, view, size, ui)
                    true
                }

                else -> false
            }
        }
    }

    /** 松手后吸附到最近侧边，并把归一化位置写进该宿主的 `ui.json`。 */
    private fun snapToEdge(
        decor: ViewGroup,
        fab: View,
        size: Int,
        ui: SettingsModel.UiState,
    ) {
        val maxX = (decor.width - size).coerceAtLeast(0).toFloat()
        val maxY = (decor.height - size).coerceAtLeast(0).toFloat()
        if (maxX <= 0f || maxY <= 0f) return

        val target = if (fab.translationX + size / 2f < decor.width / 2f) 0f else maxX
        ValueAnimator.ofFloat(fab.translationX, target).apply {
            duration = 180
            addUpdateListener { fab.translationX = it.animatedValue as Float }
            start()
        }

        runCatching {
            val context = fab.context
            SettingsStore.updateAsync(context, context.packageName) { state ->
                state.copy(
                    ui = state.ui.copy(
                        fabX = (target / maxX).coerceIn(0f, 1f),
                        fabY = (fab.translationY / maxY).coerceIn(0f, 1f),
                    ),
                )
            }
        }
    }

    private fun launchSettings(activity: Activity) {
        runCatching {
            // 模块 Activity 的注册是「每进程一次」的：withApplicationReady 只在首个进程回调，
            // 输入法设置页若跑在别的进程（如豆包），点击时补一次注册，否则会 ActivityNotFound。
            HostCatalog.settingsActivity(activity.packageName)?.let { proxy ->
                activity.registerModuleActivities(proxy)
            }
            activity.startActivity(android.content.Intent(activity, SettingsActivity::class.java))
        }.onFailure {
            Log.w(TAG, "launch SettingsActivity failed", it)
            Toast.makeText(activity, R.string.toast_open_settings_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(resources: Resources, value: Float): Int =
        (value * resources.displayMetrics.density).toInt()

    /** 圆形 FAB：纯 Canvas 绘制，避免依赖宿主主题与图标资源。 */
    private class HostFabView(context: android.content.Context) : View(context) {

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF3A6DF0.toInt()
        }

        private val bars = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 2.4f * context.resources.displayMetrics.density
        }

        override fun onDraw(canvas: Canvas) {
            val density = resources.displayMetrics.density
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawCircle(cx, cy, min(width, height) / 2f - density, fillPaint)

            val heights = floatArrayOf(7f, 12f, 16f, 12f, 7f)
            val gap = 5f * density
            var x = cx - gap * 2
            for (h in heights) {
                val half = h * density / 2f
                canvas.drawLine(x, cy - half, x, cy + half, bars)
                x += gap
            }
        }
    }
}
