package dev.androidagent.overlay

import dev.androidagent.chat.ChatModelOption
import java.util.Locale
import kotlin.math.abs

internal object ModelPickerFuzzySearch {
    fun filter(models: List<ChatModelOption>, query: String): List<ChatModelOption> {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) return models

        val queryTokens = normalizedQuery.split(' ').filter(String::isNotBlank)
        return models.mapIndexedNotNull { index, model ->
            score(model, normalizedQuery, queryTokens)?.let { score -> RankedModel(model, score, index) }
        }.sortedWith(compareBy<RankedModel>({ it.score }, { it.originalIndex }))
            .map(RankedModel::model)
    }

    private fun score(model: ChatModelOption, normalizedQuery: String, queryTokens: List<String>): Int? {
        val normalizedFields = listOfNotNull(
            model.label,
            model.id,
            model.modelId,
            model.provider
        ).map(::normalize).filter(String::isNotBlank).distinct()
        val variants = buildList {
            normalizedFields.forEach { field ->
                add(field)
                add(field.replace(" ", ""))
                addAll(field.split(' ').filter(String::isNotBlank))
            }
        }.distinct()
        val tokenScore = queryTokens.sumOf { token ->
            variants.minOfOrNull { variant -> matchScore(token, variant) }
                ?.takeIf { it < NO_MATCH }
                ?: return null
        }
        val compactQuery = normalizedQuery.replace(" ", "")
        val fullQueryScore = variants.minOfOrNull { variant -> matchScore(compactQuery, variant) }
            ?.takeIf { it < NO_MATCH }
            ?: NO_MATCH
        return tokenScore + if (fullQueryScore == NO_MATCH) 0 else fullQueryScore / 4
    }

    private fun matchScore(query: String, candidate: String): Int {
        if (query.isEmpty() || candidate.isEmpty()) return NO_MATCH
        if (candidate == query) return 0
        if (candidate.startsWith(query)) return 10 + (candidate.length - query.length).coerceAtMost(20)

        val containsAt = candidate.indexOf(query)
        if (containsAt >= 0) {
            return 35 + containsAt * 2 + (candidate.length - query.length).coerceAtMost(20)
        }

        subsequenceScore(query, candidate)?.let { return 70 + it }

        val editThreshold = when (query.length) {
            in 0..2 -> 0
            in 3..5 -> 1
            in 6..9 -> 2
            else -> 3
        }
        if (editThreshold > 0 && abs(candidate.length - query.length) <= editThreshold) {
            val distance = boundedLevenshtein(query, candidate, editThreshold)
            if (distance <= editThreshold) {
                return 110 + distance * 12 + abs(candidate.length - query.length)
            }
        }
        return NO_MATCH
    }

    private fun subsequenceScore(query: String, candidate: String): Int? {
        var candidateIndex = 0
        var firstMatch = -1
        var previousMatch = -1
        var gaps = 0
        for (character in query) {
            val match = candidate.indexOf(character, candidateIndex)
            if (match < 0) return null
            if (firstMatch < 0) firstMatch = match
            if (previousMatch >= 0) gaps += match - previousMatch - 1
            previousMatch = match
            candidateIndex = match + 1
        }
        return firstMatch * 3 + gaps * 2 + (candidate.length - query.length).coerceAtMost(30)
    }

    private fun boundedLevenshtein(left: String, right: String, limit: Int): Int {
        if (abs(left.length - right.length) > limit) return limit + 1
        var previous = IntArray(right.length + 1) { it }
        left.forEachIndexed { leftIndex, leftCharacter ->
            val current = IntArray(right.length + 1)
            current[0] = leftIndex + 1
            var rowMinimum = current[0]
            right.forEachIndexed { rightIndex, rightCharacter ->
                val substitutionCost = if (leftCharacter == rightCharacter) 0 else 1
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    previous[rightIndex] + substitutionCost
                )
                rowMinimum = minOf(rowMinimum, current[rightIndex + 1])
            }
            if (rowMinimum > limit) return limit + 1
            previous = current
        }
        return previous[right.length]
    }

    private fun normalize(value: String): String {
        return value.lowercase(Locale.ROOT)
            .replace(NON_ALPHANUMERIC, " ")
            .trim()
            .replace(MULTIPLE_SPACES, " ")
    }

    private data class RankedModel(
        val model: ChatModelOption,
        val score: Int,
        val originalIndex: Int
    )

    private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
    private val MULTIPLE_SPACES = Regex("\\s+")
    private const val NO_MATCH = 1_000_000
}

internal class ModelPickerSearchState {
    private val queriesByHarness = mutableMapOf<String, String>()

    fun queryFor(harnessId: String): String = queriesByHarness[harnessId].orEmpty()

    fun update(harnessId: String, query: String) {
        if (query.isBlank()) {
            queriesByHarness.remove(harnessId)
        } else {
            queriesByHarness[harnessId] = query
        }
    }
}
