/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jmeter.threads.openmodel.gui

import org.apache.jmeter.threads.openmodel.ThreadSchedule
import org.apache.jmeter.threads.openmodel.ThreadScheduleStep
import org.apiguardian.api.API
import org.jetbrains.letsPlot.batik.plot.component.DefaultPlotPanelBatik
import org.jetbrains.letsPlot.commons.registration.Disposable
import org.jetbrains.letsPlot.core.util.MonolithicCommon
import org.jetbrains.letsPlot.geom.extras.arrow
import org.jetbrains.letsPlot.geom.geomLine
import org.jetbrains.letsPlot.geom.geomSegment
import org.jetbrains.letsPlot.intern.toSpec
import org.jetbrains.letsPlot.label.ggtitle
import org.jetbrains.letsPlot.label.ylab
import org.jetbrains.letsPlot.letsPlot
import org.jetbrains.letsPlot.scale.scaleXTime
import org.jetbrains.letsPlot.themes.elementLine
import org.jetbrains.letsPlot.themes.elementRect
import org.jetbrains.letsPlot.themes.elementText
import org.jetbrains.letsPlot.themes.theme
import org.slf4j.LoggerFactory
import java.awt.BorderLayout
import java.awt.Color
import java.util.concurrent.TimeUnit
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * Draws a line chart with the expected load rate over time for given [ThreadSchedule].
 */
@API(status = API.Status.EXPERIMENTAL, since = "5.5")
public class TargetRateChart : JPanel() {
    private companion object {
        private val log = LoggerFactory.getLogger(TargetRateChart::class.java)
        private const val MIN_TICKS_FOR_TIME_AXIS = 2.5
    }

    private var prevSteps: List<ThreadScheduleStep>? = null
    private var prevTimes: DoubleArray? = null
    private var prevRate: DoubleArray? = null
    private var prevTitle: String? = null
    private var prevYAxisLabel: String? = null
    private var prevValuesPerMinute: Boolean? = null
    private var prevContinuation: Boolean? = null
    private var initialized = false

    init {
        layout = BorderLayout()
        isOpaque = true
        updateChartBackground()
        initialized = true
    }

    public fun updateSchedule(threadSchedule: ThreadSchedule) {
        if (threadSchedule.steps == prevSteps) {
            return
        }
        prevSteps = threadSchedule.steps
        val timeValues = mutableListOf<Double>()
        val rateValues = mutableListOf<Double>()
        var time = 0.0
        var rate = 0.0
        var addPoint = false

        for (step in threadSchedule.steps) {
            when (step) {
                is ThreadScheduleStep.RateStep -> {
                    rate = step.rate
                    if (addPoint) {
                        addPoint = false
                        timeValues += time
                        rateValues += rate
                    }
                }
                is ThreadScheduleStep.ArrivalsStep -> if (step.duration > 0) {
                    timeValues += time
                    rateValues += rate
                    addPoint = true
                    time += step.duration
                }
            }
        }
        if (addPoint) {
            timeValues += time
            rateValues += rate
        }
        setData(
            timeValues.toDoubleArray(),
            rateValues.toDoubleArray(),
            "Target load rate",
            "Threads per minute",
            valuesPerMinute = true,
            continuation = false
        )
    }

    public fun updateData(
        timeSeconds: DoubleArray,
        values: DoubleArray,
        title: String,
        yAxisLabel: String,
        continuation: Boolean
    ) {
        prevSteps = null
        setData(timeSeconds, values, title, yAxisLabel, valuesPerMinute = false, continuation = continuation)
    }

    public fun showMessage(message: String) {
        prevSteps = null
        prevTimes = null
        prevRate = null
        prevTitle = null
        prevYAxisLabel = null
        prevValuesPerMinute = null
        prevContinuation = null
        clearChart()
        add(
            JLabel(message, SwingConstants.CENTER).also {
                it.foreground = uiColor("Label.foreground", Color(0x111827))
            },
            BorderLayout.CENTER
        )
        revalidate()
        repaint()
    }

    private fun setData(
        time: DoubleArray,
        rate: DoubleArray,
        title: String,
        yAxisLabel: String,
        valuesPerMinute: Boolean,
        continuation: Boolean
    ) {
        if (time.contentEquals(prevTimes) && rate.contentEquals(prevRate) &&
            title == prevTitle && yAxisLabel == prevYAxisLabel &&
            valuesPerMinute == prevValuesPerMinute && continuation == prevContinuation
        ) {
            return
        }
        prevTimes = time.copyOf()
        prevRate = rate.copyOf()
        prevTitle = title
        prevYAxisLabel = yAxisLabel
        prevValuesPerMinute = valuesPerMinute
        prevContinuation = continuation
        val timeScale = TimeUnit.SECONDS.toMillis(1).toDouble()
        for (i in time.indices) {
            time[i] *= timeScale
        }
        if (valuesPerMinute) {
            for (i in rate.indices) {
                rate[i] *= TimeUnit.MINUTES.toSeconds(1).toDouble()
            }
        }

        clearChart()
        add(
            createChart(
                time = time,
                rate = rate,
                title = title,
                yAxisLabel = yAxisLabel,
                continuation = continuation
            ),
            BorderLayout.CENTER
        )
        revalidate()
        repaint()
    }

    private fun clearChart() {
        components.forEach {
            if (it is Disposable) {
                it.dispose()
            }
        }
        removeAll()
    }

    private fun createChart(
        time: DoubleArray,
        rate: DoubleArray,
        title: String,
        yAxisLabel: String,
        continuation: Boolean
    ): JComponent {
        val mainSize = if (continuation && time.size > 1) time.size - 1 else time.size
        val data = mapOf(
            "time" to time.copyOf(mainSize),
            "rate" to rate.copyOf(mainSize)
        )
        val colors = chartColors()
        var plot = letsPlot(data) + geomLine(color = colors.line, size = 1.2) { x = "time"; y = "rate" } +
            scaleXTime("Time since test start", expand = listOf(0, 0)) +
            ggtitle(title) +
            ylab(yAxisLabel) +
            theme(
                rect = elementRect(fill = colors.background, color = colors.background),
                plotBackground = elementRect(fill = colors.background, color = colors.background),
                panelBackground = elementRect(fill = colors.panel, color = colors.border),
                panelBorder = elementRect(fill = null, color = colors.border, size = 0.8),
                panelGridMajor = elementLine(color = colors.grid, size = 0.5),
                panelGridMinor = elementLine(color = colors.minorGrid, size = 0.3),
                axisLine = elementLine(color = colors.axis, size = 0.7),
                axisTicks = elementLine(color = colors.axis, size = 0.7),
                axisText = elementText(color = colors.text, size = 11),
                axisTitle = elementText(color = colors.text, size = 12),
                plotTitle = elementText(color = colors.text, size = 15),
                text = elementText(color = colors.text)
            )
        if (continuation && time.size > 1) {
            val last = time.lastIndex
            plot += geomSegment(
                data = mapOf(
                    "x" to listOf(time[last - 1]),
                    "y" to listOf(rate[last - 1]),
                    "xend" to listOf(time[last]),
                    "yend" to listOf(rate[last])
                ),
                color = colors.line,
                linetype = "dashed",
                size = 1.2,
                arrow = arrow(length = 9, ends = "last", type = "open")
            ) {
                x = "x"
                y = "y"
                xend = "xend"
                yend = "yend"
            }
        }

        val rawSpec = plot.toSpec()
        val processedSpec = MonolithicCommon.processRawSpecs(rawSpec, frontendOnly = false)

        return ThemedPlotPanelBatik(
            processedSpec = processedSpec,
            preserveAspectRatio = false,
            preferredSizeFromPlot = false,
            repaintDelay = 10,
            chartBackground = colors.awtBackground,
        ) { messages ->
            for (message in messages) {
                log.info(message)
            }
        }
    }

    override fun updateUI() {
        super.updateUI()
        updateChartBackground()
        if (initialized) {
            SwingUtilities.invokeLater {
                refreshChartTheme()
            }
        }
    }

    private fun updateChartBackground() {
        background = uiColor("Panel.background", Color(0xFFFFFF))
    }

    private fun refreshChartTheme() {
        val time = prevTimes?.copyOf() ?: return
        val rate = prevRate?.copyOf() ?: return
        val title = prevTitle ?: return
        val yAxisLabel = prevYAxisLabel ?: return
        val valuesPerMinute = prevValuesPerMinute ?: return
        val continuation = prevContinuation ?: return

        prevTimes = null
        prevRate = null
        prevTitle = null
        prevYAxisLabel = null
        prevValuesPerMinute = null
        prevContinuation = null
        setData(time, rate, title, yAxisLabel, valuesPerMinute, continuation)
    }

    private fun chartColors(): ChartColors {
        val container = uiColor("Panel.background", Color(0xFFFFFF))
        val text = uiColor("Label.foreground", Color(0x111827))
        val dark = luminance(container) < 0.45
        val background = if (dark) container else Color(0xF6F8FB)
        val panel = if (dark) mix(container, Color.WHITE, 0.04) else Color(0xF8FAFC)
        val grid = if (dark) mix(container, Color.WHITE, 0.14) else Color(0xE5E7EB)
        val minorGrid = if (dark) mix(container, Color.WHITE, 0.09) else Color(0xEEF2F7)
        val border = if (dark) mix(container, Color.WHITE, 0.22) else Color(0xD1D5DB)
        val axis = if (dark) mix(text, container, 0.2) else Color(0x6B7280)
        val line = if (dark) Color(0x7DB3FF) else Color(0x2962AA)
        return ChartColors(
            background = toHex(background),
            panel = toHex(panel),
            text = toHex(text),
            grid = toHex(grid),
            minorGrid = toHex(minorGrid),
            border = toHex(border),
            axis = toHex(axis),
            line = toHex(line),
            awtBackground = background
        )
    }

    private data class ChartColors(
        val background: String,
        val panel: String,
        val text: String,
        val grid: String,
        val minorGrid: String,
        val border: String,
        val axis: String,
        val line: String,
        val awtBackground: Color
    )

    private fun uiColor(key: String, fallback: Color): Color =
        UIManager.getColor(key) ?: fallback

    private fun toHex(color: Color): String =
        "#%02x%02x%02x".format(color.red, color.green, color.blue)

    private fun mix(base: Color, overlay: Color, amount: Double): Color {
        fun channel(baseChannel: Int, overlayChannel: Int): Int =
            (baseChannel + (overlayChannel - baseChannel) * amount).toInt().coerceIn(0, 255)

        return Color(
            channel(base.red, overlay.red),
            channel(base.green, overlay.green),
            channel(base.blue, overlay.blue)
        )
    }

    private fun luminance(color: Color): Double {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.03928) normalized / 12.92
            else Math.pow((normalized + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private class ThemedPlotPanelBatik(
        processedSpec: MutableMap<String, Any>,
        preserveAspectRatio: Boolean,
        preferredSizeFromPlot: Boolean,
        repaintDelay: Int,
        private val chartBackground: Color,
        computationMessagesHandler: (List<String>) -> Unit
    ) : DefaultPlotPanelBatik(
        processedSpec,
        preserveAspectRatio,
        preferredSizeFromPlot,
        repaintDelay,
        computationMessagesHandler
    ) {
        init {
            setChartBackground(this, chartBackground)
        }

        override fun plotComponentCreated(plotComponent: JComponent) {
            setChartBackground(plotComponent, chartBackground)
        }

        private fun setChartBackground(component: JComponent, color: Color) {
            component.isOpaque = true
            component.background = color
            for (child in component.components) {
                if (child is JComponent) {
                    setChartBackground(child, color)
                }
            }
        }
    }
}
