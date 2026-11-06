package com.marcofabbian.chatbot.provider

import com.marcofabbian.chatbot.knowledge.KnowledgePassage

interface ChatProvider {
    fun answer(question: String, passages: List<KnowledgePassage>): String
}
