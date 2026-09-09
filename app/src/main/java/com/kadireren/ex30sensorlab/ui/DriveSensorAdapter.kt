package com.kadireren.ex30sensorlab.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView
import com.kadireren.ex30sensorlab.R
import com.kadireren.ex30sensorlab.model.SampleStatus
import com.kadireren.ex30sensorlab.model.SensorSample
import com.kadireren.ex30sensorlab.model.SensorSource

enum class DriveLayout(val label: String) {
    GRID("Kart Izgara"), GAUGES("Dijital Kadran"), MODERN("Modern Panel"),
}

class DriveSensorAdapter(
    private val context: Context,
    var sourceFilter: SensorSource,
    var layout: DriveLayout,
    private val pageSize: Int = 9,
) : BaseAdapter() {
    private val samples = linkedMapOf<String, SensorSample>()
    var page: Int = 0

    fun update(sample: SensorSample) { samples[sample.definition.key] = sample; notifyDataSetChanged() }
    fun clear() { samples.clear(); notifyDataSetChanged() }

    fun pageCount(): Int {
        val count = orderedSamples().size
        return if (count == 0) 1 else (count + pageSize - 1) / pageSize
    }

    private fun orderedSamples(): List<SensorSample> = samples.values
        .filter { it.definition.source == sourceFilter }
        .filter { it.displayValue != "—" && it.status !in setOf(SampleStatus.WAITING, SampleStatus.UNSUPPORTED, SampleStatus.PERMISSION_DENIED) }
        .sortedWith(compareBy({ DriveSensorOrder.priority(it.definition.key, sourceFilter) }, { it.definition.name }))

    private fun pageSamples(): List<SensorSample> {
        val ordered = orderedSamples()
        if (ordered.isEmpty()) return emptyList()
        val safePage = page.coerceIn(0, pageCount() - 1)
        return ordered.drop(safePage * pageSize).take(pageSize)
    }

    override fun getCount(): Int {
        val count = pageSamples().size
        val columns = 4
        return when (layout) {
            DriveLayout.GRID -> (count + columns - 1) / columns
            DriveLayout.GAUGES -> if (count == 0) 0 else 1 + ((count - 2).coerceAtLeast(0) + columns - 1) / columns
            DriveLayout.MODERN -> if (count == 0) 0 else if (count <= 5) 1 else 2
        }
    }

    override fun getItem(position: Int): SensorSample = pageSamples().first()
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View = when (layout) {
        DriveLayout.GRID -> {
            equalRow(pageSamples().drop(position * 4).take(4), if (isEx30Window()) 120 else 88, CardStyle.GRID, 4)
        }
        DriveLayout.GAUGES -> if (position == 0) {
            equalRow(pageSamples().take(2), if (isEx30Window()) 260 else 142, CardStyle.GAUGE, 2)
        } else {
            equalRow(pageSamples().drop(2 + (position - 1) * 4).take(4), if (isEx30Window()) 92 else 72, CardStyle.COMPACT, 4)
        }
        DriveLayout.MODERN -> modernRow(position)
    }

    private fun modernRow(position: Int): View {
        val page = pageSamples()
        if (position > 0) return equalRow(page.drop(5).take(4), if (isEx30Window()) 108 else 84, CardStyle.GRID, 4)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(sensorCard(page.first(), CardStyle.HERO), weightedParams(dp(8)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val remaining = page.drop(1).take(4)
                val sideHeight = if (isEx30Window()) 126 else 86
                addView(equalRow(remaining.take(2), sideHeight, CardStyle.COMPACT, 2), rowParams())
                addView(equalRow(remaining.drop(2), sideHeight, CardStyle.COMPACT, 2), rowParams(dp(6)))
            }, weightedParams())
            layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (isEx30Window()) 258 else 178))
        }
    }

    private fun equalRow(items: List<SensorSample>, heightDp: Int, style: CardStyle, slots: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        items.forEachIndexed { index, sample ->
            addView(sensorCard(sample, style), weightedParams(if (index < slots - 1) dp(8) else 0))
        }
        repeat((slots - items.size).coerceAtLeast(0)) { index ->
            addView(View(context), weightedParams(if (items.size + index < slots - 1) dp(8) else 0))
        }
        layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp))
    }

    private fun sensorCard(sample: SensorSample, style: CardStyle): View = DriveCardView(context, style, sample.definition.key).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(if (style == CardStyle.GAUGE) 8 else 62), dp(5), dp(10), dp(5))
        background = cardBackground(false)
        addView(TextView(context).apply {
            text = sample.definition.name
            setTextColor(context.getColor(R.color.lab_text))
            textSize = when (style) { CardStyle.HERO -> 19f; CardStyle.GAUGE -> 18f; else -> 14f }
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 2
        })
        addView(TextView(context).apply {
            text = sample.displayValue
            setTextColor(context.getColor(if (sample.definition.key.contains("BRAKE") || sample.definition.key.startsWith("brake") || sample.definition.key.contains("GEAR")) R.color.lab_success else R.color.lab_accent))
            textSize = when (style) { CardStyle.HERO -> 52f; CardStyle.GAUGE -> 38f; CardStyle.COMPACT -> 21f; CardStyle.GRID -> 25f }
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
        })
    }

    private fun cardBackground(round: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(if (round) 72 else 14).toFloat()
        setColor(context.getColor(R.color.lab_surface))
        setStroke(dp(if (round) 3 else 1), context.getColor(if (round) R.color.lab_accent else R.color.lab_border))
    }

    private fun weightedParams(endMargin: Int = 0) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = endMargin }
    private fun rowParams(topMargin: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { this.topMargin = topMargin }
    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
    private fun isEx30Window(): Boolean {
        val config = context.resources.configuration
        return config.screenWidthDp <= config.screenHeightDp * 1.1f
    }
    private enum class CardStyle { GRID, GAUGE, COMPACT, HERO }

    private inner class DriveCardView(context: Context, private val style: CardStyle, private val sensorKey: String) : LinearLayout(context) {
        private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(R.color.lab_accent)
            style = Paint.Style.STROKE
            strokeWidth = dp(5).toFloat()
            strokeCap = Paint.Cap.ROUND
        }
        private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(R.color.lab_border)
            style = Paint.Style.STROKE
            strokeWidth = dp(3).toFloat()
        }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(R.color.lab_text_secondary)
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        init { setWillNotDraw(false) }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            when (style) {
                CardStyle.GAUGE -> {
                    val inset = dp(15).toFloat()
                    val oval = RectF(inset, inset, width - inset, height * 1.55f)
                    canvas.drawArc(oval, 195f, 150f, false, accentPaint)
                    for (index in 0..8) {
                        val angle = Math.toRadians((195f + index * 18.75f).toDouble())
                        val cx = oval.centerX()
                        val cy = oval.centerY()
                        val rx = oval.width() / 2f
                        val ry = oval.height() / 2f
                        val outerX = cx + kotlin.math.cos(angle).toFloat() * rx
                        val outerY = cy + kotlin.math.sin(angle).toFloat() * ry
                        val innerX = cx + kotlin.math.cos(angle).toFloat() * (rx - dp(9))
                        val innerY = cy + kotlin.math.sin(angle).toFloat() * (ry - dp(9))
                        canvas.drawLine(innerX, innerY, outerX, outerY, roadPaint)
                    }
                }
                CardStyle.HERO -> {
                    val horizon = height * 0.58f
                    val mountains = Path().apply {
                        moveTo(0f, horizon)
                        lineTo(width * .16f, horizon - dp(15))
                        lineTo(width * .31f, horizon - dp(5))
                        lineTo(width * .46f, horizon - dp(18))
                        lineTo(width * .65f, horizon - dp(3))
                        lineTo(width * .82f, horizon - dp(13))
                        lineTo(width.toFloat(), horizon)
                    }
                    canvas.drawPath(mountains, roadPaint)
                    val road = Path().apply {
                        moveTo(width * 0.43f, horizon)
                        lineTo(width * 0.16f, height.toFloat())
                        moveTo(width * 0.57f, horizon)
                        lineTo(width * 0.84f, height.toFloat())
                        moveTo(width * 0.49f, horizon)
                        lineTo(width * 0.46f, height.toFloat())
                        moveTo(width * 0.51f, horizon)
                        lineTo(width * 0.54f, height.toFloat())
                    }
                    canvas.drawPath(road, roadPaint)
                }
                else -> Unit
            }
            if (style != CardStyle.GAUGE) drawSensorIcon(canvas)
        }

        private fun drawSensorIcon(canvas: Canvas) {
            iconPaint.color = context.getColor(when {
                sensorKey.contains("BATTERY_LEVEL") || sensorKey.contains("soc") || sensorKey.contains("GEAR") || sensorKey.contains("BRAKE") || sensorKey.startsWith("brake") -> R.color.lab_success
                sensorKey.contains("TEMPERATURE") || sensorKey.contains("temp") -> R.color.lab_warning
                else -> R.color.lab_text
            })
            val x = dp(29).toFloat()
            val y = dp(if (style == CardStyle.HERO) 30 else 24).toFloat()
            val r = dp(if (style == CardStyle.HERO) 18 else 14).toFloat()
            when {
                sensorKey.contains("SPEED") || sensorKey == "vehicle_speed" -> {
                    canvas.drawArc(RectF(x - r, y - r, x + r, y + r), 190f, 160f, false, iconPaint)
                    canvas.drawLine(x, y, x + r * .65f, y - r * .45f, iconPaint)
                }
                sensorKey.contains("BATTERY_LEVEL") || sensorKey.contains("soc") || sensorKey.contains("voltage") -> {
                    canvas.drawRoundRect(RectF(x - r, y - r * .65f, x + r, y + r * .65f), dp(2).toFloat(), dp(2).toFloat(), iconPaint)
                    canvas.drawLine(x + r, y - r * .25f, x + r * 1.25f, y - r * .25f, iconPaint)
                    canvas.drawLine(x + r, y + r * .25f, x + r * 1.25f, y + r * .25f, iconPaint)
                }
                sensorKey.contains("CHARGE_RATE") || sensorKey.contains("power") || sensorKey.contains("current") -> {
                    val bolt = Path().apply {
                        moveTo(x + r * .15f, y - r)
                        lineTo(x - r * .7f, y + r * .15f)
                        lineTo(x - r * .05f, y + r * .15f)
                        lineTo(x - r * .25f, y + r)
                        lineTo(x + r * .75f, y - r * .25f)
                        lineTo(x + r * .1f, y - r * .25f)
                    }
                    canvas.drawPath(bolt, iconPaint)
                }
                sensorKey.contains("RANGE") || sensorKey.contains("odometer") -> {
                    canvas.drawLine(x - r, y + r, x - r * .3f, y - r, iconPaint)
                    canvas.drawLine(x + r, y + r, x + r * .3f, y - r, iconPaint)
                    canvas.drawLine(x, y + r, x, y + r * .35f, iconPaint)
                    canvas.drawLine(x, y - r * .1f, x, y - r * .65f, iconPaint)
                }
                sensorKey.contains("TEMPERATURE") || sensorKey.contains("temp") -> {
                    canvas.drawCircle(x, y + r * .65f, r * .38f, iconPaint)
                    canvas.drawLine(x, y + r * .3f, x, y - r, iconPaint)
                    canvas.drawRoundRect(RectF(x - r * .28f, y - r, x + r * .28f, y + r * .45f), r * .28f, r * .28f, iconPaint)
                }
                sensorKey.contains("GEAR") -> drawIconLetter(canvas, x, y, "D")
                sensorKey.contains("BRAKE") || sensorKey.startsWith("brake") -> drawIconLetter(canvas, x, y, "P")
                sensorKey.contains("wheel") || sensorKey.contains("WHEEL") -> {
                    canvas.drawCircle(x, y, r, iconPaint)
                    canvas.drawCircle(x, y, r * .35f, iconPaint)
                }
                else -> {
                    val wave = Path().apply {
                        moveTo(x - r, y)
                        lineTo(x - r * .5f, y)
                        lineTo(x - r * .2f, y - r)
                        lineTo(x + r * .2f, y + r)
                        lineTo(x + r * .5f, y)
                        lineTo(x + r, y)
                    }
                    canvas.drawPath(wave, iconPaint)
                }
            }
        }

        private fun drawIconLetter(canvas: Canvas, x: Float, y: Float, text: String) {
            canvas.drawCircle(x, y, dp(9).toFloat(), iconPaint)
            iconPaint.style = Paint.Style.FILL
            iconPaint.textAlign = Paint.Align.CENTER
            iconPaint.textSize = dp(12).toFloat()
            iconPaint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText(text, x, y + dp(4), iconPaint)
            iconPaint.style = Paint.Style.STROKE
        }
    }
}

object DriveSensorOrder {
    private val vhalPriorities = mapOf(
        "PERF_VEHICLE_SPEED_DISPLAY" to 1, "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE" to 2,
        "EV_BATTERY_LEVEL" to 3, "RANGE_REMAINING" to 4, "ENV_OUTSIDE_TEMPERATURE" to 5,
        "GEAR_SELECTION" to 6, "CURRENT_GEAR" to 7, "PERF_VEHICLE_SPEED" to 10,
        "PARKING_BRAKE_ON" to 12, "IGNITION_STATE" to 13, "EV_CHARGE_PORT_CONNECTED" to 14,
    )
    private val obdPriorities = mapOf(
        "vehicle_speed" to 1, "wheel_fl" to 2, "wheel_fr" to 3, "wheel_rl" to 4,
        "wheel_rr" to 5, "hv_power" to 6, "soc_display" to 7, "hv_voltage" to 8,
        "hv_current" to 9, "odometer" to 10, "brake_multi" to 11, "hv_temp_avg" to 12,
        "hv_temp_max" to 13, "hv_soh" to 14,
    )
    fun priority(key: String, source: SensorSource): Int = when (source) {
        SensorSource.VHAL -> vhalPriorities[key] ?: 100
        SensorSource.OBD -> obdPriorities[key] ?: 100
        SensorSource.SCANNER -> 100
    }
}
