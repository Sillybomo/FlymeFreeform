package io.github.mangi.flymefreeform.config

import android.util.Base64

/**
 * 侧边栏工具目录的文本协议：每行一个工具，字段用横向制表符分隔。
 *
 * 形如 `alias\tlabel\tavailable\ticonPngBase64`；图标缺失时第 4 段为空。
 * 不依赖 Android 运行时以外的类型，便于 JVM 单测与跨进程搬运。
 *
 * @author bomo
 */
internal object ToolCatalogCodec {
    private const val FIELD_SEPARATOR = '\t'
    private const val FIELD_COUNT = 4

    /** 编解码用记录；[iconPng] 为可选 PNG 字节。 */
    data class Record(
        val alias: String,
        val label: String,
        val available: Boolean,
        val iconPng: ByteArray?,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Record &&
                alias == other.alias &&
                label == other.label &&
                available == other.available &&
                iconPng.contentEquals(other.iconPng)

        override fun hashCode(): Int {
            var result = alias.hashCode()
            result = 31 * result + label.hashCode()
            result = 31 * result + available.hashCode()
            result = 31 * result + (iconPng?.contentHashCode() ?: 0)
            return result
        }
    }

    fun encode(records: List<Record>): String =
        records
            .asSequence()
            .filter { it.alias.isNotBlank() }
            .distinctBy(Record::alias)
            .joinToString("\n") { record ->
                listOf(
                    record.alias,
                    record.label.replace('\n', ' ').replace(FIELD_SEPARATOR, ' '),
                    if (record.available) "1" else "0",
                    record.iconPng?.let { bytes ->
                        Base64.encodeToString(bytes, Base64.NO_WRAP)
                    } ?: "",
                ).joinToString(FIELD_SEPARATOR.toString())
            }

    /** 解析目录文本；格式不符的行直接跳过，损坏内容退化为空列表而不是抛错。 */
    fun decode(value: String?): List<Record> {
        if (value.isNullOrEmpty()) return emptyList()
        return value
            .lineSequence()
            .mapNotNull { line ->
                val fields = line.split(FIELD_SEPARATOR)
                if (fields.size < FIELD_COUNT) return@mapNotNull null
                val alias = fields[0].trim()
                if (alias.isEmpty()) return@mapNotNull null
                Record(
                    alias = alias,
                    label = fields[1].trim().ifEmpty { alias },
                    available = fields[2].trim() == "1",
                    iconPng =
                        fields[3].trim().takeIf { it.isNotEmpty() }?.let { encoded ->
                            runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull()
                        },
                )
            }
            .distinctBy(Record::alias)
            .toList()
    }
}
