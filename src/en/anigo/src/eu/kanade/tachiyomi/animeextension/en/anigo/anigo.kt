package eu.kanade.tachiyomi.animeextension.en.anigo

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Anigo : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Anigo"

    override val baseUrl = "https://anigo.to"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeSelector(): String = "div.film_list-wrap div.flw-item"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/home?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        anime.thumbnail_url = element.selectFirst("img")?.attr("data-src")
        anime.title = element.selectFirst("h3.film-name a")!!.text()
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li.page-item a[title=next]"

    // =============================== Latest ===============================

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/home?page=$page")

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", query)
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url.toString())
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // ============================== Details ===============================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst("h2.film-name")?.text() ?: ""
        anime.description = document.selectFirst("div.film-description")?.text()?.trim()
        anime.genre = document.select("div.item:contains(Genre) a").joinToString { it.text() }
        anime.status = parseStatus(document.selectFirst("div.item:contains(Status)")?.text())
        anime.author = document.selectFirst("div.item:contains(Studio) a")?.text()
        return anime
    }

    private fun parseStatus(statusString: String?): Int {
        return when {
            statusString == null -> SAnime.UNKNOWN
            statusString.contains("Currently Airing", ignoreCase = true) -> SAnime.ONGOING
            statusString.contains("Finished Airing", ignoreCase = true) -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListSelector(): String = "ul.ss-list a"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("href"))
        episode.name = element.selectFirst("span.ssli-order")?.text() ?: "Episode"
        episode.episode_number = element.attr("href").substringAfterLast("-").toFloatOrNull() ?: 0F
        return episode
    }

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        
        // Try to find video sources from the page
        document.select("div.server-item").forEach { server ->
            val serverName = server.attr("data-type") ?: "Unknown"
            val dataId = server.attr("data-id")
            
            if (dataId.isNotEmpty()) {
                try {
                    val videoUrl = "$baseUrl/ajax/episode/sources/$dataId"
                    val videoResponse = client.newCall(GET(videoUrl)).execute()
                    val videoDoc = videoResponse.asJsoup()
                    
                    // Extract video URLs (this may need adjustment based on actual site structure)
                    videoDoc.select("source").forEach { source ->
                        val url = source.attr("src")
                        val quality = source.attr("label") ?: "Unknown"
                        if (url.isNotEmpty()) {
                            videoList.add(Video(url, "$serverName - $quality", url))
                        }
                    }
                } catch (e: Exception) {
                    // Skip if extraction fails
                }
            }
        }
        
        return videoList
    }

    override fun videoListSelector(): String = throw Exception("not used")

    override fun videoFromElement(element: Element): Video = throw Exception("not used")

    override fun videoUrlParse(document: Document): String = throw Exception("not used")

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }

    // ============================== Utilities =============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")
        return sortedWith(
            compareBy { it.quality.contains(quality ?: "1080") }
        ).reversed()
    }
}