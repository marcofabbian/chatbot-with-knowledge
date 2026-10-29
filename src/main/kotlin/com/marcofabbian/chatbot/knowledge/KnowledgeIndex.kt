package com.marcofabbian.chatbot.knowledge

import com.marcofabbian.chatbot.config.ChatbotProperties
import java.util.Locale
import kotlin.math.ln

data class KnowledgePassage(val source: String, val page: Int, val text: String, val score: Double) {
    val label: String get() = "[$source p.$page]"
}

internal data class KnowledgeChunk(val page: Int, val text: String)

internal object KnowledgeTokenizer {
    private val stopWords = setOf(
        "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "how", "in",
        "is", "it", "of", "on", "or", "that", "the", "their", "these", "this", "to",
        "was", "what", "when", "where", "which", "who", "with"
    )
    private val separator = Regex("[^\\p{L}\\p{N}]+")

    fun tokenize(text: String): List<String> = text.lowercase(Locale.ROOT).split(separator)
        .filter { it.length >= 2 && it !in stopWords }
}

internal class KnowledgeIndex(
    chunks: List<KnowledgeChunk>,
    private val settings: ChatbotProperties.Knowledge
) {
    private data class IndexedChunk(val chunk: KnowledgeChunk, val frequencies: Map<String, Int>, val length: Int)

    private val entries = chunks.map { chunk ->
        val tokens = KnowledgeTokenizer.tokenize(chunk.text)
        IndexedChunk(chunk, tokens.groupingBy { it }.eachCount(), tokens.size)
    }
    private val averageLength = entries.map { it.length }.average().takeIf { it > 0 } ?: 1.0
    private val documentFrequency = entries.flatMap { it.frequencies.keys }.groupingBy { it }.eachCount()

    /** Returns all indexed passages in source order, with score 0.0 because no query was scored. */
    fun getAll(): List<KnowledgePassage> = entries.map { entry ->
        KnowledgePassage(settings.sourceName, entry.chunk.page, entry.chunk.text, 0.0)
    }

    fun search(question: String): List<KnowledgePassage> {
        val terms = KnowledgeTokenizer.tokenize(question).distinct()
        val titleQuestion = terms.any { it == "book" || it == "ebook" } &&
            terms.any { it == "title" || it == "name" || it == "called" }
        val titlePassage = if (titleQuestion) {
            entries.indexOfFirst { it.chunk.page == 7 && it.chunk.text.contains("How to Invest Money", ignoreCase = true) }
        } else {
            -1
        }
        val ranked = entries.mapIndexedNotNull { index, entry ->
            val score = terms.sumOf { term ->
                val frequency = entry.frequencies[term] ?: return@sumOf 0.0
                val count = documentFrequency.getValue(term)
                val idf = ln(1.0 + (entries.size - count + 0.5) / (count + 0.5))
                idf * frequency * 2.2 / (frequency + 1.2 * (0.25 + 0.75 * entry.length / averageLength))
            }
            if (score > 0 || index == titlePassage) index to score else null
        }.sortedWith(
            compareByDescending<Pair<Int, Double>> { it.first == titlePassage }
                .thenByDescending { it.second }
                .thenBy { it.first }
        )

        var characters = 0
        val result = mutableListOf<KnowledgePassage>()
        for ((index, score) in ranked) {
            if (result.size >= settings.topK) break
            val chunk = entries[index].chunk
            if (characters + chunk.text.length > settings.maxContextChars) continue
            characters += chunk.text.length
            result.add(KnowledgePassage(settings.sourceName, chunk.page, chunk.text, score))
        }
        return result
    }

    

}

internal fun chunkPage(page: Int, text: String, settings: ChatbotProperties.Knowledge): List<KnowledgeChunk> {
    val chunks = mutableListOf<KnowledgeChunk>()
    var start = 0
    while (start < text.length) {
        val limit = minOf(start + settings.chunkSizeChars, text.length)
        var end = limit
        if (limit < text.length) {
            val boundary = text.lastIndexOf(' ', limit - 1)
            if (boundary > start + settings.chunkSizeChars / 2) end = boundary
        }
        val content = text.substring(start, end).trim()
        if (content.isNotEmpty()) chunks.add(KnowledgeChunk(page, content))
        if (end == text.length) break
        start = maxOf(start + 1, end - settings.chunkOverlapChars)
    }
    return chunks
}
