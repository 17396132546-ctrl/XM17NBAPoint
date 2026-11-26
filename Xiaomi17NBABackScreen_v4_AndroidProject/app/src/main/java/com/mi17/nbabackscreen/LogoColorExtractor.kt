package com.mi17.nbabackscreen

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 球队 Logo 主色提取器：
 * 1. 直接扫描实际加载的球队 Logo 位图，统计面积占比最大的彩色色相区间，确保灯效色调与 Logo 视觉主色完全一致
 *    （例如尼克斯 Logo 橙色面积最大 -> 提取尼克斯橙，湖人 Logo 金黄篮球面积最大 -> 提取湖金黄）；
 * 2. 若 Logo 为纯黑白灰系（如马刺、篮网），自动返回柔和银白；
 * 3. 内置 30 支球队 Logo 主色兜底映射表，首次加载未完成时也能零延迟触发；
 * 4. 对 OLED 护屏做专门限幅：适当压低纯蓝光通道亮度，避免高饱和蓝光加速子像素老化。
 */
object LogoColorExtractor {

    private val extractedCache = ConcurrentHashMap<String, Int>()

    // 兜底主色映射（按各队 Logo 主体面积最大颜色校准）
    private val fallbackLogoColorMap = mapOf(
        "ATL" to 0xFFE03A3E.toInt(), // 老鹰红
        "BOS" to 0xFF008348.toInt(), // 凯尔特绿
        "BKN" to 0xFFB0BAC9.toInt(), // 篮网银灰
        "CHA" to 0xFF00788C.toInt(), // 黄蜂青蓝
        "CHI" to 0xFFCE1141.toInt(), // 公牛红
        "CLE" to 0xFF860038.toInt(), // 骑士酒红
        "DAL" to 0xFF00538C.toInt(), // 独行侠蓝
        "DEN" to 0xFFFEC524.toInt(), // 掘金金黄
        "DET" to 0xFFC8102E.toInt(), // 活塞红
        "GS"  to 0xFF1D428A.toInt(), // 勇士宝蓝
        "GSW" to 0xFF1D428A.toInt(),
        "HOU" to 0xFFCE1141.toInt(), // 火箭红
        "IND" to 0xFFFDBB30.toInt(), // 步行者金黄
        "LAC" to 0xFFC8102E.toInt(), // 快船红
        "LAL" to 0xFFFDB927.toInt(), // 湖人金黄
        "MEM" to 0xFF5D76A9.toInt(), // 灰熊冰蓝
        "MIA" to 0xFFB81D3A.toInt(), // 热火红
        "MIL" to 0xFF005A2B.toInt(), // 雄鹿绿
        "MIN" to 0xFF236192.toInt(), // 森林狼蓝
        "NO"  to 0xFFB4975A.toInt(), // 鹈鹕金
        "NOP" to 0xFFB4975A.toInt(),
        "NY"  to 0xFFF58426.toInt(), // 尼克斯橙
        "NYK" to 0xFFF58426.toInt(),
        "OKC" to 0xFF007AC1.toInt(), // 雷霆蓝
        "ORL" to 0xFF0077C0.toInt(), // 魔术蓝
        "PHI" to 0xFF006BB6.toInt(), // 76人蓝
        "PHX" to 0xFFE56020.toInt(), // 太阳橙
        "POR" to 0xFFE03A3E.toInt(), // 开拓者红
        "SAC" to 0xFF6B3FA0.toInt(), // 国王紫
        "SA"  to 0xFFB0BAC9.toInt(), // 马刺银灰
        "SAS" to 0xFFB0BAC9.toInt(),
        "TOR" to 0xFFCE1141.toInt(), // 猛龙红
        "UTA" to 0xFFF9A01B.toInt(), // 爵士金
        "UTAH" to 0xFFF9A01B.toInt(),
        "WAS" to 0xFFE31837.toInt(), // 奇才红
        "WSH" to 0xFFE31837.toInt()
    )

    fun getCachedOrFallback(abbr: String, logoUrl: String): Int {
        extractedCache[logoUrl]?.let { return it }
        val raw = fallbackLogoColorMap[abbr.uppercase()] ?: 0xFF38BDF8.toInt()
        return tuneForOledGlow(raw)
    }

    suspend fun resolveDominantColor(abbr: String, logoUrl: String): Int = withContext(Dispatchers.Default) {
        extractedCache[logoUrl]?.let { return@withContext it }
        val bmp = NbaRepository.loadLogoBitmap(logoUrl)
        val rawColor = if (bmp != null) {
            extractDominantFromBitmap(bmp) ?: fallbackLogoColorMap[abbr.uppercase()] ?: 0xFF38BDF8.toInt()
        } else {
            fallbackLogoColorMap[abbr.uppercase()] ?: 0xFF38BDF8.toInt()
        }
        val tuned = tuneForOledGlow(rawColor)
        extractedCache[logoUrl] = tuned
        tuned
    }

    /**
     * 按色相分桶（每 15° 一桶，共 24 桶）统计 Logo 中非透明且有明显色彩的像素面积，
     * 找出面积占比最大的色相桶并取加权平均 RGB。
     */
    private fun extractDominantFromBitmap(src: Bitmap): Int? {
        val size = 48
        val scaled = Bitmap.createScaledBitmap(src, size, size, true)
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        if (scaled != src) scaled.recycle()

        val bucketCount = 24
        val bucketWeight = FloatArray(bucketCount)
        val bucketR = FloatArray(bucketCount)
        val bucketG = FloatArray(bucketCount)
        val bucketB = FloatArray(bucketCount)

        var totalOpaque = 0
        var colorfulPixels = 0
        val hsv = FloatArray(3)

        for (px in pixels) {
            val a = Color.alpha(px)
            if (a < 150) continue
            totalOpaque++

            val r = Color.red(px)
            val g = Color.green(px)
            val b = Color.blue(px)
            Color.RGBToHSV(r, g, b, hsv)
            val hue = hsv[0]
            val sat = hsv[1]
            val value = hsv[2]

            // 过滤纯白、纯黑和浅灰底色，专注提取彩色主色调
            if (sat < 0.22f || value < 0.16f || (value > 0.94f && sat < 0.28f)) continue

            colorfulPixels++
            val idx = ((hue / 15f).toInt()).coerceIn(0, bucketCount - 1)
            // 以像素面积为主权重（权重 1.0 + 轻微饱和度加成），保证面积最多的颜色胜出
            val w = 1.0f + sat * 0.45f
            bucketWeight[idx] += w
            bucketR[idx] += r * w
            bucketG[idx] += g * w
            bucketB[idx] += b * w
        }

        if (totalOpaque == 0) return null
        // 若彩色像素占比极低（如纯黑白马刺/篮网 Logo），返回柔和银灰
        if (colorfulPixels < totalOpaque * 0.06f) {
            return 0xFFB0BAC9.toInt()
        }

        // 合并相邻桶（避免例如红/橙恰好落在分界线上被拆散）
        var bestIdx = 0
        var bestCombined = -1f
        for (i in 0 until bucketCount) {
            val prev = (i + bucketCount - 1) % bucketCount
            val next = (i + 1) % bucketCount
            val score = bucketWeight[i] + bucketWeight[prev] * 0.35f + bucketWeight[next] * 0.35f
            if (score > bestCombined) {
                bestCombined = score
                bestIdx = i
            }
        }

        val wSum = bucketWeight[bestIdx]
        if (wSum <= 0f) return null
        val avgR = (bucketR[bestIdx] / wSum).toInt().coerceIn(0, 255)
        val avgG = (bucketG[bestIdx] / wSum).toInt().coerceIn(0, 255)
        val avgB = (bucketB[bestIdx] / wSum).toInt().coerceIn(0, 255)
        return Color.rgb(avgR, avgG, avgB)
    }

    /**
     * OLED 护屏调色：
     * - 保持球队原始色相不变；
     * - 避免纯蓝光满载高压输出（蓝光色相区间亮度封顶 70%，其他色相封顶 82%），
     *   既炫酷醒目又最大限度降低蓝光子像素衰减。
     */
    private fun tuneForOledGlow(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        val hue = hsv[0]
        val sat = hsv[1]
        val value = hsv[2]

        // 银灰系保持低饱和中等亮度
        if (sat < 0.12f) {
            hsv[1] = 0.08f
            hsv[2] = 0.72f
            return Color.HSVToColor(hsv)
        }

        hsv[1] = sat.coerceIn(0.62f, 0.94f)
        hsv[2] = if (hue in 195f..265f) {
            // 蓝/靛蓝系压低上限保护蓝色 OLED 像素
            value.coerceIn(0.55f, 0.72f)
        } else {
            value.coerceIn(0.62f, 0.84f)
        }
        return Color.HSVToColor(hsv)
    }

    fun formatHex(color: Int): String = String.format("#%06X", 0xFFFFFF and color)
}
