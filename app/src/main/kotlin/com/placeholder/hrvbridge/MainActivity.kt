package com.placeholder.hrvbridge

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import com.placeholder.hrvbridge.ui.HrvTheme

class MainActivity : ComponentActivity() {
    private val controller: MeasurementController by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val active = controller.isActive
            LaunchedEffect(active) {
                if (active) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            HrvTheme {
                AppRoot(controller)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) controller.onBackgrounded()
    }
}
