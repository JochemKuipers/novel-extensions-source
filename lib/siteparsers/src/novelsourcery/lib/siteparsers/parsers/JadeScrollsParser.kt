package novelsourcery.lib.siteparsers.parsers

import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import novelsourcery.lib.siteparsers.SiteParser
import novelsourcery.lib.siteparsers.combined
import novelsourcery.lib.siteparsers.domainKey
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class JadeScrollsParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "jadescrolls"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        val parts = url.toString().split("/")
        if (parts.size < 6) throw Exception("Invalid chapter URL structure")
        val novelSlug = parts[4]
        val chapterSlug = parts[5]
        val apiUrl = "${parts[0]}//api.${parts[2]}/api/novels-chapter/$novelSlug/chapters/$chapterSlug"

        val response = client.newCall(GET(apiUrl, headers)).execute()
        val data = Json.parseToJsonElement(response.body.string()).jsonObject

        val chapterNumber = data["chapter_number"]?.jsonPrimitive?.content ?: ""
        val titleText = data["title"]?.jsonPrimitive?.content ?: ""
        val content = data["content"]?.jsonPrimitive?.content ?: ""

        val title = titleText.ifEmpty { "Chapter $chapterNumber" }
        return combined(title, content)
    }
}
