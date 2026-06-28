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
import org.jetbrains.letsPlot.geom.geomLine
import org.jetbrains.letsPlot.geom.geomSegment
import org.jetbrains.letsPlot.geom.extras.arrow
import org.jetbrains.letsPlot.intern.toSpec
import org.jetbrains.letsPlot.label.ggtitle
import org.jetbrains.letsPlot.label.ylab
import org.jetbrains.letsPlot.letsPlot
import org.jetbrains.letsPlot.scale.scaleXTime
import org.slf4j.LoggerFactory
import java.awt.BorderLayout
import java.util.concurrent.TimeUnit
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Draws a line chart with the expected load rate over time for given [ThreadSchedule].
 */
@API(status = API.Status.EXPERIMENTAL, since = "5.5")
public class TargetRateChart : JPanel() {
    private companion object {
        private val log = LoggerFactory.getLogger(TargetRateChart::class.java)
        private const val LINE_COLOR = "#2962AA"
        private const val MIN_TICKS_FOR_TIME_AXIS = 2.5
    }

    init {
        layout = BorderLayout()
    }

    private var prevSteps: List<ThreadScheduleStep>? = null
    private var prevTimes: DoubleArray? = null
    private var prevRate: DoubleArray? = null
    private var prevTitle: String? = null
    private var prevYAxisLabel: String? = null
    private var prevValuesPerMinute: Boolean? = null
    private var prevContinuation: Boolean? = null

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
        add(JLabel(message, SwingConstants.CENTER), BorderLayout.CENTER)
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
        if (time.contentEquals(prevTimes) && rate.contentEquals(prevRate)
            && title == prevTitle && yAxisLabel == prevYAxisLabel
            && valuesPerMinute == prevValuesPerMinute && continuation == prevContinuation) {
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
        var plot = letsPlot(data) + geomLine(color = LINE_COLOR, size = 1.2) { x = "time"; y = "rate" } +
            scaleXTime("Time since test start", expand = listOf(0, 0)) +
            ggtitle(title) +
            ylab(yAxisLabel)
        if (continuation && time.size > 1) {
            val last = time.lastIndex
            plot += geomSegment(
                data = mapOf(
                    "x" to listOf(time[last - 1]),
                    "y" to listOf(rate[last - 1]),
                    "xend" to listOf(time[last]),
                    "yend" to listOf(rate[last])
                ),
                color = LINE_COLOR,
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

        return DefaultPlotPanelBatik(
            processedSpec = processedSpec,
            preserveAspectRatio = false,
            preferredSizeFromPlot = false,
            repaintDelay = 10,
        ) { messages ->
            for (message in messages) {
                log.info(message)
            }
        }
    }
}
