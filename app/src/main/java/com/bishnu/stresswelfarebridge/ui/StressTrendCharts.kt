package com.bishnu.stresswelfarebridge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bishnu.stresswelfarebridge.questionnaire.DailyContextCategory
import com.bishnu.stresswelfarebridge.trends.DailyContextPoint
import com.bishnu.stresswelfarebridge.trends.PerceivedPoint
import com.bishnu.stresswelfarebridge.trends.PhysiologicalPoint
import com.bishnu.stresswelfarebridge.trends.StressBand
import com.bishnu.stresswelfarebridge.trends.StressTrends

/**
 * The three stress trend graphs, kept on three genuinely separate scales.
 *
 * Each is a separate card with its own axis, its own title and its own empty state:
 *
 *  1. **How your body is responding** - physiological stress, 0-100, x = real date and time.
 *  2. **How stressed you feel** - PSS-10, 0-40, x = month.
 *  3. **Today's situation** - Daily Context, Low/Medium/High, x = date. No number, anywhere.
 *
 * ### Why they are never overlaid
 * A shared plot area would imply the three are the same kind of quantity and invite a comparison
 * between, say, a physiological 72 and a PSS-10 24. They measure different things on different
 * scales, so each keeps its own axis and its own card.
 *
 * ### Colour
 * Green / amber / red is **presentation only**. It communicates the stress level at a glance and
 * never changes a plotted value. For physiological it reuses the engine's own cut-points; for
 * PSS-10, which has no official clinical bands, it is relative to the person's own history and the
 * card says so; for Daily Context it maps the three category names.
 */
private object TrendColors {
    val low = Color(0xFF3FB27F)
    val moderate = Color(0xFFE0A33E)
    val high = Color(0xFFE0605A)

    fun bandColor(band: StressBand): Color = when (band) {
        StressBand.LOW -> low
        StressBand.MODERATE -> moderate
        StressBand.HIGH -> high
    }
}

/**
 * One plotted sample: where it sits vertically (0..1) and how it should be coloured.
 *
 * Colour is a function of [band] only and is applied at draw time, so a graph's colour can never
 * move a data point.
 */
private data class TrendSample(
    val fraction: Float,
    val band: StressBand,
    val tooltip: String
)

/** Shared empty state. Honest wording: the app never fabricates a point to fill a graph. */
@Composable
private fun TrendEmpty(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 18.dp)
    )
}

/** Small colour key, so the colours mean something without a legend paragraph. */
@Composable
private fun TrendLegend(note: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(
            StressBand.LOW to "Lower",
            StressBand.MODERATE to "Moderate",
            StressBand.HIGH to "Higher"
        ).forEach { (band, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    Modifier
                        .size(9.dp)
                        .background(TrendColors.bandColor(band), CircleShape)
                )
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    Text(
        note,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * The shared plot surface: background bands, subtle gridlines, the connecting line, the points and
 * the tap-to-inspect behaviour.
 *
 * Everything is drawn from [samples] exactly as supplied. No interpolation, smoothing or
 * gap-filling: one sample means one dot and no line, which is the honest depiction of a single real
 * measurement.
 */
@Composable
private fun TrendPlot(
    samples: List<TrendSample>,
    gridColor: Color,
    plotHeight: Dp,
    onSelected: (Int?) -> Unit
) {
    val selectedIndex = remember { mutableStateOf<Int?>(null) }

    Box(Modifier.fillMaxWidth().height(plotHeight)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(samples.size) {
                    detectTapGestures { offset ->
                        if (samples.isEmpty()) {
                            selectedIndex.value = null
                            onSelected(null)
                            return@detectTapGestures
                        }
                        val stepX = size.width / samples.size
                        val nearest = if (stepX <= 0f) 0
                        else ((offset.x / stepX).toInt()).coerceIn(0, samples.lastIndex)
                        // Tapping the selected point again clears it, returning the chart to rest.
                        selectedIndex.value =
                            if (selectedIndex.value == nearest) null else nearest
                        onSelected(selectedIndex.value)
                    }
                }
        ) {
            val h = size.height
            val w = size.width
            fun dotX(index: Int): Float =
                if (samples.size <= 1) w / 2f else (w / samples.size) * (index + 0.5f)
            fun dotY(sample: TrendSample): Float =
                h - sample.fraction.coerceIn(0f, 1f) * h

            // Subtle background bands: green low, amber middle, red high. Decoration that makes the
            // level readable at a glance; the y-axis labels carry the authoritative meaning.
            listOf(
                Triple(0f, 0.5f, TrendColors.low),
                Triple(0.5f, 0.75f, TrendColors.moderate),
                Triple(0.75f, 1f, TrendColors.high)
            ).forEach { (from, to, color) ->
                drawRect(
                    color = color.copy(alpha = 0.10f),
                    topLeft = Offset(0f, h - to * h),
                    size = Size(w, (to - from) * h)
                )
            }

            // Gridlines. A few dashed rules plus the baseline are enough to read a level cleanly.
            listOf(0.25f, 0.5f, 0.75f).forEach { g ->
                drawLine(
                    color = gridColor,
                    start = Offset(0f, h - g * h),
                    end = Offset(w, h - g * h),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
                )
            }
            drawLine(
                color = gridColor,
                start = Offset(0f, h),
                end = Offset(w, h),
                strokeWidth = 2f
            )

            if (samples.isEmpty()) return@Canvas

            // The connecting line, drawn segment by segment so each takes the colour of the level it
            // starts at. Straight segments only: a smoothed curve would imply values between two real
            // measurements that were never actually measured.
            if (samples.size > 1) {
                samples.zipWithNext().forEachIndexed { index, (a, b) ->
                    drawLine(
                        color = TrendColors.bandColor(a.band).copy(alpha = 0.85f),
                        start = Offset(dotX(index), dotY(a)),
                        end = Offset(dotX(index + 1), dotY(b)),
                        strokeWidth = 4f,
                        cap = StrokeCap.Round
                    )
                }
            }

            // Points. The selected one is enlarged and ringed so the tapped point is obvious.
            samples.forEachIndexed { index, sample ->
                val x = dotX(index)
                val y = dotY(sample)
                val selected = selectedIndex.value == index
                val radius = if (selected) 9f else 5f
                if (selected) {
                    drawCircle(
                        color = TrendColors.bandColor(sample.band).copy(alpha = 0.28f),
                        radius = radius + 7f,
                        center = Offset(x, y)
                    )
                }
                drawCircle(
                    color = TrendColors.bandColor(sample.band),
                    radius = radius,
                    center = Offset(x, y)
                )
                // A light outline keeps the dot visible against a same-hue band behind it.
                drawCircle(
                    color = Color.White.copy(alpha = 0.55f),
                    radius = radius,
                    center = Offset(x, y),
                    style = Stroke(width = 1.5f)
                )
            }
        }
    }
}

/** Rounded container shared by all three graphs: dark-mode friendly, consistent with the app. */
@Composable
private fun TrendCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/** The y-axis row: three stacked labels aligned to the top, middle and bottom of the plot. */
@Composable
private fun AxisLabels(labels: List<String>) {
    Column(Modifier.height(PLOT_HEIGHT), verticalArrangement = Arrangement.SpaceBetween) {
        labels.forEach {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Tooltip shown for the tapped point: exact date/time and value, or date and category. */
@Composable
private fun TrendTooltip(text: String) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
}

/** Shared plot height, so the y-axis labels line up with the plot in all three graphs. */
private val PLOT_HEIGHT = 150.dp

/**
 * X-axis tick labels, thinned so a long series never overlaps itself. The last label is always
 * drawn so the most recent real measurement is always named.
 */
@Composable
private fun XAxisLabels(all: List<String>) {
    val stride = trendLabelStride(all.size)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        all.forEachIndexed { index, label ->
            Text(
                if (index % stride == 0 || index == all.lastIndex) label else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/** Chooses which x-axis labels are drawn so a long series does not overlap itself. */
internal fun trendLabelStride(count: Int): Int = when {
    count <= 4 -> 1
    count <= 8 -> 2
    else -> 3
}

/**
 * GRAPH 1 - Physiological stress, 0-100, x = the real date and time of each measurement.
 *
 * [points] must come from [StressTrends.physiological], which only emits records the ML pipeline
 * actually persisted. No historical point is generated here, so with no measurements the card shows
 * its empty state rather than an empty-looking curve.
 *
 * The green/amber/red banding uses the engine's own cut-points ([StressTrends.bandFor]) so it always
 * agrees with the "Low"/"Moderate"/"High" level shown on the card above. It changes no value.
 */
@Composable
fun PhysiologicalStressTrendChart(
    points: List<PhysiologicalPoint>,
    modifier: Modifier = Modifier
) {
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val grid = MaterialTheme.colorScheme.outlineVariant

    TrendCard("How your body is responding", "Physiological Stress", modifier) {
        if (points.isEmpty()) {
            TrendEmpty("No physiological stress measurements yet.")
            return@TrendCard
        }
        Row(Modifier.fillMaxWidth()) {
            AxisLabels(listOf("100", "50", "0"))
            Column(Modifier.weight(1f)) {
                TrendPlot(
                    samples = points.map {
                        TrendSample(StressTrends.physiologicalFraction(it.score), it.band, it.tooltip())
                    },
                    gridColor = grid,
                    plotHeight = PLOT_HEIGHT
                ) { selected = it }
                // X axis: the real measurement date and time of each point.
                XAxisLabels(points.map { StressTrends.measuredAtLabel(it.atMillis) })
            }
        }
        Spacer(Modifier.height(8.dp))
        selected?.let { index ->
            TrendTooltip(points[index].tooltip())
            Spacer(Modifier.height(6.dp))
        }
        TrendLegend(
            "Colour shows the stress level of each measurement. It does not change the value."
        )
    }
}

/** Tooltip text for a physiological point: exact date/time, score out of 100, and level. */
private fun PhysiologicalPoint.tooltip(): String =
    StressTrends.measuredAtLabel(atMillis) + "\nStress: $score/100\n" + band.label

/**
 * GRAPH 2 - PSS-10 perceived stress, 0-40, x = month name.
 *
 * One point per month a person actually completed. **Missing months are not created** and no score
 * is interpolated, so the axis shows only the months that exist and the line connects those.
 *
 * Colour here is explicitly *relative to this person's own history*, not a clinical category:
 * PSS-10 publishes no official cut-offs, and the card says so rather than implying otherwise.
 */
@Composable
fun PerceivedStressTrendChart(
    points: List<PerceivedPoint>,
    modifier: Modifier = Modifier
) {
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val grid = MaterialTheme.colorScheme.outlineVariant

    TrendCard("How stressed you feel", "Perceived Stress - PSS-10", modifier) {
        if (points.isEmpty()) {
            TrendEmpty("No PSS-10 results yet.")
            return@TrendCard
        }
        Row(Modifier.fillMaxWidth()) {
            // 0-40, never rescaled onto 0-100.
            AxisLabels(listOf("40", "20", "0"))
            Column(Modifier.weight(1f)) {
                TrendPlot(
                    samples = points.map {
                        TrendSample(StressTrends.perceivedFraction(it.score), it.band, it.tooltip())
                    },
                    gridColor = grid,
                    plotHeight = PLOT_HEIGHT
                ) { selected = it }
                // X axis: month name, for the months that genuinely have a completed result.
                XAxisLabels(points.map { StressTrends.perceivedMonthLabel(it.month) })
            }
        }
        Spacer(Modifier.height(8.dp))
        selected?.let { index ->
            TrendTooltip(points[index].tooltip())
            Spacer(Modifier.height(6.dp))
        }
        TrendLegend(
            "Colour compares your months against your own range. PSS-10 has no official clinical " +
                "categories, so the score is the value that matters."
        )
    }
}

/** Tooltip text for a PSS-10 point: month and year, and the score out of 40. */
private fun PerceivedPoint.tooltip(): String =
    StressTrends.perceivedMonthLongLabel(month) + "\nPSS-10: $score/${StressTrends.PERCEIVED_MAX}"

/**
 * GRAPH 3 - Daily Context, y = Low / Medium / High, x = date.
 *
 * This is **not** a numerical stress score and never becomes one. The y-axis is labelled with the
 * three words, there is no "out of 100" anywhere on this card, and [DailyContextPoint] has no score
 * field to plot even if someone wanted to. Only dates with a real completed check-in appear.
 */
@Composable
fun DailyContextTrendChart(
    points: List<DailyContextPoint>,
    modifier: Modifier = Modifier
) {
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val grid = MaterialTheme.colorScheme.outlineVariant

    TrendCard("Today's situation", "Daily Context", modifier) {
        if (points.isEmpty()) {
            TrendEmpty("No daily check-ins yet.")
            return@TrendCard
        }
        Row(Modifier.fillMaxWidth()) {
            // Words, not numbers. Low / Medium / High is the whole vocabulary of this graph.
            AxisLabels(listOf("High", "Medium", "Low"))
            Column(Modifier.weight(1f)) {
                TrendPlot(
                    samples = points.map {
                        TrendSample(StressTrends.plotFraction(it.category), contextBand(it.category), it.tooltip())
                    },
                    gridColor = grid,
                    plotHeight = PLOT_HEIGHT
                ) { selected = it }
                // X axis: the date of each real completed check-in.
                XAxisLabels(points.map { StressTrends.contextDateLabel(it.date) })
            }
        }
        Spacer(Modifier.height(8.dp))
        selected?.let { index ->
            TrendTooltip(points[index].tooltip())
            Spacer(Modifier.height(6.dp))
        }
        TrendLegend(
            "Daily Context is a summary of your check-in answers. It is not a stress score."
        )
    }
}

/**
 * The category's own colour. An identity mapping, so Low is always green and High always red, with
 * no number involved at any point.
 */
private fun contextBand(category: DailyContextCategory): StressBand = when (category) {
    DailyContextCategory.LOW -> StressBand.LOW
    DailyContextCategory.MEDIUM -> StressBand.MODERATE
    DailyContextCategory.HIGH -> StressBand.HIGH
}

/** Tooltip text: the date and the category in words. No number is ever shown for this graph. */
private fun DailyContextPoint.tooltip(): String =
    StressTrends.contextDateLabel(date) + "\nToday's situation: " + category.label
