package io.github.fplus.core.hook.logic

import com.freegang.extension.format
import com.freegang.extension.getStringOrDefault
import com.freegang.extension.parseJSON
import com.freegang.ktutils.log.KLogCat
import com.freegang.ktutils.net.KHttpUtils
import com.ss.android.ugc.aweme.feed.model.Aweme
import com.ss.android.ugc.aweme.feed.model.Video
import com.ss.ugc.aweme.ImageUrlStruct
import io.github.xpler.core.XplerLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.reflect.Field
import java.util.Date

fun Aweme.createDate(): String {
    return Date(this.createTime * 1000).format()
}

suspend fun Aweme.cityInfo(): String {
    // 36.8.0：city 字段存在但很多视频为空，先判空再请求，避免 code=null 查出空串
    val code = this.city?.trim().orEmpty()
    if (code.isEmpty()) return "未提供（该视频无城市信息）"

    return withContext(Dispatchers.IO) {
        runCatching {
            // api.ip33.com 已失效（DNS 都解析不出来），改走自建中转
            val result = KHttpUtils.get("https://www.ccios.de/area/?code=$code")
            val areaJson = result.parseJSON()
            val province = areaJson.getStringOrDefault("province")
            val city = areaJson.getStringOrDefault("city")
            val county = areaJson.getStringOrDefault("county")

            "$province $city $county".trim().ifEmpty { "未知（code=$code）" }
        }.getOrElse {
            XplerLog.e(it)
            "获取失败"
        }
    }
}

/**
 * 按"原始 JSON 键"从 [Video] 上取播放地址列表。
 *
 * 背景（40.6.0 字段混淆）：
 * `Video` 的播放地址字段被整体混淆成单字母 ——
 * `playAddr` -> `a`、`playAddrH265` -> `b`、`h264PlayAddr` -> `d`。
 * 任何按原字段名的访问（编译期字段引用 / 反射）都会抛
 * `NoSuchFieldError: No instance field playAddr ...`，导致下载入口整条链路崩溃。
 *
 * 稳定锚点是 Gson 的 `@SerializedName` 注解：注解值保存着原始 JSON 键
 * （`play_addr` / `play_addr_265` / `play_addr_h264`），字段名怎么混淆都不受影响；
 * 命中的字段值仍是 `UrlModel` 子类，而 `UrlModel.urlList` 未被混淆，可直接反射读取。
 *
 * 查找顺序：
 *   1. `@SerializedName` 注解值匹配（40.6.0 及以后）；
 *   2. 原字段名匹配（字段未混淆的旧版本）；
 *   3. 兜底：声明类型含 `VideoUrlModel` 且 urlList 非空的字段。
 *
 * @param jsonKeys    按优先级排列的原始 JSON 键
 * @param legacyNames 按优先级排列的旧版本字段名
 */
fun Video.findVideoUrlList(
    jsonKeys: List<String>,
    legacyNames: List<String> = emptyList(),
): List<String>? {
    val fields = allFields()

    for (key in jsonKeys) {
        val field = fields.firstOrNull { serializedNameOf(it) == key } ?: continue
        val urlList = urlListOf(field, this)?.takeIf { it.isNotEmpty() } ?: continue
        return urlList
    }

    for (name in legacyNames) {
        val field = fields.firstOrNull { it.name == name } ?: continue
        val urlList = urlListOf(field, this)?.takeIf { it.isNotEmpty() } ?: continue
        return urlList
    }

    // 兜底：字段结构再次变化时，只认 VideoUrlModel 类型的字段，
    // 避免误把 cover / background 等其它 UrlModel 当成播放地址。
    val fallback = fields.firstNotNullOfOrNull { field ->
        if (!field.type.name.contains("VideoUrlModel")) return@firstNotNullOfOrNull null
        urlListOf(field, this)?.takeIf { it.isNotEmpty() }
    }

    if (fallback == null) {
        KLogCat.tagI("VideoUrl", "未命中播放地址字段, jsonKeys=$jsonKeys, fields=${fields.size}")
    }
    return fallback
}

/** [Video] 及其父类链上的全部字段（字段名被混淆，只能按注解/类型筛选） */
private fun Video.allFields(): List<Field> {
    val fields = mutableListOf<Field>()
    var clazz: Class<*>? = javaClass
    while (clazz != null && clazz != Any::class.java) {
        val current = clazz
        runCatching { fields += current.declaredFields }
        clazz = current.superclass
    }
    return fields
}

/** 读字段上的 `@SerializedName` 值；按注解类名比对，不引入编译期 gson 依赖 */
private fun serializedNameOf(field: Field): String? {
    return runCatching {
        field.annotations
            .firstOrNull { it.annotationClass.java.name == "com.google.gson.annotations.SerializedName" }
            ?.let { it.annotationClass.java.getMethod("value").invoke(it) as? String }
    }.getOrNull()
}

/** 读取字段值，并从其 UrlModel 中取出 urlList（`urlList` 字段名未被混淆） */
private fun urlListOf(field: Field, target: Any): List<String>? {
    return runCatching {
        field.isAccessible = true
        val value = field.get(target)
        if (value == null) {
            null
        } else {
            @Suppress("UNCHECKED_CAST")
            value.javaClass.getField("urlList").get(value) as? List<String>
        }
    }.getOrNull()
}

fun Aweme.getH265VideoUrlList(): List<String> {
    val video = video ?: return emptyList()
    return video.findVideoUrlList(
        jsonKeys = listOf("play_addr_265", "play_addr_h264", "play_addr"),
        legacyNames = listOf("playAddrH265", "h264PlayAddr", "playAddr"),
    ) ?: emptyList()
}

fun Aweme.getH264VideoUrlList(): List<String> {
    val video = video ?: return emptyList()
    return video.findVideoUrlList(
        jsonKeys = listOf("play_addr_h264", "play_addr"),
        legacyNames = listOf("h264PlayAddr", "playAddr"),
    ) ?: emptyList()
}

fun Aweme.getAutoVideoUrlList(): List<String> {
    val video = video ?: return emptyList()
    return video.findVideoUrlList(
        jsonKeys = listOf("play_addr", "play_addr_h264", "play_addr_265"),
        legacyNames = listOf("playAddr", "h264PlayAddr", "playAddrH265"),
    ) ?: emptyList()
}

fun Aweme.getImageUrlList(): List<ImageUrlStruct> {
    return images ?: return emptyList()
}

fun Aweme.getMusicUrlList(): List<String> {
    val music = music ?: return emptyList()
    return music.playUrl?.urlList ?: emptyList()
}

fun Aweme.sortString(): String {
    val desc = "$desc".replace(Regex("\\s"), "")
    return "awemeType=${awemeType}, desc=$desc"
}

/** geohash → 经纬度（纯计算，不需要网络） */
fun decodeGeohash(geohash: String): Pair<Double, Double>? {
    val base32 = "0123456789bcdefghjkmnpqrstuvwxyz"
    var latMin = -90.0; var latMax = 90.0
    var lonMin = -180.0; var lonMax = 180.0
    var even = true
    for (ch in geohash.lowercase()) {
        val idx = base32.indexOf(ch)
        if (idx < 0) return null
        for (bit in 4 downTo 0) {
            val mask = 1 shl bit
            if (even) {
                val mid = (lonMin + lonMax) / 2
                if (idx and mask != 0) lonMin = mid else lonMax = mid
            } else {
                val mid = (latMin + latMax) / 2
                if (idx and mask != 0) latMin = mid else latMax = mid
            }
            even = !even
        }
    }
    return ((latMin + latMax) / 2) to ((lonMin + lonMax) / 2)
}

private fun reflect(o: Any?, name: String): Any? {
    if (o == null) return null
    var c: Class<*>? = o.javaClass
    while (c != null) {
        try {
            val f = c.getDeclaredField(name)
            f.isAccessible = true
            return f.get(o)
        } catch (_: Throwable) { }
        c = c.superclass
    }
    return null
}

/** 精确属地：省 市 街道 小区（POI 优先，其次 geohash 逆地理编码，都没有就只到市） */
suspend fun Aweme.preciseLocation(): String = withContext(Dispatchers.IO) {
    val base = cityInfo()
    runCatching {
        val poi = reflect(this@preciseLocation, "poiStruct") ?: reflect(this@preciseLocation, "poiAnchorStructV2")
        val poiName = (reflect(poi, "poiName") as? String)?.trim().orEmpty()
        if (poiName.isNotEmpty()) return@withContext listOf(base, poiName).filter { it.isNotBlank() }.joinToString(" ")

        val gh = (reflect(this@preciseLocation, "geohash") as? String)?.trim().orEmpty()
        if (gh.isEmpty()) return@withContext base
        val ll = decodeGeohash(gh) ?: return@withContext base
        val json = KHttpUtils.get("https://www.ccios.de/geo/?lat=${ll.first}&lng=${ll.second}").parseJSON()
        val street = json.getStringOrDefault("street")
        val road = json.getStringOrDefault("road")
        val near = json.getStringOrDefault("poi")
        listOf(base, street, road, near).filter { it.isNotBlank() }.distinct().joinToString(" ")
    }.getOrElse { base }
}
