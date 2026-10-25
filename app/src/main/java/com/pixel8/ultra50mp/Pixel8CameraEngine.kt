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
    private var activeSurface: Surface? = null

    private val cameraThread = HandlerThread("Pixel8CameraThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val processingExecutor = Executors.newSingleThreadExecutor()

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var backCameraId: String = "0"
    private var sensorOrientation: Int = 90

    private val isBusy = AtomicBoolean(false)
    private var isHdrCapture = false
    private val hdrFrames = mutableListOf<SuperRes50MpProcessor.YuvData>()

    @SuppressLint("MissingPermission")
    fun startCamera(surface: Surface) {
        activeSurface = surface
        backCameraId = findBackCameraId()
        val characteristics = cameraManager.getCameraCharacteristics(backCameraId)
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val maxResolution = map?.getOutputSizes(ImageFormat.YUV_420_888)?.maxByOrNull { it.width * it.height }
            ?: Size(4080, 3072)

        imageReader = ImageReader.newInstance(
            maxResolution.width,
            maxResolution.height,
            ImageFormat.YUV_420_888,
            4
        ).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                val yuvBytes = SuperRes50MpProcessor.extractYuvBytes(image)
                val width = image.width
                val height = image.height
                image.close()

                if (isHdrCapture) {
                    synchronized(hdrFrames) {
                        hdrFrames.add(yuvBytes)
                        if (hdrFrames.size == 3) {
                            val capturedList = hdrFrames.toList()
                            hdrFrames.clear()
                            isHdrCapture = false
                            onStatusChanged("Processing 50MP Natural HDR...")
                            processingExecutor.execute {
                                val result = SuperRes50MpProcessor.processYuvHdr50Mp(context, capturedList, width, height)
                                isBusy.set(false)
                                if (result != null) onPhotoReady(result.uri, result.thumbnail)
                                onStatusChanged("50MP (8160x6144) • READY")
                            }
                        }
                    }
                } else {
                    onStatusChanged("Processing 50MP Detail...")
                    processingExecutor.execute {
                        val result = SuperRes50MpProcessor.processYuvSingle50Mp(context, yuvBytes, width, height)
                        isBusy.set(false)
                        if (result != null) onPhotoReady(result.uri, result.thumbnail)
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
            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                cameraDevice = null
            }
            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                cameraDevice = null
            }
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
        try {
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun capturePhoto(enableHdr: Boolean) {
        if (!isBusy.compareAndSet(false, true)) {
            onStatusChanged("Processing previous shot...")
            return
        }

        val device = cameraDevice ?: run { isBusy.set(false); return }
        val session = captureSession ?: run { isBusy.set(false); return }
        val readerSurface = imageReader?.surface ?: run { isBusy.set(false); return }
        val surf = activeSurface ?: run { isBusy.set(false); return }

        isHdrCapture = enableHdr
        onStatusChanged(if (enableHdr) "Capturing 3-Frame HDR..." else "Capturing 50MP Photo...")

        val captureCallback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                startRepeatingPreview()
            }
        }

        if (!isHdrCapture) {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(readerSurface)
                addTarget(surf)
                set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            }.build()
            session.capture(request, captureCallback, cameraHandler)
        } else {
            synchronized(hdrFrames) { hdrFrames.clear() }
            val exposures = listOf(0, -6, 6)
            val burst = exposures.map { ev ->
                device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(readerSurface)
                    addTarget(surf)
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
        try {
            captureSession?.close()
            cameraDevice?.close()
            imageReader?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        captureSession = null
        cameraDevice = null
        imageReader = null
        cameraThread.quitSafely()
        processingExecutor.shutdown()
    }
}
