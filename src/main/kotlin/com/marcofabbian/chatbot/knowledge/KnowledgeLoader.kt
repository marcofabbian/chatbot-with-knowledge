package com.marcofabbian.chatbot.knowledge

import com.marcofabbian.chatbot.config.ChatbotProperties
import jakarta.annotation.PostConstruct
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.io.IOException

@Configuration
class KnowledgeClientConfig {
    @Bean("knowledgeRestClientBuilder")
    fun knowledgeRestClientBuilder(properties: ChatbotProperties): RestClient.Builder {
        val timeout = properties.knowledge.fetchTimeout.toMillis().toInt()
        val factory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(timeout)
            setReadTimeout(timeout)
        }
        return RestClient.builder().requestFactory(factory)
    }
}

class KnowledgeUnavailableException : RuntimeException()
class KnowledgeNotFoundException : RuntimeException()

@Component
class KnowledgeLoader(
    properties: ChatbotProperties,
    @Qualifier("knowledgeRestClientBuilder") builder: RestClient.Builder
) {
    private val settings = properties.knowledge
    private val client = builder.build()
    private val logger = LoggerFactory.getLogger(KnowledgeLoader::class.java)
    private var index: KnowledgeIndex? = null

    val available: Boolean get() = index != null

    @PostConstruct
    fun initialize() {
        if (!settings.enabled) {
            logger.warn("Knowledge is disabled; chat requests are unavailable")
            return
        }
        try {
            val html = client.get().uri(settings.sourceUrl).exchange { _, response ->
                if (!response.statusCode.is2xxSuccessful) {
                    throw KnowledgeLoadException("HTTP ${response.statusCode.value()}")
                }
                val type = response.headers.contentType
                if (type == null || !MediaType.TEXT_HTML.isCompatibleWith(type)) {
                    throw KnowledgeLoadException("unexpected content type")
                }
                val bytes = response.body.readNBytes(MAX_SOURCE_BYTES + 1)
                if (bytes.size > MAX_SOURCE_BYTES) throw KnowledgeLoadException("source exceeds size limit")
                String(bytes, type.charset ?: Charsets.UTF_8)
            } ?: throw KnowledgeLoadException("empty response")
            val pages = parseBook(html)
            val chunks = pages.flatMap { (page, text) -> chunkPage(page, text, settings) }
            if (chunks.isEmpty()) throw KnowledgeLoadException("no readable book text")
            index = KnowledgeIndex(chunks, settings)
            logger.info("Knowledge ingested: pages={}, chunks={}", pages.size, chunks.size)
        } catch (e: KnowledgeLoadException) {
            logger.warn("Knowledge ingestion failed: {}", e.message)
        } catch (e: RestClientException) {
            logger.warn("Knowledge ingestion failed: {}", e.javaClass.simpleName)
        } catch (e: IOException) {
            logger.warn("Knowledge ingestion failed: {}", e.javaClass.simpleName)
        }
    }

    fun search(question: String): List<KnowledgePassage> =
        (index ?: throw KnowledgeUnavailableException()).search(question)

    /** Returns every indexed passage without retrieval limits or query scores. */
    fun getAll(): List<KnowledgePassage> =
        (index ?: throw KnowledgeUnavailableException()).getAll()

    private companion object {
        const val MAX_SOURCE_BYTES = 5 * 1024 * 1024
    }
}

internal class KnowledgeLoadException(message: String) : RuntimeException(message)

internal fun parseBook(html: String): Map<Int, String> {
    val document = Jsoup.parse(html)
    val start = document.selectFirst("#pg-start-separator")
    val footer = document.selectFirst("#pg-footer")
    if (start == null || footer == null ||
        !start.text().contains("START OF THE PROJECT GUTENBERG EBOOK HOW TO INVEST MONEY", ignoreCase = true) ||
        !footer.text().contains("END OF THE PROJECT GUTENBERG EBOOK HOW TO INVEST MONEY", ignoreCase = true)
    ) {
        throw KnowledgeLoadException("unexpected book structure")
    }

    val pages = linkedMapOf<Int, StringBuilder>()
    var currentPage: Int? = null
    var ended = false
    fun visit(node: Node) {
        if (ended) return
        if (node is Element) {
            if (node.id() == "pg-footer") {
                ended = true
                return
            }
            if (node.hasClass("tnote") || node.normalName() in setOf("script", "style", "header")) return
            if (node.hasClass("pagenum")) {
                val id = node.selectFirst("a[id^=Page_]")?.id()
                val page = id?.removePrefix("Page_")?.toIntOrNull()
                    ?: throw KnowledgeLoadException("invalid page marker")
                if (page >= 7) {
                    if (currentPage != null && page <= currentPage!!) {
                        throw KnowledgeLoadException("out-of-order page marker")
                    }
                    currentPage = page
                    pages[page] = StringBuilder()
                }
                return
            }
            if (node.isBlock) currentPage?.let { pages.getValue(it).append(' ') }
        }
        if (node is TextNode) currentPage?.let { pages.getValue(it).append(node.wholeText) }
        node.childNodes().forEach(::visit)
        if (node is Element && node.isBlock) currentPage?.let { pages.getValue(it).append(' ') }
    }
    visit(document.body())
    if (!ended || !pages.keys.containsAll(listOf(7, 91, 108, 121))) {
        throw KnowledgeLoadException("incomplete book")
    }
    return pages.mapValues { (_, text) -> text.toString().replace(Regex("\\s+"), " ").trim() }
        .filterValues { it.isNotEmpty() }
        .also { if (it.size < 4) throw KnowledgeLoadException("insufficient book text") }
}
