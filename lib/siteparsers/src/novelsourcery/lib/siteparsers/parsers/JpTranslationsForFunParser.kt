package novelsourcery.lib.siteparsers.parsers

import novelsourcery.lib.siteparsers.SiteParser
import novelsourcery.lib.siteparsers.combined
import novelsourcery.lib.siteparsers.domainKey
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class JpTranslationsForFunParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "wntranslationsforfun"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        val content = doc.selectFirst("#reader-content")
            ?: throw Exception("Could not find reader content")

        // Prefer the in-chapter h1 (the translator's actual chapter title) and
        // remove it from the body so it isn't duplicated in the content.
        val innerH1 = content.selectFirst("h1")
        val title = innerH1?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("h1.post-title")?.text()?.trim().orEmpty()
        innerH1?.remove()

        // Blogger "jump break" bookmark - no visible content, just a scroll anchor.
        content.select("a[name]").remove()

        // Drop the trailing "ToC" link paragraph - it's site navigation, not chapter text.
        content.select("p").forEach { p ->
            val onlyChild = p.children().singleOrNull()
            if (onlyChild?.tagName() == "a" && onlyChild.text().equals("ToC", ignoreCase = true)) {
                p.remove()
            }
        }

        // Drop paragraphs that carry no real text once decoded (&nbsp;-only, bare <br>, empty spans).
        content.select("p").forEach { p ->
            val text = p.text().replace("\u00a0", "").trim()
            if (text.isEmpty()) p.remove()
        }

        return combined(title, content.html())
    }
}
