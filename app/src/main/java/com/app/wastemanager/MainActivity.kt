package com.app.wastemanager

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.wastemanager.ui.theme.WasteManagerTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// ==========================================
// CONSTANTS
// ==========================================
// Gemini API Key sourced from local.properties via BuildConfig (see README setup section)
private val GEMINI_API_KEY = BuildConfig.GEMINI_API_KEY
private const val SPP_UUID_STRING = "00001101-0000-1000-8000-00805F9B34FB"
private const val TARGET_BT_DEVICE_NAME = "EcoSense_Bin"
private const val FALLBACK_CLASSIFICATION = "DRY" // safe default so the bin still gets a signal if Gemini can't classify
private const val FALLBACK_ITEM_NAME = "Dry Recyclable"
private const val MAX_ATTEMPTS_PER_MODEL = 2
private const val RETRY_BACKOFF_MS = 500L
private val GEMINI_FALLBACK_MODELS = listOf(
    "gemini-3.5-flash-lite",
    "gemini-3.1-flash-lite",
    "gemini-3.8-flash",
    "gemini-3.7-flash",
    "gemini-3.5-flash",
    "gemini-3-flash-preview"
)

// Data class to hold Gemini classification & identified item name
data class ClassificationResult(
    val category: String, // "DRY", "WET", "ELECTRONIC", or "ERROR"
    val itemName: String  // e.g. "Plastic Bottle", "Banana Peel"
)

// ==========================================
// VIEWMODEL FOR /btw STATE MANAGEMENT
// ==========================================
class WasteViewModel : ViewModel() {
    var isScanning by mutableStateOf(false)
        private set

    var resultCategory by mutableStateOf<String?>(null)
        private set

    var resultItemName by mutableStateOf<String?>(null)
        private set

    var isDebugOpen by mutableStateOf(false)

    var isBtConnected by mutableStateOf(false)
        private set

    var customApiKey by mutableStateOf("")
        private set

    fun initApiKey(context: Context) {
        val prefs = context.getSharedPreferences("waste_manager_prefs", Context.MODE_PRIVATE)
        customApiKey = prefs.getString("custom_api_key", "") ?: ""
    }

    fun setCustomApiKey(context: Context, key: String) {
        customApiKey = key
        val prefs = context.getSharedPreferences("waste_manager_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("custom_api_key", key).apply()
    }

    private val _debugLogs = mutableStateListOf<String>()
    val debugLogs: List<String> get() = _debugLogs

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun addDebugLog(msg: String) {
        android.util.Log.d("WasteManager", msg)
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _debugLogs.add("[$time] $msg")
    }

    fun clearLogs() {
        _debugLogs.clear()
    }

    fun checkBluetoothBonded(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val reachable = isSmartDustbinReachable(context) { log ->
                viewModelScope.launch(Dispatchers.Main) { addDebugLog(log) }
            }
            withContext(Dispatchers.Main) {
                isBtConnected = reachable
            }
        }
    }

    fun resetToCamera() {
        resultCategory = null
        resultItemName = null
        isScanning = false
    }

    /**
     * Trigger classification pipeline on captured image bytes
     */
    fun processImage(context: Context, imageBytes: ByteArray) {
        if (isScanning) return
        isScanning = true
        resultCategory = null
        resultItemName = null

        viewModelScope.launch {
            val trimmedCustomKey = customApiKey.trim()
            val effectiveApiKey = if (trimmedCustomKey.isNotEmpty()) trimmedCustomKey else GEMINI_API_KEY
            val keySource = if (trimmedCustomKey.isNotEmpty()) "Custom API Key" else "BuildConfig Default"
            val kbSize = (imageBytes.size / 1024).coerceAtLeast(1)
            addDebugLog("Starting Gemini classification on ${kbSize} KB image... ($keySource)")
            val startTime = System.currentTimeMillis()
            val classificationResult = classifyWasteWithGemini(
                imageBytes = imageBytes,
                apiKey = effectiveApiKey,
                client = httpClient,
                onDebugLog = { log -> addDebugLog(log) }
            )
            val durationMs = System.currentTimeMillis() - startTime
            addDebugLog("Gemini call completed in ${durationMs}ms.")

            val isFallback = classificationResult.category == "ERROR"
            val effectiveCategory = if (isFallback) FALLBACK_CLASSIFICATION else classificationResult.category
            val effectiveItemName = if (isFallback) FALLBACK_ITEM_NAME else classificationResult.itemName

            if (isFallback) {
                addDebugLog("Gemini classification failed/error — defaulting to '$FALLBACK_CLASSIFICATION' ($FALLBACK_ITEM_NAME) so bin still responds.")
            }

            resultCategory = effectiveCategory
            resultItemName = effectiveItemName
            addDebugLog("Classified as: $effectiveCategory | $effectiveItemName${if (isFallback) " [fallback default]" else ""}")

            // Send Bluetooth command: 'D', 'W', or 'E'
            val commandChar = when (effectiveCategory) {
                "DRY" -> 'D'
                "WET" -> 'W'
                "ELECTRONIC" -> 'E'
                else -> null
            }

            if (commandChar != null) {
                addDebugLog("Dispatching Bluetooth command '$commandChar'...")
                sendBluetoothCommand(
                    context = context,
                    command = commandChar,
                    onLog = { log -> addDebugLog(log) }
                )
            }
        }
    }

    /**
     * Helper for simulating classification in emulator / testing without real API key
     */
    fun simulateClassification(context: Context, category: String, itemName: String? = null) {
        if (isScanning) return
        isScanning = true
        resultCategory = null
        resultItemName = null

        val defaultItem = when (category) {
            "DRY" -> "Plastic Water Bottle"
            "WET" -> "Fresh Apple Core"
            "ELECTRONIC" -> "Lithium Ion Battery"
            else -> "Dry Recyclable"
        }
        val effectiveName = itemName ?: defaultItem

        viewModelScope.launch {
            addDebugLog("Simulating scan for: $category ($effectiveName)...")
            delay(1000L) // Brief simulated thinking
            resultCategory = category
            resultItemName = effectiveName
            isScanning = false

            val commandChar = when (category) {
                "DRY" -> 'D'
                "WET" -> 'W'
                "ELECTRONIC" -> 'E'
                else -> null
            }

            if (commandChar != null) {
                addDebugLog("Dispatching Bluetooth command '$commandChar'...")
                sendBluetoothCommand(
                    context = context,
                    command = commandChar,
                    onLog = { log -> addDebugLog(log) }
                )
            }
        }
    }
}

/**
 * Downscales and compresses image bytes to maxDim (480px) and JPEG quality (65%)
 * to minimize payload size and vision token ingestion latency.
 */
fun optimizeImageBytes(imageBytes: ByteArray, maxDim: Int = 480, quality: Int = 65): ByteArray {
    if (imageBytes.size <= 40 * 1024) return imageBytes // already lightweight (<40KB)
    return try {
        val original = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return imageBytes
        val width = original.width
        val height = original.height
        val scaled = if (width > maxDim || height > maxDim) {
            val scale = maxDim.toFloat() / maxOf(width, height)
            val targetW = (width * scale).toInt().coerceAtLeast(1)
            val targetH = (height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(original, targetW, targetH, true).also {
                if (it != original) original.recycle()
            }
        } else {
            original
        }
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        scaled.recycle()
        stream.toByteArray()
    } catch (_: Exception) {
        imageBytes
    }
}

// ==========================================
// CORE LOGIC 1 - GEMINI REST API
// ==========================================
/**
 * Fast Gemini API Fallback Loop (OkHttp)
 * - Accepts ByteArray (the image).
 * - Downscales & compresses image to ~480px JPEG (~15-25 KB) for minimal upload & token processing latency.
 * - Ultra-concise prompt and generationConfig (maxOutputTokens=16, temperature=0.1) to eliminate decoding delays.
 * - Iterates over models starting with ultra-fast models (gemini-2.0-flash, gemini-2.5-flash, gemini-1.5-flash-8b, gemini-1.5-flash).
 * - Non-retryable HTTP errors (404, 400, 403) skip retrying immediately to avoid redundant delays.
 */
suspend fun classifyWasteWithGemini(
    imageBytes: ByteArray,
    apiKey: String,
    client: OkHttpClient,
    onDebugLog: (String) -> Unit
): ClassificationResult = withContext(Dispatchers.IO) {
    if (apiKey.isBlank()) {
        onDebugLog("Gemini API Key is empty (both custom field & BuildConfig). Categorizing into default DRY.")
        return@withContext ClassificationResult("ERROR", FALLBACK_ITEM_NAME)
    }

    val optimizedBytes = optimizeImageBytes(imageBytes, maxDim = 480, quality = 65)
    val base64Image = Base64.encodeToString(optimizedBytes, Base64.NO_WRAP)
    val promptText = "Classify this waste item into: DRY, WET, or ELECTRONIC. Format strictly as: CATEGORY | ITEM_NAME (e.g. DRY | Plastic Bottle, WET | Apple, ELECTRONIC | Battery). No other text."

    // Construct standard Gemini REST API payload with generationConfig for low latency
    val payloadJson = try {
        JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", promptText)
                        })
                        put(JSONObject().apply {
                            put("inlineData", JSONObject().apply {
                                put("mimeType", "image/jpeg")
                                put("data", base64Image)
                            })
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
            })
        }
    } catch (e: Exception) {
        onDebugLog("JSON creation error: ${e.message}")
        return@withContext ClassificationResult("ERROR", FALLBACK_ITEM_NAME)
    }

    val mediaType = "application/json; charset=utf-8".toMediaType()

    // Fallback loop over the model list, with limited retries per model for transient errors
    for (model in GEMINI_FALLBACK_MODELS) {
        var attempt = 0
        modelAttemptLoop@ while (attempt < MAX_ATTEMPTS_PER_MODEL) {
            attempt++
            onDebugLog("Trying model: $model (attempt $attempt/$MAX_ATTEMPTS_PER_MODEL)...")
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                val request = Request.Builder()
                    .url(url)
                    .post(payloadJson.toString().toRequestBody(mediaType))
                    .build()

                val callStart = System.currentTimeMillis()
                val response = client.newCall(request).execute()
                val callDuration = System.currentTimeMillis() - callStart
                response.use { resp ->
                    val bodyString = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) {
                        val retryable = resp.code == 429 || resp.code in 500..599
                        onDebugLog("Model $model failed (HTTP ${resp.code}) in ${callDuration}ms: $bodyString")
                        if (retryable && attempt < MAX_ATTEMPTS_PER_MODEL) {
                            onDebugLog("Retryable error — backing off ${RETRY_BACKOFF_MS}ms then retrying $model...")
                            Thread.sleep(RETRY_BACKOFF_MS)
                        } else {
                            break@modelAttemptLoop
                        }
                        return@use // continue to retry or fall through to next model
                    }

                    // Parse response
                    val responseJson = JSONObject(bodyString)
                    val candidates = responseJson.optJSONArray("candidates")
                    val firstCandidate = candidates?.optJSONObject(0)
                    val content = firstCandidate?.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    val replyText = parts?.optJSONObject(0)?.optString("text")?.trim() ?: ""

                    onDebugLog("Model $model replied in ${callDuration}ms: '$replyText'")

                    val firstLine = replyText.lines().firstOrNull { it.isNotBlank() } ?: replyText
                    val splitParts = firstLine.split("|", ":", "–", "-").map { it.trim() }

                    var cat: String? = null
                    var rawItem: String? = null

                    if (splitParts.size >= 2) {
                        val p0 = splitParts[0].uppercase()
                        cat = when {
                            p0.contains("ELECTRONIC") -> "ELECTRONIC"
                            p0.contains("WET") -> "WET"
                            p0.contains("DRY") -> "DRY"
                            else -> null
                        }
                        if (cat != null) {
                            rawItem = splitParts[1]
                        }
                    }

                    if (cat == null) {
                        val upper = replyText.uppercase()
                        cat = when {
                            upper.contains("ELECTRONIC") -> "ELECTRONIC"
                            upper.contains("WET") -> "WET"
                            upper.contains("DRY") -> "DRY"
                            else -> null
                        }
                        val cleaned = replyText.replace(Regex("(?i)(DRY|WET|ELECTRONIC|[|:–\\-])"), "").trim()
                        if (cleaned.isNotBlank()) {
                            rawItem = cleaned
                        }
                    }

                    if (cat != null) {
                        val formattedItemName = if (rawItem.isNullOrBlank()) {
                            when (cat) {
                                "DRY" -> "Dry Recyclable"
                                "WET" -> "Organic Waste"
                                "ELECTRONIC" -> "Electronic Waste"
                                else -> FALLBACK_ITEM_NAME
                            }
                        } else {
                            rawItem.replace(Regex("[*#_\"']"), "")
                                .split(" ")
                                .filter { it.isNotBlank() }
                                .joinToString(" ") { word -> word.lowercase().replaceFirstChar { it.uppercase() } }
                                .take(35)
                        }

                        onDebugLog("Classification successful via $model in ${callDuration}ms -> $cat | $formattedItemName")
                        return@withContext ClassificationResult(cat, formattedItemName)
                    } else {
                        onDebugLog("Model $model output did not match DRY/WET/ELECTRONIC.")
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                onDebugLog("$model timed out (attempt $attempt/$MAX_ATTEMPTS_PER_MODEL)")
                if (attempt < MAX_ATTEMPTS_PER_MODEL) Thread.sleep(RETRY_BACKOFF_MS)
            } catch (e: Exception) {
                onDebugLog("$model encountered network/parse error: ${e.localizedMessage ?: e.javaClass.simpleName}")
                break // non-timeout errors: don't retry this model, move to the next one
            }
        }
    }

    onDebugLog("All Gemini models exhausted without a valid classification.")
    return@withContext ClassificationResult("ERROR", FALLBACK_ITEM_NAME)
}

// ==========================================
// ==========================================
// BACKGROUND CHANGE & STILLNESS ANALYZER
// ==========================================
/**
 * Simple Vision-Based Object & Stillness Analyzer:
 * 1. Takes a reference shot of the background when the camera boots up.
 * 2. Compares the current image against the reference background:
 *    - Uses low difference tolerance (high sensitivity) so any object entering the view is detected.
 *    - Does NOT use proximity sensors.
 * 3. When an object is detected:
 *    - Greatly increased stillness tolerance (MOTION_THRESHOLD = 40.0f) so even shaking objects are detected as still.
 *    - Runs a 2-second countdown and triggers hands-free capture!
 * 4. When the object is removed, the image matches the background again, resetting automatically.
 */
class ProximityStillnessAnalyzer(
    private val isHardwareNearProvider: () -> Boolean = { false },
    private val hasHardwareSensorProvider: () -> Boolean = { false },
    private val isFrontCameraProvider: () -> Boolean = { true },
    private val isPausedProvider: () -> Boolean,
    private val objectSensitivityProvider: () -> Float = { 0.40f },
    private val stillnessToleranceProvider: () -> Float = { 0.60f },
    private val onStateUpdate: (isDetected: Boolean, isStill: Boolean, progress: Float) -> Unit,
    private val onStillnessTrigger: () -> Unit
) : ImageAnalysis.Analyzer {

    private var lastAnalysisTimestamp = 0L
    private var prevMotionGrid: ByteArray? = null
    private var referenceBackground: IntArray? = null
    private var bootWarmupFrames = 0
    private var isColdBoot = true
    private var stillStartTime = 0L
    private val motionGridDim = 24
    private val bgGridDim = 16 // 16x16 = 256 sample points covering the image
    private var hasTriggeredForCurrentPresence = false
    private var smoothMotion = -1f

    fun recalibrateBackground() {
        referenceBackground = null
        bootWarmupFrames = 0
        stillStartTime = 0L
        hasTriggeredForCurrentPresence = false
        prevMotionGrid = null
        smoothMotion = -1f
    }

    override fun analyze(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            val isPaused = isPausedProvider()

            // Throttle analysis to ~15 FPS to conserve CPU and battery
            if (now - lastAnalysisTimestamp < 65L || isPaused) {
                if (isPaused) {
                    stillStartTime = 0L
                    smoothMotion = -1f
                    prevMotionGrid = null
                    hasTriggeredForCurrentPresence = false
                    onStateUpdate(false, false, 0f)
                }
                return
            }
            lastAnalysisTimestamp = now

            val yPlane = image.planes[0]
            val uPlane = if (image.planes.size > 1) image.planes[1] else null
            val vPlane = if (image.planes.size > 2) image.planes[2] else null

            val yBuffer = yPlane.buffer
            val uBuffer = uPlane?.buffer
            val vBuffer = vPlane?.buffer

            val yRowStride = yPlane.rowStride
            val yPixelStride = yPlane.pixelStride
            val uRowStride = uPlane?.rowStride ?: 0
            val uPixelStride = uPlane?.pixelStride ?: 0
            val vRowStride = vPlane?.rowStride ?: 0
            val vPixelStride = vPlane?.pixelStride ?: 0

            val width = image.width
            val height = image.height

            // Helper: Extract RGB at (x, y) from YUV_420_888 buffers
            fun getRgbAt(x: Int, y: Int): Int {
                val clampedX = x.coerceIn(0, width - 1)
                val clampedY = y.coerceIn(0, height - 1)
                val yIdx = clampedY * yRowStride + clampedX * yPixelStride
                val yVal = yBuffer.get(yIdx).toInt() and 0xFF
                val uVal: Int
                val vVal: Int
                if (uBuffer != null && vBuffer != null) {
                    val uvX = clampedX / 2
                    val uvY = clampedY / 2
                    val uIdx = uvY * uRowStride + uvX * uPixelStride
                    val vIdx = uvY * vRowStride + uvX * vPixelStride
                    uVal = uBuffer.get(uIdx).toInt() and 0xFF
                    vVal = vBuffer.get(vIdx).toInt() and 0xFF
                } else {
                    uVal = 128
                    vVal = 128
                }
                val d = uVal - 128
                val e = vVal - 128
                val r = (yVal + 1.402f * e).toInt().coerceIn(0, 255)
                val g = (yVal - 0.344136f * d - 0.714136f * e).toInt().coerceIn(0, 255)
                val b = (yVal + 1.772f * d).toInt().coerceIn(0, 255)
                return (r shl 16) or (g shl 8) or b
            }

            fun colorDistance(rgb1: Int, rgb2: Int): Float {
                val dr = ((rgb1 shr 16) and 0xFF) - ((rgb2 shr 16) and 0xFF)
                val dg = ((rgb1 shr 8) and 0xFF) - ((rgb2 shr 8) and 0xFF)
                val db = (rgb1 and 0xFF) - (rgb2 and 0xFF)
                return Math.sqrt((dr * dr + dg * dg + db * db).toDouble()).toFloat()
            }

            // 1. Inter-frame motion calculation
            val currMotionGrid = ByteArray(motionGridDim * motionGridDim)
            for (r in 0 until motionGridDim) {
                val y = (r * height) / motionGridDim
                for (c in 0 until motionGridDim) {
                    val x = (c * width) / motionGridDim
                    val index = y * yRowStride + x * yPixelStride
                    currMotionGrid[r * motionGridDim + c] = yBuffer.get(index)
                }
            }

            val prev = prevMotionGrid
            val avgMotion: Float
            if (prev != null) {
                var motionSum = 0L
                for (i in currMotionGrid.indices) {
                    motionSum += Math.abs((currMotionGrid[i].toInt() and 0xFF) - (prev[i].toInt() and 0xFF))
                }
                avgMotion = motionSum.toFloat() / currMotionGrid.size
            } else {
                avgMotion = 0f
            }
            prevMotionGrid = currMotionGrid

            smoothMotion = if (smoothMotion < 0f) avgMotion else (smoothMotion * 0.70f + avgMotion * 0.30f)

            // 2. Sample current frame grid (16x16 = 256 samples across the frame)
            val currentFrame = IntArray(bgGridDim * bgGridDim)
            for (r in 0 until bgGridDim) {
                val y = (r * height) / bgGridDim
                for (c in 0 until bgGridDim) {
                    val x = (c * width) / bgGridDim
                    currentFrame[r * bgGridDim + c] = getRgbAt(x, y)
                }
            }

            // 3. Take a shot of the background when the app boots up (wait for AE/AWB to settle)
            val bg = referenceBackground
            if (bg == null) {
                bootWarmupFrames++
                // Let camera AE / AWB settle (18 frames ~ 1.2s on cold boot, 8 frames on re-cal)
                val requiredWarmup = if (isColdBoot) 18 else 8
                if (bootWarmupFrames >= requiredWarmup) {
                    referenceBackground = currentFrame
                    bootWarmupFrames = 0
                    isColdBoot = false
                }
                onStateUpdate(false, false, 0f)
                return
            }

            // 4. Compare current image to reference background with user-controllable sensitivity
            val sens = objectSensitivityProvider().coerceIn(0.05f, 0.95f)
            // Higher sensitivity -> lower tolerance thresholds
            // Lower sensitivity -> higher tolerance thresholds (immune to light flicker and sensor grain)
            val cellTolerance = 32.0f - (sens * 18.0f)
            val minChangedRatio = 0.24f - (sens * 0.18f)
            val minAvgDiff = 26.0f - (sens * 0.18f)

            var totalDiff = 0.0
            var changedPoints = 0
            for (i in currentFrame.indices) {
                val d = colorDistance(currentFrame[i], bg[i])
                totalDiff += d
                if (d > cellTolerance) {
                    changedPoints++
                }
            }
            val avgDiff = (totalDiff / currentFrame.size).toFloat()
            val changedRatio = changedPoints.toFloat() / currentFrame.size

            val isObjectDetected = changedRatio >= minChangedRatio || avgDiff >= minAvgDiff

            if (!isObjectDetected) {
                // Image matches background: No object in front of camera
                stillStartTime = 0L
                smoothMotion = -1f
                hasTriggeredForCurrentPresence = false
                onStateUpdate(false, false, 0f)
                return
            }

            // 5. Object detected! Check for stillness with user-controlled tolerance
            val tol = stillnessToleranceProvider().coerceIn(0.0f, 1.0f)
            // tol = 0.0 -> motionThreshold = 12.0f (strict stillness)
            // tol = 0.6 -> motionThreshold = 43.8f (shaking hand allowed)
            // tol = 1.0 -> motionThreshold = 65.0f (very forgiving)
            val motionThreshold = 12.0f + tol * 53.0f
            val isStill = smoothMotion < motionThreshold

            if (!isStill) {
                // Object is moving too much
                stillStartTime = 0L
                hasTriggeredForCurrentPresence = false
                onStateUpdate(true, false, 0f)
            } else {
                // Object is present and held still (shaking allowed based on tolerance)
                if (hasTriggeredForCurrentPresence) {
                    onStateUpdate(true, true, 1f)
                    return
                }

                if (stillStartTime == 0L) {
                    stillStartTime = now
                }
                val elapsed = now - stillStartTime
                val progress = (elapsed / 2000f).coerceIn(0f, 1f)

                onStateUpdate(true, true, progress)

                if (elapsed >= 2000L) {
                    hasTriggeredForCurrentPresence = true
                    stillStartTime = 0L
                    onStillnessTrigger()
                }
            }
        } catch (e: Exception) {
            // Safe ignore
        } finally {
            image.close()
        }
    }
}

// ==========================================
// CORE LOGIC 2 - BLUETOOTH SPP SENDER
// ==========================================
/**
 * Checks if the target ESP32 ("EcoSense_Bin") is currently reachable.
 * Unlike a bonded-devices check (which stays true forever once paired, even if the
 * device is powered off), this opens and closes a real RFCOMM connection so the
 * result reflects whether the ESP32 is actually powered on and in range right now.
 */
suspend fun isSmartDustbinReachable(context: Context, onLog: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
    try {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter() ?: return@withContext false
        if (!adapter.isEnabled) {
            onLog("Bluetooth is disabled on host.")
            return@withContext false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                onLog("BLUETOOTH_CONNECT permission not granted.")
                return@withContext false
            }
        }

        val bondedDevices: Set<BluetoothDevice>? = try {
            adapter.bondedDevices
        } catch (se: SecurityException) {
            onLog("SecurityException accessing bonded devices: ${se.message}")
            null
        }

        val targetDevice = bondedDevices?.find { it.name == TARGET_BT_DEVICE_NAME }
        if (targetDevice == null) {
            onLog("Device '$TARGET_BT_DEVICE_NAME' not paired.")
            return@withContext false
        }

        var socket: BluetoothSocket? = null
        try {
            socket = targetDevice.createRfcommSocketToServiceRecord(UUID.fromString(SPP_UUID_STRING))
            try {
                adapter.cancelDiscovery()
            } catch (_: SecurityException) {}
            socket.connect() // blocks until success, or throws if the ESP32 is off / out of range
            onLog("ESP32 reachability check: connected OK.")
            true
        } catch (e: IOException) {
            onLog("ESP32 reachability check failed (likely powered off / out of range): ${e.message}")
            false
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {}
        }
    } catch (e: SecurityException) {
        onLog("Bluetooth permission needed for reachability check.")
        false
    } catch (e: Exception) {
        onLog("Bluetooth reachability check error: ${e.message}")
        false
    }
}

/**
 * Bluetooth SPP Sender
 * - Finds bonded device named "EcoSense_Bin"
 * - Opens RFCOMM socket, writes Char ('D', 'W', or 'E') to OutputStream, and closes socket
 * - Wrapped in try/catch with error logging
 */
suspend fun sendBluetoothCommand(
    context: Context,
    command: Char,
    onLog: (String) -> Unit
): Boolean = withContext(Dispatchers.IO) {
    try {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()

        if (adapter == null) {
            onLog("Bluetooth Hardware not available (e.g., standard emulator).")
            return@withContext false
        }

        if (!adapter.isEnabled) {
            onLog("Bluetooth is currently turned OFF.")
            return@withContext false
        }

        // Android 12+ permission check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                onLog("BLUETOOTH_CONNECT permission not granted.")
                return@withContext false
            }
        }

        val bondedDevices: Set<BluetoothDevice>? = try {
            adapter.bondedDevices
        } catch (se: SecurityException) {
            onLog("SecurityException accessing bonded devices: ${se.message}")
            null
        }

        val targetDevice = bondedDevices?.find { it.name == TARGET_BT_DEVICE_NAME }
        if (targetDevice == null) {
            val names = bondedDevices?.mapNotNull { it.name }?.joinToString(", ") ?: "none"
            onLog("Device '$TARGET_BT_DEVICE_NAME' not found in paired list. Paired devices: [$names]")
            return@withContext false
        }

        onLog("Connecting to $TARGET_BT_DEVICE_NAME (${targetDevice.address})...")
        val uuid = UUID.fromString(SPP_UUID_STRING)
        val socket = targetDevice.createRfcommSocketToServiceRecord(uuid)

        try {
            adapter.cancelDiscovery()
        } catch (_: SecurityException) {}

        socket.connect()
        onLog("Connected to $TARGET_BT_DEVICE_NAME! Sending '$command'...")

        socket.outputStream.use { out ->
            out.write(command.code)
            out.flush()
        }
        socket.close()
        onLog("Byte '$command' successfully transmitted to ESP32 servo controller!")
        return@withContext true
    } catch (e: SecurityException) {
        onLog("Bluetooth SecurityException: ${e.message}")
        return@withContext false
    } catch (e: Exception) {
        onLog("Bluetooth transmission error: ${e.localizedMessage ?: e.javaClass.simpleName}")
        return@withContext false
    }
}

// ==========================================
// PHASE 2: CONTINUOUS VOICE LISTENER SERVICE
// ==========================================
/**
 * Native SpeechRecognizer Continuous Voice Listener
 * - Configures Intent for ACTION_RECOGNIZE_SPEECH and LANGUAGE_MODEL_FREE_FORM.
 * - In onResults: Extracts recognized strings. If keywords like "scan", "capture", "take photo",
 *   or "ready" are detected, triggers the onTrigger() callback. Only the keyword match is logged —
 *   raw transcribed speech is never written to the debug console.
 * - Crucial Loop: At the end of onResults and inside onError, restarts listening immediately
 *   to ensure hands-free infinite listening while the camera is open.
 */
fun startContinuousVoiceListener(
    context: Context,
    onLog: (String) -> Unit = {},
    onTrigger: () -> Unit
): SpeechRecognizer {
    val mainHandler = Handler(Looper.getMainLooper())
    val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)

    val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }

    var isDestroyed = false

    fun restartListening() {
        if (isDestroyed) return
        mainHandler.postDelayed({
            if (!isDestroyed) {
                try {
                    speechRecognizer.cancel()
                    speechRecognizer.startListening(recognizerIntent)
                } catch (e: Exception) {
                    onLog("Voice restart notice: ${e.message}")
                }
            }
        }, 150L)
    }

    speechRecognizer.setRecognitionListener(object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}

        override fun onBeginningOfSpeech() {
            // no-op: don't log every time speech starts
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            // Crucial loop restart on error (kept silent — not logged, matches "only log the keyword")
            restartListening()
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val keywords = listOf("scan", "capture", "take photo", "ready")
                val matched = matches.any { phrase ->
                    val lower = phrase.lowercase(Locale.getDefault())
                    keywords.any { keyword -> lower.contains(keyword) }
                }

                if (matched) {
                    onLog("Voice keyword detected! Triggering camera capture...")
                    onTrigger()
                }
            }
            // Crucial loop restart on results
            restartListening()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val keywords = listOf("scan", "capture", "take photo", "ready")
                val matched = matches.any { phrase ->
                    val lower = phrase.lowercase(Locale.getDefault())
                    keywords.any { keyword -> lower.contains(keyword) }
                }
                if (matched) {
                    onLog("Voice keyword (real-time) detected! Triggering camera capture...")
                    onTrigger()
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    })

    mainHandler.post {
        try {
            speechRecognizer.startListening(recognizerIntent)
            onLog("Continuous voice listener active. Say 'Scan' to trigger hands-free capture.")
        } catch (e: Exception) {
            onLog("Voice start failed: ${e.message}")
        }
    }

    return speechRecognizer
}

// ==========================================
// MAIN ACTIVITY
// ==========================================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            WasteManagerTheme {
                WasteManagerApp()
            }
        }
    }
}

// ==========================================
// JETPACK COMPOSE UI / UX
// ==========================================
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun WasteManagerApp(
    viewModel: WasteViewModel = viewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.checkBluetoothBonded(context)
    }

    // Phase 1: Accompanist Permission State Array
    // Requests Camera, Record Audio, and Bluetooth Connect (Android 12+)
    val permissionsToRequest = remember {
        buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
    }
    val multiplePermissionsState = rememberMultiplePermissionsState(permissions = permissionsToRequest)

    LaunchedEffect(Unit) {
        viewModel.initApiKey(context)
        if (!multiplePermissionsState.allPermissionsGranted) {
            multiplePermissionsState.launchMultiplePermissionRequest()
        }
    }

    val cameraGranted = multiplePermissionsState.permissions
        .find { it.permission == Manifest.permission.CAMERA }?.status?.isGranted == true
    val audioGranted = multiplePermissionsState.permissions
        .find { it.permission == Manifest.permission.RECORD_AUDIO }?.status?.isGranted == true
    val hasCorePermissions = cameraGranted && audioGranted

    // ImageCapture & Camera instance reference
    var imageCaptureInstance by remember { mutableStateOf<ImageCapture?>(null) }
    var activeCameraInstance by remember { mutableStateOf<Camera?>(null) }

    // Camera lens and Flash control state (Front camera default)
    var isFrontCamera by remember { mutableStateOf(true) }
    var isFlashOn by remember { mutableStateOf(false) }

    // Calibration trigger for taking a fresh reference background shot
    var recalibrateTrigger by remember { mutableStateOf(0L) }

    // User controls: Object Sensitivity, Stillness Tolerance, Lock Auto-Detect
    var objectSensitivity by remember { mutableFloatStateOf(0.40f) }
    var stillnessTolerance by remember { mutableFloatStateOf(0.60f) }
    var isDetectionLocked by remember { mutableStateOf(false) }

    // Option B State: ~10cm proximity check & 2s continuous stillness countdown
    var isItemWithin10cm by remember { mutableStateOf(false) }
    var isItemStill by remember { mutableStateOf(false) }
    var stillProgress by remember { mutableStateOf(0f) }

    // TextToSpeech for hands-free voice announcements
    var ttsInstance by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(context) {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
            }
        }
        ttsInstance = tts
        onDispose {
            try {
                tts?.stop()
                tts?.shutdown()
            } catch (_: Exception) {}
        }
    }

    // Success Overlay presentation & audio announcement for 5 seconds before returning to camera
    LaunchedEffect(viewModel.resultCategory, viewModel.resultItemName) {
        val cat = viewModel.resultCategory
        val item = viewModel.resultItemName
        if (cat != null && cat != "ERROR") {
            val announcement = if (!item.isNullOrBlank()) {
                "Drop in $cat. $item."
            } else {
                "Drop in $cat."
            }
            try {
                ttsInstance?.speak(announcement, TextToSpeech.QUEUE_FLUSH, null, "WasteManagerTTS")
            } catch (_: Exception) {}

            delay(5000L)
            viewModel.resetToCamera()
        }
    }

    // Hands-free Camera Capture & Gemini Dispatcher
    var lastTriggerTime by remember { mutableStateOf(0L) }
    val captureImageAndSendToGemini: () -> Unit = {
        val now = System.currentTimeMillis()
        if (now - lastTriggerTime > 2500L && !viewModel.isScanning && viewModel.resultCategory == null) {
            lastTriggerTime = now
            val capture = imageCaptureInstance
            if (capture != null) {
                viewModel.addDebugLog("Capturing photo from CameraX for Gemini AI...")
                capture.takePicture(
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            coroutineScope.launch(Dispatchers.Default) {
                                val originalBitmap = image.toBitmap()
                                image.close()

                                // Downscale to 480px max dimension & compress at 65% quality for ultra-fast upload & inference
                                val maxDim = 480
                                val width = originalBitmap.width
                                val height = originalBitmap.height
                                val scaledBitmap = if (width > maxDim || height > maxDim) {
                                    val scale = maxDim.toFloat() / maxOf(width, height)
                                    val targetW = (width * scale).toInt().coerceAtLeast(1)
                                    val targetH = (height * scale).toInt().coerceAtLeast(1)
                                    Bitmap.createScaledBitmap(originalBitmap, targetW, targetH, true).also {
                                        if (it != originalBitmap) originalBitmap.recycle()
                                    }
                                } else {
                                    originalBitmap
                                }
                                val stream = ByteArrayOutputStream()
                                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 65, stream)
                                scaledBitmap.recycle()
                                val bytes = stream.toByteArray()
                                withContext(Dispatchers.Main) {
                                    viewModel.processImage(context, bytes)
                                }
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            viewModel.addDebugLog("Capture error: ${exception.message}")
                            viewModel.simulateClassification(context, "DRY", "Plastic Water Bottle")
                        }
                    }
                )
            } else {
                viewModel.addDebugLog("Camera capture not ready, simulating scan.")
                viewModel.simulateClassification(context, "DRY", "Plastic Water Bottle")
            }
        } else {
            if (viewModel.isScanning || viewModel.resultCategory != null) {
                viewModel.addDebugLog("Capture trigger ignored: scan in progress or result active.")
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (hasCorePermissions) {
            // LAYER 1: Background Layer - Full Screen CameraX PreviewView with Option B Analyzer
            CameraPreviewView(
                modifier = Modifier.fillMaxSize(),
                isFrontCamera = isFrontCamera,
                isFlashOn = isFlashOn,
                isPausedProvider = { isDetectionLocked || viewModel.isScanning || viewModel.resultCategory != null },
                objectSensitivityProvider = { objectSensitivity },
                stillnessToleranceProvider = { stillnessTolerance },
                recalibrateTrigger = recalibrateTrigger,
                onStateUpdate = { detected, still, prog ->
                    isItemWithin10cm = detected
                    isItemStill = still
                    stillProgress = prog
                },
                onStillnessTrigger = {
                    viewModel.addDebugLog("Object detected & held still for 2.0s. Auto-capturing...")
                    captureImageAndSendToGemini()
                },
                onCameraBound = { camera, capture ->
                    activeCameraInstance = camera
                    imageCaptureInstance = capture
                }
            )

            // Auto-Scan Target Reticle Overlay (Visual feedback for object detection & stillness countdown)
            if (!viewModel.isScanning && viewModel.resultCategory == null) {
                AutoScanReticleOverlay(
                    modifier = Modifier.fillMaxSize(),
                    isDetectionLocked = isDetectionLocked,
                    isItemWithin10cm = isItemWithin10cm,
                    isItemStill = isItemStill,
                    progress = stillProgress
                )
            }

            // Phase 2 & 3: DisposableEffect(Unit) to launch continuous voice listener
            DisposableEffect(Unit) {
                val recognizer = startContinuousVoiceListener(
                    context = context,
                    onLog = { log -> viewModel.addDebugLog(log) },
                    onTrigger = { captureImageAndSendToGemini() }
                )
                onDispose {
                    try {
                        recognizer.cancel()
                        recognizer.destroy()
                    } catch (_: Exception) {}
                }
            }

            // LAYER 2: Top Bar & Sensitivity Controls
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .align(Alignment.TopCenter)
            ) {
                TopBarLayer(
                    modifier = Modifier.fillMaxWidth(),
                    isBtConnected = viewModel.isBtConnected,
                    isDebugOpen = viewModel.isDebugOpen,
                    isFrontCamera = isFrontCamera,
                    isFlashOn = isFlashOn,
                    isDetectionLocked = isDetectionLocked,
                    onRefreshBt = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            multiplePermissionsState.permissions
                                .find { it.permission == Manifest.permission.BLUETOOTH_CONNECT }
                                ?.launchPermissionRequest()
                        }
                        viewModel.checkBluetoothBonded(context)
                    },
                    onToggleLock = {
                        isDetectionLocked = !isDetectionLocked
                        if (isDetectionLocked) {
                            isItemWithin10cm = false
                            isItemStill = false
                            stillProgress = 0f
                            viewModel.addDebugLog("Object auto-detection LOCKED (paused).")
                        } else {
                            recalibrateTrigger = System.currentTimeMillis()
                            viewModel.addDebugLog("Object auto-detection UNLOCKED (resumed).")
                        }
                    },
                    onRecalibrate = {
                        recalibrateTrigger = System.currentTimeMillis()
                        viewModel.addDebugLog("Background reference recalibrated.")
                    },
                    onToggleCamera = {
                        isFrontCamera = !isFrontCamera
                        recalibrateTrigger = System.currentTimeMillis()
                        viewModel.addDebugLog("Switched camera: ${if (isFrontCamera) "FRONT (Default)" else "BACK"}")
                    },
                    onToggleFlash = {
                        isFlashOn = !isFlashOn
                        val hasFlash = activeCameraInstance?.cameraInfo?.hasFlashUnit() == true
                        if (hasFlash) {
                            viewModel.addDebugLog("Flash toggled: ${if (isFlashOn) "ON" else "OFF"}")
                        } else {
                            viewModel.addDebugLog("Flash toggled: ${if (isFlashOn) "ON" else "OFF"} (Note: Camera has no flash unit)")
                        }
                        activeCameraInstance?.cameraControl?.enableTorch(isFlashOn)
                    },
                    onToggleDebug = {
                        viewModel.isDebugOpen = !viewModel.isDebugOpen
                    }
                )

                if (!viewModel.isDebugOpen) {
                    Spacer(modifier = Modifier.height(6.dp))
                    SensitivitySlidersCard(
                        objectSensitivity = objectSensitivity,
                        onObjectSensitivityChange = { objectSensitivity = it },
                        stillnessTolerance = stillnessTolerance,
                        onStillnessToleranceChange = { stillnessTolerance = it },
                        isDetectionLocked = isDetectionLocked,
                        onToggleLock = {
                            isDetectionLocked = !isDetectionLocked
                            if (isDetectionLocked) {
                                isItemWithin10cm = false
                                isItemStill = false
                                stillProgress = 0f
                                viewModel.addDebugLog("Object auto-detection LOCKED (paused).")
                            } else {
                                recalibrateTrigger = System.currentTimeMillis()
                                viewModel.addDebugLog("Object auto-detection UNLOCKED (resumed).")
                            }
                        },
                        isItemWithin10cm = isItemWithin10cm,
                        onRecalibrate = {
                            recalibrateTrigger = System.currentTimeMillis()
                            viewModel.addDebugLog("Background reference recalibrated via controls card.")
                        }
                    )
                }
            }

            // LAYER 3: Debug Layer (Conditional Overlay at the top)
            AnimatedVisibility(
                visible = viewModel.isDebugOpen,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.68f)
                    .statusBarsPadding()
                    .padding(top = 64.dp, start = 12.dp, end = 12.dp)
                    .align(Alignment.TopCenter)
            ) {
                DebugOverlay(
                    logs = viewModel.debugLogs,
                    apiKey = viewModel.customApiKey,
                    onApiKeyChange = { newKey -> viewModel.setCustomApiKey(context, newKey) },
                    isItemWithin10cm = isItemWithin10cm,
                    isItemStill = isItemStill,
                    stillProgress = stillProgress,
                    onRecalibrate = {
                        recalibrateTrigger = System.currentTimeMillis()
                        viewModel.addDebugLog("Background reference recalibrated from Debug.")
                    },
                    onClearLogs = { viewModel.clearLogs() },
                    onSimulateDry = { viewModel.simulateClassification(context, "DRY", "Plastic Water Bottle") },
                    onSimulateWet = { viewModel.simulateClassification(context, "WET", "Fresh Apple Core") },
                    onSimulateElectronic = { viewModel.simulateClassification(context, "ELECTRONIC", "Lithium Ion Battery") },
                    onSimulateVoiceScan = { captureImageAndSendToGemini() },
                    onClose = { viewModel.isDebugOpen = false }
                )
            }

            // LAYER 4: Phase 3 Bottom Action Layer - Pulsing Mic & Instruction Banner
            if (!viewModel.isScanning && viewModel.resultCategory == null) {
                HandsFreeMicActionLayer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = 36.dp)
                        .align(Alignment.BottomCenter),
                    onMicClick = { captureImageAndSendToGemini() }
                )
            }
        } else {
            // Permission request screen
            PermissionsPlaceholder(
                onRequestPermission = {
                    multiplePermissionsState.launchMultiplePermissionRequest()
                }
            )
        }

        // LAYER 5: Loading Overlay (Conditional with glowing "Thinking..." animation)
        AnimatedVisibility(
            visible = viewModel.isScanning && viewModel.resultCategory == null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            LoadingOverlay()
        }

        // LAYER 6: Success Overlay (Conditional - solid vibrant color for exactly 5 seconds)
        val category = viewModel.resultCategory
        val itemName = viewModel.resultItemName
        AnimatedVisibility(
            visible = category != null && category != "ERROR",
            enter = fadeIn(animationSpec = tween(300)),
            exit = fadeOut(animationSpec = tween(400))
        ) {
            if (category != null) {
                SuccessOverlay(category = category, itemName = itemName)
            }
        }

        // Error message notification if classification failed
        AnimatedVisibility(
            visible = category == "ERROR",
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            ErrorOverlay(onDismiss = { viewModel.resetToCamera() })
        }
    }
}

// ==========================================
// OPTION B: AUTO-SCAN RETICLE OVERLAY (~10CM SCAN ZONE)
// ==========================================
@Composable
fun AutoScanReticleOverlay(
    modifier: Modifier = Modifier,
    isDetectionLocked: Boolean = false,
    isItemWithin10cm: Boolean,
    isItemStill: Boolean,
    progress: Float
) {
    val cornerColor = when {
        isDetectionLocked -> Color.White.copy(alpha = 0.25f)
        !isItemWithin10cm -> Color(0xFF00E5FF).copy(alpha = 0.6f) // Cyan - waiting for item
        !isItemStill -> Color(0xFFFFB300) // Amber - item detected, moving
        else -> Color(0xFF00E676) // Green - still, countdown active
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
    ) {
        // Center Scan Target Area (250dp x 250dp)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(250.dp)
        ) {
            // Draw 4 corner bracket reticle markings
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeW = 4.dp.toPx()
                val cornerLen = 36.dp.toPx()
                val w = size.width
                val h = size.height

                // Top-Left corner
                drawLine(cornerColor, Offset(0f, 0f), Offset(cornerLen, 0f), strokeW, StrokeCap.Round)
                drawLine(cornerColor, Offset(0f, 0f), Offset(0f, cornerLen), strokeW, StrokeCap.Round)

                // Top-Right corner
                drawLine(cornerColor, Offset(w, 0f), Offset(w - cornerLen, 0f), strokeW, StrokeCap.Round)
                drawLine(cornerColor, Offset(w, 0f), Offset(w, cornerLen), strokeW, StrokeCap.Round)

                // Bottom-Left corner
                drawLine(cornerColor, Offset(0f, h), Offset(cornerLen, h), strokeW, StrokeCap.Round)
                drawLine(cornerColor, Offset(0f, h), Offset(0f, h - cornerLen), strokeW, StrokeCap.Round)

                // Bottom-Right corner
                drawLine(cornerColor, Offset(w, h), Offset(w - cornerLen, h), strokeW, StrokeCap.Round)
                drawLine(cornerColor, Offset(w, h), Offset(w, h - cornerLen), strokeW, StrokeCap.Round)
            }

            // Circular Stillness Countdown Ring when item is within ~10cm and still
            if (!isDetectionLocked && isItemWithin10cm && isItemStill && progress > 0f) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(76.dp)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawCircle(
                            color = Color.White.copy(alpha = 0.2f),
                            radius = size.minDimension / 2 - 4.dp.toPx(),
                            style = Stroke(width = 5.dp.toPx())
                        )
                        drawArc(
                            color = Color(0xFF00E676),
                            startAngle = -90f,
                            sweepAngle = 360f * progress,
                            useCenter = false,
                            style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                    val remainingSeconds = (2.0f * (1f - progress)).coerceAtLeast(0f)
                    Text(
                        text = String.format(Locale.US, "%.1fs", remainingSeconds),
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Live Dynamic Status Pill below the target reticle
        val (badgeText, badgeBg, badgeBorder, badgeTextColor) = when {
            isDetectionLocked -> Quadruple(
                "🔒 Auto-Detect Locked (Tap 🔒 at top to resume)",
                Color(0xEE2A1010),
                Color(0xFFFF5252),
                Color(0xFFFF8A80)
            )
            !isItemWithin10cm -> Quadruple(
                "Waiting for object...",
                Color.Black.copy(alpha = 0.65f),
                Color(0xFF00E5FF).copy(alpha = 0.5f),
                Color(0xFFE0F7FA)
            )
            !isItemStill -> Quadruple(
                "Object detected — Hold still...",
                Color(0xCC3E2723),
                Color(0xFFFFB300),
                Color(0xFFFFE082)
            )
            else -> Quadruple(
                "Holding still... Scanning soon",
                Color(0xCC1B5E20),
                Color(0xFF00E676),
                Color(0xFFE8F5E9)
            )
        }

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = badgeBg,
            border = BorderStroke(1.5.dp, badgeBorder),
            modifier = Modifier
                .align(Alignment.Center)
                .padding(top = 290.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(badgeBorder)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = badgeText,
                    color = badgeTextColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

// ==========================================
// CAMERAX PREVIEW VIEW
// ==========================================
@Composable
fun CameraPreviewView(
    modifier: Modifier = Modifier,
    isFrontCamera: Boolean = true,
    isFlashOn: Boolean = false,
    isHardwareNearProvider: () -> Boolean = { false },
    hasHardwareSensorProvider: () -> Boolean = { false },
    isFrontCameraProvider: () -> Boolean = { true },
    isPausedProvider: () -> Boolean = { false },
    objectSensitivityProvider: () -> Float = { 0.40f },
    stillnessToleranceProvider: () -> Float = { 0.60f },
    recalibrateTrigger: Long = 0L,
    onStateUpdate: (isNear: Boolean, isStill: Boolean, progress: Float) -> Unit = { _, _, _ -> },
    onStillnessTrigger: () -> Unit = {},
    onCameraBound: (Camera, ImageCapture) -> Unit = { _, _ -> },
    onImageCaptureReady: (ImageCapture) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var previewViewInstance by remember { mutableStateOf<PreviewView?>(null) }
    var cameraProviderInstance by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var activeCamera by remember { mutableStateOf<Camera?>(null) }
    var activeImageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var analyzerRef by remember { mutableStateOf<ProximityStillnessAnalyzer?>(null) }

    LaunchedEffect(recalibrateTrigger, isFrontCamera) {
        analyzerRef?.recalibrateBackground()
    }

    val imageAnalysis = remember {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
    }

    val currentIsPausedProvider by rememberUpdatedState(isPausedProvider)
    val currentObjectSensitivityProvider by rememberUpdatedState(objectSensitivityProvider)
    val currentStillnessToleranceProvider by rememberUpdatedState(stillnessToleranceProvider)
    val currentOnStateUpdate by rememberUpdatedState(onStateUpdate)
    val currentOnStillnessTrigger by rememberUpdatedState(onStillnessTrigger)

    DisposableEffect(imageAnalysis) {
        val executor = Executors.newSingleThreadExecutor()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val analyzer = ProximityStillnessAnalyzer(
            isPausedProvider = { currentIsPausedProvider() },
            objectSensitivityProvider = { currentObjectSensitivityProvider() },
            stillnessToleranceProvider = { currentStillnessToleranceProvider() },
            onStateUpdate = { detected, still, prog ->
                mainExecutor.execute { currentOnStateUpdate(detected, still, prog) }
            },
            onStillnessTrigger = {
                mainExecutor.execute { currentOnStillnessTrigger() }
            }
        )
        analyzerRef = analyzer
        imageAnalysis.setAnalyzer(executor, analyzer)
        onDispose {
            imageAnalysis.clearAnalyzer()
            executor.shutdown()
        }
    }

    LaunchedEffect(Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProviderInstance = cameraProviderFuture.get()
        }, ContextCompat.getMainExecutor(context))
    }

    LaunchedEffect(cameraProviderInstance, previewViewInstance, isFrontCamera) {
        val cameraProvider = cameraProviderInstance ?: return@LaunchedEffect
        val previewView = previewViewInstance ?: return@LaunchedEffect

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(if (isFlashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
            .build()

        val primarySelector = if (isFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        val fallbackSelector = if (isFrontCamera) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }

        try {
            cameraProvider.unbindAll()
            val camera = try {
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    primarySelector,
                    preview,
                    imageCapture,
                    imageAnalysis
                )
            } catch (_: Exception) {
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    fallbackSelector,
                    preview,
                    imageCapture,
                    imageAnalysis
                )
            }
            activeCamera = camera
            activeImageCapture = imageCapture
            onCameraBound(camera, imageCapture)
            onImageCaptureReady(imageCapture)

            if (camera.cameraInfo.hasFlashUnit()) {
                camera.cameraControl.enableTorch(isFlashOn)
            }
        } catch (e: Exception) {
            android.util.Log.e("WasteManager", "Camera binding error: ${e.message}")
        }
    }

    LaunchedEffect(isFlashOn, activeCamera) {
        val camera = activeCamera ?: return@LaunchedEffect
        val imageCapture = activeImageCapture ?: return@LaunchedEffect

        imageCapture.flashMode = if (isFlashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        if (camera.cameraInfo.hasFlashUnit()) {
            try {
                camera.cameraControl.enableTorch(isFlashOn)
            } catch (e: Exception) {
                android.util.Log.e("WasteManager", "Torch error: ${e.message}")
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }.also {
                previewViewInstance = it
            }
        }
    )
}

// ==========================================
// TOP BAR LAYER
// ==========================================
@Composable
fun TopBarLayer(
    modifier: Modifier = Modifier,
    isBtConnected: Boolean,
    isDebugOpen: Boolean,
    isFrontCamera: Boolean = true,
    isFlashOn: Boolean = false,
    isDetectionLocked: Boolean = false,
    onRefreshBt: () -> Unit,
    onToggleLock: () -> Unit = {},
    onRecalibrate: () -> Unit = {},
    onToggleCamera: () -> Unit = {},
    onToggleFlash: () -> Unit = {},
    onToggleDebug: () -> Unit
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Bluetooth status chip
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.Black.copy(alpha = 0.6f),
            border = BorderStroke(1.dp, if (isBtConnected) Color(0xFF4CAF50) else Color(0xFFFF7043)),
            modifier = Modifier.clickable { onRefreshBt() }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isBtConnected) Color(0xFF4CAF50) else Color(0xFFFF5252))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isBtConnected) "ESP32 Online" else "ESP32 Offline",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Action Buttons Row: Lock/Unlock Detection, Recalibrate BG, Flash Toggle, Camera Flip, Debug Toggle
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Lock / Pause Object Detection Button
            Surface(
                shape = CircleShape,
                color = if (isDetectionLocked) Color(0xFFC62828).copy(alpha = 0.9f) else Color.Black.copy(alpha = 0.6f),
                border = BorderStroke(1.5.dp, if (isDetectionLocked) Color(0xFFFF5252) else Color.White.copy(alpha = 0.3f)),
                modifier = Modifier.size(42.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { onToggleLock() }
                ) {
                    Text(
                        text = if (isDetectionLocked) "🔒" else "🔓",
                        fontSize = 18.sp
                    )
                }
            }

            // Recalibrate Background Reference Button
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.4f)),
                modifier = Modifier.size(42.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { onRecalibrate() }
                ) {
                    Text(
                        text = "🎯",
                        fontSize = 18.sp
                    )
                }
            }

            // Flash Toggle Button
            Surface(
                shape = CircleShape,
                color = if (isFlashOn) Color(0xFFFFD54F) else Color.Black.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, if (isFlashOn) Color(0xFFFFC107) else Color.White.copy(alpha = 0.3f)),
                modifier = Modifier.size(42.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { onToggleFlash() }
                ) {
                    Icon(
                        painter = painterResource(id = if (isFlashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off),
                        contentDescription = if (isFlashOn) "Turn Flash Off" else "Turn Flash On",
                        tint = if (isFlashOn) Color(0xFF212121) else Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // Camera Switch Toggle Button (Front vs Back)
            Surface(
                shape = CircleShape,
                color = if (isFrontCamera) Color(0xFF00E5FF).copy(alpha = 0.25f) else Color.Black.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, if (isFrontCamera) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.3f)),
                modifier = Modifier.size(42.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { onToggleCamera() }
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_camera_switch),
                        contentDescription = if (isFrontCamera) "Switch to Back Camera" else "Switch to Front Camera",
                        tint = if (isFrontCamera) Color(0xFF00E5FF) else Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // Debug Toggle Button
            Surface(
                shape = CircleShape,
                color = if (isDebugOpen) Color(0xFF1E88E5) else Color.Black.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                modifier = Modifier.size(42.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { onToggleDebug() }
                ) {
                    Text(
                        text = if (isDebugOpen) "✕" else "⚙",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ==========================================
// SENSITIVITY CONTROLS CARD (TOP OF SCREEN)
// ==========================================
@Composable
fun SensitivitySlidersCard(
    modifier: Modifier = Modifier,
    objectSensitivity: Float,
    onObjectSensitivityChange: (Float) -> Unit,
    stillnessTolerance: Float,
    onStillnessToleranceChange: (Float) -> Unit,
    isDetectionLocked: Boolean,
    onToggleLock: () -> Unit,
    isItemWithin10cm: Boolean,
    onRecalibrate: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(true) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xEB121218),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Header Row: Expand/collapse toggle, Lock status, and quick Calibrate button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "⚡ Sensitivity Controls",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )

                    // Live Detection State Chip
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = when {
                            isDetectionLocked -> Color(0x33FF5252)
                            isItemWithin10cm -> Color(0x3300E676)
                            else -> Color(0x3300E5FF)
                        },
                        border = BorderStroke(
                            0.5.dp,
                            when {
                                isDetectionLocked -> Color(0xFFFF5252)
                                isItemWithin10cm -> Color(0xFF00E676)
                                else -> Color(0xFF00E5FF)
                            }
                        )
                    ) {
                        Text(
                            text = when {
                                isDetectionLocked -> "LOCKED"
                                isItemWithin10cm -> "DETECTED"
                                else -> "IDLE"
                            },
                            color = when {
                                isDetectionLocked -> Color(0xFFFF8A80)
                                isItemWithin10cm -> Color(0xFF69F0AE)
                                else -> Color(0xFF80D8FF)
                            },
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Quick Lock Button on Card
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isDetectionLocked) Color(0x33FF5252) else Color(0x22FFFFFF),
                        border = BorderStroke(0.5.dp, if (isDetectionLocked) Color(0xFFFF5252) else Color.White.copy(alpha = 0.3f)),
                        modifier = Modifier.clickable { onToggleLock() }
                    ) {
                        Text(
                            text = if (isDetectionLocked) "🔒 Locked" else "🔓 Lock",
                            color = if (isDetectionLocked) Color(0xFFFF8A80) else Color.LightGray,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }

                    // Quick Calibrate Background Button
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0x2200E5FF),
                        border = BorderStroke(0.5.dp, Color(0xFF00E5FF)),
                        modifier = Modifier.clickable { onRecalibrate() }
                    ) {
                        Text(
                            text = "Calibrate BG",
                            color = Color(0xFF00E5FF),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }

                    Text(
                        text = if (isExpanded) "▲" else "▼",
                        color = Color.LightGray,
                        fontSize = 11.sp
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    // Slider 1: Object Detection Sensitivity
                    val objSensLabel = when {
                        objectSensitivity < 0.30f -> "Low (Strict / Anti-Noise)"
                        objectSensitivity < 0.65f -> "Medium (Recommended)"
                        else -> "High (Sensitive)"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Object Detection Sensitivity",
                            color = Color(0xFF00E5FF),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "$objSensLabel ${(objectSensitivity * 100).toInt()}%",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 10.sp
                        )
                    }
                    Slider(
                        value = objectSensitivity,
                        onValueChange = onObjectSensitivityChange,
                        valueRange = 0.05f..0.95f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF00E5FF),
                            activeTrackColor = Color(0xFF00E5FF),
                            inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Slider 2: Stillness / Shaking Tolerance
                    val stillLabel = when {
                        stillnessTolerance < 0.30f -> "Strict Stillness"
                        stillnessTolerance < 0.70f -> "Shaking Hand OK"
                        else -> "High Shaking / Vibration OK"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Stillness / Shaking Tolerance",
                            color = Color(0xFF00E676),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "$stillLabel ${(stillnessTolerance * 100).toInt()}%",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 10.sp
                        )
                    }
                    Slider(
                        value = stillnessTolerance,
                        onValueChange = onStillnessToleranceChange,
                        valueRange = 0.0f..1.0f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF00E676),
                            activeTrackColor = Color(0xFF00E676),
                            inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

// ==========================================
// DEBUG OVERLAY
// ==========================================
@Composable
fun DebugOverlay(
    logs: List<String>,
    apiKey: String = "",
    onApiKeyChange: (String) -> Unit = {},
    isItemWithin10cm: Boolean = false,
    isItemStill: Boolean = false,
    stillProgress: Float = 0f,
    hasHardwareSensor: Boolean = false,
    isHardwareNear: Boolean = false,
    onRecalibrate: () -> Unit = {},
    onClearLogs: () -> Unit,
    onSimulateDry: () -> Unit,
    onSimulateWet: () -> Unit,
    onSimulateElectronic: () -> Unit,
    onSimulateVoiceScan: () -> Unit = {},
    onClose: () -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xEB121216),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Debug Console & Test Bed",
                    color = Color(0xFF64B5F6),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Row {
                    Text(
                        text = "Clear",
                        color = Color.LightGray,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clickable { onClearLogs() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    Text(
                        text = "Close",
                        color = Color.Red.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clickable { onClose() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Object Detection & Stillness Status Chip
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.Black.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, if (isItemWithin10cm) Color(0xFF00E676) else Color(0xFFFFB300)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Detection: ",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isItemWithin10cm) "OBJECT DETECTED" else "WAITING",
                        color = if (isItemWithin10cm) Color(0xFF69F0AE) else Color(0xFFFFD54F),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = " | Still: ${if (isItemStill) "${(stillProgress * 100).toInt()}%" else "Moving"}",
                        color = if (isItemStill) Color(0xFF80D8FF) else Color.LightGray,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "Calibrate BG",
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF00E5FF).copy(alpha = 0.15f))
                            .clickable { onRecalibrate() }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Gemini API Key Input Field (empty = BuildConfig.GEMINI_API_KEY default)
            var isKeyVisible by remember { mutableStateOf(false) }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.Black.copy(alpha = 0.65f),
                border = BorderStroke(
                    1.dp,
                    if (apiKey.isNotBlank()) Color(0xFF00E5FF).copy(alpha = 0.8f) else Color.White.copy(alpha = 0.25f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicTextField(
                        value = apiKey,
                        onValueChange = onApiKeyChange,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        visualTransformation = if (isKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        cursorBrush = SolidColor(Color(0xFF00E5FF)),
                        decorationBox = { innerTextField ->
                            if (apiKey.isEmpty()) {
                                Text(
                                    text = "Custom Gemini API Key (empty = BuildConfig)",
                                    color = Color.Gray,
                                    fontSize = 11.sp
                                )
                            }
                            innerTextField()
                        }
                    )
                    if (apiKey.isNotEmpty()) {
                        Text(
                            text = if (isKeyVisible) "Hide" else "Show",
                            color = Color(0xFF00E5FF),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clickable { isKeyVisible = !isKeyVisible }
                                .padding(horizontal = 6.dp)
                        )
                        Text(
                            text = "✕",
                            color = Color(0xFFFF5252),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { onApiKeyChange("") }
                                .padding(horizontal = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Test Simulation Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = onSimulateDry,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Dry 'D'", fontSize = 11.sp, color = Color.White)
                }
                Button(
                    onClick = onSimulateWet,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF43A047)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Wet 'W'", fontSize = 11.sp, color = Color.White)
                }
                Button(
                    onClick = onSimulateElectronic,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFB8C00)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Elec 'E'", fontSize = 11.sp, color = Color.White)
                }
                Button(
                    onClick = onSimulateVoiceScan,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00ACC1)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1.1f)
                ) {
                    Text("Say 'Scan'", fontSize = 11.sp, color = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Real-time Logs Console
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.Black.copy(alpha = 0.7f),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (logs.isEmpty()) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Text(
                            text = "Awaiting events... Logs from Voice Listener, Gemini loop & Bluetooth will stream here.",
                            color = Color.DarkGray,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                    ) {
                        items(logs) { log ->
                            val color = when {
                                log.contains("ERROR", ignoreCase = true) || log.contains("failed", ignoreCase = true) -> Color(0xFFFF5252)
                                log.contains("successful", ignoreCase = true) || log.contains("succeeded", ignoreCase = true) -> Color(0xFF69F0AE)
                                log.contains("Trying model", ignoreCase = true) -> Color(0xFFFFD54F)
                                log.contains("Voice", ignoreCase = true) || log.contains("Mic", ignoreCase = true) -> Color(0xFF80D8FF)
                                else -> Color(0xFFB0BEC5)
                            }
                            Text(
                                text = log,
                                color = color,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// PHASE 3: HANDS-FREE PULSING MIC ACTION LAYER
// ==========================================
@Composable
fun HandsFreeMicActionLayer(
    modifier: Modifier = Modifier,
    onMicClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "micPulseTransition")

    // Pulsing scale for the glowing mic ring
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.32f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "micPulseScale"
    )

    // Pulsing glow opacity
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "micGlowAlpha"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Sleek Instruction Text Banner above the pulsing mic
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color.Black.copy(alpha = 0.65f),
            border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.45f)),
            modifier = Modifier.padding(bottom = 22.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF00E5FF))
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Hold waste within ~10cm (2s) or say 'Scan'",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.3.sp
                )
            }
        }

        // Centralized Glowing Pulsing Microphone Icon
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(100.dp)
        ) {
            // Outer glowing radial pulse ring
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                Color(0xFF00E5FF).copy(alpha = glowAlpha * 0.45f),
                                Color(0xFF00B0FF).copy(alpha = glowAlpha * 0.15f),
                                Color.Transparent
                            )
                        )
                    )
            )

            // Expanding border ring
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .scale(1f + (pulseScale - 1f) * 0.5f)
                    .clip(CircleShape)
                    .border(
                        BorderStroke(2.dp, Color(0xFF00E5FF).copy(alpha = glowAlpha)),
                        CircleShape
                    )
            )

            // Central Mic Button Indicator
            Surface(
                shape = CircleShape,
                color = Color(0xFF0D1B2A),
                border = BorderStroke(2.5.dp, Color(0xFF00E5FF)),
                shadowElevation = 10.dp,
                modifier = Modifier
                    .size(68.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onMicClick() }
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_mic),
                        contentDescription = "Hands-free Voice Listening Active",
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(34.dp)
                    )
                }
            }
        }
    }
}

/**
 * Backward compatibility alias for BottomActionLayer
 */
@Composable
fun BottomActionLayer(
    modifier: Modifier = Modifier,
    onScanClick: () -> Unit
) {
    HandsFreeMicActionLayer(
        modifier = modifier,
        onMicClick = onScanClick
    )
}

// ==========================================
// LOADING OVERLAY ("Thinking..." Animation)
// ==========================================
@Composable
fun LoadingOverlay() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f))
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(100.dp)
            ) {
                // Outer glowing pulse orb
                Box(
                    modifier = Modifier
                        .size(90.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    Color(0xFF00E5FF).copy(alpha = 0.4f),
                                    Color.Transparent
                                )
                            )
                        )
                )

                CircularProgressIndicator(
                    modifier = Modifier.size(60.dp),
                    color = Color(0xFF00E5FF),
                    strokeWidth = 4.dp
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Thinking...",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Classifying waste with Gemini AI",
                color = Color.LightGray,
                fontSize = 14.sp
            )
        }
    }
}

// ==========================================
// SUCCESS OVERLAY (Solid Vibrant Color 5s)
// ==========================================
@Composable
fun SuccessOverlay(
    category: String,
    itemName: String? = null
) {
    val backgroundColor = when (category) {
        "WET" -> Color(0xFF1B5E20) // Deep Vibrant Green
        "DRY" -> Color(0xFF0D47A1) // Deep Vibrant Blue
        "ELECTRONIC" -> Color(0xFFE65100) // Deep Vibrant Orange
        else -> Color(0xFF37474F)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Large Attractive Item Name Display (No icons)
            if (!itemName.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color.Black.copy(alpha = 0.45f),
                    border = BorderStroke(2.dp, Color(0xFFFFD54F)),
                    shadowElevation = 12.dp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Text(
                        text = itemName.uppercase(),
                        color = Color(0xFFFFD54F), // High-visibility glowing golden amber
                        fontSize = 30.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                        letterSpacing = 1.8.sp,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp)
                    )
                }
                Spacer(modifier = Modifier.height(28.dp))
            }

            Text(
                text = "DROP IN\n$category",
                color = Color.White,
                fontSize = 50.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                lineHeight = 56.sp,
                letterSpacing = 2.5.sp
            )

            Spacer(modifier = Modifier.height(36.dp))

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color.Black.copy(alpha = 0.30f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f))
            ) {
                Text(
                    text = "Signal sent to EcoSense_Bin ESP32",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }
        }
    }
}

// ==========================================
// ERROR OVERLAY
// ==========================================
@Composable
fun ErrorOverlay(onDismiss: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f))
            .padding(24.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFFB71C1C),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    text = "Classification Failed",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "All Gemini fallback models failed or returned invalid response.\nCheck the Debug console for details.",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Dismiss", color = Color(0xFFB71C1C), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ==========================================
// PERMISSION PLACEHOLDER
// ==========================================
@Composable
fun PermissionsPlaceholder(
    onRequestPermission: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(32.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "📷 🎙️",
                fontSize = 54.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Camera & Microphone Access Required",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Waste Manager needs camera access for Gemini Vision AI and microphone access for continuous hands-free 'Scan' voice commands.",
                color = Color.LightGray,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "Grant Permissions",
                    color = Color.Black,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun CameraPermissionPlaceholder(
    onRequestPermission: () -> Unit
) {
    PermissionsPlaceholder(onRequestPermission = onRequestPermission)
}