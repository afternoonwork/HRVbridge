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
import androidx.compose.ui.unit.IntSize
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

/** On a small screen (720 x 1280 at 320 dpi) the dial keeps one size from Connected through measuring; the result never grows it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xhdpi")
class DialSizeTest {
    private val activity by lazy { Robolectric.buildActivity(ComponentActivity::class.java).setup().get() }

    private fun result(upload: ResultSummary.Upload) =
        MainPhase.Result(ResultSummary(52, 68.4, 61.2, 64, 64, true, upload))

    private fun dialSize(state: MainScreenState): IntSize {
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
        return all(root).single { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("dial") }.size
    }

    private fun check(minDp: Float): Float {
        val strap = "Polar H9 A1B2C3D4"
        val running = listOf(
            MainScreenState(MainPhase.Connected(strap), heartRate = 53, strapConnected = true),
            MainScreenState(MainPhase.Settling(38, false), heartRate = 53, handAngle = 132.0),
            MainScreenState(MainPhase.Measuring(36, 22, false), heartRate = 52, handAngle = 504.0),
        ).map(::dialSize)
        running.forEach { assertEquals(running[0], it) }
        val dp = running[0].width / activity.resources.displayMetrics.density
        assertTrue("dial $dp dp", dp >= minDp)
        for (upload in listOf(ResultSummary.Upload.NONE, ResultSummary.Upload.UPLOADED)) {
            val r = dialSize(MainScreenState(result(upload)))
            assertTrue("result $upload: $r > ${running[0]}", r.width <= running[0].width && r.width > 0)
        }
        return dp
    }

    /** Robolectric has no system bars: at 360 x 640 the dial is width bound, min(264, 360 - 112). */
    @Test
    fun dialKeepsItsSizeFromConnectedThroughMeasuring() {
        assertEquals(248f, check(248f), 0.5f)
    }

    /** As on the emulator: 640 dp minus the status bar (24) and the navigation bar (48), so height bound. */
    @Test
    @Config(qualifiers = "w360dp-h568dp-xhdpi")
    fun dialKeepsItsSizeWhenHeightBound() {
        check(230f)
    }
}
