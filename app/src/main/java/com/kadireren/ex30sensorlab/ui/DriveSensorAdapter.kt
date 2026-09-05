package com.kadireren.ex30sensorlab.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView
import com.kadireren.ex30sensorlab.model.SampleStatus
import com.kadireren.ex30sensorlab.model.SensorSample
import com.kadireren.ex30sensorlab.model.SensorSource

class DriveSensorAdapter(
    private val context: Context,
    var sourceFilter: SensorSource,
    private val pageSize: Int = 6,
) : BaseAdapter() {
    private val samples = linkedMapOf<String, SensorSample>()
    var page: Int = 0

    fun update(sample: SensorSample) {
        samples[sample.definition.key] = sample
        notifyDataSetChanged()
    }

    fun clear() {
        samples.clear()
        notifyDataSetChanged()
    }

    fun pageCount(): Int {
        val count = orderedSamples().size
        return if (count == 0) 1 else (count + pageSize - 1) / pageSize
    }

    private fun orderedSamples(): List<SensorSample> = samples.values
        .filter { it.definition.source == sourceFilter }
        .filter { sample ->
            sample.displayValue != "—" &&
                sample.status != SampleStatus.WAITING &&
                sample.status != SampleStatus.UNSUPPORTED &&
                sample.status != SampleStatus.PERMISSION_DENIED
        }
        .sortedWith(compareBy({ DriveSensorOrder.priority(it.definition.key, sourceFilter) }, { it.definition.name }))

    private fun pageSamples(): List<SensorSample> {
        val ordered = orderedSamples()
        if (ordered.isEmpty()) return emptyList()
        val safePage = page.coerceIn(0, pageCount() - 1)
        val from = safePage * pageSize
        return ordered.drop(from).take(pageSize)
    }

    override fun getCount(): Int = pageSamples().size

    override fun getItem(position: Int): SensorSample = pageSamples()[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val sample = getItem(position)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(TextView(context).apply {
                text = sample.definition.name
                setTextColor(Color.WHITE)
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 2
            })
            addView(TextView(context).apply {
                text = sample.displayValue
                setTextColor(context.getColor(com.kadireren.ex30sensorlab.R.color.lab_accent))
                textSize = 34f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.END
                maxLines = 2
            })
            layoutParams = android.widget.AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}

object DriveSensorOrder {
    private val vhalPriorities = mapOf(
        "PERF_VEHICLE_SPEED_DISPLAY" to 1,
        "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE" to 2,
        "EV_BATTERY_LEVEL" to 3,
        "RANGE_REMAINING" to 4,
        "ENV_OUTSIDE_TEMPERATURE" to 5,
        "GEAR_SELECTION" to 6,
        "CURRENT_GEAR" to 7,
        "HV_BATTERY_VOLTAGE" to 8,
        "HV_BATTERY_CURRENT" to 9,
        "PERF_VEHICLE_SPEED" to 10,
        "ABS_VEHICLE_SPEED" to 11,
        "PARKING_BRAKE_ON" to 12,
        "IGNITION_STATE" to 13,
        "EV_CHARGE_PORT_CONNECTED" to 14,
    )

    private val obdPriorities = mapOf(
        "vehicle_speed" to 1,
        "wheel_fl" to 2,
        "wheel_fr" to 3,
        "wheel_rl" to 4,
        "wheel_rr" to 5,
        "hv_power" to 6,
        "soc_display" to 7,
        "hv_voltage" to 8,
        "hv_current" to 9,
        "odometer" to 10,
        "brake_multi" to 11,
        "hv_temp_avg" to 12,
        "hv_temp_max" to 13,
        "hv_soh" to 14,
    )

    fun priority(key: String, source: SensorSource): Int = when (source) {
        SensorSource.VHAL -> vhalPriorities[key] ?: 100
        SensorSource.OBD -> obdPriorities[key] ?: 100
        SensorSource.SCANNER -> 100
    }
}
