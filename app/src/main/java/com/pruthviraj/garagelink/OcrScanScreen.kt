package com.pruthviraj.garagelink

import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

@Serializable
data class ScanUsageInfo(val limit: Int? = null, val used: Int = 0, val unlimited: Boolean = false)

@Composable
fun OcrScanScreen(garageId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) { if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA) }

    var isCapturing by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var structuredRows by remember { mutableStateOf<List<ParsedInvoiceRow>?>(null) }
    var scanUsage by remember { mutableStateOf<ScanUsageInfo?>(null) }
    val imageCapture = remember { ImageCapture.Builder().build() }

    LaunchedEffect(Unit) {
        scanUsage = try {
            SupabaseClientProvider.client.postgrest
                .rpc("get_invoice_scan_usage", buildJsonObject { put("p_garage_id", garageId) })
                .decodeAs<ScanUsageInfo>()
        } catch (e: Exception) { null }
    }

    if (structuredRows != null) {
        InvoiceReviewScreen(
            garageId = garageId,
            rawText = "",
            structuredRows = structuredRows,
            onDone = onBack
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Text("< Back") }
        Text(
            "Scan Invoice",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Text(
            "Point the camera at a supplier invoice - matched parts will restock automatically",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
        )
        scanUsage?.let { usage ->
            Text(
                if (usage.unlimited) "Unlimited AI scans on your plan"
                else "${usage.used} of ${usage.limit} AI scans used this month",
                style = MaterialTheme.typography.labelSmall,
                color = if (!usage.unlimited && usage.used >= (usage.limit ?: 0))
                    MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.height(8.dp))

        if (!hasCameraPermission) {
            Text("Camera permission needed", modifier = Modifier.padding(24.dp))
        } else {
            Box(modifier = Modifier.weight(1f)) {
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                            cameraProvider.unbindAll()
                            val cameraSelector = when {
                                cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                                cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                                else -> CameraSelector.Builder().addCameraFilter { it }.build()
                            }
                            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture)
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            scanError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
            }

            Button(
                onClick = {
                    isCapturing = true
                    scanError = null
                    val photoFile = File(context.cacheDir, "invoice_${System.currentTimeMillis()}.jpg")
                    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
                    imageCapture.takePicture(
                        outputOptions,
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                isCapturing = false
                                isScanning = true
                                scope.launch {
                                    when (val result = InvoiceScanApi.scanInvoice(photoFile, garageId)) {
                                        is ScanInvoiceResult.Success -> {
                                            isScanning = false
                                            structuredRows = result.items.map { item ->
                                                ParsedInvoiceRow(
                                                    rawText = item.name,
                                                    itemNameGuess = item.name,
                                                    quantityGuess = item.quantity,
                                                    priceGuess = item.price
                                                )
                                            }
                                        }
                                        is ScanInvoiceResult.LimitReached -> {
                                            isScanning = false
                                            scanError = "You've reached your plan's monthly scan limit (${result.limit ?: 5}). Upgrade your plan to scan more invoices."
                                        }
                                        is ScanInvoiceResult.Failure -> {
                                            isScanning = false
                                            scanError = result.message
                                        }
                                    }
                                }
                            }
                            override fun onError(exc: ImageCaptureException) {
                                isCapturing = false
                                scanError = "Capture failed: ${exc.message}"
                            }
                        }
                    )
                },
                enabled = !isCapturing && !isScanning,
                modifier = Modifier.fillMaxWidth().padding(24.dp)
            ) {
                Text(
                    when {
                        isScanning -> "Reading invoice..."
                        isCapturing -> "Capturing..."
                        else -> "Capture & Scan"
                    }
                )
            }
        }
    }
}