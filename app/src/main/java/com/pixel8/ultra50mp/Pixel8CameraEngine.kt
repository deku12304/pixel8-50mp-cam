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
                    onStatusChanged("Upscaling to 50MP...")
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
            // Enforce continuous phase-detection autofocus for sharp, instant tracking
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
        try {
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Instantly triggers capture with continuous autofocus locked, ensuring zero preview freezes.
     */
    fun capturePhoto(enableHdr: Boolean) {
        if (!isBusy.compareAndSet(false, true)) {
            onStatusChanged("Processing previous 50MP shot...")
            return
        }

        val device = cameraDevice ?: run { isBusy.set(false); return }
        val session = captureSession ?: run { isBusy.set(false); return }
        val readerSurface = imageReader?.surface ?: run { isBusy.set(false); return }
        val surf = previewSurface ?: run { isBusy.set(false); return }

        isHdrCapture = enableHdr
        onStatusChanged(if (enableHdr) "Capturing 3-Frame HDR..." else "Capturing 50MP Photo...")

        val captureCallback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                // Keep preview repeating seamlessly so the app never freezes
                startRepeatingPreview()
            }
        }

        if (!isHdrCapture) {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(readerSurface)
                addTarget(surf) // Keeps viewfinder streaming uninterrupted
                set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            }.build()

            session.capture(request, captureCallback, cameraHandler)
        } else {
            synchronized(hdrFrames) { hdrFrames.clear() }
            // Natural exposure steps: Base (0 EV), Underexposed (-6), Overexposed (+6)
            val exposures = listOf(0, -6, 6)
            val burst = exposures.map { ev ->
                device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(readerSurface)
                    addTarget(surf)
                    set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                    set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev)
                    set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                    set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                }.build()
            }
            session.captureBurst(burst, captureCallback, cameraHandler)
        }
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
