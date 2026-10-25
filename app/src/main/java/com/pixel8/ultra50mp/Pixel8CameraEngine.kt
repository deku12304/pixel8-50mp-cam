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
    private var imageReader: ImageReader? = null
    private var activeSurface: Surface? = null

    private val cameraThread = HandlerThread("Pixel8CameraThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val processingExecutor = Executors.newSingleThreadExecutor()

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var backCameraId: String = "0"
    
    private val isBusy = AtomicBoolean(false)
    private var isHdrCapture = false
    private val hdrFrames = mutableListOf<ByteArray>()

    @SuppressLint("MissingPermission")
    fun startCamera(surface: Surface) {
        activeSurface = surface
        backCameraId = findBackCameraId()

        val characteristics = cameraManager.getCameraCharacteristics(backCameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val maxResolution = map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height }
            ?: Size(4080, 3072)

        imageReader = ImageReader.newInstance(
            maxResolution.width,
            maxResolution.height,
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
                            isHdrCapture = false
                            onStatusChanged("Processing 50MP Natural HDR...")
                            processingExecutor.execute {
                                val result = SuperRes50MpProcessor.processNaturalHdr50Mp(context, capturedList)
                                isBusy.set(false)
                                if (result != null) onPhotoReady(result.uri, result.thumbnail)
                                onStatusChanged("50MP (8160x6144) • READY")
                                // Restart preview session safely after save
                                restartPreviewSession()
                            }
                        }
                    }
                } else {
                    onStatusChanged("Processing 50MP Detail...")
                    processingExecutor.execute {
                        val result = SuperRes50MpProcessor.processSingle50Mp(context, bytes)
                        isBusy.set(false)
                        if (result != null) onPhotoReady(result.uri, result.thumbnail)
                        onStatusChanged("50MP (8160x6144) • READY")
                        // Restart preview session safely after save
                        restartPreviewSession()
                    }
                }
            }, cameraHandler)
        }

        openCameraDevice(surface)
    }

    @SuppressLint("MissingPermission")
    private fun openCameraDevice(surface: Surface) {
        try {
            cameraManager.openCamera(backCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createPreviewSession(camera, surface)
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createPreviewSession(device: CameraDevice, surface: Surface) {
        val readerSurface = imageReader?.surface ?: return

        try {
            val previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
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
                        try {
                            session.setRepeatingRequest(previewRequestBuilder.build(), null, cameraHandler)
                            onStatusChanged("50MP (8160x6144) • READY")
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        onStatusChanged("Session Config Failed")
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun restartPreviewSession() {
        val surface = activeSurface ?: return
        val device = cameraDevice ?: return
        try {
            captureSession?.close()
            captureSession = null
            createPreviewSession(device, surface)
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

        isHdrCapture = enableHdr
        onStatusChanged(if (enableHdr) "Capturing 3-Frame HDR..." else "Capturing 50MP Photo...")

        if (!isHdrCapture) {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(readerSurface)
                set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                // Fixed portrait orientation (90 degrees)
                set(CaptureRequest.JPEG_ORIENTATION, 90)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            }.build()
            session.capture(request, null, cameraHandler)
        } else {
            synchronized(hdrFrames) { hdrFrames.clear() }
            val exposures = listOf(0, -6, 6)
            val burst = exposures.map { ev ->
                device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(readerSurface)
                    set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                    set(CaptureRequest.JPEG_ORIENTATION, 90)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev)
                    set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                    set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                }.build()
            }
            session.captureBurst(burst, null, cameraHandler)
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
        try {
            cameraThread.quitSafely()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        processingExecutor.shutdown()
    }
}
