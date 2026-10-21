package com.jarvis.assistant.ui.home

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.FaceDetector
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.background.JarvisConversationService
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/** Camera2 live view. Frames are sent only while the user keeps this screen open. */
class CameraVisionActivity : AppCompatActivity() {
    companion object { private const val TAG = "JarvisCameraVision" }

    private lateinit var preview: TextureView
    private lateinit var overlay: CameraParticleOverlayView
    private lateinit var status: TextView
    private lateinit var switchButton: Button
    private lateinit var cameraManager: CameraManager

    private var cameraId: String? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSize = Size(1280, 720)
    private var usingFrontCamera = false
    private var captureRequestBuilder: CaptureRequest.Builder? = null
    private var lastFrameSentAt = 0L
    private val captureBusy = AtomicBoolean(false)
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var sessionConfigured = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCameraWhenReady() else {
            Toast.makeText(this, "Canlı görüşme için kamera izni gerekli.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_vision)
        preview = findViewById(R.id.cameraPreview)
        overlay = findViewById(R.id.cameraParticleOverlay)
        status = findViewById(R.id.tvCameraStatus)
        switchButton = findViewById(R.id.btnSwitchCamera)
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        switchButton.setOnClickListener {
            usingFrontCamera = !usingFrontCamera
            closeCamera()
            updateCameraButton()
            startCameraWhenReady()
        }
        updateCameraButton()

        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = startCameraWhenReady()
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = configureTransform(width, height)
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }

    override fun onResume() {
        super.onResume()
        startCameraThread()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraWhenReady()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onPause() {
        closeCamera()
        stopCameraThread()
        super.onPause()
    }

    private fun startCameraThread() {
        if (cameraThread != null) return
        cameraThread = HandlerThread("JarvisCameraFrames").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
    }

    private fun stopCameraThread() {
        cameraThread?.quitSafely()
        try { cameraThread?.join(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        cameraThread = null
        cameraHandler = null
    }

    private fun updateCameraButton() {
        switchButton.text = if (usingFrontCamera) "ARKA KAMERA" else "ÖN KAMERA"
    }

    @SuppressLint("MissingPermission")
    private fun startCameraWhenReady() {
        if (isFinishing || isDestroyed || !::preview.isInitialized || !preview.isAvailable || cameraDevice != null) return
        if (cameraHandler == null) startCameraThread()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (!JarvisConversationService.isSessionOn.value || !JarvisConversationService.connectionStatus.value.equals("LIVE", true)) {
            status.text = "Jarvis canlı bağlantısı hazır değil. Ana ekrana dönüp oturumu başlat."
            Toast.makeText(this, "Kamera canlı Jarvis oturumuna bağlı çalışır.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        try {
            cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val facing = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                if (usingFrontCamera) facing == CameraCharacteristics.LENS_FACING_FRONT else facing == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.firstOrNull()
            val id = cameraId ?: run { status.text = "Bu cihazda kamera bulunamadı."; return }
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG).orEmpty()
            val chosen = jpegSizes.filter { it.width <= 1280 && it.height <= 960 }
                .minByOrNull { kotlin.math.abs(it.width * it.height - 640 * 480) }
            val width = chosen?.width ?: 640
            val height = chosen?.height ?: 480
            val streamSizes = map?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
            val textureChoice = streamSizes.firstOrNull { it.width >= width && it.height >= height }
                ?: streamSizes.firstOrNull()
            if (textureChoice != null) previewSize = textureChoice
            imageReader?.close()
            imageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 3).apply {
                setOnImageAvailableListener({ reader -> processLatestFrame(reader) }, cameraHandler)
            }
            status.text = "Kamera açılıyor…"
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createPreviewSession()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (cameraDevice === camera) cameraDevice = null
                    runOnUiThread { status.text = "Kamera bağlantısı kesildi." }
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cameraDevice === camera) cameraDevice = null
                    runOnUiThread {
                        status.text = "Kamera açılamadı (kod $error)."
                        Toast.makeText(this@CameraVisionActivity, "Kamera başlatılamadı.", Toast.LENGTH_SHORT).show()
                    }
                }
            }, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Kamera başlatılamadı", e)
            status.text = "Kamera başlatılamadı: ${e.localizedMessage ?: "bilinmeyen hata"}"
        }
    }

    private fun createPreviewSession() {
        val device = cameraDevice ?: return
        val texture = preview.surfaceTexture ?: return
        try {
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            val previewSurface = Surface(texture)
            val readerSurface = imageReader?.surface ?: return
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            captureRequestBuilder = builder
            device.createCaptureSession(listOf(previewSurface, readerSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) { session.close(); return }
                    captureSession = session
                    sessionConfigured = true
                    try {
                        session.setRepeatingRequest(builder.build(), null, cameraHandler)
                        runOnUiThread { status.text = "Canlı görüntü Jarvis'e aktarılıyor" }
                        scheduleStillCapture()
                    } catch (e: Exception) {
                        Log.e(TAG, "Önizleme isteği kurulamadı", e)
                        runOnUiThread { status.text = "Kamera görüntüsü başlatılamadı." }
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    runOnUiThread { status.text = "Kamera oturumu yapılandırılamadı." }
                }
            }, cameraHandler)
            configureTransform(preview.width, preview.height)
        } catch (e: Exception) {
            Log.e(TAG, "Önizleme oturumu kurulamadı", e)
            status.text = "Kamera hazırlığı başarısız oldu."
        }
    }

    private fun scheduleStillCapture() {
        val handler = cameraHandler ?: return
        handler.postDelayed(object : Runnable {
            override fun run() {
                val session = captureSession
                val device = cameraDevice
                val reader = imageReader
                if (isFinishing || isDestroyed || !sessionConfigured || session == null || device == null || reader == null) return
                try {
                    if (captureBusy.compareAndSet(false, true)) {
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(reader.surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation())
                        }.build()
                        session.capture(request, object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: android.hardware.camera2.TotalCaptureResult) {
                                captureBusy.set(false)
                            }
                            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
                                captureBusy.set(false)
                            }
                        }, cameraHandler)
                    }
                } catch (e: Exception) {
                    captureBusy.set(false)
                    Log.w(TAG, "Kare alınamadı: ${e.message}")
                }
                if (!isFinishing && !isDestroyed && cameraDevice != null) handler.postDelayed(this, 1400L)
            }
        }, 300L)
    }

    private fun processLatestFrame(reader: ImageReader) {
        val image = try { reader.acquireLatestImage() } catch (_: Exception) { null } ?: return
        val bytes = try {
            val buffer = image.planes[0].buffer
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Kamera karesi okunamadı", e)
            null
        } finally {
            image.close()
            captureBusy.set(false)
        } ?: return
        val now = System.currentTimeMillis()
        if (now - lastFrameSentAt >= 1050L) {
            lastFrameSentAt = now
            val sent = JarvisConversationService.sendCameraVideoFrame(bytes)
            runOnUiThread {
                if (sent) status.text = if (usingFrontCamera) "Ön kamera • canlı görüntü aktarılıyor" else "Arka kamera • canlı görüntü aktarılıyor"
                else status.text = "Jarvis bağlantısı bekleniyor…"
            }
            if (usingFrontCamera) updateFaceOverlay(bytes)
            else runOnUiThread { overlay.updateDetectedFace(0.5f, 0.45f, 0.12f, false) }
        }
    }

    private fun updateFaceOverlay(jpeg: ByteArray) {
        try {
            val options = BitmapFactory.Options().apply { inSampleSize = 4; inPreferredConfig = Bitmap.Config.RGB_565 }
            val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) ?: return
            val width = decoded.width - (decoded.width % 2)
            val faceBitmap = if (decoded.width != width) Bitmap.createBitmap(decoded, 0, 0, width, decoded.height) else decoded
            if (faceBitmap.config != Bitmap.Config.RGB_565 || faceBitmap.width < 2) {
                if (faceBitmap !== decoded) faceBitmap.recycle()
                decoded.recycle()
                return
            }
            val face = arrayOfNulls<FaceDetector.Face>(1)
            val detector = FaceDetector(faceBitmap.width, faceBitmap.height, 1)
            val count = detector.findFaces(faceBitmap, face)
            val foundFace = count > 0 && face[0] != null
            var nx = 0.5f
            var ny = 0.4f
            var radius = 0.13f
            if (foundFace) {
                val mid = android.graphics.PointF()
                face[0]!!.getMidPoint(mid)
                nx = (mid.x / faceBitmap.width)
                if (usingFrontCamera) nx = 1f - nx
                ny = (mid.y / faceBitmap.height)
                radius = (face[0]!!.eyesDistance() / faceBitmap.width * 2.6f).coerceIn(0.07f, 0.22f)
            }
            if (faceBitmap !== decoded) faceBitmap.recycle()
            decoded.recycle()
            runOnUiThread { overlay.updateDetectedFace(nx, ny, radius, foundFace) }
        } catch (e: Exception) {
            Log.d(TAG, "Yüz parçacığı tespiti bu karede atlandı: ${e.message}")
        }
    }

    private fun jpegOrientation(): Int {
        val sensor = cameraId?.let { cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.SENSOR_ORIENTATION) } ?: 0
        val rotation = windowManager.defaultDisplay.rotation
        val degrees = when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (usingFrontCamera) (sensor + degrees) % 360 else (sensor - degrees + 360) % 360
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        if (viewWidth <= 0 || viewHeight <= 0 || !::preview.isInitialized) return
        val rotation = windowManager.defaultDisplay.rotation
        val matrix = android.graphics.Matrix()
        val viewRect = android.graphics.RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = android.graphics.RectF(
            0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat()
        )
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, android.graphics.Matrix.ScaleToFit.FILL)
            val scale = max(viewHeight.toFloat() / previewSize.height, viewWidth.toFloat() / previewSize.width)
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate(90f * (rotation - 2), centerX, centerY)
        } else {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, android.graphics.Matrix.ScaleToFit.FILL)
            val scale = max(viewWidth.toFloat() / previewSize.height, viewHeight.toFloat() / previewSize.width)
            matrix.postScale(scale, scale, centerX, centerY)
        }
        preview.setTransform(matrix)
    }

    private fun closeCamera() {
        sessionConfigured = false
        try { captureSession?.stopRepeating() } catch (_: Exception) { }
        try { captureSession?.close() } catch (_: Exception) { }
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) { }
        cameraDevice = null
        try { imageReader?.close() } catch (_: Exception) { }
        imageReader = null
        captureBusy.set(false)
        overlay.updateDetectedFace(0.5f, 0.4f, 0.12f, false)
    }
}
