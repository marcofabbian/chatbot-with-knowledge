package com.example.chatbot

import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

@Controller
class ChatbotController {

    private val logger = LoggerFactory.getLogger(ChatbotController::class.java)

    /**
     * Loads and displays the chatbot page (static/index.html).
     */
    @GetMapping("/", "/chatbot")
    fun chatbotPage(): ResponseEntity<Resource> {
        logger.info("Loading chatbot page")
        val page = ClassPathResource("static/index.html")
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_HTML)
            .body(page)
    }
}
