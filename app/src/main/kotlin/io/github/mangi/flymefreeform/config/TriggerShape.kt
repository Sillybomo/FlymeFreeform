package io.github.mangi.flymefreeform.config

/**
 * 角落触发热区（trigger hot zone）的形状。
 *
 * 存储值属于已发布配置格式，不得改作其他含义。
 *
 * @author bomo
 * - [Sector]：四分之一圆，只用单一半径；**默认**，即改造前的原有行为。
 * - [Triangle]：以屏幕角落为直角顶点的三角区，两条直角边分别沿底边（宽）与侧边（高），
 *   斜边连接 `(宽, 0)` 与 `(0, 高)` —— 越靠角落越"胖"，远离角落线性收窄。
 *   适合「沿边缘横向拉长、但不要占满整条侧边」这类需求。
 */
internal enum class TriggerShape(val storedValue: Int) {
    Sector(0),
    Triangle(1),
    ;

    companion object {
        /** 未写入配置时的默认形状：保持改造前的扇形行为。 */
        val DEFAULT = Sector

        /** 未知存储值一律回退默认形状（热区形状错误不应导致手势完全失效）。 */
        fun fromStoredValue(value: Int): TriggerShape =
            entries.firstOrNull { it.storedValue == value } ?: DEFAULT
    }
}
