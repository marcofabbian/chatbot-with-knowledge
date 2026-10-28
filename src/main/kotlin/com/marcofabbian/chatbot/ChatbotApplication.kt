package com.marcofabbian.chatbot

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class ChatbotApplication

private val logger = LoggerFactory.getLogger(ChatbotApplication::class.java)

fun main(args: Array<String>) {
    logger.info("Starting ChatbotApplication...")
    runApplication<ChatbotApplication>(*args)
}
