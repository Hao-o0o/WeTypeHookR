package com.haostoo.wetypehookr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs

/** 曲线几何：节点与采样点均为画布像素坐标，节点索引与原始数组一一对应。 */
internal class CurveGeometry(
    val nodes: List<Offset>,
    val curve: List<Offset>,
    val totalTimeMs: Float,
    val maxAmplitude: Int,
)

/**
 * 将离散 timings/amplitudes 转为可视化节点：
 * x 轴为该波段累计时长（总时长为 0 时退化为等距点位），y 轴为强度 0..255（反向绘制）。
 */
internal fun buildCurveGeometry(
    width: Float,
    height: Float,
    density: Density,
    timings: List<Int>,
    amplitudes: List<Int>,
    count: Int,
): CurveGeometry {
    val padL = with(density) { 14.dp.toPx() }
    val padR = with(density) { 10.dp.toPx() }
    val padT = with(density) { 14.dp.toPx() }
    val padB = with(density) { 18.dp.toPx() }
    val n = count.coerceIn(2, 8)
    val usableW = (width - padL - padR).coerceAtLeast(1f)
    val usableH = (height - padT - padB).coerceAtLeast(1f)
    val t = timings.take(n).map { it.coerceAtLeast(0) }
    val a = amplitudes.take(n).map { it.coerceIn(0, 255) }
    val cum = FloatArray(n)
    var acc = 0f
    for (i in 0 until n) {
        cum[i] = acc
        acc += t[i].toFloat()
    }
    val total = acc
    val xs = FloatArray(n)
    val ys = FloatArray(n)
    for (i in 0 until n) {
        xs[i] = if (total > 0f) {
            padL + usableW * (cum[i] / total)
        } else {
            padL + usableW * (i.toFloat() / (n - 1).coerceAtLeast(1))
        }
        ys[i] = padT + usableH * (1f - a[i] / 255f)
    }
    return CurveGeometry(
        nodes = List(n) { Offset(xs[it], ys[it]) },
        curve = pchipSample(xs, ys),
        totalTimeMs = total,
        maxAmplitude = a.maxOrNull() ?: 0,
    )
}

private fun endpointSlope(h0: Float, h1: Float?, d0: Float, d1: Float?): Float {
    if (h1 == null || d1 == null) return d0
    val d = ((2f * h0 + h1) * d0 - h0 * d1) / (h0 + h1)
    return when {
        d * d0 <= 0f -> 0f
        d0 * d1 <= 0f && abs(d) > abs(3f * d0) -> 3f * d0
        else -> d
    }
}

/**
 * 单调三次 Hermite（PCHIP）采样：不会在相邻点间产生明显过冲，
 * 因此曲线不会越过 0..255 的振幅边界。零长度区间直接跳过，避免除零。
 */
internal fun pchipSample(xs: FloatArray, ys: FloatArray, perSegment: Int = 24): List<Offset> {
    val n = xs.size
    if (n < 2) return if (n == 1) listOf(Offset(xs[0], ys[0])) else emptyList()
    val h = FloatArray(n - 1) { xs[it + 1] - xs[it] }
    val delta = FloatArray(n - 1) { if (h[it] > 1e-4f) (ys[it + 1] - ys[it]) / h[it] else 0f }
    val d = FloatArray(n)
    for (k in 1 until n - 1) {
        if (delta[k - 1] * delta[k] <= 0f) {
            d[k] = 0f
        } else {
            val w1 = 2f * h[k] + h[k - 1]
            val w2 = h[k] + 2f * h[k - 1]
            d[k] = (w1 + w2) / (w1 / delta[k - 1] + w2 / delta[k])
        }
    }
    d[0] = endpointSlope(h[0], if (n > 2) h[1] else null, delta[0], if (n > 2) delta[1] else null)
    d[n - 1] = endpointSlope(h[n - 2], if (n > 3) h[n - 3] else null, delta[n - 2], if (n > 3) delta[n - 3] else null)
    val out = ArrayList<Offset>(n * perSegment)
    for (k in 0 until n - 1) {
        if (h[k] <= 1e-4f) {
            // 零长区间（timings[0]=0 或重复时间点）：直接连线，不跳过起点
            out.add(Offset(xs[k], ys[k]))
            out.add(Offset(xs[k + 1], ys[k + 1]))
            continue
        }
        for (s in 0..perSegment) {
            val u = s.toFloat() / perSegment
            val h00 = 2f * u * u * u - 3f * u * u + 1f
            val h10 = u * u * u - 2f * u * u + u
            val h01 = -2f * u * u * u + 3f * u * u
            val h11 = u * u * u - u * u
            val y = h00 * ys[k] + h10 * h[k] * d[k] + h01 * ys[k + 1] + h11 * h[k] * d[k + 1]
            out.add(Offset(xs[k] + u * h[k], y))
        }
    }
    return out
}

/**
 * 波形曲线预览：平滑拟合曲线 + 可点选节点。
 * 曲线仅作视觉预览，实际震动始终使用原始离散 timings/amplitudes。
 */
@Composable
fun WaveformCurve(
    timings: List<Int>,
    amplitudes: List<Int>,
    count: Int,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val primary = MiuixTheme.colorScheme.primary
    val onSurface = MiuixTheme.colorScheme.onSurface
    val grid = MiuixTheme.colorScheme.dividerLine
    val track = MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.07f)

    var size by remember { mutableStateOf(IntSize.Zero) }
    val geometry = if (size.width > 0 && size.height > 0) {
        buildCurveGeometry(size.width.toFloat(), size.height.toFloat(), density, timings, amplitudes, count)
    } else {
        null
    }
    val hitRadius = with(density) { 22.dp.toPx() }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp)
            .onSizeChanged { size = it }
            .semantics { contentDescription = "波形曲线预览，点击节点可编辑对应波段；下方输入行同样可编辑" }
            .pointerInput(geometry) {
                detectTapGestures { offset ->
                    val nodes = geometry?.nodes ?: return@detectTapGestures
                    var best = -1
                    var bestDist = Float.MAX_VALUE
                    nodes.forEachIndexed { i, p ->
                        val dist = (p - offset).getDistance()
                        if (dist < bestDist) {
                            bestDist = dist
                            best = i
                        }
                    }
                    if (best >= 0 && bestDist <= hitRadius) onSelect(best)
                }
            },
    ) {
        drawRoundRect(color = track, cornerRadius = CornerRadius(12.dp.toPx()))
        val padL = 14.dp.toPx()
        val padB = 18.dp.toPx()
        val right = this.size.width - 10.dp.toPx()
        val bottom = this.size.height - padB
        for (frac in listOf(0f, 0.5f, 1f)) {
            val y = padB + (bottom - padB) * frac
            drawLine(grid, Offset(padL, y), Offset(right, y), strokeWidth = 1.dp.toPx())
        }
        drawLine(grid, Offset(padL, 0f), Offset(padL, bottom), strokeWidth = 1.dp.toPx())
        drawLine(grid, Offset(padL, bottom), Offset(right, bottom), strokeWidth = 1.dp.toPx())

        val geo = geometry ?: return@Canvas
        val path = Path().apply {
            geo.curve.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
        }
        drawPath(path, color = primary, style = Stroke(width = 3.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        geo.nodes.forEachIndexed { i, p ->
            val selected = i == selectedIndex
            if (selected) drawCircle(color = primary.copy(alpha = 0.18f), radius = 11.dp.toPx(), center = p)
            drawCircle(
                color = if (selected) primary else onSurface,
                radius = (if (selected) 4.5f else 3f).dp.toPx(),
                center = p,
            )
        }
    }
}
