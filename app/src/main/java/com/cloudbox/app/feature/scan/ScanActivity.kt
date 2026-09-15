package com.cloudbox.app.feature.scan

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.cloudbox.app.ui.theme.CloudBoxTheme
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * 二维码扫描（对齐原版 `qr.lua`）。
 *
 * ─────────────────────────────────────────────────────────────
 * 为什么要这个功能
 * ─────────────────────────────────────────────────────────────
 * 原版分享页可以生成二维码，扫码是它的对偶能力：把别人发来的二维码
 * （截图、纸面、另一个屏幕）扫成链接，直接进解析。
 * 少了它，用户只能"看着二维码发呆"。
 *
 * ─────────────────────────────────────────────────────────────
 * 为什么用 MLKit 而不是 ZXing 的扫描端
 * ─────────────────────────────────────────────────────────────
 * ZXing 的 `core` 只做编解码，扫描端要自己接相机、自己处理 YUV、
 * 自己做预览变换（坑极多）。MLKit 的 `barcode-scanning` 是**离线**模型
 * （不需要 Google Play 服务），配合 CameraX 的 `ImageAnalysis` 只需十几行。
 *
 * 结果通过 `Intent` 回传（`EXTRA_RESULT`），比 EventBus / 全局单例更可控 ——
 * 只有一个调用方（解析页）。
 */
class ScanActivity : ComponentActivity() {

    companion object {
        const val EXTRA_RESULT = "extra_scan_result"
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
    }

    private var hasPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)

        setContent {
            CloudBoxTheme(darkMode = "system") {
                Surface(Modifier.fillMaxSize()) {
                    ScanPage(
                        hasPermission = hasPermission,
                        onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        onResult = { text ->
                            // 扫到即回传并结束 —— 不在这里做任何业务判断，
                            // 是不是蓝奏云链接交给解析页的 extractShareUrl 决定。
                            setResult(
                                RESULT_OK,
                                android.content.Intent().putExtra(EXTRA_RESULT, text)
                            )
                            finish()
                        },
                        onBack = { finish() }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanPage(
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onResult: (String) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("扫描二维码") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (!hasPermission) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("需要相机权限才能扫描二维码", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRequestPermission) { Text("授予权限") }
                }
            } else {
                CameraPreview(onResult = onResult)
                Text(
                    "将二维码放入取景框内",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(24.dp)
                )
            }
        }
    }
}

/**
 * CameraX 预览 + 逐帧交给 MLKit 识别。
 *
 * ⚠️ 关键点
 * 1. `ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST` —— 否则帧会堆积，越跑越卡。
 * 2. 分析器里必须 `imageProxy.close()`，漏掉几帧就彻底卡住（这是 CameraX 最经典的坑）。
 * 3. **扫到第一个结果就停**：用 `done` 标志位挡住后续帧。不停的话，
 *    `onResult` 会被连续调用几十次（每帧一次），触发多次 finish/setResult。
 * 4. 用单线程 executor：MLKit 识别本身有内部线程池，外层再并发只会抢资源。
 */
@Composable
private fun CameraPreview(onResult: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember { BarcodeScanning.getClient() }
    var done by remember { mutableStateOf(false) }

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }

    DisposableEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { a ->
                    a.setAnalyzer(executor) { imageProxy ->
                        if (done) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        analyzeFrame(imageProxy, scanner) { text ->
                            if (!done) {
                                done = true
                                onResult(text)
                            }
                        }
                    }
                }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            runCatching { scanner.close() }
            executor.shutdown()
        }
    }

    AndroidView({ previewView }, Modifier.fillMaxSize())
}

/**
 * 识别单帧。所有异常路径都必须关闭 [imageProxy]，否则相机会静默停摆。
 */
private fun analyzeFrame(
    imageProxy: ImageProxy,
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    onFound: (String) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }
    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    scanner.process(image)
        .addOnSuccessListener { barcodes ->
            // 只取"有可读文本"的第一个：QR 码可能是 URL / 纯文本 / 联系人等，
            // 这里不挑格式，统一把 rawValue 交出去，由解析页判断。
            barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }
                ?.let { barcode ->
                    barcode.rawValue?.takeIf { it.isNotBlank() }?.let(onFound)
                }
        }
        .addOnFailureListener {
            // 单帧识别失败是常态（模糊、过曝、无码），静默忽略继续下一帧
        }
        .addOnCompleteListener { imageProxy.close() }
}
