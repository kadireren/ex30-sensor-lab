package com.kadireren.ex30sensorlab.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView
import com.kadireren.ex30sensorlab.model.SampleStatus
import com.kadireren.ex30sensorlab.model.SensorSample
import java.util.Locale

class SensorListAdapter(private val context: Context) : BaseAdapter() {
    private val samples = linkedMapOf<String, SensorSample>()
    var focusedKey: String? = null

    fun update(sample: SensorSample) {
        samples[sample.definition.key] = sample
        notifyDataSetChanged()
    }

    fun clear() {
        samples.clear()
        notifyDataSetChanged()
    }

    override fun getCount(): Int = samples.size
    override fun getItem(position: Int): SensorSample = samples.values.elementAt(position)
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val sample = getItem(position)
        val ageMs = (SystemClock.elapsedRealtime() - sample.monotonicTimestampMs).coerceAtLeast(0L)
        val staleAfterMs = if (sample.definition.targetHz > 0f) (3_000f / sample.definition.targetHz).toLong().coerceAtLeast(750L) else 10_000L
        val effectiveStatus = if (sample.status == SampleStatus.LIVE && ageMs > staleAfterMs) SampleStatus.STALE else sample.status
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (sample.definition.key == focusedKey) Color.rgb(20, 62, 86) else Color.rgb(16, 29, 46))
                setStroke(dp(1), statusColor(effectiveStatus))
            }
        }
        root.addView(TextView(context).apply {
            text = sample.definition.name
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = sample.displayValue
                setTextColor(Color.rgb(57, 200, 255))
                textSize = 17f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(context).apply {
                text = String.format(Locale.US, "%s · %.1f Hz · %d ms · yaş %d ms", effectiveStatus.name, sample.actualHz, sample.latencyMs, ageMs)
                setTextColor(Color.LTGRAY)
                textSize = 13f
            })
        })
        root.addView(TextView(context).apply {
            text = "${sample.definition.identifier} · ham: ${sample.rawValue} · ${sample.detail}"
            setTextColor(Color.rgb(174, 185, 199))
            textSize = 12f
            maxLines = 3
        })
        root.layoutParams = android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return root
    }

    private fun statusColor(status: SampleStatus): Int = when (status) {
        SampleStatus.LIVE -> Color.rgb(49, 209, 109)
        SampleStatus.ERROR, SampleStatus.PERMISSION_DENIED -> Color.rgb(255, 107, 107)
        SampleStatus.UNSUPPORTED -> Color.DKGRAY
        else -> Color.rgb(57, 200, 255)
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
