package com.pixel8.ultra50mp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class Pixel8CameraEngine(
    private val context: Context,
    private val onStatusChanged: (String) -> Unit,
    private val onAfStateChanged: (Int) -> Unit,
    private val onPhotoReady: (Uri, Bitmap) -> Unit
) {
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private val processingExecutor = Executors.newSingleThreadExecutor()
    private val cameraOpenCloseLock = Semaphore(1)

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var backCameraId: String = "0"
    private var sensorArraySize: Rect = Rect(0, 0, 4080, 3072)
    private var sensorOrientation: Int = 90

    var currentZoomRatio: Float = 1.0f
        private set
    var maxZoomRatio: Float = 8.0f
        private set

    private val isBusy = AtomicBoolean(false)
    private var pendingHdrMode = true
    private var lastReportedAfState = -1

    private fun startBackgroundThread() {
        if (cameraThread == null || cameraThread?.isAlive == false) {
            cameraThread = HandlerThread("Pixel8ProCameraThread").apply { start() }
            cameraHandler = Handler(cameraThread!!.looper)
        }
    }

    private fun stopBackgroundThread() {
        cameraThread?.quitSafely()
        try {
            cameraThread?.join(500)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        cameraThread = null
        cameraHandler = null
    }

    private val previewCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val afState = result.get(CaptureResult.CONTROL_AF_STATE) ?: return
            val mappedState = when (afState) {
                CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN,
                CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN -> CameraOverlayView.AF_SCANNING
                CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
                CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> CameraOverlayView.AF_LOCKED
                else -> CameraOverlayView.AF_PASSIVE
            }
            if (mappedState != lastReportedAfState) {
                lastReportedAfState = mappedState
                onAfStateChanged(mappedState)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startCamera(surface: Surface) {
        startBackgroundThread()
        val handler = cameraHandler ?: return

        handler.post {
            try {
                if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                    return@post
                }
                // Close any stale session cleanly before opening
                captureSession?.close()
                captureSession = null
                cameraDevice?.close()
                cameraDevice = null
                imageReader?.close()

                previewSurface = surface
                backCameraId = findBackCameraId()

                val characteristics = cameraManager.getCameraCharacteristics(backCameraId)
                sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                sensorArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    ?: Rect(0, 0, 4080, 3072)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val zoomRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                    maxZoomRatio = (zoomRange?.upper ?: 8.0f).coerceAtMost(8.0f)
                }

                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val maxJpegSize = map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height }
                    ?: Size(4080, 3072)

                imageReader = ImageReader.newInstance(
                    maxJpegSize.width,
                    maxJpegSize.height,
                    ImageFormat.JPEG,
                    3
                ).apply {
                    setOnImageAvailableListener({ reader ->
                        val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                        val bytes = SuperRes50MpProcessor.extractBytes(image)
                        image.close()

                        val hdrActive = pendingHdrMode
                        val zoomActive = currentZoomRatio
                        onStatusChanged("AI Upscaling to 50MP (${String.format("%.1fx", zoomActive)})...")

                        processingExecutor.execute {
                            try {
                                val result = SuperRes50MpProcessor.process50MpPhoto(
                                    context = context,
                                    jpegBytes = bytes,
                                    enableHdr = hdrActive,
                                    zoomRatio = zoomActive
                                )
                                if (result != null) {
                                    onPhotoReady(result.uri, result.thumbnail)
                                }
                            } finally {
                                isBusy.set(false)
                                onStatusChanged("50MP PRO • ${String.format("%.1fx", currentZoomRatio)} READY")
                            }
                        }
                    }, handler)
                }

                cameraManager.openCamera(backCameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraOpenCloseLock.release()
                        cameraDevice = camera
                        createPreviewSession(surface)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        cameraOpenCloseLock.release()
                        camera.close()
                        cameraDevice = null
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        cameraOpenCloseLock.release()
                        camera.close()
                        cameraDevice = null
                        onStatusChanged("Camera Error ($error) - Retrying...")
                    }
                }, handler)
            } catch (e: Exception) {
                cameraOpenCloseLock.release()
                e.printStackTrace()
            }
        }
    }

    private fun createPreviewSession(surface: Surface) {
        val device = cameraDevice ?: return
        val readerSurface = imageReader?.surface ?: return
        val handler = cameraHandler ?: return

        try {
            previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                applyZoomToBuilder(this, currentZoomRatio)
            }

            device.createCaptureSession(
                listOf(surface, readerSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        updateRepeatingPreview()
                        onStatusChanged("50MP PRO • ${String.format("%.1fx", currentZoomRatio)} READY")
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        onStatusChanged("Preview Session Failed")
                    }
                },
                handler
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateRepeatingPreview() {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        val handler = cameraHandler ?: return
        try {
            session.setRepeatingRequest(builder.build(), previewCaptureCallback, handler)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * AI-Enhanced Zoom: Applies hardware sensor crop / zoom ratio smoothly in real time.
     */
    fun setZoom(ratio: Float) {
        currentZoomRatio = ratio.coerceIn(1.0f, maxZoomRatio)
        val builder = previewRequestBuilder ?: return
        applyZoomToBuilder(builder, currentZoomRatio)
        updateRepeatingPreview()
        onStatusChanged("50MP PRO • ${String.format("%.1fx", currentZoomRatio)} AI ZOOM")
    }

    private fun applyZoomToBuilder(builder: CaptureRequest.Builder, zoom: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        } else {
            val cropW = (sensorArraySize.width() / zoom).toInt()
            val cropH = (sensorArraySize.height() / zoom).toInt()
            val cropX = (sensorArraySize.width() - cropW) / 2
            val cropY = (sensorArraySize.height() - cropH) / 2
            builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(cropX, cropY, cropX + cropW, cropY + cropH))
        }
    }

    /**
     * Dedicated Crosshair Tap-to-Focus: Locks hardware AF & AE onto the exact tapped coordinates.
     */
    fun focusAtPoint(normX: Float, normY: Float) {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        val handler = cameraHandler ?: return

        // Map portrait screen normalized (0..1) coordinates to sensor coordinates (rotated 90 deg)
        val sensorX = (normY * sensorArraySize.width()).toInt().coerceIn(0, sensorArraySize.width() - 1)
        val sensorY = ((1f - normX) * sensorArraySize.height()).toInt().coerceIn(0, sensorArraySize.height() - 1)

        val halfTouchSize = 180
        val left = (sensorX - halfTouchSize).coerceAtLeast(0)
        val top = (sensorY - halfTouchSize).coerceAtLeast(0)
        val right = (sensorX + halfTouchSize).coerceAtMost(sensorArraySize.width())
        val bottom = (sensorY + halfTouchSize).coerceAtMost(sensorArraySize.height())

        val focusRect = MeteringRectangle(Rect(left, top, right, bottom), MeteringRectangle.METERING_WEIGHT_MAX - 1)

        try {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focusRect))
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(focusRect))
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            session.setRepeatingRequest(builder.build(), previewCaptureCallback, handler)

            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            session.capture(builder.build(), previewCaptureCallback, handler)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            onStatusChanged("AF TARGET LOCKED • ${String.format("%.1fx", currentZoomRatio)}")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Resets focus back to center Continuous Autofocus (AF-C).
     */
    fun resetContinuousFocus() {
        val builder = previewRequestBuilder ?: return
        try {
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, null)
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, null)
            updateRepeatingPreview()
            onStatusChanged("50MP PRO • AF-C TRACKING")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun capturePhoto(enableHdr: Boolean) {
        if (!isBusy.compareAndSet(false, true)) {
            onStatusChanged("Saving previous 50MP shot...")
            return
        }

        val device = cameraDevice ?: run { isBusy.set(false); return }
        val session = captureSession ?: run { isBusy.set(false); return }
        val readerSurface = imageReader?.surface ?: run { isBusy.set(false); return }
        val surf = previewSurface ?: run { isBusy.set(false); return }
        val handler = cameraHandler ?: run { isBusy.set(false); return }

        pendingHdrMode = enableHdr
        onStatusChanged(if (enableHdr) "Capturing 50MP Natural HDR..." else "Capturing 50MP Pro Shot...")

        try {
            val stillBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                // Attach BOTH surfaces so the viewfinder never goes black or freezes during capture!
                addTarget(readerSurface)
                addTarget(surf)

                set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
                set(CaptureRequest.CONTROL_AF_MODE, previewRequestBuilder?.get(CaptureRequest.CONTROL_AF_MODE)
                    ?: CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)

                // Carry over active focus metering regions & AI zoom ratio
                previewRequestBuilder?.get(CaptureRequest.CONTROL_AF_REGIONS)?.let {
                    set(CaptureRequest.CONTROL_AF_REGIONS, it)
                }
                previewRequestBuilder?.get(CaptureRequest.CONTROL_AE_REGIONS)?.let {
                    set(CaptureRequest.CONTROL_AE_REGIONS, it)
                }
                applyZoomToBuilder(this, currentZoomRatio)
            }

            session.capture(
                stillBuilder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        updateRepeatingPreview()
                    }
                },
                handler
            )
        } catch (e: Exception) {
            e.printStackTrace()
            isBusy.set(false)
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

    /**
     * Called on onPause(): Cleanly closes camera hardware without killing the app's executor.
     */
    fun stopCamera() {
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cameraOpenCloseLock.release()
            stopBackgroundThread()
        }
    }

    fun destroy() {
        stopCamera()
        processingExecutor.shutdown()
    }
}
