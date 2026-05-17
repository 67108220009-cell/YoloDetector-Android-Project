package com.example.yolodetector

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "YoloDetector"
        private const val REQUEST_CAMERA_PERMISSION = 100
        private const val MODEL_FILE = "yolov8n_float32.tflite"
        private const val INPUT_SIZE = 640
        private const val CONF_THRESHOLD = 0.10f
        private const val IOU_THRESHOLD = 0.45f
        private const val NUM_BOXES = 8400
        private const val INPUT_BYTES = INPUT_SIZE * INPUT_SIZE * 3 * 4
    }

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var tvFps: TextView
    private lateinit var tvCount: TextView
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var tvConf: TextView
    private lateinit var tvInference: TextView
    private lateinit var tvStatus: TextView
    private lateinit var liveDot: View

    private var interpreter: Interpreter? = null
    private var nnApiDelegate: NnApiDelegate? = null
    private var lastAnalysisTime = 0L

    private val isProcessing = AtomicBoolean(false)
    private var isChannelFirst = true

    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(INPUT_BYTES).apply { order(ByteOrder.nativeOrder()) }
    private val pixelInts = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val resizedBitmap: Bitmap =
        Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
    private val resizeCanvas = Canvas(resizedBitmap)
    private val resizePaint  = Paint(Paint.FILTER_BITMAP_FLAG)

    private val outputA = Array(1) { Array(84)        { FloatArray(NUM_BOXES) } }
    private val outputB = Array(1) { Array(NUM_BOXES) { FloatArray(84) } }

    private var debugCounter = 0

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.viewFinder)
        overlayView = findViewById(R.id.overlayView)
        tvFps       = findViewById(R.id.tvFps)
        tvCount     = findViewById(R.id.tvCount)
        tvConf      = findViewById(R.id.tvConf)
        tvInference = findViewById(R.id.tvInference)
        tvStatus    = findViewById(R.id.tvStatus)
        liveDot     = findViewById(R.id.liveDot)

        overlayView.bringToFront()
        startLiveDotAnimation()

        cameraExecutor = Executors.newSingleThreadExecutor()
        loadModel()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera()
        else ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION
        )
    }

    // ── Model ────────────────────────────────────────────────────────────────

    private fun loadModel() {
        try {
            val modelBuffer = FileUtil.loadMappedFile(this, MODEL_FILE)

            val options = Interpreter.Options().apply {
                try {
                    nnApiDelegate = NnApiDelegate()
                    addDelegate(nnApiDelegate!!)
                    Log.d(TAG, "NNAPI delegate enabled")
                } catch (e: Exception) {
                    Log.w(TAG, "NNAPI not available, fallback to CPU: ${e.message}")
                    numThreads = 4
                }
            }

            interpreter = Interpreter(modelBuffer, options)

            val outShape = interpreter!!.getOutputTensor(0).shape()
            Log.d(TAG, "INPUT  = ${interpreter!!.getInputTensor(0).shape().contentToString()}")
            Log.d(TAG, "OUTPUT = ${outShape.contentToString()}")

            isChannelFirst = outShape[1] == 84
            Log.d(TAG, "Shape: ${if (isChannelFirst) "[1,84,8400]" else "[1,8400,84]"}")

        } catch (e: Exception) {
            Log.e(TAG, "Model load failed", e)
            Toast.makeText(this, "Model load failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── Camera ───────────────────────────────────────────────────────────────

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val cameraProvider = future.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analyzer = ImageAnalysis.Builder()
                .setTargetResolution(Size(INPUT_SIZE, INPUT_SIZE))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(cameraExecutor) { img -> processImage(img) } }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analyzer
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ── Inference ────────────────────────────────────────────────────────────

    private fun processImage(imageProxy: ImageProxy) {
        if (!isProcessing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        val startTime = System.currentTimeMillis()
        val interp = interpreter ?: run {
            isProcessing.set(false)
            imageProxy.close()
            return
        }

        try {
            val bitmap = imageProxy.toBitmap()
            val srcRect = android.graphics.Rect(0, 0, bitmap.width, bitmap.height)
            val dstRect = android.graphics.RectF(0f, 0f, INPUT_SIZE.toFloat(), INPUT_SIZE.toFloat())
            resizeCanvas.drawBitmap(bitmap, srcRect, dstRect, resizePaint)
            bitmap.recycle()

            resizedBitmap.getPixels(pixelInts, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            inputBuffer.rewind()
            for (pixel in pixelInts) {
                inputBuffer.putFloat(((pixel shr 16) and 0xFF) * (1f / 255f))
                inputBuffer.putFloat(((pixel shr  8) and 0xFF) * (1f / 255f))
                inputBuffer.putFloat(( pixel         and 0xFF) * (1f / 255f))
            }
            inputBuffer.rewind()

            val results = if (isChannelFirst) {
                interp.run(inputBuffer, outputA)
                debugLog(outputA[0], true)
                parseChannelFirst(outputA[0])
            } else {
                interp.run(inputBuffer, outputB)
                debugLog(outputB[0], false)
                parseBoxFirst(outputB[0])
            }

            val inferenceMs = System.currentTimeMillis() - startTime
            val now = System.currentTimeMillis()
            val fps = if (lastAnalysisTime != 0L) 1000f / (now - lastAnalysisTime) else 0f
            lastAnalysisTime = now

            runOnUiThread {
                overlayView.setResults(results, imageProxy.height, imageProxy.width)
                tvFps.text = "%.1f FPS · %dms".format(fps, inferenceMs)
                tvInference.text = "${inferenceMs}ms"
                tvCount.text = "${results.size}"

                if (results.isNotEmpty()) {
                    val avgConf = results.sumOf { it.score.toDouble() } / results.size
                    tvConf.text = "avg conf %.0f%%".format(avgConf * 100)
                } else {
                    tvConf.text = "avg conf —"
                }

                tvStatus.text = if (results.isEmpty()) "IDLE" else "OK"
                tvStatus.setTextColor(
                    if (results.isEmpty())
                        android.graphics.Color.parseColor("#FF888888")
                    else
                        android.graphics.Color.parseColor("#FF00FF88")
                )
            }
        } finally {
            imageProxy.close()
            isProcessing.set(false)
        }
    }

    // ── Debug ─────────────────────────────────────────────────────────────────

    private fun debugLog(out: Any, channelFirst: Boolean) {
        if (debugCounter++ % 30 != 0) return
        var maxConf = 0f
        if (channelFirst) {
            val o = out as Array<FloatArray>
            for (i in 0 until NUM_BOXES)
                for (c in 4 until 84)
                    if (o[c][i] > maxConf) maxConf = o[c][i]
            Log.d(TAG, "[CF] max conf=$maxConf cx=${o[0][0]}")
        } else {
            val o = out as Array<FloatArray>
            for (i in 0 until NUM_BOXES)
                for (c in 4 until 84)
                    if (o[i][c] > maxConf) maxConf = o[i][c]
            Log.d(TAG, "[BF] max conf=$maxConf cx=${o[0][0]}")
        }
    }

    // ── Parsers ───────────────────────────────────────────────────────────────

    private fun parseChannelFirst(out: Array<FloatArray>): List<DetectionResult> {
        val det = ArrayList<DetectionResult>(32)
        for (i in 0 until NUM_BOXES) {
            var maxScore = 0f; var classIdx = -1
            for (c in 4 until 84) {
                val s = out[c][i]
                if (s > maxScore) { maxScore = s; classIdx = c - 4 }
            }
            if (maxScore <= CONF_THRESHOLD) continue
            val cx = out[0][i]; val cy = out[1][i]
            val w  = out[2][i]; val h  = out[3][i]
            val s  = if (cx > 2f) INPUT_SIZE.toFloat() else 1f
            det.add(DetectionResult(RectF(
                ((cx - w * 0.5f) / s).coerceIn(0f, 1f),
                ((cy - h * 0.5f) / s).coerceIn(0f, 1f),
                ((cx + w * 0.5f) / s).coerceIn(0f, 1f),
                ((cy + h * 0.5f) / s).coerceIn(0f, 1f)
            ), classIdx, maxScore))
        }
        return nms(det)
    }

    private fun parseBoxFirst(out: Array<FloatArray>): List<DetectionResult> {
        val det = ArrayList<DetectionResult>(32)
        for (i in 0 until NUM_BOXES) {
            val row = out[i]
            var maxScore = 0f; var classIdx = -1
            for (c in 4 until 84) {
                val s = row[c]
                if (s > maxScore) { maxScore = s; classIdx = c - 4 }
            }
            if (maxScore <= CONF_THRESHOLD) continue
            val cx = row[0]; val cy = row[1]
            val w  = row[2]; val h  = row[3]
            val s  = if (cx > 2f) INPUT_SIZE.toFloat() else 1f
            det.add(DetectionResult(RectF(
                ((cx - w * 0.5f) / s).coerceIn(0f, 1f),
                ((cy - h * 0.5f) / s).coerceIn(0f, 1f),
                ((cx + w * 0.5f) / s).coerceIn(0f, 1f),
                ((cy + h * 0.5f) / s).coerceIn(0f, 1f)
            ), classIdx, maxScore))
        }
        return nms(det)
    }

    // ── NMS ───────────────────────────────────────────────────────────────────

    private fun nms(detections: List<DetectionResult>): List<DetectionResult> {
        val sorted = detections.sortedByDescending { it.score }.toMutableList()
        val kept = ArrayList<DetectionResult>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            kept.add(best)
            sorted.removeAll { iou(best.boundingBox, it.boundingBox) > IOU_THRESHOLD }
        }
        return kept
    }

    private fun iou(a: RectF, b: RectF): Float {
        val iw = minOf(a.right, b.right)   - maxOf(a.left, b.left)
        val ih = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (iw <= 0f || ih <= 0f) return 0f
        val inter = iw * ih
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union > 0f) inter / union else 0f
    }

    // ── Live dot animation ────────────────────────────────────────────────────

    private fun startLiveDotAnimation() {
        liveDot.animate()
            .alpha(0.2f)
            .setDuration(600)
            .withEndAction {
                liveDot.animate()
                    .alpha(1f)
                    .setDuration(600)
                    .withEndAction { startLiveDotAnimation() }
                    .start()
            }
            .start()
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) startCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        interpreter?.close()
        nnApiDelegate?.close()
        resizedBitmap.recycle()
    }
}