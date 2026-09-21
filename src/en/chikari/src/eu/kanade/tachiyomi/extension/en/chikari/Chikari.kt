package eu.kanade.tachiyomi.extension.en.chikari

import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.lib.chapterutils.paginatedChapterList
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.tryParse
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Entities
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@Source
abstract class Chikari :
    KeiSource(),
    NovelSource {

    override fun OkHttpClient.Builder.configureClient() = rateLimit(permits = 4, period = 1.seconds)

    override val supportsFilterFetching = true

    override suspend fun getPopularManga(page: Int): MangasPage = fetchNovelList(page, query = "", sort = "popular", adultMode = AdultMode.BOTH, filters = FilterList())

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchNovelList(page, query = "", sort = "updated", adultMode = AdultMode.BOTH, filters = FilterList())

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val sort = filters.firstInstanceOrNull<SortFilter>()?.toUriPart() ?: "popular"
        val adultMode = filters.firstInstanceOrNull<AdultFilter>()?.mode() ?: AdultMode.BOTH
        return fetchNovelList(page, query, sort, adultMode, filters)
    }

    private suspend fun fetchNovelList(
        page: Int,
        query: String,
        sort: String,
        adultMode: AdultMode,
        filters: FilterList,
    ): MangasPage = when (adultMode) {
        AdultMode.SAFE -> fetchSingleAdultList(page, query, sort, adult = false, filters)
        AdultMode.ADULT -> fetchSingleAdultList(page, query, sort, adult = true, filters)
        AdultMode.BOTH -> fetchMixedAdultList(page, query, sort, filters)
    }

    private suspend fun fetchSingleAdultList(
        page: Int,
        query: String,
        sort: String,
        adult: Boolean,
        filters: FilterList,
    ): MangasPage {
        val offset = (page - 1) * PAGE_SIZE
        val dto = client.get(novelsListUrl(query, sort, adult, PAGE_SIZE, offset, filters), headers)
            .parseAs<NovelListDto>()
        return MangasPage(
            mangas = dto.items.map { it.toSManga() },
            hasNextPage = offset + dto.items.size < dto.total,
        )
    }

    private suspend fun fetchMixedAdultList(
        page: Int,
        query: String,
        sort: String,
        filters: FilterList,
    ): MangasPage = coroutineScope {
        val half = PAGE_SIZE / 2
        val offset = (page - 1) * half
        val safeDeferred = async {
            client.get(novelsListUrl(query, sort, adult = false, half, offset, filters), headers)
                .parseAs<NovelListDto>()
        }
        val adultDeferred = async {
            client.get(novelsListUrl(query, sort, adult = true, half, offset, filters), headers)
                .parseAs<NovelListDto>()
        }
        val safe = safeDeferred.await()
        val adult = adultDeferred.await()
        MangasPage(
            mangas = interleave(safe.items, adult.items).map { it.toSManga() },
            hasNextPage = offset + safe.items.size < safe.total || offset + adult.items.size < adult.total,
        )
    }

    private fun novelsListUrl(
        query: String,
        sort: String,
        adult: Boolean,
        limit: Int,
        offset: Int,
        filters: FilterList,
    ): HttpUrl {
        val builder = "$baseUrl/api/novels".toHttpUrl().newBuilder()
            .addQueryParameter("sort", sort)
            .addQueryParameter("adult", adult.toString())
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())

        if (query.isNotBlank()) {
            builder.addQueryParameter("q", query)
        }

        filters.firstInstanceOrNull<StatusFilter>()?.toUriPart()?.takeIf { it.isNotEmpty() }?.let {
            builder.addQueryParameter("status", it)
        }

        filters.firstInstanceOrNull<GenreFilter>()?.state?.forEach { genre ->
            when {
                genre.isIncluded() -> builder.addQueryParameter("genre", genre.slug)
                genre.isExcluded() -> builder.addQueryParameter("genre_exclude", genre.slug)
            }
        }

        filters.firstInstanceOrNull<TagFilter>()?.state?.forEach { tag ->
            when {
                tag.isIncluded() -> builder.addQueryParameter("tag", tag.id.toString())
                tag.isExcluded() -> builder.addQueryParameter("tag_exclude", tag.id.toString())
            }
        }

        return builder.build()
    }

    private fun <T> interleave(a: List<T>, b: List<T>): List<T> {
        val result = ArrayList<T>(a.size + b.size)
        val max = maxOf(a.size, b.size)
        for (i in 0 until max) {
            if (i < a.size) result.add(a[i])
            if (i < b.size) result.add(b[i])
        }
        return result
    }

    override suspend fun fetchFilterData(): JsonElement = coroutineScope {
        val genresDeferred = async {
            client.get("$baseUrl/api/novels/genres", headers).parseAs<List<NamedSlugDto>>()
        }
        val tagsDeferred = async {
            client.get("$baseUrl/api/novels/tags", headers).parseAs<List<NamedIdDto>>()
        }
        FilterDataDto(genres = genresDeferred.await(), tags = tagsDeferred.await()).toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filterData = data?.let { runCatching { it.parseAs<FilterDataDto>() }.getOrNull() }
        return FilterList(
            buildList {
                add(SortFilter())
                add(StatusFilter())
                add(AdultFilter())
                if (filterData != null) {
                    add(Filter.Separator())
                    if (filterData.genres.isNotEmpty()) {
                        add(GenreFilter(filterData.genres))
                    }
                    if (filterData.tags.isNotEmpty()) {
                        add(TagFilter(filterData.tags))
                    }
                }
            },
        )
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val slug = url.pathSegments
            .dropWhile { it != "novels" }
            .drop(1)
            .firstOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return client.get("$baseUrl/api/novels/$slug", headers).parseAs<NovelDto>().toSMangaDetails()
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/novels/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/novels/${chapter.url}"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val slug = manga.url
        val detailsDeferred = if (fetchDetails) {
            async { client.get("$baseUrl/api/novels/$slug", headers).parseAs<NovelDto>() }
        } else {
            null
        }

        val chaptersDeferred = if (fetchChapters) {
            async {
                val siteTotal = detailsDeferred?.await()?.chapterCount ?: 0
                paginatedChapterList(
                    existingChapters = chapters,
                    siteTotal = siteTotal,
                    assumedPageSize = CHAPTER_PAGE_SIZE,
                    fetchPage = { page ->
                        val offset = (page - 1) * CHAPTER_PAGE_SIZE
                        val dto = client.get(
                            "$baseUrl/api/novels/$slug/chapters".toHttpUrl().newBuilder()
                                .addQueryParameter("limit", CHAPTER_PAGE_SIZE.toString())
                                .addQueryParameter("offset", offset.toString())
                                .build(),
                            headers,
                        ).parseAs<ChapterListDto>()
                        val parsed = dto.items.map { chapter ->
                            SChapter.create().apply {
                                url = "$slug/${formatNumber(chapter.number)}"
                                name = chapter.title.ifEmpty { "Chapter ${formatNumber(chapter.number)}" }
                                chapter_number = chapter.number.toFloat()
                                date_upload = Instant.tryParse(chapter.createdAt)
                            }
                        }
                        parsed to (offset + dto.items.size < dto.total)
                    },
                )
            }
        } else {
            null
        }

        SMangaUpdate(
            manga = detailsDeferred?.await()?.toSMangaDetails() ?: manga,
            chapters = chaptersDeferred?.await() ?: chapters,
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val parts = page.url.split('/', limit = 2)
        require(parts.size == 2) { "Invalid chapter url: ${page.url}" }
        val (slug, number) = parts
        val chapter = client.get("$baseUrl/api/novels/$slug/chapters/$number/read", headers)
            .parseAs<ChapterReadDto>()
        if (chapter.locked) {
            throw Exception(chapter.lockReason.ifEmpty { "Chapter is locked" })
        }
        return chapter.body
            .split(PARAGRAPH_SPLIT)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n") { "<p>${Entities.escape(it).replace("\n", "<br>")}</p>" }
    }

    private fun formatNumber(number: Double): String = number.toString().removeSuffix(".0")

    companion object {
        private const val PAGE_SIZE = 36
        private const val CHAPTER_PAGE_SIZE = 100
        private val PARAGRAPH_SPLIT = Regex("\n{2,}")
    }
}
