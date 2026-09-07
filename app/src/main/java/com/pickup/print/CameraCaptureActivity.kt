package com.pickup.print

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pickup.print.ui.theme.PickupTheme
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import java.io.File

class CameraCaptureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "无相机权限", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContent {
            PickupTheme {
                CameraCaptureScreen(
                    fromQuick = intent.getBooleanExtra(EXTRA_FROM_QUICK, false),
                    onCaptured = { file ->
                        if (intent.getBooleanExtra(EXTRA_FROM_QUICK, false)) {
                            startActivity(
                                Intent(this, MainActivity::class.java).apply {
                                    addFlags(
                                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                                    )
                                    putExtra(EXTRA_BITMAP_PATH, file.absolutePath)
                                    putExtra(MainActivity.EXTRA_FROM_CAMERA_QUICK, true)
                                }
                            )
                            finish()
                        } else {
                            setResult(RESULT_OK, Intent().putExtra(EXTRA_BITMAP_PATH, file.absolutePath))
                            finish()
                        }
                    },
                    onError = { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_BITMAP_PATH = "bitmap_path"
        const val EXTRA_FROM_QUICK = "from_quick"
    }
}

@Composable
fun CameraCaptureScreen(
    fromQuick: Boolean,
    onCaptured: (File) -> Unit,
    onError: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    val previewView = remember { PreviewView(context) }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        val executor = ContextCompat.getMainExecutor(context)
        val listener = Runnable {
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            imageCapture = capture
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                capture
            )
        }
        future.addListener(listener, executor)
        onDispose {
            try {
                future.get().unbindAll()
            } catch (_: Exception) {
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        TextButton(
            text = if (fromQuick) "拍照识别" else "拍照",
            onClick = {
                val capture = imageCapture ?: return@TextButton
                val file = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
                val options = ImageCapture.OutputFileOptions.Builder(file).build()
                capture.takePicture(
                    options,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            onCaptured(file)
                        }
                        override fun onError(exception: ImageCaptureException) {
                            onError("拍照失败：${exception.message}")
                        }
                    }
                )
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp),
            colors = ButtonDefaults.textButtonColorsPrimary()
        )
    }
}
