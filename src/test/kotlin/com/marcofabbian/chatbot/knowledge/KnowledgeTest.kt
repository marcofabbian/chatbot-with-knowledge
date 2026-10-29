package com.marcofabbian.chatbot.knowledge

import com.marcofabbian.chatbot.config.ChatbotProperties
import com.marcofabbian.chatbot.ChatbotService
import com.marcofabbian.chatbot.provider.MockChatProvider
import com.marcofabbian.chatbot.provider.OpenAiChatProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.net.SocketTimeoutException

class KnowledgeTest {
    private val settings = ChatbotProperties.Knowledge()
    private val html = javaClass.getResource("/knowledge-fixtures/book.html")!!.readText()

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ChatbotProperties::class)
    @Import(KnowledgeLoader::class)
    class LoaderContext

    @Test
    fun `page parser respects inline markers and excludes non-book text`() {
        val pages = parseBook(html)
        assertTrue(pages.getValue(7).endsWith("provides security"))
        assertEquals("for investors.", pages.getValue(8))
        assertEquals("Municipal bonds finance public projects.", pages.getValue(91))
        assertFalse(pages.values.joinToString().contains("boilerplate"))
        assertFalse(pages.values.joinToString().contains("secret note"))
        assertThrows(KnowledgeLoadException::class.java) { parseBook(html.replace("Page_121", "Page_122")) }
        assertThrows(KnowledgeLoadException::class.java) { parseBook("<html>wrong content</html>") }
    }

    @Test
    fun `chunking and retrieval are deterministic and respect caps`() {
        val small = settings.copy(chunkSizeChars = 200, chunkOverlapChars = 40, topK = 2, maxContextChars = 500)
        val text = ("municipal bonds are safe investments. ").repeat(35).trim()
        val chunks = chunkPage(91, text, small)
        assertTrue(chunks.size > 2)
        assertTrue(chunks.all { it.text.length <= 200 })
        assertEquals(chunks, chunkPage(91, text, small))
        assertTrue(chunks[0].text.takeLast(30).trim() in chunks[1].text)
        assertTrue(chunkPage(7, "", small).isEmpty())
        val index = KnowledgeIndex(chunks + KnowledgeChunk(7, "Railroad mortgage securities."), small)
        val results = index.search("municipal bonds")
        assertEquals(2, results.size)
        assertTrue(results.all { it.page == 91 })
        assertTrue(results.sumOf { it.text.length } <= 500)
        assertEquals(results, index.search("municipal bonds"))
        assertTrue(index.search("the and banana").isEmpty())
    }

    @Test
    fun `getAll returns every passage in source order without search limits`() {
        val settings = settings.copy(topK = 1, maxContextChars = 500)
        val chunks = listOf(
            KnowledgeChunk(7, "investments ".repeat(20)),
            KnowledgeChunk(8, "railroad ".repeat(25)),
            KnowledgeChunk(91, "municipal ".repeat(25))
        )
        val index = KnowledgeIndex(chunks, settings)

        assertEquals(
            chunks.map { KnowledgePassage(settings.sourceName, it.page, it.text, 0.0) },
            index.getAll()
        )
        assertTrue(index.getAll().sumOf { it.text.length } > settings.maxContextChars)
        assertEquals(1, index.search("investments").size)
        assertTrue(KnowledgeIndex(emptyList(), settings).getAll().isEmpty())
    }

    @Test
    fun `loader fetches once without authorization and builds index`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo(settings.sourceUrl)).andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
            .andRespond(withSuccess(html, MediaType.TEXT_HTML))
        val loader = KnowledgeLoader(ChatbotProperties(), builder)
        loader.initialize()
        assertTrue(loader.available)
        assertEquals(91, loader.search("municipal bonds").first().page)
        assertTrue(loader.search("boilerplate").isEmpty())
        assertEquals(listOf(7, 8, 91, 108, 121), loader.getAll().map { it.page })
        assertTrue(loader.getAll().all { it.score == 0.0 })
        server.verify()
    }

    @Test
    fun `book title questions retrieve actual title passage and service returns it`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo(settings.sourceUrl)).andRespond(withSuccess(html, MediaType.TEXT_HTML))
        val loader = KnowledgeLoader(ChatbotProperties(knowledge = settings.copy(topK = 1)), builder)
        loader.initialize()
        val service = ChatbotService(MockChatProvider(), ChatbotProperties(), loader)

        for (question in listOf("What is the title of the book?", "What is this book called?", "What is the name of the ebook?")) {
            val passage = loader.search(question).single()
            assertEquals(7, passage.page)
            assertTrue(passage.text.contains("HOW TO INVEST MONEY"))
            assertTrue(service.answerQuestion(question).contains("HOW TO INVEST MONEY"))
            assertTrue(service.answerQuestion(question).contains("[How to Invest Money p.7]"))
        }
        assertTrue(loader.search("What is the capital of France?").isEmpty())
        server.verify()
    }

    @Test
    fun `book title question reaches openai with the title passage`() {
        val sourceBuilder = RestClient.builder()
        val sourceServer = MockRestServiceServer.bindTo(sourceBuilder).build()
        sourceServer.expect(requestTo(settings.sourceUrl)).andRespond(withSuccess(html, MediaType.TEXT_HTML))
        val properties = ChatbotProperties(openai = ChatbotProperties.OpenAi(apiKey = "test-key"))
        val loader = KnowledgeLoader(properties, sourceBuilder)
        loader.initialize()

        val providerBuilder = RestClient.builder()
        val providerServer = MockRestServiceServer.bindTo(providerBuilder).build()
        providerServer.expect(requestTo("https://api.openai.com/v1/chat/completions"))
            .andExpect(jsonPath("$.messages[0].content").value(org.hamcrest.Matchers.containsString("HOW TO INVEST MONEY")))
            .andExpect(jsonPath("$.messages[0].content").value(org.hamcrest.Matchers.containsString("[How to Invest Money p.7]")))
            .andRespond(withSuccess(
                """{"choices":[{"message":{"content":"The title is How to Invest Money. [How to Invest Money p.7]"}}]}""",
                MediaType.APPLICATION_JSON
            ))
        val service = ChatbotService(OpenAiChatProvider(properties, providerBuilder), properties, loader)
        assertEquals(
            "The title is How to Invest Money. [How to Invest Money p.7]",
            service.answerQuestion("What is the title of the book?")
        )
        sourceServer.verify()
        providerServer.verify()
    }

    @Test
    fun `context completes ingestion before publishing loader`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo(settings.sourceUrl)).andRespond(withSuccess(html, MediaType.TEXT_HTML))
        ApplicationContextRunner().withUserConfiguration(LoaderContext::class.java)
            .withBean("knowledgeRestClientBuilder", RestClient.Builder::class.java, { builder })
            .run { context ->
                assertTrue(context.isRunning)
                assertTrue(context.getBean(KnowledgeLoader::class.java).available)
                assertEquals(91, context.getBean(KnowledgeLoader::class.java).search("municipal bonds").first().page)
            }
        server.verify()
    }

    @Test
    fun `context starts with unavailable knowledge after fetch failure`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo(settings.sourceUrl)).andRespond(withStatus(HttpStatus.BAD_GATEWAY))
        ApplicationContextRunner().withUserConfiguration(LoaderContext::class.java)
            .withBean("knowledgeRestClientBuilder", RestClient.Builder::class.java, { builder })
            .run { context ->
                assertTrue(context.isRunning)
                assertFalse(context.getBean(KnowledgeLoader::class.java).available)
                assertThrows(KnowledgeUnavailableException::class.java) {
                    context.getBean(KnowledgeLoader::class.java).search("municipal bonds")
                }
            }
        server.verify()
    }

    @Test
    fun `invalid nested knowledge settings fail validation`() {
        val builder = RestClient.builder()
        ApplicationContextRunner().withUserConfiguration(LoaderContext::class.java)
            .withBean("knowledgeRestClientBuilder", RestClient.Builder::class.java, { builder })
            .withPropertyValues("chatbot.knowledge.chunk-overlap-chars=800")
            .run { context -> assertTrue(context.startupFailure != null) }
    }

    @Test
    fun `unavailable and disabled loaders reject search`() {
        for (failure in listOf(
            withStatus(HttpStatus.BAD_GATEWAY),
            withSuccess("wrong", MediaType.TEXT_PLAIN),
            withSuccess("<html>wrong</html>", MediaType.TEXT_HTML),
            withSuccess("x".repeat(5 * 1024 * 1024 + 1), MediaType.TEXT_HTML),
            org.springframework.test.web.client.ResponseCreator { throw ResourceAccessException("timeout", SocketTimeoutException()) }
        )) {
            val builder = RestClient.builder()
            val server = MockRestServiceServer.bindTo(builder).build()
            server.expect(requestTo(settings.sourceUrl)).andRespond(failure)
            val loader = KnowledgeLoader(ChatbotProperties(), builder)
            loader.initialize()
            assertFalse(loader.available)
            assertThrows(KnowledgeUnavailableException::class.java) { loader.search("municipal bonds") }
            assertThrows(KnowledgeUnavailableException::class.java) { loader.getAll() }
            server.verify()
        }
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val loader = KnowledgeLoader(ChatbotProperties(knowledge = settings.copy(enabled = false)), builder)
        loader.initialize()
        assertFalse(loader.available)
        assertThrows(KnowledgeUnavailableException::class.java) { loader.search("municipal bonds") }
        assertThrows(KnowledgeUnavailableException::class.java) { loader.getAll() }
        server.verify()
    }

    @Test
    fun `live Gutenberg source is compatible when explicitly enabled`() {
        assumeTrue(System.getenv("KNOWLEDGE_LIVE_TEST") == "true")
        val properties = ChatbotProperties()
        val loader = KnowledgeLoader(properties, KnowledgeClientConfig().knowledgeRestClientBuilder(properties))
        loader.initialize()
        assertTrue(loader.available)
        assertTrue(loader.search("municipal bonds").any { it.page in 91..99 })
        assertTrue(loader.search("What is the title of the book?").first().let {
            it.page == 7 && it.text.contains("How to Invest Money", ignoreCase = true)
        })
        assertTrue(loader.search("railroad equipment bonds").any { it.page in 40..50 })
        assertTrue(loader.search("real estate mortgages").any { it.page in 51..62 })
    }
}
