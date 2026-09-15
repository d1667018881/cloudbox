package com.cloudbox.app.common

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.io.FileOutputStream

/** ZXing 二维码生成工具（BitMatrix → Bitmap），用于分享链接二维码 */
object QrCodeUtil {

    fun generate(content: String, sizePx: Int = 512): Bitmap? {
        return runCatching {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 1
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            // #28 修复：一次性填充 IntArray 再创建 Bitmap（旧实现逐像素 setPixel，
            // 512×512 = 26 万次 JNI 调用，慢一个数量级）
            val pixels = IntArray(sizePx * sizePx)
            for (x in 0 until sizePx) {
                for (y in 0 until sizePx) {
                    pixels[y * sizePx + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.RGB_565)
        }.getOrNull()
    }

    /**
     * 把二维码保存到相册（Pictures/CloudBox 下）。返回给用户看的结果文案。
     *
     * 为什么要走 MediaStore 而不是直接写文件：
     * targetSdk 34（Android 10+ 的分区存储）下，App 不能随便往公共目录写文件，
     * 必须通过 MediaStore 声明归属。直接 `File(Environment.getExternalStorageDirectory()…)`
     * 在 Android 10+ 上会抛 EACCES（除非申请 MANAGE_EXTERNAL_STORAGE，那是另一个坑）。
     *
     * Android 9 及以下（API < 29）MediaStore 的 RELATIVE_PATH 不可用，
     * 退回直接写公共 Pictures 目录 + 通知相册扫描。
     */
    fun saveToGallery(context: Context, bitmap: Bitmap): String {
        val fileName = "cloudbox_qr_${System.currentTimeMillis()}.png"
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/CloudBox"
                    )
                }
                val uri: Uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                ) ?: error("无法创建相册条目")
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                } ?: error("无法写入图片")
                "已保存到相册：Pictures/CloudBox"
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "CloudBox"
                ).apply { mkdirs() }
                val file = File(dir, fileName)
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                // 让系统相册立刻看到这张图
                android.media.MediaScannerConnection.scanFile(
                    context, arrayOf(file.absolutePath), arrayOf("image/png"), null
                )
                "已保存到相册：${file.absolutePath}"
            }
        }.getOrElse { e ->
            "保存失败：${e.message ?: e.javaClass.simpleName}"
        }
    }
}
