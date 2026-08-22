package com.anaalarm.data

/**
 * List-price estimates for the usage dashboard. These are display-only approximations: update
 * the constants when DeepSeek pricing changes. The authoritative number is always the user's
 * DeepSeek billing page; the UI labels this value as an estimate.
 */
object UsageCostEstimates {
    const val INPUT_USD_PER_MILLION = 0.28
    const val CACHED_INPUT_USD_PER_MILLION = 0.028
    const val OUTPUT_USD_PER_MILLION = 0.42

    /** Estimates one summary's cost from billable (uncached) input plus output tokens. */
    fun estimateUsd(summary: TokenUsageSummary): Double {
        val uncachedInput = (summary.inputTokens - summary.cachedTokens).coerceAtLeast(0L)
        return uncachedInput / 1_000_000.0 * INPUT_USD_PER_MILLION +
            summary.cachedTokens / 1_000_000.0 * CACHED_INPUT_USD_PER_MILLION +
            summary.outputTokens / 1_000_000.0 * OUTPUT_USD_PER_MILLION
    }
}
