package tk.horiuchi.sekoiline

data class CourseSegment(
    val length: Int,         // セグメントの長さ（単位：距離）
    val curve: Float,        // カーブの強さ（-1.0 = 左カーブ、0 = 直線、1.0 = 右カーブ）
    val background: Int?      // 背景画像のリソースID（例：R.drawable.bg_country）
)

class CourseManager {
    private val segments = mutableListOf<CourseSegment>()
    private var totalLength = 0

    init {
        // 仮のコース構成（田舎→都会→夜）
        addSegment(1500, 0f, R.drawable.bg_country)
        addSegment(800,  2.0f, R.drawable.bg_country)
        addSegment(800,  5.0f, R.drawable.bg_country)
        addSegment(800, -2.5f, R.drawable.bg_country)
        addSegment(600,  1.5f, R.drawable.bg_country)
        addSegment(400, 0f, R.drawable.bg_country)

        addSegment(1000, 0f, null)      // トンネル

        addSegment(500, 0f, R.drawable.bg_city)
        addSegment(1000,  2.5f, R.drawable.bg_city)
        addSegment(1000, -2.0f, R.drawable.bg_city)
        addSegment(1000, -5.0f, R.drawable.bg_city)

        addSegment(1000, 0f, null)      // トンネル

        addSegment(500, 0f, R.drawable.bg_city_night)
        addSegment(1000,  -1.7f, R.drawable.bg_city_night)
        addSegment(1000,  -5.0f, R.drawable.bg_city_night)
        addSegment(500,  2.3f, R.drawable.bg_city_night)
        addSegment(500,  1.5f, R.drawable.bg_city_night)

        addSegment(1000, 0f, null)      // トンネル

        addSegment(500, 0f, R.drawable.bg_country_snow)
        addSegment(1000,  -5.0f, R.drawable.bg_country_snow)
        addSegment(1000,  -2.0f, R.drawable.bg_country_snow)
        addSegment(500,  0.5f, R.drawable.bg_country_snow)
        addSegment(500,  2.5f, R.drawable.bg_country_snow)

        addSegment(1000, 0f, null)      // トンネル
    }

    private fun addSegment(length: Int, curve: Float, background: Int?) {
        segments.add(CourseSegment(length, curve, background))
        totalLength += length
    }

    // 現在の距離に応じたコースセグメント情報を返す
    fun getCurrentSegment(distance: Float): CourseSegment {
        val loopedDistance = distance % totalLength
        var covered = 0
        for (segment in segments) {
            if (loopedDistance < covered + segment.length) {
                return segment
            }
            covered += segment.length
        }
        return segments.last()  // 通常到達しないが保険
    }

    // 現在トンネルかどうか（呼び出し元で distance を渡す）
    fun isInTunnel(distance: Float): Boolean {
        return getCurrentSegment(distance).background == null
    }
}
