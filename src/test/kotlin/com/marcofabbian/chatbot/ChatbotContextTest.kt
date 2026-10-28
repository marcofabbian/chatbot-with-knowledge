package com.marcofabbian.chatbot

import com.marcofabbian.chatbot.config.ChatbotProperties
import com.marcofabbian.chatbot.provider.ChatProvider
import com.marcofabbian.chatbot.provider.MockChatProvider
import com.marcofabbian.chatbot.provider.OpenAiChatProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.web.client.RestClient

class ChatbotContextTest {

    @Configuration(proxyBeanMethods = false)
    @Import(ChatbotService::class, MockChatProvider::class, OpenAiChatProvider::class)
    @EnableConfigurationProperties(ChatbotProperties::class)
    class TestConfig {
        @Bean
        fun restClientBuilder(): RestClient.Builder = RestClient.builder()
    }

    private val runner = ApplicationContextRunner()
        .withUserConfiguration(TestConfig::class.java)

    @Test
    fun `default profile wires the mock provider`() {
        runner.run { context ->
            assertThat(context).hasSingleBean(ChatProvider::class.java)
            assertThat(context.getBean(ChatProvider::class.java)).isInstanceOf(MockChatProvider::class.java)
        }
    }

    @Test
    fun `prod profile without an api key fails at startup`() {
        runner
            .withPropertyValues("spring.profiles.active=prod")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasRootCauseInstanceOf(IllegalArgumentException::class.java)
            }
    }

    @Test
    fun `prod profile with an api key wires the openai provider`() {
        runner
            .withPropertyValues(
                "spring.profiles.active=prod",
                "chatbot.openai.api-key=test-key"
            )
            .run { context ->
                assertThat(context).hasSingleBean(ChatProvider::class.java)
                assertThat(context.getBean(ChatProvider::class.java)).isInstanceOf(OpenAiChatProvider::class.java)
            }
    }
}
