package com.marcofabbian.chatbot

import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseBody

@Controller
class ChatbotController(
    private val chatbotService: ChatbotService
) {

    private val logger = LoggerFactory.getLogger(ChatbotController::class.java)

    fun constructor() {
        logger.info("ChatbotController initialized")
    }

    @GetMapping("/about")
    fun about(): ResponseEntity<String> {
        logger.info("Loading about page")
        val message = "This chatbot helps answer questions using the project knowledge base."
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_PLAIN)
            .body(message)
    }

    @PostMapping("/api/chat")
    @ResponseBody
    fun askQuestion(@RequestBody request: ChatbotRequest): ResponseEntity<Any> {
        logger.info("Received question: {}", request.question)

        return try {
            val answer = chatbotService.answerQuestion(request.question)
            ResponseEntity.status(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ChatbotResponse(answer))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ChatbotErrorResponse(e.message ?: "Question must not be blank"))
        }
    }

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

