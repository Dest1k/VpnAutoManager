package com.vpnauto.manager.service

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Минималистичный QR-код генератор на основе zxing без зависимости от библиотеки.
 * Генерирует Bitmap с QR-кодом для настройки прокси.
 *
 * Содержимое QR: socks5://host:port  или  http://host:port
 */
object QrCodeGenerator {

    /** Генерирует строку с настройками прокси для QR */
    fun buildProxyUrl(host: String, port: Int, type: String = "socks5"): String =
        "$type://$host:$port"

    /**
     * Простой текстовый QR для отображения — используем ZXing через отдельную зависимость.
     * Если ZXing недоступен, возвращаем null и показываем текст.
     */
    fun generate(content: String, size: Int = 512): Bitmap? {
        return try {
            val writerClass = Class.forName("com.google.zxing.qrcode.QRCodeWriter")
            val writer = writerClass.newInstance()
            val encodeMethod = writerClass.getMethod("encode",
                String::class.java,
                Class.forName("com.google.zxing.BarcodeFormat"),
                Int::class.java, Int::class.java)
            val formatClass = Class.forName("com.google.zxing.BarcodeFormat")
            val qrFormat = formatClass.getField("QR_CODE").get(null)
            val bitMatrix = encodeMethod.invoke(writer, content, qrFormat, size, size)

            val widthMethod = bitMatrix.javaClass.getMethod("getWidth")
            val heightMethod = bitMatrix.javaClass.getMethod("getHeight")
            val getMethod = bitMatrix.javaClass.getMethod("get", Int::class.java, Int::class.java)
            val w = widthMethod.invoke(bitMatrix) as Int
            val h = heightMethod.invoke(bitMatrix) as Int

            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    pixels[y * w + x] = if (getMethod.invoke(bitMatrix, x, y) as Boolean)
                        Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(pixels, w, h, Bitmap.Config.RGB_565)
        } catch (e: Exception) {
            // ZXing не подключён — вернём null, UI покажет текст
            null
        }
    }
}
