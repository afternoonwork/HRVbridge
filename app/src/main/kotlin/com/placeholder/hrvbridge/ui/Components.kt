package com.placeholder.hrvbridge.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val Pill = RoundedCornerShape(percent = 50)

@Composable
private fun pressScale(source: MutableInteractionSource): Pair<Float, Boolean> {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, tween(90), label = "key")
    return scale to pressed
}

/** Primary key: ink pill, 56 dp, at least 200 dp wide. */
@Composable
fun PrimaryKey(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
               leading: (@Composable () -> Unit)? = null) {
    val source = remember { MutableInteractionSource() }
    val (scale, pressed) = pressScale(source)
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = source,
        shape = Pill,
        colors = ButtonDefaults.buttonColors(
            containerColor = hrv.ink, contentColor = hrv.bg,
            disabledContainerColor = hrv.key, disabledContentColor = hrv.ink2,
        ),
        elevation = null,
        modifier = modifier
            .scale(scale)
            .shadow(if (enabled && !pressed) 4.dp else 0.dp, Pill, clip = false)
            .defaultMinSize(minWidth = 200.dp, minHeight = 56.dp),
    ) {
        leading?.let {
            it()
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/** Secondary key: surface pill with a hairline, 52 dp, at least 180 dp wide. */
@Composable
fun SecondaryKey(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val (scale, pressed) = pressScale(source)
    OutlinedButton(
        onClick = onClick,
        interactionSource = source,
        shape = Pill,
        border = BorderStroke(1.dp, hrv.line),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = hrv.surface, contentColor = hrv.ink),
        modifier = modifier
            .scale(scale)
            .shadow(if (!pressed) 4.dp else 0.dp, Pill, clip = false)
            .defaultMinSize(minWidth = 180.dp, minHeight = 52.dp),
    ) { Text(text, style = MaterialTheme.typography.titleMedium) }
}

@Composable
fun TextKey(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = hrv.ink)
    }
}

/** A notice card. Warnings carry an icon and an outline, never colour, and are announced. */
@Composable
fun Notice(text: String, modifier: Modifier = Modifier, lead: String? = null, warning: Boolean = false) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .background(hrv.surface, RoundedCornerShape(14.dp))
            .border(if (warning) 1.5.dp else 1.dp, if (warning) hrv.lineStrong else hrv.line, RoundedCornerShape(14.dp))
            .padding(16.dp)
            .then(if (warning) Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite } else Modifier),
    ) {
        if (warning) WarningIcon(hrv.ink)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            lead?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = hrv.ink) }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = hrv.ink)
        }
    }
}

/** True in the screenshot test: spinners are drawn still, since they never settle there. */
val LocalStillSpinners = staticCompositionLocalOf { false }

/** The system spinner (indeterminate). */
@Composable
fun Spinner(size: androidx.compose.ui.unit.Dp, color: Color) {
    if (LocalStillSpinners.current) {
        CircularProgressIndicator(progress = { 0.7f }, modifier = Modifier.size(size), color = color, strokeWidth = 2.dp,
                                  trackColor = Color.Transparent)
    } else {
        CircularProgressIndicator(Modifier.size(size), color = color, strokeWidth = 2.dp)
    }
}

enum class StatusMark { HOLLOW, INK, ACCENT, SPINNER }

@Composable
fun StatusDot(mark: StatusMark) {
    when (mark) {
        StatusMark.HOLLOW -> Spacer(Modifier.size(8.dp).border(1.5.dp, hrv.ink2, CircleShape))
        StatusMark.INK -> Spacer(Modifier.size(8.dp).background(hrv.ink, CircleShape))
        StatusMark.ACCENT -> Spacer(Modifier.size(8.dp).background(hrv.accent, CircleShape))
        StatusMark.SPINNER -> Spinner(14.dp, hrv.ink)
    }
}

@Composable
fun StatusLine(mark: StatusMark, text: String, bold: String? = null) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        // Live region: strap status changes are announced.
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        StatusDot(mark)
        bold?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = hrv.ink) }
        Text(text, style = MaterialTheme.typography.bodySmall, color = if (bold == null) hrv.ink else hrv.ink2)
    }
}

enum class Step(val title: String) { CHECK("check"), SETTLE("settle"), MEASURE("measure"), UPLOAD("upload") }

enum class StepMark { INK, ACCENT, HOLLOW }

/**
 * Four steps. Done: filled ink-2; current: filled ink, accent or hollow; later: hollow.
 * The current label is bold, so steps never differ by colour alone.
 */
@Composable
fun StepIndicator(current: Step, mark: StepMark = StepMark.INK) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = "Step ${current.ordinal + 1} of 4: ${current.title}"
            // Phase changes are announced.
            liveRegion = LiveRegionMode.Polite
        },
    ) {
        for (step in Step.entries) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val dot = Modifier.size(8.dp)
                when {
                    step.ordinal < current.ordinal -> Spacer(dot.background(hrv.ink2, CircleShape))
                    step == current -> when (mark) {
                        StepMark.INK -> Spacer(dot.background(hrv.ink, CircleShape))
                        StepMark.ACCENT -> Spacer(dot.background(hrv.accent, CircleShape))
                        StepMark.HOLLOW -> Spacer(dot.border(1.5.dp, hrv.ink, CircleShape))
                    }
                    else -> Spacer(dot.border(1.dp, hrv.ink2, CircleShape))
                }
                Text(
                    step.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (step == current) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (step == current) hrv.ink else hrv.ink2,
                )
            }
        }
    }
}

/** Outline warning triangle with "!" (drawn, no icon library). */
@Composable
fun WarningIcon(color: Color, modifier: Modifier = Modifier.size(20.dp)) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round)
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w / 2, w * 0.12f)
            lineTo(w * 0.94f, w * 0.88f)
            lineTo(w * 0.06f, w * 0.88f)
            close()
        }
        drawPath(path, color, style = stroke)
        drawLine(color, androidx.compose.ui.geometry.Offset(w / 2, w * 0.4f), androidx.compose.ui.geometry.Offset(w / 2, w * 0.62f), 1.6.dp.toPx())
        drawCircle(color, 1.1.dp.toPx(), androidx.compose.ui.geometry.Offset(w / 2, w * 0.74f))
    }
}

/** Three sliders (the settings icon), drawn. */
@Composable
fun SlidersIcon(color: Color, modifier: Modifier = Modifier.size(22.dp)) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val sw = 1.6.dp.toPx()
        listOf(0.25f to 0.3f, 0.5f to 0.68f, 0.75f to 0.45f).forEach { (y, knob) ->
            drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.08f, w * y), androidx.compose.ui.geometry.Offset(w * 0.92f, w * y), sw)
            drawCircle(hrvBg(color), 2.6.dp.toPx(), androidx.compose.ui.geometry.Offset(w * knob, w * y))
            drawCircle(color, 2.6.dp.toPx(), androidx.compose.ui.geometry.Offset(w * knob, w * y),
                style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
        }
    }
}

private fun hrvBg(ink: Color) = if (ink == DarkHrvColors.ink) DarkHrvColors.bg else LightHrvColors.bg
