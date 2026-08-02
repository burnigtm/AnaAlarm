package com.anaalarm.ai

object ResponseTextExtractor {

    fun extract(response: ResponsesResponse): String? {
        val topLevel = response.outputText?.trim()
        if (!topLevel.isNullOrBlank()) return topLevel
        return extractFromOutput(response.output)
    }

    fun extractFromOutput(output: List<OutputItem>?): String? {
        if (output == null) return null
        val sb = StringBuilder()
        for (item in output) {
            if (item.type == "message") {
                item.content?.forEach { part ->
                    val t = part.outputText ?: part.text
                    if (!t.isNullOrBlank()) sb.append(t).append('\n')
                }
            }
        }
        return sb.toString().trim().ifBlank { null }
    }
}
