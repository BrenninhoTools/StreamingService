package com.brenninho.streamingservice.android

import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.brenninho.streamingservice.App
import com.brenninho.streamingservice.AppEnvironment
import com.brenninho.streamingservice.android.capture.MediaProjectionCapturer
import com.brenninho.streamingservice.core.AndroidAppContext
import kotlinx.coroutines.CompletableDeferred

class MainActivity : ComponentActivity() {
    private var pendingProjection: CompletableDeferred<ActivityResult?>? = null

    // Registered while the Activity is created, as the Activity Result API requires.
    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pendingProjection?.complete(result)
        pendingProjection = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AndroidAppContext.application = application

        val environment = AppEnvironment(
            capturer = MediaProjectionCapturer(applicationContext, ::requestProjection),
        )
        setContent { App(environment) }
    }

    /** Shows the system dialog that asks the user to allow screen capture. */
    private suspend fun requestProjection(): ActivityResult? {
        val manager = getSystemService(MediaProjectionManager::class.java) ?: return null
        val deferred = CompletableDeferred<ActivityResult?>()
        pendingProjection = deferred
        projectionLauncher.launch(manager.createScreenCaptureIntent())
        return deferred.await()
    }
}
