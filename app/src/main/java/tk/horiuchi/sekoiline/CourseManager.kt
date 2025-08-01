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
        addSegment(400, 0f, R.drawable.bg_country)   // 直線
        addSegment(400,  2.0f, R.drawable.bg_country) // 緩い右カーブ
        addSegment(300, -1.0f, R.drawable.bg_country) // 急な左カーブ

        addSegment(300, 0f, null)      // トンネル

        addSegment(400, 0f, R.drawable.bg_city)      // 都会 直線
        addSegment(200,  2.0f, R.drawable.bg_city)    // 急な右カーブ
        addSegment(200, -0.3f, R.drawable.bg_city)    // 緩い左カーブ

        addSegment(300, 0f, null)      // トンネル

        addSegment(400, 0f, R.drawable.bg_city_night)     // 夜
        addSegment(200,  0.7f, R.drawable.bg_city_night)
        addSegment(300,  -2.0f, R.drawable.bg_city_night)

        addSegment(300, 0f, null)      // トンネル
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
