package com.pixel8.ultra50mp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executors

class Pixel8CameraEngine(
    private val context: Context,
    private val onStatusChanged: (String) -> Unit,
    private val onPhotoSaved: (Uri) -> Unit
) {
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    private val cameraThread = HandlerThread("Pixel8CameraThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val ioExecutor = Executors.newFixedThreadPool(2)

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var backCameraId: String = "0"
    var maxSensorResolution: Size = Size(8160, 6144)
        private set

    private var isCapturingHdr = false
    private val hdrFrameBuffer = mutableListOf<ByteArray>()

    @SuppressLint("MissingPermission")
    fun startCamera(previewSurface: Surface) {
        backCameraId = findBack50MpCameraId()
        val characteristics = cameraManager.getCameraCharacteristics(backCameraId)

        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        maxSensorResolution = map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height }
            ?: Size(4080, 3072)

        imageReader = ImageReader.newInstance(
            maxSensorResolution.width,
            maxSensorResolution.height,
            ImageFormat.JPEG,
            4
        ).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                val bytes = SuperRes50MpProcessor.extractBytes(image)
                image.close()

                if (isCapturingHdr) {
                    synchronized(hdrFrameBuffer) {
                        hdrFrameBuffer.add(bytes)
                        if (hdrFrameBuffer.size == 3) {
                            val framesToMerge = hdrFrameBuffer.toList()
                            hdrFrameBuffer.clear()
                            isCapturingHdr = false
                            onStatusChanged("Merging 50MP Natural HDR...")
                            ioExecutor.execute {
                                val uri = SuperRes50MpProcessor.processAndSaveNaturalHdr(context, framesToMerge)
                                if (uri != null) onPhotoSaved(uri)
                                onStatusChanged("50MP • RAW NATURAL READY")
                            }
                        }
                    }
                } else {
                    onStatusChanged("Saving 50MP Photo...")
                    ioExecutor.execute {
                        val uri = SuperRes50MpProcessor.saveDirect50MpJpeg(context, bytes)
                        if (uri != null) onPhotoSaved(uri)
                        onStatusChanged("50MP • RAW NATURAL READY")
                    }
                }
            }, cameraHandler)
        }

        cameraManager.openCamera(backCameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createPreviewSession(previewSurface)
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close() }
            override fun onError(camera: CameraDevice, error: Int) { camera.close() }
        }, cameraHandler)
    }

    private fun createPreviewSession(previewSurface: Surface) {
        val device = cameraDevice ?: return
        val readerSurface = imageReader?.surface ?: return

        val previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }

        device.createCaptureSession(
            listOf(previewSurface, readerSurface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    session.setRepeatingRequest(previewRequestBuilder.build(), null, cameraHandler)
                    onStatusChanged("50MP (${maxSensorResolution.width}x${maxSensorResolution.height}) READY")
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    onStatusChanged("Camera Session Failed")
                }
            },
            cameraHandler
        )
    }

    fun capturePhoto(enableHdr: Boolean) {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val readerSurface = imageReader?.surface ?: return

        if (!enableHdr) {
            isCapturingHdr = false
            val singleRequest = buildNatural50MpRequest(device, readerSurface, 0)
            session.capture(singleRequest, null, cameraHandler)
        } else {
            synchronized(hdrFrameBuffer) { hdrFrameBuffer.clear() }
            isCapturingHdr = true
            onStatusChanged("Capturing 3-Frame HDR Bracket...")
            // 0 EV (Normal), -6 steps (~ -1 EV for highlights), +6 steps (~ +1 EV for shadows)
            val exposures = listOf(0, -6, 6)
            val burstRequests = exposures.map { ev ->
                buildNatural50MpRequest(device, readerSurface, ev)
            }
            session.captureBurst(burstRequests, null, cameraHandler)
        }
    }

    private fun buildNatural50MpRequest(
        device: CameraDevice,
        targetSurface: Surface,
        evCompensation: Int
    ): CaptureRequest {
        return device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(targetSurface)
            set(CaptureRequest.JPEG_QUALITY, 100.toByte())
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evCompensation)

            // Natural optical look: high quality sensor denoising without harsh artificial oversharpening
            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
        }.build()
    }

    private fun findBack50MpCameraId(): String {
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
        ioExecutor.shutdown()
    }
}
