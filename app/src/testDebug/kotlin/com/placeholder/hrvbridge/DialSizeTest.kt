package com.placeholder.hrvbridge

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.geometry.Rect
import com.placeholder.hrvbridge.ui.HrvTheme
import com.placeholder.hrvbridge.ui.LocalStillSpinners
import com.placeholder.hrvbridge.ui.MainActions
import com.placeholder.hrvbridge.ui.MainPhase
import com.placeholder.hrvbridge.ui.MainScreenContent
import com.placeholder.hrvbridge.ui.MainScreenState
import com.placeholder.hrvbridge.ui.MainTopBar
import com.placeholder.hrvbridge.ui.ResultSummary
import com.placeholder.hrvbridge.ui.hrv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The dial keeps one size and place from Connected through measuring, also with poor contact; the result never grows it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xhdpi")
class DialSizeTest {
    private val activity by lazy { Robolectric.buildActivity(ComponentActivity::class.java).setup().get() }

    private fun result(upload: ResultSummary.Upload) =
        MainPhase.Result(ResultSummary(52, 68.4, 61.2, 64, 64, true, upload))

    private fun dialBounds(state: MainScreenState): Rect {
        val view = ComposeView(activity)
        activity.setContentView(view.apply { setContent {
            HrvTheme(dark = false) { CompositionLocalProvider(LocalStillSpinners provides true) {
                Scaffold(containerColor = hrv.bg, topBar = { MainTopBar(!state.isMeasuring) {} }) { padding ->
                    MainScreenContent(state, MainActions(), Modifier.padding(padding), dialDescription = "dial")
                }
            } }
        } })
        shadowOf(Looper.getMainLooper()).idle()
        fun all(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::all)
        val root = (view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode
        return all(root).single { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("dial") }.boundsInRoot
    }

    /** Size and position identical in every active state; the result never grows it. Returns the dial width in dp. */
    private fun check(minDp: Float): Float {
        val strap = "Polar H9 A1B2C3D4"
        val running = listOf(
            MainScreenState(MainPhase.Connected(strap), heartRate = 53, strapConnected = true),
            MainScreenState(MainPhase.Settling(38, false), heartRate = 53, handAngle = 132.0),
            MainScreenState(MainPhase.Measuring(36, 22, false), heartRate = 52, handAngle = 504.0),
            MainScreenState(MainPhase.Settling(41, true), heartRate = 55, handAngle = 115.0),
            MainScreenState(MainPhase.Measuring(30, 25, true), heartRate = 55, handAngle = 540.0),
        ).map(::dialBounds)
        assertEquals(List(running.size) { running[0] }, running)
        val dp = running[0].width / activity.resources.displayMetrics.density
        assertTrue("dial $dp dp", dp >= minDp)
        for (upload in listOf(ResultSummary.Upload.NONE, ResultSummary.Upload.UPLOADED)) {
            val r = dialBounds(MainScreenState(result(upload)))
            assertTrue("result $upload: $r > ${running[0]}", r.width <= running[0].width && r.width > 0)
        }
        return dp
    }

    private val widthDp get() = activity.resources.configuration.screenWidthDp

    /** Robolectric has no system bars. At 360 x 640 the dial is at least its old size (248 dp). */
    @Test
    fun dialKeepsItsSizeFromConnectedThroughMeasuring() {
        check(248f)
    }

    /** As on the emulator: 640 dp minus the status bar (24) and the navigation bar (48), so height bound. */
    @Test
    @Config(qualifiers = "w360dp-h568dp-xhdpi")
    fun dialKeepsItsSizeWhenHeightBound() {
        check(230f)
    }

    /** Larger phones: the dial grows with the width (86%, at least 80%). */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun dialGrowsOnAMediumPhone() {
        check(0.8f * widthDp)
    }

    @Test
    @Config(qualifiers = "w448dp-h998dp-xxhdpi")
    fun dialGrowsOnALargePhone() {
        check(0.8f * widthDp)
    }
}
