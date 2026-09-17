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
import kotlin.math.max

enum class DriveLayout(val label: String) {
    GRID("Kart Izgara"), GAUGES("Dijital Kadran"), MODERN("Modern Panel"),
}

class DriveSensorAdapter(
    private val context: Context,
    var sourceFilter: SensorSource,
    var layout: DriveLayout,
    private val visibleKeys: ((SensorSource) -> Set<String>)? = null,
    private val language: UiLanguage = UiLanguage.TURKISH,
    var pageSize: Int = DEFAULT_PAGE_SIZE,
) : BaseAdapter() {
    private val samples = linkedMapOf<String, SensorSample>()
    var page: Int = 0
    private var viewportHeightPx: Int = 0
    private var rowGapPx: Int = dp(4)

    fun update(sample: SensorSample) { samples[sample.definition.key] = sample; notifyDataSetChanged() }
    fun clear() { samples.clear(); notifyDataSetChanged() }

    /** Pack as many sensors as the ListView height allows, then stretch rows to fill it. */
    fun fitToViewport(widthPx: Int, heightPx: Int, gapPx: Int = dp(4)): Boolean {
        if (widthPx <= 0 || heightPx <= 0) return false
        val nextPageSize = computePageSize(heightPx)
        val changed = rowGapPx != gapPx || viewportHeightPx != heightPx || pageSize != nextPageSize
        if (!changed) return false
        rowGapPx = gapPx
        viewportHeightPx = heightPx
        pageSize = nextPageSize
        page = page.coerceIn(0, pageCount() - 1)
        notifyDataSetChanged()
        return true
    }

    fun pageCount(): Int {
        val count = orderedSamples().size
        return if (count == 0) 1 else (count + pageSize - 1) / pageSize
    }

    private fun orderedSamples(): List<SensorSample> = samples.values
        .filter { it.definition.source == sourceFilter }
        .filter { sample -> visibleKeys?.invoke(sourceFilter)?.let { sample.definition.key in it } ?: true }
        .filter { it.displayValue != "—" && it.status !in setOf(SampleStatus.WAITING, SampleStatus.UNSUPPORTED, SampleStatus.PERMISSION_DENIED) }
        .sortedWith(compareBy({ DriveSensorOrder.priority(it.definition.key, sourceFilter) }, { it.definition.name }))

    private fun pageSamples(): List<SensorSample> {
        val ordered = orderedSamples()
        if (ordered.isEmpty()) return emptyList()
        val safePage = page.coerceIn(0, pageCount() - 1)
        return ordered.drop(safePage * pageSize).take(pageSize)
    }

    private fun computePageSize(heightPx: Int): Int {
        val heightDp = heightPx / context.resources.displayMetrics.density
        val minRowDp = if (isEx30Window()) 72f else 64f
        val rows = max(4, (heightDp / minRowDp).toInt())
        return when (layout) {
            DriveLayout.GRID -> (COLUMNS * rows).coerceIn(12, 28)
            DriveLayout.GAUGES -> (2 + COLUMNS * (rows - 1)).coerceIn(10, 26)
            DriveLayout.MODERN -> (5 + COLUMNS * (rows - 1).coerceAtLeast(1)).coerceIn(13, 29)
        }
    }

    override fun getCount(): Int {
        val count = pageSamples().size
        return when (layout) {
            DriveLayout.GRID -> (count + COLUMNS - 1) / COLUMNS
            DriveLayout.GAUGES -> if (count == 0) 0 else 1 + ((count - 2).coerceAtLeast(0) + COLUMNS - 1) / COLUMNS
            DriveLayout.MODERN -> if (count == 0) 0 else 1 + ((count - 5).coerceAtLeast(0) + COLUMNS - 1) / COLUMNS
        }
    }

    override fun getItem(position: Int): SensorSample = pageSamples().first()
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View = when (layout) {
        DriveLayout.GRID -> {
            equalRow(pageSamples().drop(position * COLUMNS).take(COLUMNS), rowHeightFor(position), CardStyle.GRID, COLUMNS)
        }
        DriveLayout.GAUGES -> if (position == 0) {
            equalRow(pageSamples().take(2), rowHeightFor(position), CardStyle.GAUGE, 2)
        } else {
            equalRow(
                pageSamples().drop(2 + (position - 1) * COLUMNS).take(COLUMNS),
                rowHeightFor(position),
                CardStyle.COMPACT,
                COLUMNS,
            )
        }
        DriveLayout.MODERN -> modernRow(position)
    }

    private fun modernRow(position: Int): View {
        val page = pageSamples()
        if (position > 0) {
            return equalRow(
                page.drop(5 + (position - 1) * COLUMNS).take(COLUMNS),
                rowHeightFor(position),
                CardStyle.GRID,
                COLUMNS,
            )
        }
        val heroHeight = rowHeightFor(0)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(sensorCard(page.first(), CardStyle.HERO), weightedParams(dp(6)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val remaining = page.drop(1).take(4)
                val sideHeight = ((heroHeight - dp(4)) / 2).coerceAtLeast(dp(64))
                addView(equalRow(remaining.take(2), sideHeight, CardStyle.COMPACT, 2, attachListParams = false), rowParams())
                addView(equalRow(remaining.drop(2), sideHeight, CardStyle.COMPACT, 2, attachListParams = false), rowParams(dp(4)))
            }, weightedParams())
            layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heroHeight)
        }
    }

    private fun equalRow(
        items: List<SensorSample>,
        heightPx: Int,
        style: CardStyle,
        slots: Int,
        attachListParams: Boolean = true,
    ) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        items.forEachIndexed { index, sample ->
            addView(sensorCard(sample, style), weightedParams(if (index < slots - 1) dp(6) else 0))
        }
        repeat((slots - items.size).coerceAtLeast(0)) { index ->
            addView(View(context), weightedParams(if (items.size + index < slots - 1) dp(6) else 0))
        }
        if (attachListParams) {
            layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx)
        } else {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx)
        }
    }

    private fun rowHeightFor(position: Int): Int {
        val rows = getCount().coerceAtLeast(1)
        val gaps = rowGapPx * (rows - 1).coerceAtLeast(0)
        val minRow = dp(if (isEx30Window()) 68 else 60)
        if (viewportHeightPx <= 0) {
            return when {
                layout == DriveLayout.GAUGES && position == 0 -> dp(if (isEx30Window()) 200 else 132)
                layout == DriveLayout.MODERN && position == 0 -> dp(if (isEx30Window()) 220 else 160)
                else -> dp(if (isEx30Window()) 96 else 78)
            }
        }
        val base = ((viewportHeightPx - gaps) / rows).coerceAtLeast(minRow)
        return when {
            layout == DriveLayout.GAUGES && position == 0 && rows > 1 -> {
                val remainingRows = rows - 1
                val hero = (viewportHeightPx * 0.38f).toInt().coerceAtLeast(minRow * 2)
                val rest = ((viewportHeightPx - gaps - hero) / remainingRows).coerceAtLeast(minRow)
                if (rest == minRow && hero + remainingRows * minRow + gaps > viewportHeightPx) {
                    (viewportHeightPx - gaps - remainingRows * minRow).coerceAtLeast(minRow)
                } else hero
            }
            layout == DriveLayout.MODERN && position == 0 && rows > 1 -> {
                val remainingRows = rows - 1
                val hero = (viewportHeightPx * 0.34f).toInt().coerceAtLeast(minRow * 2)
                val restBudget = viewportHeightPx - gaps - hero
                if (restBudget < remainingRows * minRow) {
                    (viewportHeightPx - gaps - remainingRows * minRow).coerceAtLeast(minRow)
                } else hero
            }
            layout == DriveLayout.GAUGES && position > 0 && rows > 1 -> {
                val hero = rowHeightFor(0)
                ((viewportHeightPx - gaps - hero) / (rows - 1)).coerceAtLeast(minRow)
            }
            layout == DriveLayout.MODERN && position > 0 && rows > 1 -> {
                val hero = rowHeightFor(0)
                ((viewportHeightPx - gaps - hero) / (rows - 1)).coerceAtLeast(minRow)
            }
            else -> base
        }
    }

    private fun sensorCard(sample: SensorSample, style: CardStyle): View = DriveCardView(context, style, sample.definition.key).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        val iconPad = when (style) {
            CardStyle.GAUGE -> 8
            CardStyle.HERO -> 40
            else -> 34
        }
        setPadding(dp(iconPad), dp(4), dp(8), dp(4))
        background = cardBackground(false)
        addView(TextView(context).apply {
            text = UiLanguageText.sensorName(sample.definition.key, sample.definition.name, language)
            setTextColor(context.getColor(R.color.lab_text))
            textSize = when (style) { CardStyle.HERO -> 16f; CardStyle.GAUGE -> 15f; else -> 12f }
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 2
        })
        addView(TextView(context).apply {
            text = if (language == UiLanguage.ENGLISH) {
                sample.displayValue.replace("sensör", "sensor").replace("hücre", "cell")
            } else {
                sample.displayValue
            }
            setTextColor(context.getColor(if (sample.definition.key.contains("BRAKE") || sample.definition.key.startsWith("brake") || sample.definition.key.contains("GEAR")) R.color.lab_success else R.color.lab_accent))
            textSize = when (style) { CardStyle.HERO -> 44f; CardStyle.GAUGE -> 32f; CardStyle.COMPACT -> 18f; CardStyle.GRID -> 20f }
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
        })
    }

    private fun cardBackground(round: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(if (round) 72 else 12).toFloat()
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
                    val inset = dp(12).toFloat()
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
                        val innerX = cx + kotlin.math.cos(angle).toFloat() * (rx - dp(8))
                        val innerY = cy + kotlin.math.sin(angle).toFloat() * (ry - dp(8))
                        canvas.drawLine(innerX, innerY, outerX, outerY, roadPaint)
                    }
                }
                CardStyle.HERO -> {
                    val horizon = height * 0.58f
                    val mountains = Path().apply {
                        moveTo(0f, horizon)
                        lineTo(width * .16f, horizon - dp(12))
                        lineTo(width * .31f, horizon - dp(4))
                        lineTo(width * .46f, horizon - dp(14))
                        lineTo(width * .65f, horizon - dp(2))
                        lineTo(width * .82f, horizon - dp(10))
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
            val x = dp(20).toFloat()
            val y = dp(if (style == CardStyle.HERO) 24 else 18).toFloat()
            val r = dp(if (style == CardStyle.HERO) 14 else 11).toFloat()
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
            canvas.drawCircle(x, y, dp(8).toFloat(), iconPaint)
            iconPaint.style = Paint.Style.FILL
            iconPaint.textAlign = Paint.Align.CENTER
            iconPaint.textSize = dp(11).toFloat()
            iconPaint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText(text, x, y + dp(4), iconPaint)
            iconPaint.style = Paint.Style.STROKE
        }
    }

    companion object {
        const val COLUMNS = 4
        const val DEFAULT_PAGE_SIZE = 16
    }
}

object DriveSensorOrder {
    private val vhalPriorities = mapOf(
        "PERF_VEHICLE_SPEED_DISPLAY" to 1, "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE" to 2,
        "INSTANT_CONSUMPTION" to 3, "BATTERY_SOC_PERCENT" to 4, "EV_BATTERY_LEVEL" to 5,
        "RANGE_REMAINING" to 6, "ENV_OUTSIDE_TEMPERATURE" to 7,
        "GEAR_SELECTION" to 8, "CURRENT_GEAR" to 9, "PERF_VEHICLE_SPEED" to 10,
        "PARKING_BRAKE_ON" to 12, "IGNITION_STATE" to 13, "EV_CHARGE_PORT_CONNECTED" to 14,
    )
    private val obdPriorities = mapOf(
        "vehicle_speed" to 1, "pedal_pwm" to 2, "erad_motor_speed" to 3, "erad_actual_torque" to 4,
        "wheel_fl" to 5, "wheel_fr" to 6, "wheel_rl" to 7, "wheel_rr" to 8,
        "hv_power" to 9, "soc_display" to 10, "hv_voltage" to 11, "hv_current" to 12,
        "odometer" to 13, "hv_temp_avg" to 14, "hv_temp_max" to 15, "hv_soh" to 16,
    )
    fun priority(key: String, source: SensorSource): Int = when (source) {
        SensorSource.VHAL -> vhalPriorities[key] ?: 100
        SensorSource.OBD -> obdPriorities[key] ?: 100
        SensorSource.SCANNER -> 100
    }
}
