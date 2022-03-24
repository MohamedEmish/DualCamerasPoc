package com.amosh.dualcameraspoc

import android.Manifest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import com.amosh.dualcameraspoc.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

class MainActivity : AppCompatActivity() {


    private lateinit var activityCameraBinding: ActivityMainBinding

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            var granted = true
            permissions.entries.forEach {
                if (it.value == false)
                    granted = false
            }
            if (!granted) {
                Toast.makeText(this, "some permission is missing", Toast.LENGTH_SHORT).show()
            } else {
                Handler(Looper.getMainLooper()).postDelayed({
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.container, CameraFragment())
                        .commit()
                }, 1000)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityCameraBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(activityCameraBinding.root)

        requestCameraPermission.launch(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        )
    }

    companion object {

        /** Milliseconds used for UI animations */
        const val ANIMATION_FAST_MILLIS = 50L
        const val ANIMATION_SLOW_MILLIS = 100L
        private const val IMMERSIVE_FLAG_TIMEOUT = 500L
    }
}