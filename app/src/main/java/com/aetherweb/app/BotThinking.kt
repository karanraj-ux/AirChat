package com.aetherweb.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.random.Random

/**
 * Universal Game Bot Coordinator & Pacing Engine.
 *
 * Provides human-like thinking, evaluation, and action pacing across all games
 * (Ludo, Chess, Tic-Tac-Toe, Connect 4, etc.) to prevent instantaneous 0ms bot loops
 * and give games realistic, observable, and enjoyable rhythm.
 */
enum class GamePacing(val label: String, val icon: String, val description: String) {
    RELAXED("Relaxed", "🐢", "Realistic human thinking (~2.5s - 3.5s/turn)"),
    NORMAL("Normal", "⚡", "Balanced match pacing (~1.5s - 2.0s/turn)"),
    BLITZ("Blitz", "🚀", "Fast-paced action (~0.6s - 0.9s/turn)")
}

object BotThinking {

    /** Current user-selected pacing preference across all games */
    var currentPacing by mutableStateOf(GamePacing.NORMAL)

    /**
     * Randomized turn-awareness delay (bot acknowledges it's its turn).
     */
    fun getTurnAwarenessDelay(pacing: GamePacing = currentPacing): Long = when (pacing) {
        GamePacing.RELAXED -> 700L + Random.nextLong(400L) // 700 - 1100ms
        GamePacing.NORMAL -> 400L + Random.nextLong(300L)  // 400 - 700ms
        GamePacing.BLITZ -> 150L + Random.nextLong(150L)   // 150 - 300ms
    }

    /**
     * Delay before rolling dice (e.g., in Ludo).
     */
    fun getDiceRollDelay(pacing: GamePacing = currentPacing): Long = when (pacing) {
        GamePacing.RELAXED -> 900L + Random.nextLong(500L) // 900 - 1400ms
        GamePacing.NORMAL -> 600L + Random.nextLong(300L)  // 600 - 900ms
        GamePacing.BLITZ -> 250L + Random.nextLong(150L)   // 250 - 400ms
    }

    /**
     * Delay while bot analyzes board and evaluates moves (Chess, Ludo piece choice, Connect 4, Tic-Tac-Toe).
     */
    fun getEvaluationDelay(pacing: GamePacing = currentPacing): Long = when (pacing) {
        GamePacing.RELAXED -> 1200L + Random.nextLong(700L) // 1200 - 1900ms
        GamePacing.NORMAL -> 700L + Random.nextLong(400L)   // 700 - 1100ms
        GamePacing.BLITZ -> 300L + Random.nextLong(200L)    // 300 - 500ms
    }

    /**
     * Brief pause after a move is made before the next turn or animation concludes.
     */
    fun getPostMoveDelay(pacing: GamePacing = currentPacing): Long = when (pacing) {
        GamePacing.RELAXED -> 500L + Random.nextLong(300L) // 500 - 800ms
        GamePacing.NORMAL -> 300L + Random.nextLong(200L)  // 300 - 500ms
        GamePacing.BLITZ -> 100L + Random.nextLong(100L)   // 100 - 200ms
    }

    /** Legacy helper for backward compatibility */
    fun delayMs(fastMode: Boolean = false): Long =
        if (fastMode) getEvaluationDelay(GamePacing.BLITZ) else getEvaluationDelay(currentPacing)

    /** Realistic reaction messages for bots during game actions */
    fun getThinkingLabel(gameType: String = ""): String = when (gameType.lowercase()) {
        "ludo" -> listOf("🎲 Bot is rolling...", "🤔 Choosing best token...", "🎯 Planning path to home...").random()
        "chess" -> listOf("♟️ Evaluating position...", "🤔 Calculating options...", "🛡️ Assessing threats...").random()
        "connect4" -> listOf("🔴 Sizing up columns...", "🤔 Looking for line of 4...", "⚡ Planning drop...").random()
        "tictactoe" -> listOf("⭕ Examining grid...", "🤔 Choosing square...", "🎯 Calculating move...").random()
        else -> "🤖 Bot is thinking…"
    }

    const val THINKING_LABEL = "🤖 thinking…"
}
