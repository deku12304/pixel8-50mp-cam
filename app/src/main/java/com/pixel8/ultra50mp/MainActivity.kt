package com.pixel8.ultra50mp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var viewFinder: AutoFitTextureView
    private lateinit var cameraOverlay: CameraOverlayView
    private lateinit var tvStatusBadge: TextView
    private lateinit var btnGridToggle: ToggleButton
    private lateinit var btnHdrToggle: ToggleButton
    private lateinit var btnCapture: ImageButton
    private lateinit var btnPreviewThumbnail: ImageButton

    private lateinit var cameraEngine: Pixel8CameraEngine
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null

    private var lastSavedPhotoUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        cameraOverlay = findViewById(R.id.cameraOverlay)
        tvStatusBadge = findViewById(R.id.tvStatusBadge)
        btnGridToggle = findViewById(R.id.btnGridToggle)
        btnHdrToggle = findViewById(R.id.btnHdrToggle)
        btnCapture = findViewById(R.id.btnCapture)
        btnPreviewThumbnail = findViewById(R.id.btnPreviewThumbnail)

        // Lock viewfinder to 3:4 portrait aspect ratio so preview is never squeezed
        viewFinder.setAspectRatio(3, 4)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        cameraEngine = Pixel8CameraEngine(
            context = this,
            onStatusChanged = { statusText ->
                runOnUiThread { tvStatusBadge.text = statusText }
            },
            onPhotoReady = { savedUri, thumbBitmap ->
                lastSavedPhotoUri = savedUri
                runOnUiThread {
                    // Uses lightweight 200x200 bitmap so UI never freezes
                    btnPreviewThumbnail.setImageBitmap(thumbBitmap)
                    Toast.makeText(this, "50MP Photo Saved!", Toast.LENGTH_SHORT).show()
                }
            }
        )

        btnGridToggle.setOnCheckedChangeListener { _, isChecked ->
            cameraOverlay.showGrid = isChecked
        }

        btnCapture.setOnClickListener {
            cameraEngine.capturePhoto(enableHdr = btnHdrToggle.isChecked)
        }

        btnPreviewThumbnail.setOnClickListener {
            openInGooglePhotos(lastSavedPhotoUri)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            setupViewFinder()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1001)
        }
    }

    private fun setupViewFinder() {
        viewFinder.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                // Set native 4:3 buffer size and lock 3:4 portrait ratio
                viewFinder.setAspectRatio(3, 4)
                texture.setDefaultBufferSize(1440, 1080)
                cameraEngine.startCamera(Surface(texture))
            }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
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
                viewFinder.setAspectRatio(3, 4)
                viewFinder.surfaceTexture?.setDefaultBufferSize(1440, 1080)
                cameraEngine.startCamera(Surface(viewFinder.surfaceTexture))
            } else {
                setupViewFinder()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
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
        cameraEngine.release()
    }
}
