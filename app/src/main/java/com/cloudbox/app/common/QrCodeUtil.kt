package com.cloudbox.app.common

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

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
}
