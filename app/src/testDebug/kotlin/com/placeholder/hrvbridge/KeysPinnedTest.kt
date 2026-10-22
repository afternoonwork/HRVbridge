package com.placeholder.hrvbridge

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.positionInRoot
import com.placeholder.hrvbridge.ui.HrvTheme
import com.placeholder.hrvbridge.ui.LocalStillSpinners
import com.placeholder.hrvbridge.ui.MainActions
import com.placeholder.hrvbridge.ui.MainPhase
import com.placeholder.hrvbridge.ui.MainScreenContent
import com.placeholder.hrvbridge.ui.MainScreenState
import com.placeholder.hrvbridge.ui.MainTopBar
import com.placeholder.hrvbridge.ui.ResultSummary
import com.placeholder.hrvbridge.ui.hrv
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** At the largest text size on a small phone the keys stay fully on screen (content scrolls above them). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xhdpi")
class KeysPinnedTest {
    private val activity by lazy { Robolectric.buildActivity(ComponentActivity::class.java).setup().get() }

    private fun assertKeysOnScreen(state: MainScreenState, vararg keys: Int) {
        val view = ComposeView(activity)
        activity.setContentView(view.apply { setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 2f)) {
                HrvTheme(dark = false) { CompositionLocalProvider(LocalStillSpinners provides true) {
                    Scaffold(containerColor = hrv.bg, topBar = { MainTopBar(!state.isMeasuring) {} }) { padding ->
                        MainScreenContent(state, MainActions(), Modifier.padding(padding))
                    }
                } }
            }
        } })
        shadowOf(Looper.getMainLooper()).idle()
        fun all(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::all)
        val root = (view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode
        val screen = root.boundsInRoot
        for (key in keys) {
            val text = activity.getString(key)
            val b = all(root).first { n ->
                n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
            }.let { n -> // unclipped: boundsInRoot is clipped by the scroll container
                val c = n.layoutInfo.coordinates
                Rect(c.positionInRoot(), c.size.toSize())
            }
            assertTrue("'$text' at $b, screen $screen", b.top >= screen.top && b.bottom <= screen.bottom)
        }
    }

    @Test
    fun stopKeyOnScreenWhileMeasuringWithPoorContact() = assertKeysOnScreen(
        MainScreenState(MainPhase.Measuring(30, 25, true), heartRate = 55, handAngle = 540.0), R.string.stop)

    @Test
    fun uploadKeysOnScreenOnTheResult() = assertKeysOnScreen(
        MainScreenState(MainPhase.Result(ResultSummary(52, 68.4, 61.2, 64, 64, true, ResultSummary.Upload.NONE))),
        R.string.upload_to_intervals, R.string.close_without_uploading)
}
