package com.voicereader.update

/** Только стабильные числовые версии Читалки; теги остальных проектов пропускаются. */
class ReleaseVersion private constructor(private val parts: List<Int>) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val comparison = parts.getOrElse(i) { 0 }.compareTo(other.parts.getOrElse(i) { 0 })
            if (comparison != 0) return comparison
        }
        return 0
    }

    companion object {
        fun parse(value: String): ReleaseVersion? {
            if (!value.matches(Regex("[0-9]+(\\.[0-9]+)*"))) return null
            val parts = value.split('.').map { it.toIntOrNull() ?: return null }
            return ReleaseVersion(parts)
        }

        fun fromTag(tag: String): ReleaseVersion? =
            if (tag.startsWith("chitalka-v")) parse(tag.removePrefix("chitalka-v")) else null
    }
}
