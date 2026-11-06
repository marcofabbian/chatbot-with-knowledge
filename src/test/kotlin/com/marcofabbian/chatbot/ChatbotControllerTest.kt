package com.marcofabbian.chatbot

import com.marcofabbian.chatbot.config.ChatbotProperties
import com.marcofabbian.chatbot.config.GlobalExceptionHandler
import com.marcofabbian.chatbot.knowledge.KnowledgeLoader
import com.marcofabbian.chatbot.knowledge.KnowledgePassage
import com.marcofabbian.chatbot.provider.MockChatProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(ChatbotController::class)
@Import(ChatbotService::class, MockChatProvider::class, GlobalExceptionHandler::class)
@EnableConfigurationProperties(ChatbotProperties::class)
class ChatbotControllerTest(
    @Autowired private val mockMvc: MockMvc
) {
    @MockBean
    private lateinit var knowledge: KnowledgeLoader

    @BeforeEach
    fun setUp() {
        Mockito.`when`(knowledge.available).thenReturn(true)
        Mockito.`when`(knowledge.search("What are municipal bonds?"))
            .thenReturn(listOf(KnowledgePassage("How to Invest Money", 91, "Municipal bonds fund public needs.", 1.0)))
    }

    @Test
    fun `valid question returns answer`() {
        mockMvc.perform(
            post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"question":"What are municipal bonds?"}""")
        )
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.answer").value("Mock excerpt (not an evaluated answer): Municipal bonds fund public needs. [How to Invest Money p.91]"))
    }

    @Test
    fun `status and unavailable chat response`() {
        Mockito.`when`(knowledge.available).thenReturn(false)
        Mockito.`when`(knowledge.search("question")).thenThrow(com.marcofabbian.chatbot.knowledge.KnowledgeUnavailableException())
        mockMvc.perform(get("/api/knowledge/status")).andExpect(status().isOk).andExpect(jsonPath("$.available").value(false))
        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("""{"question":"question"}"""))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.error").exists())
    }

    @Test
    fun `unmatched question returns 422`() {
        Mockito.`when`(knowledge.search("unmatched")).thenReturn(emptyList())
        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("""{"question":"unmatched"}"""))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.error").value("The answer was not found in our knowledge base."))
    }

    @Test
    fun `blank question returns bad request`() {
        mockMvc.perform(
            post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"question":"   "}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error").value("Question must not be blank"))
    }

    @Test
    fun `too long question returns bad request`() {
        val longQuestion = "a".repeat(1001)
        mockMvc.perform(
            post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"question":"$longQuestion"}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("Question must be at most 1000 characters"))
    }
}
