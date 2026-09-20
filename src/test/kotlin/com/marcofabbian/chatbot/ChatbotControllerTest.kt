package com.marcofabbian.chatbot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(ChatbotController::class)
@Import(ChatbotService::class, MockChatProvider::class)
class ChatbotControllerTest(
    @Autowired private val mockMvc: MockMvc
) {

    @Test
    fun `valid question returns answer`() {
        mockMvc.perform(
            post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"question":"What is custody and clearing?"}""")
        )
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.answer").value("Mock answer: 'What is custody and clearing?' -> This chatbot can explain custody and clearing services in the securities trade lifecycle."))
    }

    @Test
    fun `blank question returns bad request`() {
        val response = mockMvc.perform(
            post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"question":"   "}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn()

        val body = response.response.contentAsString
        assertEquals(true, body.contains("Question must not be blank"))
    }
}
