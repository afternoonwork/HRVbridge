package com.placeholder.hrvbridge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.placeholder.hrvbridge.core.FailReason
import com.placeholder.hrvbridge.ui.ConnectPhase
import com.placeholder.hrvbridge.ui.ConnectScreen
import com.placeholder.hrvbridge.ui.DialModel
import com.placeholder.hrvbridge.ui.HeartDial
import com.placeholder.hrvbridge.ui.HrvTheme
import com.placeholder.hrvbridge.ui.LocalStillSpinners
import com.placeholder.hrvbridge.ui.MainActions
import com.placeholder.hrvbridge.ui.MainPhase
import com.placeholder.hrvbridge.ui.MainScreenContent
import com.placeholder.hrvbridge.ui.MainScreenState
import com.placeholder.hrvbridge.ui.ResultSummary
import com.placeholder.hrvbridge.ui.SettingsActions
import com.placeholder.hrvbridge.ui.SettingsContent
import com.placeholder.hrvbridge.ui.Txt
import com.placeholder.hrvbridge.ui.hrv
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the dial and every main screen state in light and dark at 375 x 667 dp into
 * build/screenshots (uploaded by CI), so the UI can be reviewed without a phone.
 * Robolectric's native graphics; fonts may differ slightly from a device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w375dp-h667dp-xhdpi")
class ScreenshotTest {
    private val out = File(System.getProperty("screenshotDir") ?: "build/screenshots").apply { mkdirs() }

    private fun summary(reliable: Boolean = true, upload: ResultSummary.Upload) = MainPhase.Result(
        ResultSummary(52, if (reliable) 68.4 else 41.7, if (reliable) 61.2 else 58.3,
                      if (reliable) 64 else 57, if (reliable) 64 else 63, reliable, upload),
    )

    private val strap = "Polar H9 A1B2C3D4"

    private val states = mapOf(
        "no-strap" to MainScreenState(MainPhase.NoStrap),
        "searching" to MainScreenState(MainPhase.Searching(listOf(
            FoundStrap("A", strap, -58), FoundStrap("B", "HRM-Dual 12345", -77)))),
        "connecting" to MainScreenState(MainPhase.Connecting(strap)),
        "connected" to MainScreenState(MainPhase.Connected(strap), heartRate = 53, strapConnected = true),
        "retry" to MainScreenState(MainPhase.Connected(strap), heartRate = 53, strapConnected = true, retryUploaded = 2),
        "cancelled" to MainScreenState(MainPhase.Connected(strap), heartRate = 54, strapConnected = true, cancelled = true),
        "could-not-connect" to MainScreenState(MainPhase.CouldNotConnect(strap)),
        "bluetooth-off" to MainScreenState(MainPhase.BluetoothOff),
        "not-allowed" to MainScreenState(MainPhase.NotAllowed),
        "poor-contact" to MainScreenState(MainPhase.Settling(41, true), heartRate = 55, handAngle = 115.0),
        "settling" to MainScreenState(MainPhase.Settling(38, false), heartRate = 53, handAngle = 132.0),
        "measuring" to MainScreenState(MainPhase.Measuring(36, 22, false), heartRate = 52, handAngle = 504.0),
        "failed-no-rr" to MainScreenState(MainPhase.Failed(FailReason.NO_RR), heartRate = 61),
        "failed-disconnected" to MainScreenState(MainPhase.Failed(FailReason.DISCONNECTED)),
        "result" to MainScreenState(summary(upload = ResultSummary.Upload.NONE)),
        "uploading" to MainScreenState(summary(upload = ResultSummary.Upload.IN_PROGRESS)),
        "uploaded" to MainScreenState(summary(upload = ResultSummary.Upload.UPLOADED)),
        "unreliable" to MainScreenState(summary(reliable = false, upload = ResultSummary.Upload.NONE)),
        "not-enough-beats" to MainScreenState(MainPhase.NotEnoughBeats),
        "upload-failed" to MainScreenState(summary(upload = ResultSummary.Upload.FAILED)),
        "not-connected" to MainScreenState(summary(upload = ResultSummary.Upload.NOT_CONNECTED)),
    )

    @Test
    fun lightAndDark() {
        renderAll(dark = false)
        renderAll(dark = true)
    }

    private fun renderAll(dark: Boolean) {
        val suffix = if (dark) "dark" else "light"
        render("dial-$suffix", dark) {
            Box(Modifier.fillMaxSize().background(hrv.bg), contentAlignment = Alignment.Center) {
                HeartDial(DialModel.running(52, 504.0, false), 264.dp)
            }
        }
        // Inside the real top app bar, so the height left for the content is as on a phone
        // (no status bar in Robolectric: about 24 dp more than on a device).
        for ((name, state) in states) {
            render("$name-$suffix", dark) { MainScreen(state) }
        }
        render("connect-$suffix", dark) { ConnectScreen("", {}, ConnectPhase.Idle, {}, {}) }
        render("connect-rejected-$suffix", dark) {
            ConnectScreen("abc123", {}, ConnectPhase.Problem(Txt(R.string.api_key_rejected)), {}, {})
        }
        render("connect-connected-$suffix", dark) { ConnectScreen("", {}, ConnectPhase.Connected(true), {}, {}) }
        render("settings-$suffix", dark) { SettingsContent(60, SettingsActions()) }
        render("questions-$suffix", dark) {
            com.placeholder.hrvbridge.ui.QuestionsPage({}, initial = com.placeholder.hrvbridge.core.ReadinessAnswers.of(2, 1, 2, 1, false))
        }
    }

    /** Three phone sizes: the dial keeps its size and place from Connected through measuring. */
    private fun sizes(tag: String) {
        for (name in listOf("connected", "settling", "poor-contact", "measuring", "result")) {
            render("$name-$tag-light", false) { MainScreen(states.getValue(name)) }
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun smallScreen() = sizes("360x640")

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun mediumScreen() = sizes("411x891")

    @Test
    @Config(qualifiers = "w448dp-h998dp-xxhdpi")
    fun largeScreen() = sizes("448x998")

    @Composable
    private fun MainScreen(state: MainScreenState) {
        androidx.compose.material3.Scaffold(
            containerColor = hrv.bg,
            topBar = { com.placeholder.hrvbridge.ui.MainTopBar(!state.isMeasuring) {} },
        ) { padding -> MainScreenContent(state, MainActions(), Modifier.padding(padding)) }
    }

    /** Launcher icon: the adaptive icon with the device mask, then the themed (monochrome) layer. */
    @Test
    fun launcherIcon() {
        val px = 432
        val icon = activity.getDrawable(R.mipmap.ic_launcher) as android.graphics.drawable.AdaptiveIconDrawable
        val bitmap = Bitmap.createBitmap(px * 2, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(android.graphics.Color.rgb(0xE2, 0xE2, 0xDF)) }
        icon.setBounds(0, 0, px, px)
        icon.draw(canvas)
        val mono = icon.monochrome!!.mutate().apply { setTint(android.graphics.Color.rgb(0x1A, 0x1A, 0x1A)) }
        mono.setBounds(px, 0, px * 2, px)
        mono.draw(canvas)
        File(out, "launcher-icon.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // One activity for all images; each image gets a fresh ComposeView (composed on attach).
    private val activity by lazy { Robolectric.buildActivity(ComponentActivity::class.java).setup().get() }

    private fun render(name: String, dark: Boolean, content: @Composable () -> Unit) {
        activity.setContentView(ComposeView(activity).apply { setContent {
            HrvTheme(dark = dark) { CompositionLocalProvider(LocalStillSpinners provides true) { content() } }
        } })
        // A second of frames, so layouts that depend on measured heights settle (spinners are still).
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        val view: View = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
