package com.marcofabbian.chatbot.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient

/**
 * Why a builder bean: the provider must not set the request factory itself,
 * otherwise tests could not bind a MockRestServiceServer to the client.
 */
@Configuration
class RestClientConfig {

    @Bean
    fun restClientBuilder(properties: ChatbotProperties): RestClient.Builder {
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(properties.openai.connectTimeout.toMillis().toInt())
            setReadTimeout(properties.openai.readTimeout.toMillis().toInt())
        }
        return RestClient.builder().requestFactory(requestFactory)
    }
}
