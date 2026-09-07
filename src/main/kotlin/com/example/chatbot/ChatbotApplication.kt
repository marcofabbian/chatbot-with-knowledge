package com.example.chatbot

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ChatbotApplication

private val logger = LoggerFactory.getLogger(ChatbotApplication::class.java)

fun main(args: Array<String>) {
    logger.info("Starting ChatbotApplication...")
    runApplication<ChatbotApplication>(*args)
}
