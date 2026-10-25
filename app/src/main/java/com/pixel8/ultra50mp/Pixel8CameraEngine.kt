package com.pixel8.ultra50mp

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

data class SensorModeInfo(
    val cameraId: String,
    val native50MpUnlocked: Boolean,
    val sensorCaptureSize: Size,
    val rawSupported: Boolean,
    val rawSize: Size?
)

class Pixel8CameraEngine(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraThread = HandlerThread("Pixel8CameraThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val cameraExecutor = Executor { cmd -> cameraHandler.post(cmd) }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null

    lateinit var modeInfo: SensorModeInfo
        private set
    private lateinit var characteristics: CameraCharacteristics

    fun inspectPixel8MainSensor(): SensorModeInfo {
        val backCameraId = cameraManager.cameraIdList.firstOrNull { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraManager.cameraIdList.first()

        characteristics = cameraManager.getCameraCharacteristics(backCameraId)
        val caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()

        val hasUltraHighRes = caps.contains(
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR
        )
        val hasRaw = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)

        val maxResMap = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION
        )
        val defaultMap = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        )!!

        val activeMap = if (hasUltraHighRes && maxResMap != null) maxResMap else defaultMap

        val jpegSizes = activeMap.getOutputSizes(ImageFormat.JPEG) ?: defaultMap.getOutputSizes(ImageFormat.JPEG)
        val maxJpeg = jpegSizes.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: Size(4080, 3072)

        val rawSizes = if (hasRaw) {
            activeMap.getOutputSizes(ImageFormat.RAW_SENSOR) ?: defaultMap.getOutputSizes(ImageFormat.RAW_SENSOR)
        } else null
        val maxRaw = rawSizes?.maxByOrNull { it.width.toLong() * it.height.toLong() }

        modeInfo = SensorModeInfo(
            cameraId = backCameraId,
            native50MpUnlocked = hasUltraHighRes && maxResMap != null && maxJpeg.width >= 8000,
            sensorCaptureSize = maxJpeg,
            rawSupported = hasRaw && maxRaw != null,
            rawSize = maxRaw
        )
        return modeInfo
    }

    @SuppressLint("MissingPermission")
    fun openCamera(previewSurface: Surface, onReady: (SensorModeInfo) -> Unit, onError: (String) -> Unit) {
        val info = inspectPixel8MainSensor()

        jpegReader = ImageReader.newInstance(
            info.sensorCaptureSize.width,
            info.sensorCaptureSize.height,
            ImageFormat.JPEG,
            10
        )

        if (info.rawSupported && info.rawSize != null) {
            rawReader = ImageReader.newInstance(
                info.rawSize.width,
                info.rawSize.height,
                ImageFormat.RAW_SENSOR,
                2
            )
        }

        cameraManager.openCamera(
            info.cameraId,
            cameraExecutor,
            object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    startPreviewSession(device, previewSurface, onReady, onError)
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    onError("Camera open error code: $error")
                }
            }
        )
    }

    private fun startPreviewSession(
        device: CameraDevice,
        previewSurface: Surface,
        onReady: (SensorModeInfo) -> Unit,
        onError: (String) -> Unit
    ) {
        val outputs = mutableListOf(
            OutputConfiguration(previewSurface),
            OutputConfiguration(jpegReader!!.surface)
        )
        rawReader?.let { outputs.add(OutputConfiguration(it.surface)) }

        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputs,
            cameraExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val previewReq = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(previewSurface)
                        set(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                        )
                        set(
                            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                        )
                    }.build()
                    session.setRepeatingRequest(previewReq, null, cameraHandler)
                    onReady(modeInfo)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    onError("Failed to configure Camera2 capture session")
                }
            }
        )
        device.createCaptureSession(sessionConfig)
    }

    /**
     * Executes the unrooted 50MP capture pipeline:
     * 1. Captures a high-speed locked burst of full-sensor frames (plus an optional 16-bit RAW DNG).
     * 2. Runs sub-pixel multi-frame alignment & 2x Super-Resolution to generate an 8160x6144 (50.1 MP) photo.
     * 3. Saves the 50.1 MP JPEG (and optional 16-bit DNG) to DCIM/Pixel8_50MP.
     */
    suspend fun capture50MpPhoto(
        burstCount: Int,
        saveRawDng: Boolean,
        onStepUpdate: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val session = captureSession ?: throw IllegalStateException("Camera session not ready")
        val device = cameraDevice ?: throw IllegalStateException("Camera device not ready")
        val jReader = jpegReader ?: throw IllegalStateException("JPEG reader not initialized")

        val actualBurst = if (modeInfo.native50MpUnlocked) 1 else burstCount
        val capturedBitmaps = mutableListOf<Bitmap>()
        val burstCompleted = CompletableDeferred<Unit>()

        var rawImageSlot: Image? = null
        var rawResultSlot: TotalCaptureResult? = null
        val shouldCaptureRaw = saveRawDng && modeInfo.rawSupported && rawReader != null

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        jReader.setOnImageAvailableListener({ reader ->
            val img = reader.acquireNextImage() ?: return@setOnImageAvailableListener
            try {
                val buffer = img.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    synchronized(capturedBitmaps) {
                        capturedBitmaps.add(bmp)
                        onStepUpdate("Captured sensor frame ${capturedBitmaps.size}/$actualBurst…")
                        if (capturedBitmaps.size >= actualBurst && !burstCompleted.isCompleted) {
                            burstCompleted.complete(Unit)
                        }
                    }
                }
            } finally {
                img.close()
            }
        }, cameraHandler)

        if (shouldCaptureRaw) {
            rawReader?.setOnImageAvailableListener({ reader ->
                val img = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                rawImageSlot = img
            }, cameraHandler)
        }

        val requests = (0 until actualBurst).map { index ->
            device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(jReader.surface)
                if (index == 0 && shouldCaptureRaw) {
                    rawReader?.surface?.let { addTarget(it) }
                }
                set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_LOCK, true)
                set(CaptureRequest.CONTROL_AWB_LOCK, true)
                set(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                )
                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)

                if (modeInfo.native50MpUnlocked) {
                    set(
                        CaptureRequest.SENSOR_PIXEL_MODE,
                        CaptureMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION
                    )
                }
            }.build()
        }

        session.captureBurst(
            requests,
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    if (rawResultSlot == null) {
                        rawResultSlot = result
                    }
                }
            },
            cameraHandler
        )

        // Wait for all burst frames to arrive from the ISOCELL GNV sensor
        burstCompleted.await()

        // Save 16-bit DNG if enabled and captured
        var savedDngName: String? = null
        val rImg = rawImageSlot
        val rRes = rawResultSlot
        if (shouldCaptureRaw && rImg != null && rRes != null) {
            try {
                onStepUpdate("Writing 16-bit uncompressed RAW (.DNG)…")
                savedDngName = saveDngToMediaStore(rImg, rRes, "PXL8_RAW_$timestamp.dng")
            } finally {
                rImg.close()
            }
        } else {
            rImg?.close()
        }

        // Reconstruct the 8160 x 6144 (50.1 MP) image from the burst
        val final50MpBitmap = SuperRes50MpProcessor.reconstruct50MpFromBurst(
            frames = capturedBitmaps,
            onProgress = onStepUpdate
        )

        // Free intermediate 12.5MP burst bitmaps immediately
        capturedBitmaps.forEach { if (it !== final50MpBitmap) it.recycle() }
        capturedBitmaps.clear()

        onStepUpdate("Encoding 50.1 MP (8160×6144) JPEG…")
        val jpegName = "PXL8_50MP_$timestamp.jpg"
        saveJpegToMediaStore(final50MpBitmap, jpegName)
        final50MpBitmap.recycle()

        if (savedDngName != null) {
            "Saved $jpegName (50.1 MP) + $savedDngName"
        } else {
            "Saved $jpegName (8160×6144 • 50.1 MP)"
        }
    }

    private fun saveJpegToMediaStore(bitmap: Bitmap, fileName: String) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_DCIM}/Pixel8_50MP"
            )
            put(MediaStore.Images.Media.WIDTH, bitmap.width)
            put(MediaStore.Images.Media.HEIGHT, bitmap.height)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Failed to create MediaStore JPEG entry")

        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 98, out)
        }

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    private fun saveDngToMediaStore(image: Image, result: TotalCaptureResult, fileName: String): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_DCIM}/Pixel8_50MP"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return fileName

        resolver.openOutputStream(uri)?.use { out ->
            DngCreator(characteristics, result).use { dngCreator ->
                dngCreator.writeImage(out, image)
            }
        }

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return fileName
    }

    fun close() {
        captureSession?.close()
        cameraDevice?.close()
        jpegReader?.close()
        rawReader?.close()
        cameraThread.quitSafely()
    }
}
