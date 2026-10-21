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

/** On a small screen (720 x 1280 at 320 dpi) the dial keeps its size from Connected to the result. */
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

    @Test
    fun dialKeepsItsSizeFromStartToResult() {
        val strap = "Polar H9 A1B2C3D4"
        val sizes = listOf(
            MainScreenState(MainPhase.Connected(strap), heartRate = 53, strapConnected = true),
            MainScreenState(MainPhase.Settling(38, false), heartRate = 53, handAngle = 132.0),
            MainScreenState(MainPhase.Measuring(36, 22, false), heartRate = 52, handAngle = 504.0),
            MainScreenState(result(ResultSummary.Upload.NONE)),
            MainScreenState(result(ResultSummary.Upload.UPLOADED)),
        ).map(::dialSize)
        assertTrue("dial not rendered: $sizes", sizes[0].width > 0)
        sizes.forEach { assertEquals(sizes[0], it) }
    }
}
