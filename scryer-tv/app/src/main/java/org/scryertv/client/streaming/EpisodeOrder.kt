package org.scryertv.client.streaming

/** Only numbered episodes from the same series and uploader are joined automatically. */
object EpisodeOrder {
    private val extras = Regex("(?iu)\\b(трейлер|обзор|реакция|анонс|отрывок|trailer|recap)\\b")
    data class Number(val series: String, val season: Int, val episode: Int)

    private val code = Regex("(?iu)\\bs(\\d{1,3})[ ._-]*e(\\d{1,4})\\b")
    private val season = Regex("(?iu)(?:сезон\\s*(\\d{1,3})|(\\d{1,3})\\s*сезон|season\\s*(\\d{1,3}))")
    private val episode = Regex("(?iu)(?:серия\\s*(\\d{1,4})|(\\d{1,4})\\s*серия|эпизод\\s*(\\d{1,4})|episode\\s*(\\d{1,4}))")

    fun parse(title: String): Number? {
        if (extras.containsMatchIn(title)) return null
        val compact = code.find(title)
        val ep = episode.find(title)
        if (compact == null && ep == null) return null
        val se = season.find(title)
        val start = listOfNotNull(compact?.range?.first, ep?.range?.first, se?.range?.first).minOrNull()!!
        val key = title.take(start).lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        if (key.length < 2) return null
        val seasonNumber = compact?.groupValues?.get(1)?.toIntOrNull()
            ?: se?.groupValues?.drop(1)?.firstNotNullOfOrNull { it.toIntOrNull() } ?: 1
        val episodeNumber = compact?.groupValues?.get(2)?.toIntOrNull()
            ?: ep?.groupValues?.drop(1)?.firstNotNullOfOrNull { it.toIntOrNull() } ?: return null
        if (seasonNumber < 1 || episodeNumber < 1) return null
        return Number(key, seasonNumber, episodeNumber)
    }

    fun ordered(current: VideoItem, candidates: List<VideoItem>): List<VideoItem> {
        val number = parse(current.title) ?: return listOf(current)
        if (current.authorId == 0L) return listOf(current)
        return (listOf(current) + candidates).distinctBy { it.id }
            .filter { item ->
                val other = parse(item.title)
                !item.isLive && item.authorId == current.authorId && other?.series == number.series
            }
            .sortedWith(compareBy({ parse(it.title)!!.season }, { parse(it.title)!!.episode }))
            .distinctBy { parse(it.title)!!.let { n -> n.season to n.episode } }
    }

    fun next(current: VideoItem, candidates: List<VideoItem>): VideoItem? {
        val number = parse(current.title) ?: return null
        val ordered = ordered(current, candidates)
        // Never silently skip a missing episode.
        return ordered.firstOrNull { parse(it.title)?.let { n ->
            n.season == number.season && n.episode == number.episode + 1
        } == true } ?: ordered.takeUnless { items -> items.any { item ->
            parse(item.title)?.let { it.season == number.season && it.episode > number.episode } == true
        } }?.firstOrNull { parse(it.title)?.let { n ->
            n.season == number.season + 1 && n.episode == 1
        } == true }
    }
}
