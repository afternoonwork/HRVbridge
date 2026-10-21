package com.placeholder.hrvbridge.ui

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * What the dial shows. The hand angle is cumulative degrees: settling is the first
 * turn (0 to 360), measuring the second (360 to 720), done parks it at 720.
 */
@Immutable
data class DialModel(
    /** Heart rate in the window, or a label instead. */
    val heartRate: Int? = null,
    val label: String? = null,
    val hand: Hand = Hand.HIDDEN,
    val handAngle: Double = 0.0,
    val band: Band = Band.NONE,
    /** Degrees from 12 o'clock, 360 = full ring. */
    val bandAngle: Double = 0.0,
    val faint: Boolean = false,
) {
    enum class Hand { HIDDEN, INK, ACCENT }

    enum class Band { NONE, SETTLE, MEASURE, MUTED }

    companion object {
        /** A measurement in progress, derived from the cumulative hand angle. */
        fun running(hr: Int?, handAngle: Double, poorContact: Boolean): DialModel {
            val measuring = handAngle >= 360
            return DialModel(
                heartRate = hr,
                label = if (poorContact) "poor contact" else null,
                hand = if (measuring) Hand.ACCENT else Hand.INK,
                handAngle = handAngle,
                band = if (handAngle <= 0) Band.NONE else if (measuring) Band.MEASURE else Band.SETTLE,
                bandAngle = if (measuring) handAngle - 360 else handAngle,
            )
        }
    }
}

/** Dial size: min(264, width - 112) dp. */
fun dialSize(screenWidth: Dp): Dp = minOf(264.dp, screenWidth - 112.dp)

/** "Remove animations" in the system settings. */
@Composable
fun animationsOff(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** The heart rate dial, drawn in code. [jump] moves the hand without animation (reset). */
@Composable
fun HeartDial(model: DialModel, size: Dp, modifier: Modifier = Modifier, jump: Boolean = false,
              description: String = "") {
    val c = hrv
    val still = animationsOff() || jump
    val handSpec = if (still) snap() else spring<Float>(dampingRatio = 0.7f, stiffness = Spring.StiffnessHigh)
    val angle by animateFloatAsState(model.handAngle.toFloat(), handSpec, label = "hand")
    val band by animateFloatAsState(model.bandAngle.toFloat(), handSpec, label = "band")
    val handColor by animateColorAsState(
        if (model.hand == DialModel.Hand.ACCENT) c.accent else c.ink,
        if (animationsOff()) snap() else tween(200), label = "handColour",
    )
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Dial numerals do not scale with the font size.
    val numeral = TextStyle(fontSize = with(density) { 11.dp.toSp() }, color = c.ink2, fontFeatureSettings = "tnum")
    val r = size / 2

    Box(
        modifier
            .size(size)
            .semantics(mergeDescendants = false) { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(size)
                .shadow(if (c.dark) 10.dp else 8.dp, androidx.compose.foundation.shape.CircleShape, clip = false)
                .background(c.surface, androidx.compose.foundation.shape.CircleShape)
                .border(1.dp, c.line, androidx.compose.foundation.shape.CircleShape),
        )
        Canvas(Modifier.size(size)) {
            val rp = this.size.minDimension / 2
            val centre = Offset(this.size.width / 2, this.size.height / 2)
            fun at(deg: Double, radius: Float) = Offset(
                centre.x + radius * sin(deg * PI / 180).toFloat(),
                centre.y - radius * cos(deg * PI / 180).toFloat(),
            )
            // Band: 0.932 R to 0.970 R from 12 clockwise, butt ends.
            if (model.band != DialModel.Band.NONE && band > 0f) {
                val mid = rp * (0.932f + 0.970f) / 2
                val w = rp * (0.970f - 0.932f)
                drawArc(
                    color = if (model.band == DialModel.Band.MEASURE) c.accent else c.ink2.copy(alpha = 0.45f),
                    startAngle = -90f, sweepAngle = min(band, 360f), useCenter = false,
                    topLeft = Offset(centre.x - mid, centre.y - mid), size = Size(mid * 2, mid * 2),
                    style = Stroke(width = w, cap = StrokeCap.Butt),
                )
            }
            val tickAlpha = if (model.faint) 0.35f else 1f
            for (i in 0 until 60) {
                val major = i % 5 == 0
                val deg = i * 6.0
                drawLine(
                    c.ink.copy(alpha = tickAlpha),
                    at(deg, rp * if (major) 0.818f else 0.856f), at(deg, rp * 0.917f),
                    strokeWidth = (if (major) 2.5.dp else 1.dp).toPx(),
                )
                if (major) {
                    val text = measurer.measure(if (i == 0) "60" else "$i", numeral)
                    val p = at(deg, rp * 0.712f)
                    drawText(text, topLeft = Offset(p.x - text.size.width / 2f, p.y - text.size.height / 2f), alpha = tickAlpha)
                }
            }
        }
        // Window: 96 x 46, 0.379 R below the centre.
        Box(
            Modifier
                .offset(y = r * 0.379f)
                .size(96.dp, 46.dp)
                .background(c.bg, RoundedCornerShape(10.dp))
                .border(1.dp, c.line, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (model.label != null) {
                Text(model.label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                     color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text(model.heartRate?.toString() ?: "", style = Readout.hr, color = c.ink, maxLines = 1)
            }
        }
        if (model.hand != DialModel.Hand.HIDDEN) {
            Canvas(Modifier.size(size)) {
                val rp = this.size.minDimension / 2
                val centre = Offset(this.size.width / 2, this.size.height / 2)
                val rad = angle * PI / 180
                val dx = sin(rad).toFloat()
                val dy = -cos(rad).toFloat()
                drawLine(
                    handColor,
                    Offset(centre.x - dx * rp * 0.136f, centre.y - dy * rp * 0.136f),
                    Offset(centre.x + dx * rp * 0.879f, centre.y + dy * rp * 0.879f),
                    strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
                )
                drawCircle(handColor, 7.dp.toPx(), centre)
                drawCircle(c.surface, 2.dp.toPx(), centre)
            }
        }
    }
}
