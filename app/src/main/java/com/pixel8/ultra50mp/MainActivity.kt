package com.pixel8.ultra50mp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var viewFinder: AutoFitTextureView
    private lateinit var cameraOverlay: CameraOverlayView
    private lateinit var tvStatusBadge: TextView
    private lateinit var tvTelemetry: TextView
    private lateinit var layoutProcessingBar: LinearLayout
    private lateinit var tvProcessingStage: TextView
    private lateinit var tvProcessingPercent: TextView
    private lateinit var progressBar50Mp: ProgressBar

    private lateinit var btnGridToggle: ToggleButton
    private lateinit var btnHdrToggle: ToggleButton
    private lateinit var tvEvReset: TextView
    private lateinit var evSeekBar: SeekBar
    private lateinit var btnZoom1x: Button
    private lateinit var btnZoom2x: Button
    private lateinit var btnZoom5x: Button
    private lateinit var btnZoom8x: Button
    private lateinit var zoomSeekBar: SeekBar
    private lateinit var btnAfReset: Button
    private lateinit var btnCapture: ImageButton
    private lateinit var btnPreviewThumbnail: ImageButton

    private lateinit var cameraEngine: Pixel8CameraEngine
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null

    private var lastSavedPhotoUri: Uri? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        cameraOverlay = findViewById(R.id.cameraOverlay)
        tvStatusBadge = findViewById(R.id.tvStatusBadge)
        tvTelemetry = findViewById(R.id.tvTelemetry)
        layoutProcessingBar = findViewById(R.id.layoutProcessingBar)
        tvProcessingStage = findViewById(R.id.tvProcessingStage)
        tvProcessingPercent = findViewById(R.id.tvProcessingPercent)
        progressBar50Mp = findViewById(R.id.progressBar50Mp)

        btnGridToggle = findViewById(R.id.btnGridToggle)
        btnHdrToggle = findViewById(R.id.btnHdrToggle)
        tvEvReset = findViewById(R.id.tvEvReset)
        evSeekBar = findViewById(R.id.evSeekBar)
        btnZoom1x = findViewById(R.id.btnZoom1x)
        btnZoom2x = findViewById(R.id.btnZoom2x)
        btnZoom5x = findViewById(R.id.btnZoom5x)
        btnZoom8x = findViewById(R.id.btnZoom8x)
        zoomSeekBar = findViewById(R.id.zoomSeekBar)
        btnAfReset = findViewById(R.id.btnAfReset)
        btnCapture = findViewById(R.id.btnCapture)
        btnPreviewThumbnail = findViewById(R.id.btnPreviewThumbnail)

        viewFinder.setAspectRatio(3, 4)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        cameraEngine = Pixel8CameraEngine(
            context = this,
            onStatusChanged = { statusText ->
                runOnUiThread { tvStatusBadge.text = statusText }
            },
            onAfStateChanged = { afState ->
                runOnUiThread { cameraOverlay.afState = afState }
            },
            onProcessingProgress = { isVisible, percent, stageText ->
                runOnUiThread {
                    if (isVisible) {
                        layoutProcessingBar.visibility = View.VISIBLE
                        progressBar50Mp.progress = percent
                        tvProcessingStage.text = stageText
                        tvProcessingPercent.text = "$percent%"
                    } else {
                        layoutProcessingBar.visibility = View.GONE
                    }
                }
            },
            onTelemetryUpdated = { telemetryText ->
                runOnUiThread { tvTelemetry.text = telemetryText }
            },
            onPhotoReady = { savedUri, thumbBitmap ->
                lastSavedPhotoUri = savedUri
                runOnUiThread {
                    btnPreviewThumbnail.setImageBitmap(thumbBitmap)
                    Toast.makeText(this, "50MP Pro Photo Saved!", Toast.LENGTH_SHORT).show()
                }
            }
        )

        // Tap anywhere on viewfinder to move the AF Crosshair and lock focus
        viewFinder.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                cameraOverlay.setFocusPoint(event.x, event.y)
                val normX = (event.x / v.width.toFloat()).coerceIn(0f, 1f)
                val normY = (event.y / v.height.toFloat()).coerceIn(0f, 1f)
                cameraEngine.focusAtPoint(normX, normY)
                btnAfReset.setTextColor(Color.parseColor("#FFD600"))
                btnAfReset.text = "LOCK"
            }
            true
        }

        // AF-C Button resets crosshair to center Continuous Autofocus
        btnAfReset.setOnClickListener {
            cameraOverlay.resetFocusToCenter()
            cameraEngine.resetContinuousFocus()
            btnAfReset.setTextColor(Color.WHITE)
            btnAfReset.text = "AF-C"
        }

        btnGridToggle.setOnCheckedChangeListener { _, isChecked ->
            cameraOverlay.showGrid = isChecked
        }

        // Exposure Compensation (EV) Slider (-2.0 EV to +2.0 EV)
        evSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val ev = (progress - 20) / 10.0f
                    cameraEngine.setExposureEv(ev)
                    val sign = if (ev >= 0f) "+" else ""
                    tvEvReset.text = "EV $sign${String.format("%.1f", ev)}"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Tap "EV" badge to reset exposure back to 0.0 EV
        tvEvReset.setOnClickListener {
            evSeekBar.progress = 20
            cameraEngine.setExposureEv(0.0f)
            tvEvReset.text = "EV 0.0"
        }

        // AI Zoom Preset Buttons
        btnZoom1x.setOnClickListener { applyZoomLevel(1.0f) }
        btnZoom2x.setOnClickListener { applyZoomLevel(2.0f) }
        btnZoom5x.setOnClickListener { applyZoomLevel(5.0f) }
        btnZoom8x.setOnClickListener { applyZoomLevel(8.0f) }

        // Smooth AI Zoom Slider (1.0x to 8.0x in 0.1x steps)
        zoomSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val ratio = 1.0f + (progress / 10.0f)
                    cameraEngine.setZoom(ratio)
                    highlightActiveZoomButton(ratio)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnCapture.setOnClickListener {
            cameraEngine.capturePhoto(enableHdr = btnHdrToggle.isChecked)
        }

        btnPreviewThumbnail.setOnClickListener {
            openInGooglePhotos(lastSavedPhotoUri)
        }

        viewFinder.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                startCameraIfPermitted(texture)
            }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1001)
        }
    }

    private fun applyZoomLevel(targetZoom: Float) {
        cameraEngine.setZoom(targetZoom)
        zoomSeekBar.progress = ((targetZoom - 1.0f) * 10).toInt().coerceIn(0, 70)
        highlightActiveZoomButton(targetZoom)
    }

    private fun highlightActiveZoomButton(zoom: Float) {
        val activeColor = Color.parseColor("#00E676")
        btnZoom1x.setTextColor(if (zoom < 1.5f) activeColor else Color.WHITE)
        btnZoom2x.setTextColor(if (zoom in 1.5f..3.5f) activeColor else Color.WHITE)
        btnZoom5x.setTextColor(if (zoom in 3.51f..6.5f) activeColor else Color.WHITE)
        btnZoom8x.setTextColor(if (zoom > 6.5f) activeColor else Color.WHITE)
    }

    private fun startCameraIfPermitted(texture: SurfaceTexture) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            viewFinder.setAspectRatio(3, 4)
            texture.setDefaultBufferSize(1440, 1080)
            cameraEngine.startCamera(Surface(texture))
        }
    }

    private fun openInGooglePhotos(photoUri: Uri?) {
        if (photoUri == null) {
            Toast.makeText(this, "Capture a 50MP photo first!", Toast.LENGTH_SHORT).show()
            return
        }

        val photosIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(photoUri, "image/jpeg")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage("com.google.android.apps.photos")
        }

        try {
            startActivity(photosIntent)
        } catch (e: Exception) {
            val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(photoUri, "image/jpeg")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(fallbackIntent)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (viewFinder.isAvailable) {
                viewFinder.surfaceTexture?.let { startCameraIfPermitted(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        if (viewFinder.isAvailable) {
            viewFinder.surfaceTexture?.let { startCameraIfPermitted(it) }
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        cameraEngine.stopCamera()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val orientationAngles = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)

            val rollDegrees = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()
            cameraOverlay.rollAngle = rollDegrees
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        cameraEngine.destroy()
    }
}
