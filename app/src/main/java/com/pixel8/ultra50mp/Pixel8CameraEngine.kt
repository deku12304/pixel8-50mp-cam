package com.pixel8.ultra50mp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class Pixel8CameraEngine(
    private val context: Context,
    private val onStatusChanged: (String) -> Unit,
    private val onPhotoReady: (Uri, Bitmap) -> Unit
) {
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null

    private val cameraThread = HandlerThread("Pixel8CameraThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val processingExecutor = Executors.newSingleThreadExecutor()

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var backCameraId: String = "0"
    private var sensorOrientation: Int = 90

    var previewSize: Size = Size(1920, 1440)
        private set

    private val isBusy = AtomicBoolean(false)
    private var isHdrCapture = false
    private var focusTriggered = false
    private val hdrFrames = mutableListOf<ByteArray>()

    @SuppressLint("MissingPermission")
    fun startCamera(surface: Surface) {
        previewSurface = surface
        backCameraId = findBackCameraId()
        val characteristics = cameraManager.getCameraCharacteristics(backCameraId)
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val fullSensorJpegSize = map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height }
            ?: Size(4080, 3072)

        // Pick a true 4:3 preview size so the viewfinder matches the sensor and never looks squeezed
        previewSize = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
            ?.filter { it.width * 3 == it.height * 4 && it.width <= 1920 }
            ?.maxByOrNull { it.width * it.height }
            ?: Size(1440, 1080)

        imageReader = ImageReader.newInstance(
            fullSensorJpegSize.width,
            fullSensorJpegSize.height,
            ImageFormat.JPEG,
            4
        ).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                val bytes = SuperRes50MpProcessor.extractBytes(image)
                image.close()

                if (isHdrCapture) {
                    synchronized(hdrFrames) {
                        hdrFrames.add(bytes)
                        if (hdrFrames.size == 3) {
                            val capturedList = hdrFrames.toList()
                            hdrFrames.clear()
                            onStatusChanged("Upscaling 50MP Natural HDR...")
                            processingExecutor.execute {
                                val result = SuperRes50MpProcessor.processNaturalHdr50Mp(context, capturedList)
                                isBusy.set(false)
                                if (result != null) {
                                    onPhotoReady(result.uri, result.thumbnail)
                                }
                                onStatusChanged("50MP (8160x6144) • READY")
                            }
                        }
                    }
                } else {
                    onStatusChanged("Upscaling to 50MP (8160x6144)...")
                    processingExecutor.execute {
                        val result = SuperRes50MpProcessor.processSingle50Mp(context, bytes)
                        isBusy.set(false)
                        if (result != null) {
                            onPhotoReady(result.uri, result.thumbnail)
                        }
                        onStatusChanged("50MP (8160x6144) • READY")
                    }
                }
            }, cameraHandler)
        }

        cameraManager.openCamera(backCameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createSession(surface)
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close() }
            override fun onError(camera: CameraDevice, error: Int) { camera.close() }
        }, cameraHandler)
    }

    private fun createSession(surface: Surface) {
        val device = cameraDevice ?: return
        val readerSurface = imageReader?.surface ?: return

        previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        }

        device.createCaptureSession(
            listOf(surface, readerSurface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    startRepeatingPreview()
                    onStatusChanged("50MP (8160x6144) • READY")
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    onStatusChanged("Session Configuration Failed")
                }
            },
            cameraHandler
        )
    }

    private fun startRepeatingPreview() {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
        session.setRepeatingRequest(builder.build(), null, cameraHandler)
    }

    /**
     * Step 1 of Capture: Locks Autofocus & Auto-Exposure first so photos are tack-sharp.
     */
    fun capturePhoto(enableHdr: Boolean) {
        if (!isBusy.compareAndSet(false, true)) {
            onStatusChanged("Processing previous 50MP shot...")
            return
        }

        val session = captureSession ?: run { isBusy.set(false); return }
        val builder = previewRequestBuilder ?: run { isBusy.set(false); return }

        isHdrCapture = enableHdr
        focusTriggered = true
        onStatusChanged("Locking Focus...")

        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
        session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                if (!focusTriggered) return
                val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                val isFocused = afState == null ||
                        afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                        afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED ||
                        afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED

                if (isFocused) {
                    focusTriggered = false
                    executeStillCapture()
                }
            }
        }, cameraHandler)

        // Safety timeout: if low light delays AF lock beyond 900ms, fire capture anyway
        cameraHandler.postDelayed({
            if (focusTriggered) {
                focusTriggered = false
                executeStillCapture()
            }
        }, 900)
    }

    private fun executeStillCapture() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val readerSurface = imageReader?.surface ?: return

        val unlockCallback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                unlockFocusAndResumePreview()
            }
        }

        if (!isHdrCapture) {
            onStatusChanged("Capturing 50MP Frame...")
            val request = buildStillRequest(device, readerSurface, 0)
            session.capture(request, unlockCallback, cameraHandler)
        } else {
            synchronized(hdrFrames) { hdrFrames.clear() }
            onStatusChanged("Capturing 3-Frame HDR...")
            // 0 EV (Normal), -6 (~ -1 EV highlights), +6 (~ +1 EV shadows)
            val exposures = listOf(0, -6, 6)
            val burst = exposures.map { ev -> buildStillRequest(device, readerSurface, ev) }
            session.captureBurst(burst, unlockCallback, cameraHandler)
        }
    }

    private fun buildStillRequest(
        device: CameraDevice,
        readerSurface: Surface,
        evCompensation: Int
    ): CaptureRequest {
        return device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(readerSurface)
            // Keep preview surface attached so the viewfinder NEVER freezes during capture
            previewSurface?.let { addTarget(it) }

            set(CaptureRequest.JPEG_QUALITY, 100.toByte())
            set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evCompensation)

            // High-quality optical sharpness & denoising
            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
        }.build()
    }

    private fun unlockFocusAndResumePreview() {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
        session.capture(builder.build(), null, cameraHandler)
        startRepeatingPreview()
    }

    private fun findBackCameraId(): String {
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                return id
            }
        }
        return cameraManager.cameraIdList.first()
    }

    fun release() {
        captureSession?.close()
        cameraDevice?.close()
        imageReader?.close()
        cameraThread.quitSafely()
        processingExecutor.shutdown()
    }
}
