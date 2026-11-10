package dev.lumaphase.android.camera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize

/**
 * Independent camera capture test. It is not exported and is reached only from the settings
 * screen. The Activity owns the [CameraController] lifecycle: it stops and releases all camera
 * resources as soon as the Activity is no longer started, and shuts the camera thread down on
 * destroy.
 */
class CameraTestActivity : ComponentActivity() {
    private lateinit var controller: CameraController
    private val resumed = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = CameraController(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CameraTestScreen(resumed = resumed.value, controller = controller)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        resumed.value = true
    }

    override fun onStop() {
        resumed.value = false
        controller.stop()
        super.onStop()
    }

    override fun onDestroy() {
        controller.shutdown()
        super.onDestroy()
    }
}
