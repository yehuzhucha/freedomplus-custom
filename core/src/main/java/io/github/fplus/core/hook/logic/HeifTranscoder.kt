package io.github.fplus.core.hook.logic

import android.graphics.Bitmap
import com.freegang.ktutils.log.KLogCat
import java.io.File
import java.lang.reflect.Method

/**
 * 把抖音的 VVIC / HEIC 图片就地转成 JPEG（解决"下载下来打不开"）。
 *
 * 抖音部分图文的展示地址下发的是 HEIF 容器：`ftyp` brand `vvic`（VVC/BVC2 编码）
 * 或 `heic`（HEVC）。手机相册、Windows、图床都不认这类编码，存成 .jpg/.heic 都打不开。
 *
 * 不引入 ffmpeg、不写 native：抖音进程里本来就有解码器，且 JNI 桥注册在**未混淆**的类上——
 * `libbdheif.so` 的 JNI_OnLoad 把 `libttheif_dec.so`（`heif_decode_to_rgba*`）与
 * `libbyteVC2dec.so`（BVC2/VVC）注册到 `com.bytedance.fresco.nativeheif.Heif`。
 * 应用自己的解码路径（`X.C3008001eUr.LIZ`）就是：
 * ```
 * HeifData d = Heif.toRgba(bytes, ...15 参...);          // data = RGBA 像素
 * Bitmap bmp = d.newBitmap(Bitmap.Config.ARGB_8888);
 * ```
 * 这里照抄这条链路，把结果 JPEG 写回**同一个文件路径**：文件名/扩展名都保持原样，
 * 不加新的命名逻辑，也不动调用方后续的上传/媒体库刷新。
 */
object HeifTranscoder {
    private const val TAG = "HeifTranscoder"
    private const val HEIF_CLASS = "com.bytedance.fresco.nativeheif.Heif"
    private const val HEIF_DATA_CLASS = "com.bytedance.fresco.nativeheif.HeifData"

    private var toRgba: Method? = null
    private var newBitmap: Method? = null

    /** ISO-BMFF 判断：`[4..7] == "ftyp"`。普通 JPEG/PNG 直接跳过，不做无谓的 native 调用。 */
    private fun isIsoBmff(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        return bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte()
    }

    @Synchronized
    private fun resolve(loader: ClassLoader?): Boolean {
        if (toRgba != null && newBitmap != null) return true
        return runCatching {
            val heif = Class.forName(HEIF_CLASS, true, loader ?: javaClass.classLoader)
            toRgba = heif.declaredMethods.firstOrNull {
                it.name == "toRgba" && it.parameterTypes.size == 15 &&
                    it.parameterTypes[0] == ByteArray::class.java
            }?.apply { isAccessible = true }

            val heifData = Class.forName(HEIF_DATA_CLASS, true, loader ?: javaClass.classLoader)
            newBitmap = heifData.declaredMethods.firstOrNull {
                it.name == "newBitmap" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Bitmap.Config::class.java
            }?.apply { isAccessible = true }

            toRgba != null && newBitmap != null
        }.getOrDefault(false)
    }

    /**
     * 就地转码：成功则把 [file] 覆盖成 JPEG 并返回 true；
     * 非 HEIF、解码失败或方法找不到都返回 false（原文件原样保留，调用方无需处理返回值）。
     */
    fun transcodeInPlace(file: File, quality: Int = 95): Boolean {
        if (!file.isFile || file.length() <= 0L) return false

        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return false
        if (!isIsoBmff(bytes)) return false
        if (!resolve(null)) {
            KLogCat.tagI(TAG, "未找到 Heif.toRgba / HeifData.newBitmap，跳过转码")
            return false
        }

        val jpeg = runCatching {
            // 参数顺序见 Heif.toRgba 的 15 参重载：
            // bytes, bool, 长度, bool, int, bool, int, bool, int, sampleSize, l, t, h, w, bool
            val args = arrayOf<Any?>(
                bytes, false, bytes.size, false, 0, false, 0, false, 0,
                1, 0, 0, 0, 0, false,
            )
            val data = toRgba!!.invoke(null, *args) ?: return@runCatching null
            val width = runCatching { data.javaClass.getField("width").getInt(data) }.getOrDefault(0)
            val height = runCatching { data.javaClass.getField("height").getInt(data) }.getOrDefault(0)
            if (width <= 0 || height <= 0) return@runCatching null

            val bitmap = newBitmap!!.invoke(data, Bitmap.Config.ARGB_8888) as? Bitmap
                ?: return@runCatching null

            // 先写临时文件再覆盖，避免解码/压缩中途失败留下坏图
            val tmp = File(file.parentFile, "${file.name}.tmp")
            val ok = runCatching {
                tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            }.getOrDefault(false)
            bitmap.recycle()

            if (!ok || tmp.length() <= 0L) {
                tmp.delete()
                null
            } else {
                tmp to "${width}x$height"
            }
        }.getOrNull() ?: return false

        val (tmp, size) = jpeg
        val replaced = runCatching {
            file.delete()
            tmp.renameTo(file)
        }.getOrDefault(false)
        if (!replaced) {
            tmp.delete()
            return false
        }

        KLogCat.tagI(TAG, "已转码: ${file.name} ($size)")
        return true
    }
}
