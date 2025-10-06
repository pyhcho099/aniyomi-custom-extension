package eu.kanade.tachiyomi.animeextension.en.anigo

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Anigo : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "AniGo"

    // Corrected baseUrl - removed trailing space
    override val baseUrl = "https://anigo.to"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    // Uses the observed selector from the first code snippet
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/home?page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select("div.film_list-wrap div.flw-item").mapNotNull { element ->
            runCatching {
                SAnime.create().apply {
                    // Get URL from the first anchor tag within the item
                    val urlElement = element.selectFirst("a") ?: throw Exception("No anchor found for anime item")
                    setUrlWithoutDomain(urlElement.attr("href"))
                    // Get thumbnail from the image tag, likely using data-src for lazy loading
                    thumbnail_url = element.selectFirst("img")?.attr("data-src")
                    // Get title from the h3.film-name anchor
                    title = element.selectFirst("h3.film-name a")?.text() ?: "No Title"
                }
            }.getOrNull() // Use getOrNull to gracefully handle exceptions during mapping
        }.filterNotNull() // Ensure no null elements remain after mapping

        // Check for next page link
        val hasNextPage = document.selectFirst("ul.pagination li.page-item a[title=next]") != null
        return AnimesPage(animeList, hasNextPage)
    }

    // =============================== Latest ===============================
    // Reuse popular selectors as they often fetch the same layout
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/home?page=$page", headers)
    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", query)
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url.toString(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== Details ===============================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("h2.film-name")?.text() ?: "No Title"
            description = document.selectFirst("div.film-description")?.text()?.trim()
            genre = document.select("div.item:contains(Genre) a").joinToString(", ") { it.text() }
            status = parseStatus(document.selectFirst("div.item:contains(Status)")?.text())
            author = document.selectFirst("div.item:contains(Studio) a")?.text()
            // Thumbnail might already be set from popular/search, but confirm here if needed
            thumbnail_url = thumbnail_url ?: document.selectFirst("img.film-poster-img")?.attr("data-src")
        }
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
    // Uses the observed selector for episode list
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select("ul.ss-list a").mapNotNull { element ->
             runCatching {
                SEpisode.create().apply {
                    // Get the episode URL
                    setUrlWithoutDomain(element.attr("href"))
                    // Get the episode name, defaulting to "Episode" if not found
                    name = element.selectFirst("span.ssli-order")?.text() ?: "Episode"
                    // Attempt to extract episode number from the URL (e.g., .../episode-5 -> 5)
                    val epNumMatch = Regex("-(\\d+)(?:[^\\d]|$)").find(element.attr("href"))
                    episode_number = epNumMatch?.groupValues?.get(1)?.toFloatOrNull() ?: 0F
                }
             }.getOrNull()
        }.filterNotNull()
    }

    // ============================ Video Links =============================
    // This is the crucial part based on the likely AJAX structure observed
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        // Find server items which likely contain data attributes needed for AJAX calls
        document.select("div.server-item").forEach { serverElement ->
            val serverName = serverElement.attr("data-type") ?: "Unknown Server" // Default name if not found
            val dataId = serverElement.attr("data-id")

            if (dataId.isNotEmpty()) {
                try {
                    // Construct the AJAX endpoint URL using the data-id
                    val ajaxUrl = "$baseUrl/ajax/episode/sources/$dataId"
                    val ajaxHeaders = headers.newBuilder()
                        .add("X-Requested-With", "XMLHttpRequest") // Common header for AJAX requests
                        .build()
                    val ajaxResponse = client.newCall(GET(ajaxUrl, ajaxHeaders)).execute()
                    val ajaxResponseBody = ajaxResponse.body?.string()

                    if (ajaxResponse.isSuccessful && !ajaxResponseBody.isNullOrEmpty()) {
                        // Parse the AJAX response (likely HTML fragment containing source info)
                        val ajaxDoc = ajaxResponseBody.asJsoup()

                        // Look for video source elements within the AJAX response
                        ajaxDoc.select("source").forEach { sourceElement ->
                            val videoUrl = sourceElement.attr("src")
                            val qualityLabel = sourceElement.attr("label") ?: "Default"
                            val resolution = sourceElement.attr("size")?.toIntOrNull() // Get resolution if available
                            val quality = if (resolution != null) "${resolution}p - $serverName" else "$serverName - $qualityLabel"

                            if (videoUrl.startsWith("http")) { // Ensure it's a valid URL
                                videoList.add(Video(videoUrl, quality, videoUrl, headers = headers)) // Pass headers if needed by video server
                            }
                        }

                    } else {
                         // Log or handle potential failure in AJAX call if needed for debugging
                         // println("Failed to fetch AJAX response for data-id: $dataId, Status: ${ajaxResponse.code}")
                    }
                } catch (e: Exception) {
                    // Log the error and continue with other servers if one fails
                    // println("Error fetching video sources for data-id $dataId: ${e.message}")
                    // e.printStackTrace()
                }
            }
        }

        // Filter out any videos that might not have loaded correctly
        return videoList.filter { !it.url.isNullOrEmpty() }
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
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
        }.also(screen::addPreference) // Use the 'also' pattern like AnimeGG
    }

    // ============================== Utilities =============================

    override fun List<Video>.sort(): List<Video> {
        val preferredQuality = preferences.getString("preferred_quality", "1080")
        val quality = preferredQuality ?: "1080"

        return sortedWith(
            compareBy { it.quality.contains(quality, ignoreCase = true) }
        ).reversed() // Put the preferred quality first
    }
}