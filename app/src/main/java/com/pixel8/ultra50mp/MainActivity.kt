package com.pixel8.ultra50mp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.pixel8.ultra50mp.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraEngine: Pixel8CameraEngine

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (binding.viewfinder.isAvailable) {
                startCamera(binding.viewfinder.surfaceTexture!!)
            }
        } else {
            Toast.makeText(this, "Camera permission is required for 50MP capture", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraEngine = Pixel8CameraEngine(this)

        // Set 4:3 aspect ratio matching the Pixel 8 50MP sensor (8160 x 6144)
        binding.viewfinder.setAspectRatio(3, 4)

        binding.viewfinder.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                checkPermissionAndStart(texture)
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
        }

        binding.btnShutter.setOnClickListener {
            trigger50MpCapture()
        }
    }

    private fun checkPermissionAndStart(texture: SurfaceTexture) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera(texture)
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera(texture: SurfaceTexture) {
        // Configure preview buffer at 4:3 (1920x1440)
        texture.setDefaultBufferSize(1920, 1440)
        val previewSurface = Surface(texture)

        cameraEngine.openCamera(
            previewSurface = previewSurface,
            onReady = { info ->
                runOnUiThread {
                    val modeLabel = if (info.native50MpUnlocked) {
                        "ISOCELL GNV • Native 50.1 MP HAL Mode"
                    } else {
                        "ISOCELL GNV • Unrooted 50.1 MP Super-Res Mode"
                    }
                    binding.tvSensorStatus.text = modeLabel
                    binding.tvResolutionSub.text =
                        "Sensor Readout: ${info.sensorCaptureSize.width}×${info.sensorCaptureSize.height} → Output: 8160×6144 (50.1 MP)"
                }
            },
            onError = { err ->
                runOnUiThread {
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun trigger50MpCapture() {
        binding.btnShutter.isEnabled = false
        binding.processingOverlay.visibility = View.VISIBLE

        val burstCount = if (binding.switchEightFrame.isChecked) 8 else 4
        val saveDng = binding.switchSaveDng.isChecked

        lifecycleScope.launch {
            try {
                val summary = cameraEngine.capture50MpPhoto(
                    burstCount = burstCount,
                    saveRawDng = saveDng,
                    onStepUpdate = { stepText ->
                        runOnUiThread {
                            binding.tvProcessingStep.text = stepText
                        }
                    }
                )
                binding.tvLastSaved.text = summary
                Toast.makeText(this@MainActivity, summary, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                binding.tvLastSaved.text = "Error: ${e.localizedMessage}"
                Toast.makeText(this@MainActivity, "Capture failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.processingOverlay.visibility = View.GONE
                binding.btnShutter.isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraEngine.close()
    }
}
