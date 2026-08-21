package com.anaalarm.ai.stream

/** Thrown when an SSE stream violates the Responses streaming contract. */
internal class SseProtocolException(message: String) : Exception(message)

/**
 * One dispatched text/event-stream frame: an optional event name plus its joined data payload.
 */
internal data class SseFrame(
    val event: String?,
    val data: String
)

/**
 * Incremental `text/event-stream` frame parser for the DeepSeek Responses SSE dialect.
 *
 * Contract implemented here (see docs/RELIABILITY_AND_LATENCY.md):
 * - Frames are delimited by blank lines; CRLF, LF and bare CR all terminate a line.
 * - Repeated `data:` lines are joined with '\n' in arrival order.
 * - Comment lines beginning with ':' (provider keep-alives) are ignored.
 * - `event:` names are captured verbatim; semantic validation happens in the decoder.
 * - A single frame is capped so a hostile or broken peer cannot grow memory without limit.
 * - No reconnect support: `id:`/`retry:` fields are deliberately ignored.
 *
 * The parser is stateful and single-threaded; one instance belongs to one stream read loop.
 */
internal class SseParser(
    private val maxFrameChars: Int = DEFAULT_MAX_FRAME_CHARS
) {
    private val pending = StringBuilder()
    private val dataLines = ArrayList<String>()
    private var eventName: String? = null
    private var frameChars = 0
    private var finished = false

    /** Consumes one decoded text chunk and returns every frame it completed. */
    fun feed(chunk: String): List<SseFrame> {
        check(!finished) { "feed() after finish()" }
        pending.append(chunk)
        val frames = ArrayList<SseFrame>(1)
        while (true) {
            val terminator = nextLineTerminator()
                // A trailing CR could still be the first half of CRLF; wait for more input.
                ?: break
            val line = pending.substring(0, terminator.index)
            val resumeAfter = if (
                pending[terminator.index] == '\r' &&
                terminator.index + 1 < pending.length &&
                pending[terminator.index + 1] == '\n'
            ) {
                terminator.index + 2
            } else {
                terminator.index + 1
            }
            pending.delete(0, resumeAfter)
            processLine(line)?.let(frames::add)
        }
        return frames
    }

    /**
     * Ends the stream: dispatches any buffered final line and any unterminated frame.
     * Frames completed earlier remain the caller's responsibility.
     */
    fun finish(): List<SseFrame> {
        finished = true
        val frames = ArrayList<SseFrame>(1)
        if (pending.isNotEmpty()) {
            var lastLine = pending.toString()
            if (lastLine.endsWith("\r")) lastLine = lastLine.removeSuffix("\r")
            pending.clear()
            processLine(lastLine)?.let(frames::add)
        }
        if (dataLines.isNotEmpty() || eventName != null) {
            dispatch()?.let(frames::add)
        }
        return frames
    }

    private fun nextLineTerminator(): LineTerminator? {
        var index = 0
        while (index < pending.length) {
            when (pending[index]) {
                '\n' -> return LineTerminator(index)
                '\r' ->
                    // Lone CR at the buffer end is ambiguous until the next chunk arrives.
                    if (index == pending.length - 1) return null else return LineTerminator(index)
            }
            index++
        }
        return null
    }

    private fun processLine(line: String): SseFrame? {
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon == -1) line else line.substring(0, colon)
        var value = if (colon == -1) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> eventName = value
            "data" -> {
                frameChars += value.length + 1
                if (frameChars > maxFrameChars) throw SseProtocolException("frame exceeds size cap")
                dataLines.add(value)
            }
        }
        return null
    }

    private fun dispatch(): SseFrame? {
        val name = eventName
        val data = dataLines.joinToString("\n")
        eventName = null
        dataLines.clear()
        frameChars = 0
        // An empty dispatch (blank lines / comments only) is not a frame per the SSE spec.
        if (name == null && data.isEmpty()) return null
        return SseFrame(event = name, data = data)
    }

    private data class LineTerminator(val index: Int)

    companion object {
        const val DEFAULT_MAX_FRAME_CHARS = 64 * 1024
    }
}
