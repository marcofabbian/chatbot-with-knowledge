package com.marcofabbian.chatbot.provider

import com.fasterxml.jackson.annotation.JsonProperty

// Chat Completions API payloads; unknown response fields are ignored via Jackson defaults.
data class OpenAiChatRequest(
    val model: String,
    val messages: List<Message>,
    @param:JsonProperty("max_completion_tokens") val maxCompletionTokens: Int
) {
    data class Message(val role: String, val content: String)
}

data class OpenAiChatResponse(
    val choices: List<Choice> = emptyList()
) {
    data class Choice(val message: Message? = null)

    data class Message(val content: String? = null)
}
