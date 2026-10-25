# Pixel 8 50MP Photo-Only Camera App (Unrooted)

A custom Android Camera2 application built specifically for the **Google Pixel 8** (`com.pixel8.ultra50mp`) to produce **50.1 MP (`8160 × 6144`)** photos **without root** and with **zero video recording overhead**.

## How It Works on an Unrooted Pixel 8
On stock unrooted Pixel 8 firmware, Google's Camera HAL locks single-frame quad-bayer remosaicing (`SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION`) and exposes a maximum `4080 × 3072` (12.53 MP) sensor readout alongside `16-bit RAW_SENSOR` capture.

To utilize the full 50MP Samsung ISOCELL GNV sensor without rooting:
1. **Locked Multi-Frame Sensor Burst:** When you press the shutter, the app locks exposure (`AE_LOCK`), white balance (`AWB_LOCK`), and Optical Image Stabilization (`LENS_OPTICAL_STABILIZATION_MODE_ON`), capturing a fast 4-frame or 8-frame burst at maximum quality (`JPEG_QUALITY = 100`, `EDGE_MODE_HIGH_QUALITY`) plus an optional **16-bit uncompressed Adobe DNG (`RAW_SENSOR`)**.
2. **Sub-Pixel Hand-Tremor Alignment:** Natural hand micro-tremors shift the image across the physical 50MP photodiode array between burst frames. `SuperRes50MpProcessor` performs block-matching sub-pixel registration (`0.5px` precision on the 12.5MP grid = `1.0px` on the 50MP grid).
3. **2× Super-Resolution Synthesis & Deconvolution:** The aligned burst frames are projected and fused onto an **`8160 × 6144` (50.14 MP)** canvas (`android:largeHeap="true"` enabled) and passed through a multi-threaded Laplacian micro-contrast recovery pass, saving the final 50.1 MP JPEG (and optional 16-bit `.DNG`) into `DCIM/Pixel8_50MP/`.

---

## How to Get the Installable `.APK` File (2 Easy Ways)

### Option A: Build the `.APK` in the Cloud Without Installing Anything (GitHub Actions)
This project includes a pre-configured GitHub Actions workflow (`.github/workflows/build-apk.yml`):
1. Create a new repository on [GitHub](https://github.com/new) and upload the extracted files from this ZIP.
2. Click the **Actions** tab in your GitHub repository -> **Build Pixel 8 50MP Camera APK**.
3. In ~2 minutes, download **`Pixel8-50MP-Camera-APK.zip`** from the **Artifacts** section at the bottom of the workflow run, extract `app-debug.apk`, and install it on your Pixel 8!

### Option B: Build in Android Studio (Local PC/Mac)
1. Extract this ZIP folder and open it in **Android Studio**.
2. Wait for Gradle Sync to finish.
3. Click **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
4. Copy `app/build/outputs/apk/debug/app-debug.apk` to your Pixel 8 and tap to install.
