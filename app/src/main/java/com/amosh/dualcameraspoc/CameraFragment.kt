package com.amosh.dualcameraspoc

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.LayoutInflater
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.amosh.dualcameraspoc.FileUtilsKt.getPath
import com.amosh.dualcameraspoc.FileUtilsKt.writeFileOnInternalStorage
import com.amosh.dualcameraspoc.databinding.FragmentCameraBinding
import com.arthenica.mobileffmpeg.Config.RETURN_CODE_CANCEL
import com.arthenica.mobileffmpeg.Config.RETURN_CODE_SUCCESS
import com.arthenica.mobileffmpeg.FFmpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine


@RequiresApi(Build.VERSION_CODES.O)
@SuppressLint("NewApi")
class CameraFragment : Fragment() {

    /** Android ViewBinding */
    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    private var isRecording = false

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        val context = requireContext().applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    /** [CameraCharacteristics] corresponding to the provided Camera ID */
    private val rearCameraCharacteristics: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics(cameraManager.cameraIdList[0])
    }

    /** [CameraCharacteristics] corresponding to the provided Camera ID */
    private val frontCameraCharacteristics: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics(cameraManager.cameraIdList[1])
    }

    /** File where the recording will be saved */
    private val rearOutputFile: File by lazy { createFile("rear", requireContext()) }

    /** File where the recording will be saved */
    private val frontOutputFile: File by lazy { createFile("front", requireContext()) }

    /**
     * Setup a persistent [Surface] for the recorder so we can use it as an output target for the
     * camera session without preparing the recorder
     */
    private val rearRecorderSurface: Surface by lazy {

        // Get a persistent Surface from MediaCodec, don't forget to release when done
        val surface = MediaCodec.createPersistentInputSurface()

        // Prepare and release a dummy MediaRecorder with our new surface
        // Required to allocate an appropriately sized buffer before passing the Surface as the
        //  output target to the capture session
        createRearCameraRecorder(surface).apply {
            prepare()
            release()
        }

        surface
    }

    /**
     * Setup a persistent [Surface] for the recorder so we can use it as an output target for the
     * camera session without preparing the recorder
     */
    private val frontRecorderSurface: Surface by lazy {

        // Get a persistent Surface from MediaCodec, don't forget to release when done
        val surface = MediaCodec.createPersistentInputSurface()

        // Prepare and release a dummy MediaRecorder with our new surface
        // Required to allocate an appropriately sized buffer before passing the Surface as the
        //  output target to the capture session
        createFrontCameraRecorder(surface).apply {
            prepare()
            release()
        }

        surface
    }

    /** Saves the video recording */
    private val rearRecorder: MediaRecorder by lazy { createRearCameraRecorder(rearRecorderSurface) }

    /** Saves the video recording */
    private val frontRecorder: MediaRecorder by lazy { createFrontCameraRecorder(frontRecorderSurface) }

    /** [HandlerThread] where all camera operations run */
    private val rearCameraThread = HandlerThread("RearCameraThread").apply { start() }

    /** [HandlerThread] where all camera operations run */
    private val frontCameraThread = HandlerThread("FrontCameraThread").apply { start() }

    /** [Handler] corresponding to [RearCameraThread] */
    private val rearCameraHandler = Handler(rearCameraThread.looper)

    /** [Handler] corresponding to [FrontCameraThread] */
    private val frontCameraHandler = Handler(frontCameraThread.looper)

    /** Performs recording animation of flashing screen */
    private val animationTask: Runnable by lazy {
        Runnable {
            // Flash white animation
            fragmentCameraBinding.overlay.foreground = Color.argb(150, 255, 255, 255).toDrawable()
            // Wait for ANIMATION_FAST_MILLIS
            fragmentCameraBinding.overlay.postDelayed({
                // Remove white flash animation
                fragmentCameraBinding.overlay.foreground = null
                // Restart animation recursively
                fragmentCameraBinding.overlay.postDelayed(animationTask, MainActivity.ANIMATION_FAST_MILLIS)
            }, MainActivity.ANIMATION_FAST_MILLIS)
        }
    }

    /** Captures frames from a [CameraDevice] for our video recording */
    private lateinit var rearCameraSession: CameraCaptureSession

    /** Captures frames from a [CameraDevice] for our video recording */
    private lateinit var frontCameraSession: CameraCaptureSession

    /** The [CameraDevice] that will be opened in this fragment */
    private lateinit var rearCameraDevice: CameraDevice

    /** The [CameraDevice] that will be opened in this fragment */
    private lateinit var frontCameraDevice: CameraDevice

    /** Requests used for preview only in the [CameraCaptureSession] */
    private val rearCameraPreviewRequest: CaptureRequest by lazy {
        // Capture request holds references to target surfaces
        rearCameraSession.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            // Add the preview surface target
            addTarget(fragmentCameraBinding.rearViewFinder.holder.surface)
        }.build()
    }

    /** Requests used for preview only in the [CameraCaptureSession] */
    private val frontCameraPreviewRequest: CaptureRequest by lazy {
        // Capture request holds references to target surfaces
        frontCameraSession.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            // Add the preview surface target
            addTarget(fragmentCameraBinding.frontViewFinder.holder.surface)
        }.build()
    }

    /** Requests used for preview and recording in the [CameraCaptureSession] */
    private val rearCameraRecordRequest: CaptureRequest by lazy {
        // Capture request holds references to target surfaces
        rearCameraSession.device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            // Add the preview and recording surface targets
            addTarget(fragmentCameraBinding.rearViewFinder.holder.surface)
            addTarget(rearRecorderSurface)
            // Sets user requested FPS for all targets
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
        }.build()
    }

    /** Requests used for preview and recording in the [CameraCaptureSession] */
    private val frontCameraRecordRequest: CaptureRequest by lazy {
        // Capture request holds references to target surfaces
        frontCameraSession.device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            // Add the preview and recording surface targets
            addTarget(fragmentCameraBinding.frontViewFinder.holder.surface)
            addTarget(frontRecorderSurface)
            // Sets user requested FPS for all targets
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
        }.build()
    }

    private var recordingStartMillis: Long = 0L

    /** Live data listener for changes in the device orientation relative to the camera */
    private lateinit var relativeOrientation: OrientationLiveData

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        fragmentCameraBinding.frontViewFinder.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
            ) = Unit

            override fun surfaceCreated(holder: SurfaceHolder) {
                // Selects appropriate preview size and configures view finder
                val previewSize = getPreviewOutputSize(
                    fragmentCameraBinding.frontViewFinder.display, frontCameraCharacteristics, SurfaceHolder::class.java)
                Log.d(TAG, "frontViewFinder size: ${fragmentCameraBinding.frontViewFinder.width} x ${fragmentCameraBinding.frontViewFinder.height}")
                Log.d(TAG, "Selected preview size: $previewSize")

                fragmentCameraBinding.frontViewFinder.setAspectRatio(previewSize.width, previewSize.height)

                // To ensure that size is set, initialize camera in the view's thread
                fragmentCameraBinding.frontViewFinder.post { initializeFrontCamera() }
            }
        })

        fragmentCameraBinding.rearViewFinder.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
            ) = Unit

            override fun surfaceCreated(holder: SurfaceHolder) {
                // Selects appropriate preview size and configures view finder
                val previewSize = getPreviewOutputSize(
                    fragmentCameraBinding.rearViewFinder.display, rearCameraCharacteristics, SurfaceHolder::class.java)
                Log.d(TAG, "rearViewFinder size: ${fragmentCameraBinding.rearViewFinder.width} x ${fragmentCameraBinding.rearViewFinder.height}")
                Log.d(TAG, "Selected preview size: $previewSize")
                fragmentCameraBinding.rearViewFinder.setAspectRatio(previewSize.width, previewSize.height)

                // To ensure that size is set, initialize camera in the view's thread
                fragmentCameraBinding.rearViewFinder.post { initializeRearCamera() }
            }
        })

        // Used to rotate the output media to match device orientation
        relativeOrientation = OrientationLiveData(requireContext(), rearCameraCharacteristics).apply {
            observe(viewLifecycleOwner) { orientation ->
                Log.d(TAG, "Orientation changed: $orientation")
            }
        }

        // React to user touching the capture button
        fragmentCameraBinding.captureButton.setOnClickListener {
            if (!isRecording) {
                fragmentCameraBinding.captureButton.text = "STOP"
                isRecording = true
                lifecycleScope.launch(Dispatchers.IO) {

                    // Prevents screen rotation during the video recording
                    requireActivity().requestedOrientation =
                        ActivityInfo.SCREEN_ORIENTATION_LOCKED

                    // Start recording repeating requests, which will stop the ongoing preview
                    //  repeating requests without having to explicitly call `session.stopRepeating`
                    rearCameraSession.setRepeatingRequest(rearCameraRecordRequest, null, rearCameraHandler)
                    frontCameraSession.setRepeatingRequest(frontCameraRecordRequest, null, frontCameraHandler)


                    // Finalizes recorder setup and starts recording
                    rearRecorder.apply {
                        // Sets output orientation based on current sensor value at start time
                        relativeOrientation.value?.let { setOrientationHint(it) }
                        prepare()
                        start()
                    }

                    frontRecorder.apply {
                        // Sets output orientation based on current sensor value at start time
                        relativeOrientation.value?.let { setOrientationHint(it) }
                        prepare()
                        start()
                    }

                    recordingStartMillis = System.currentTimeMillis()
                    Log.d(TAG, "Recording started")

                    // Starts recording animation
                    fragmentCameraBinding.overlay.post(animationTask)
                }
            } else {
                fragmentCameraBinding.captureButton.text = "RECORD"
                isRecording = false
                lifecycleScope.launch(Dispatchers.IO) {

                    // Unlocks screen rotation after recording finished
                    requireActivity().requestedOrientation =
                        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

                    // Requires recording of at least MIN_REQUIRED_RECORDING_TIME_MILLIS
                    val elapsedTimeMillis = System.currentTimeMillis() - recordingStartMillis
                    if (elapsedTimeMillis < MIN_REQUIRED_RECORDING_TIME_MILLIS) {
                        delay(MIN_REQUIRED_RECORDING_TIME_MILLIS - elapsedTimeMillis)
                    }

                    Log.d(TAG, "Recording stopped. Output file: $rearOutputFile")
                    rearRecorder.stop()
                    frontRecorder.stop()

                    // Removes recording animation
                    fragmentCameraBinding.overlay.removeCallbacks(animationTask)

                    // Broadcasts the media file to the rest of the system
                    MediaScannerConnection.scanFile(
                        view.context, arrayOf(rearOutputFile.absolutePath), null, null)

                    // Launch external activity via intent to play video recorded using our provider
                    val rearUri = FileUtilsKt.getFileUri(rearOutputFile, requireContext())
                    val frontUri = FileUtilsKt.getFileUri(frontOutputFile, requireContext())
                    val rearPath = getPath(requireContext(), rearUri ?: Uri.EMPTY)
                    val frontPath = getPath(requireContext(), frontUri ?: Uri.EMPTY)

                    val combinedFile = writeFileOnInternalStorage(requireContext())
                    val combinedFileUri = FileUtilsKt.getFileUri(combinedFile, requireContext()) ?: Uri.EMPTY
                    val combinedPath = getPath(requireContext(), combinedFileUri)
                    Log.i("Uris", "rear uri= $rearUri\n path =$rearPath")
                    Log.i("Uris", "front uri= $frontUri\n path =$frontPath")

                    Log.d("FFmpeg", "combined exists ${combinedFile?.isFile ?: false && combinedFile?.exists() ?: false}")
//                    val command = arrayOf(
//                        "-i",
//                        rearPath,
//                        "-f",
//                        "lavfi",
//                        "-i",
//                        "movie=" + frontPath +
//                            ":loop=1000,setpts=N/FRAME_RATE/TB",
//                        "-y",
//                        "-filter_complex",
//                        "[1:v][0:v]scale2ref[ua][b];[ua]setsar=1,format=yuva444p,colorchannelmixer=aa=1[u];[b][u]overlay=1:1:shortest=1",
//                        combinedPath
//                    )
//
//                    FFmpeg.executeAsync(command) { _, returnCode ->
//                        Log.d("FFmpeg", "returnCode = $returnCode")
//                        Log.d("FFmpeg", "result file uri $combinedFileUri")
//                        Log.d("FFmpeg", "result file path $combinedPath")
//                        when (returnCode) {
//                            RETURN_CODE_SUCCESS -> {
//                                Log.d("FFmpeg", "RETURN_CODE_SUCCESS")
//                                Log.d("FFmpeg", "combined exists ${combinedFile?.isFile ?: false && combinedFile?.exists() ?: false}")
//                                Toast.makeText(requireContext(), "SUCCESS", Toast.LENGTH_LONG).show()
//                            }
//                            RETURN_CODE_CANCEL -> Log.d("FFmpeg", "RETURN_CODE_CANCEL")
//                            else -> Log.d("FFmpeg", "else")
//                        }
//                    }

                    concatenate(rearPath ?: "", frontPath ?: "", combinedPath ?: "")
                    // Finishes our current camera screen
                    delay(MainActivity.ANIMATION_SLOW_MILLIS)
                }
            }
        }
    }

    fun concatenate(inputFile1: String, inputFile2: String, outputFile: String) {
        Log.d(TAG, "Concatenating $inputFile1 and $inputFile2 to $outputFile")
        val list: String = generateList(listOf(inputFile1, inputFile2))
        val vk: Videokit = Videokit.getInstance()
        vk.run(arrayOf(
            "ffmpeg",
            "-f",
            "concat",
            "-i",
            list,
            "-c",
            "copy",
            outputFile
        ))
    }

    private fun generateList(inputs: List<String>): String {
        val list: File?
        val writer: Writer?
        try {
            list = File.createTempFile("ffmpeg-list", ".txt")
            writer = BufferedWriter(OutputStreamWriter(FileOutputStream(list)))
            for (input in inputs) {
                writer.write("file '$input'\n")
                Log.d(TAG, "Writing to list file: file '$input'")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return "/"
        }
        return writer.toString()
    }

    /** Creates a [MediaRecorder] instance using the provided [Surface] as input */
    private fun createRearCameraRecorder(surface: Surface) = MediaRecorder(requireContext()).apply {
        setAudioSource(MediaRecorder.AudioSource.MIC)
        setVideoSource(MediaRecorder.VideoSource.SURFACE)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setOutputFile(rearOutputFile.absolutePath)
        setVideoEncodingBitRate(RECORDER_VIDEO_BITRATE)
        setVideoFrameRate(30)
//        setVideoSize(704, 576)
        setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        setInputSurface(surface)
    }

    /** Creates a [MediaRecorder] instance using the provided [Surface] as input */
    private fun createFrontCameraRecorder(surface: Surface) = MediaRecorder(requireContext()).apply {
        setVideoSource(MediaRecorder.VideoSource.SURFACE)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setOutputFile(frontOutputFile.absolutePath)
        setVideoEncodingBitRate(RECORDER_VIDEO_BITRATE)
        setVideoFrameRate(30)
//        setVideoSize(128, 96)
        setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        setInputSurface(surface)
    }

    /**
     * Begin all camera operations in a coroutine in the main thread. This function:
     * - Opens the camera
     * - Configures the camera session
     * - Starts the preview by dispatching a repeating request
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun initializeRearCamera() = lifecycleScope.launch(Dispatchers.Main) {

        // Open the selected camera
        rearCameraDevice = openCamera(cameraManager, cameraManager.cameraIdList[0], rearCameraHandler)

        // Creates list of Surfaces where the camera will output frames
        val targets = listOf(fragmentCameraBinding.rearViewFinder.holder.surface, rearRecorderSurface)

        // Start a capture session using our open camera and list of Surfaces where frames will go
        rearCameraSession = createCaptureSession(rearCameraDevice, targets, rearCameraHandler)

        // Sends the capture request as frequently as possible until the session is torn down or
        //  session.stopRepeating() is called
        rearCameraSession.setRepeatingRequest(rearCameraPreviewRequest, null, rearCameraHandler)

    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initializeFrontCamera() = lifecycleScope.launch(Dispatchers.Main) {

        // Open the selected camera
        frontCameraDevice = openCamera(cameraManager, cameraManager.cameraIdList[1], frontCameraHandler)

        // Creates list of Surfaces where the camera will output frames
        val targets = listOf(fragmentCameraBinding.frontViewFinder.holder.surface, frontRecorderSurface)

        // Start a capture session using our open camera and list of Surfaces where frames will go
        frontCameraSession = createCaptureSession(frontCameraDevice, targets, frontCameraHandler)

        // Sends the capture request as frequently as possible until the session is torn down or
        //  session.stopRepeating() is called
        frontCameraSession.setRepeatingRequest(frontCameraPreviewRequest, null, frontCameraHandler)

    }

    /** Opens the camera and returns the opened device (as the result of the suspend coroutine) */
    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        handler: Handler? = null,
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = cont.resume(device)

            override fun onDisconnected(device: CameraDevice) {
                Log.w(TAG, "Camera $cameraId has been disconnected")
                requireActivity().finish()
            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = "Rear Camera " + when (error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                val exc = RuntimeException("Rear Camera $cameraId error: ($error) $msg")
                Log.e(TAG, exc.message, exc)
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }, handler)
    }

    /**
     * Creates a [CameraCaptureSession] and returns the configured session (as the result of the
     * suspend coroutine)
     */
    private suspend fun createCaptureSession(
        device: CameraDevice,
        targets: List<Surface>,
        handler: Handler? = null,
    ): CameraCaptureSession = suspendCoroutine { cont ->
        // Creates a capture session using the predefined targets, and defines a session state
        // callback which resumes the coroutine once the session is configured
        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {

            override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)

            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }, handler)
    }

    override fun onStop() {
        super.onStop()
        try {
            rearCameraDevice.close()
            frontCameraDevice.close()
        } catch (exc: Throwable) {
            Log.e(TAG, "Error closing camera", exc)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        rearCameraThread.quitSafely()
        frontCameraThread.quitSafely()
        rearRecorder.release()
        frontRecorder.release()
        rearRecorderSurface.release()
        frontRecorderSurface.release()
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()
    }

    companion object {
        private val TAG = CameraFragment::class.java.simpleName

        private const val RECORDER_VIDEO_BITRATE: Int = 10_000_000
        private const val MIN_REQUIRED_RECORDING_TIME_MILLIS: Long = 1000L

        /** Creates a [File] named with the current date and time */
        private fun createFile(name: String, context: Context): File {
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
            return File(context.filesDir, "VID_${name}_${sdf.format(Date())}.$QR_CODE_FILE_EXT")
        }
    }
}