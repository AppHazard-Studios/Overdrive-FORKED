package com.overdrive.app.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Always-visible HUD bar shown at the bottom of MultiCameraPlayerFragment.
 * Displays speed, gear, pedal activity, and turn signals from the JSON sidecar
 * telemetry[] array. Hidden (GONE) when no telemetry data is loaded.
 */
class TelemetryBarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class TelemetryEntry(
        val tMs: Long,
        val speedKmh: Int,
        val gearMode: Int,
        val accelPct: Int,
        val brakePct: Int,
        val leftSignal: Boolean,
        val rightSignal: Boolean
    )

    private var entries: List<TelemetryEntry> = emptyList()
    private var positionMs: Long = 0
    private var current: TelemetryEntry? = null

    private val paintBg = Paint().apply { color = 0xDD000000.toInt() }
    private val paintSpeed = Paint().apply {
        color = 0xFFFFFFFF.toInt(); isAntiAlias = true
        textAlign = Paint.Align.LEFT; isFakeBoldText = true
    }
    private val paintUnit = Paint().apply {
        color = 0xAAFFFFFF.toInt(); isAntiAlias = true; textAlign = Paint.Align.LEFT
    }
    private val paintGearBg = Paint().apply { isAntiAlias = true }
    private val paintGearText = Paint().apply {
        color = 0xFFFFFFFF.toInt(); isAntiAlias = true
        textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val paintSignal = Paint().apply { isAntiAlias = true; textAlign = Paint.Align.LEFT }
    private val paintPedal = Paint().apply { isAntiAlias = true; textAlign = Paint.Align.LEFT }
    private val gearRect = RectF()

    fun setTelemetryData(data: List<TelemetryEntry>) {
        entries = data
        resolveEntry()
        invalidate()
    }

    fun setPosition(posMs: Long) {
        positionMs = posMs
        resolveEntry()
        invalidate()
    }

    private fun resolveEntry() {
        if (entries.isEmpty()) { current = null; return }
        var lo = 0; var hi = entries.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (entries[mid].tMs <= positionMs) lo = mid else hi = mid - 1
        }
        current = entries[lo]
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val entry = current ?: return
        val w = width.toFloat()
        val h = height.toFloat()

        canvas.drawRect(0f, 0f, w, h, paintBg)

        val baseline = h * 0.72f
        var x = h * 0.3f

        // Left turn signal
        if (entry.leftSignal) {
            paintSignal.color = 0xFFFFAA00.toInt()
            paintSignal.textSize = h * 0.52f
            canvas.drawText("◄", x, baseline, paintSignal)
        }
        x += h * 0.75f

        // Speed (large)
        val speedStr = entry.speedKmh.toString()
        paintSpeed.textSize = h * 0.58f
        canvas.drawText(speedStr, x, baseline, paintSpeed)
        x += paintSpeed.measureText(speedStr) + h * 0.08f

        // Unit
        paintUnit.textSize = h * 0.28f
        canvas.drawText("km/h", x, baseline - h * 0.12f, paintUnit)
        x += paintUnit.measureText("km/h") + h * 0.25f

        // Gear badge (rounded rect)
        val gr = h * 0.36f
        val gearCx = x + gr
        gearRect.set(gearCx - gr, h * 0.1f, gearCx + gr, h * 0.9f)
        paintGearBg.color = gearColorFor(entry.gearMode)
        canvas.drawRoundRect(gearRect, gr * 0.35f, gr * 0.35f, paintGearBg)
        paintGearText.textSize = h * 0.52f
        canvas.drawText(gearCharFor(entry.gearMode).toString(), gearCx, baseline, paintGearText)
        x += gr * 2 + h * 0.25f

        // Pedal indicators (only when > 5%)
        paintPedal.textSize = h * 0.32f
        if (entry.accelPct > 5) {
            paintPedal.color = 0xFF88EE88.toInt()
            val s = "▲${entry.accelPct}%"
            canvas.drawText(s, x, baseline, paintPedal)
            x += paintPedal.measureText(s) + h * 0.15f
        }
        if (entry.brakePct > 5) {
            paintPedal.color = 0xFFFF7777.toInt()
            canvas.drawText("▼${entry.brakePct}%", x, baseline, paintPedal)
        }

        // Right turn signal (right-aligned)
        if (entry.rightSignal) {
            paintSignal.color = 0xFFFFAA00.toInt()
            paintSignal.textSize = h * 0.52f
            val rw = paintSignal.measureText("►")
            canvas.drawText("►", w - h * 0.3f - rw, baseline, paintSignal)
        }
    }

    private fun gearCharFor(gear: Int) = when (gear) {
        1 -> 'P'; 2 -> 'R'; 3 -> 'N'; 4 -> 'D'; 5 -> 'M'; 6 -> 'S'; else -> '?'
    }

    private fun gearColorFor(gear: Int) = when (gear) {
        1 -> 0xFF555555.toInt()
        2 -> 0xFFAA0000.toInt()
        3 -> 0xFF0055AA.toInt()
        4 -> 0xFF006600.toInt()
        5 -> 0xFF7700AA.toInt()
        6 -> 0xFFAA5500.toInt()
        else -> 0xFF333333.toInt()
    }
}
