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

    override val name = "AniGo"

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
        val urlElement = element.selectFirst("a") ?: throw Exception("No anchor found for anime item")
        anime.setUrlWithoutDomain(urlElement.attr("href"))
        anime.thumbnail_url = element.selectFirst("img")?.attr("data-src")
        anime.title = element.selectFirst("h3.film-name a")?.text() ?: "No Title"
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
        anime.title = document.selectFirst("h2.film-name")?.text() ?: "No Title"
        anime.description = document.selectFirst("div.film-description")?.text()?.trim()
        anime.genre = document.select("div.item:contains(Genre) a").joinToString(", ") { it.text() }
        anime.status = parseStatus(document.selectFirst("div.item:contains(Status)")?.text())
        anime.author = document.selectFirst("div.item:contains(Studio) a")?.text()
        anime.thumbnail_url = anime.thumbnail_url ?: document.selectFirst("img.film-poster-img")?.attr("data-src")
        return anime
    }

    private fun parseStatus(statusString: String?): Int {
        if (statusString.isNullOrEmpty()) return SAnime.UNKNOWN
        return when {
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
        val epNumMatch = Regex("-(\\d+)(?:[^\\d]|$)").find(element.attr("href"))
        episode.episode_number = epNumMatch?.groupValues?.get(1)?.toFloatOrNull() ?: 0F
        return episode
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        document.select("div.server-item").forEach { serverElement ->
            val serverName = serverElement.attr("data-type")
            val dataId = serverElement.attr("data-id")

            if (dataId.isNotEmpty()) {
                try {
                    val ajaxUrl = "$baseUrl/ajax/episode/sources/$dataId"
                    val headers = Headers.headersOf("X-Requested-With", "XMLHttpRequest")
                    val ajaxResponse = client.newCall(GET(ajaxUrl, headers)).execute()
                    val ajaxResponseBody = ajaxResponse.body?.string()

                    if (ajaxResponse.isSuccessful && !ajaxResponseBody.isNullOrEmpty()) {
                        val ajaxDoc = ajaxResponseBody.asJsoup()

                        ajaxDoc.select("source").forEach { sourceElement ->
                            val videoUrl = sourceElement.attr("src")
                            val qualityLabel = sourceElement.attr("label") ?: "Default"
                            val resolution = sourceElement.attr("size") ?: ""
                            val quality = if (resolution.isNotEmpty()) {
                                "$serverName - ${resolution}p"
                            } else {
                                "$serverName - $qualityLabel"
                            }

                            if (videoUrl.startsWith("http")) {
                                videoList.add(Video(videoUrl, quality, videoUrl))
                            }
                        }
                    } else {
                        println("Failed to fetch AJAX response for data-id: $dataId, Status: ${ajaxResponse.code}, Body: $ajaxResponseBody")
                    }
                } catch (e: Exception) {
                    println("Error fetching video sources for data-id $dataId: ${e.message}")
                    e.printStackTrace()
                }
            }
        }

        return videoList.filter { !it.url.isNullOrEmpty() }
    }

    override fun videoListSelector(): String = throw Exception("Not used")
    override fun videoFromElement(element: Element): Video = throw Exception("Not used")
    override fun videoUrlParse(document: Document): String = throw Exception("Not used")

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred video quality"
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
        val preferredQuality = preferences.getString("preferred_quality", "1080")
        val quality = preferredQuality ?: "1080"

        return sortedWith(
            compareBy { it.quality.contains(quality, ignoreCase = true) },
        ).reversed()
    }
}

