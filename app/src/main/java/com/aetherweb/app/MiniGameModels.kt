package com.aetherweb.app

import org.json.JSONObject
import org.json.JSONArray

data class TicTacToeState(
    val board: List<String> = List(9) { "" },
    val isXTurn: Boolean = true,
    val xPlayerId: String = "",
    val oPlayerId: String = "",
    val winner: String = "",
    val botThinking: Boolean = false
) {
    
    /**
     * If the side to move is a bot, apply its move immediately.
     * Call after claiming a bot seat — otherwise a bot playing X stalls,
     * since the old code only triggered bot moves after a human move.
     */
    fun maybeBotMove(): TicTacToeState {
        if (winner.isNotEmpty()) return this
        return if ((isXTurn && xPlayerId == "bot") || (!isXTurn && oPlayerId == "bot")) makeBotMove() else this
    }

    fun makeBotMove(): TicTacToeState {
        if (!board.contains("")) return this
        if (winner.isNotEmpty()) return this

        val botPiece = if (isXTurn) "X" else "O"
        val humanPiece = if (isXTurn) "O" else "X"

        // Full minimax: bot plays perfectly. With optimal play TTT is always
        // a draw, which feels "human-expert" rather than "random beginner".
        // Move ordering: center, corners, edges (human-like preferences on ties).
        val order = listOf(4, 0, 2, 6, 8, 1, 3, 5, 7)
        var bestScore = Int.MIN_VALUE
        var bestMove = -1
        for (i in order) {
            if (board[i].isNotEmpty()) continue
            val test = board.toMutableList()
            test[i] = botPiece
            val score = minimax(test, false, botPiece, humanPiece, 0)
            if (score > bestScore) {
                bestScore = score
                bestMove = i
            }
        }
        if (bestMove == -1) return this
        val next = board.toMutableList()
        next[bestMove] = botPiece
        return this.copy(board = next, isXTurn = !isXTurn).checkWinner()
    }

    private fun minimax(board: List<String>, isMax: Boolean, bot: String, human: String, depth: Int): Int {
        val result = evalBoard(board)
        if (result == bot) return 10 - depth
        if (result == human) return depth - 10
        if (!board.contains("")) return 0
        return if (isMax) {
            var best = Int.MIN_VALUE
            for (i in board.indices) {
                if (board[i].isNotEmpty()) continue
                val test = board.toMutableList()
                test[i] = bot
                best = maxOf(best, minimax(test, false, bot, human, depth + 1))
            }
            best
        } else {
            var best = Int.MAX_VALUE
            for (i in board.indices) {
                if (board[i].isNotEmpty()) continue
                val test = board.toMutableList()
                test[i] = human
                best = minOf(best, minimax(test, true, bot, human, depth + 1))
            }
            best
        }
    }

    private fun evalBoard(board: List<String>): String {
        val lines = listOf(
            listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7, 8),
            listOf(0, 3, 6), listOf(1, 4, 7), listOf(2, 5, 8),
            listOf(0, 4, 8), listOf(2, 4, 6)
        )
        for (line in lines) {
            if (board[line[0]].isNotEmpty() && board[line[0]] == board[line[1]] && board[line[1]] == board[line[2]]) {
                return board[line[0]]
            }
        }
        return ""
    }

    fun checkWinner(): TicTacToeState {
        val lines = listOf(
            listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7, 8),
            listOf(0, 3, 6), listOf(1, 4, 7), listOf(2, 5, 8),
            listOf(0, 4, 8), listOf(2, 4, 6)
        )
        for (line in lines) {
            if (board[line[0]].isNotEmpty() && board[line[0]] == board[line[1]] && board[line[1]] == board[line[2]]) {
                return this.copy(winner = board[line[0]])
            }
        }
        if (!board.contains("")) return this.copy(winner = "Draw")
        return this
    }

    fun toJson(): String {
        val obj = JSONObject()
        val arr = JSONArray()
        board.forEach { arr.put(it) }
        obj.put("board", arr)
        obj.put("isXTurn", isXTurn)
        obj.put("xPlayerId", xPlayerId)
        obj.put("oPlayerId", oPlayerId)
        obj.put("winner", winner)
        return obj.toString()
    }

    companion object {
        fun fromJson(json: String): TicTacToeState {
            val obj = JSONObject(json)
            val arr = obj.getJSONArray("board")
            val board = List(9) { i -> arr.getString(i) }
            return TicTacToeState(
                board = board,
                isXTurn = obj.getBoolean("isXTurn"),
                xPlayerId = obj.getString("xPlayerId"),
                oPlayerId = obj.getString("oPlayerId"),
                winner = obj.getString("winner")
            )
        }
    }
}

data class Connect4State(
    val board: List<List<Int>> = List(6) { List(7) { 0 } }, // 0 empty, 1 red, 2 yellow
    val isRedTurn: Boolean = true,
    val redPlayerId: String = "",
    val yellowPlayerId: String = "",
    val winner: String = "",
    val botThinking: Boolean = false
) {
    
    /**
     * If the side to move is a bot, apply its move immediately.
     * Call after claiming a bot seat — otherwise a bot playing Red stalls,
     * since the old code only triggered bot moves after a human move.
     */
    fun maybeBotMove(): Connect4State {
        if (winner.isNotEmpty()) return this
        return if ((isRedTurn && redPlayerId == "bot") || (!isRedTurn && yellowPlayerId == "bot")) makeBotMove() else this
    }

    fun makeBotMove(): Connect4State {
        if (winner.isNotEmpty()) return this

        val availCols = mutableListOf<Int>()
        for (c in 0..6) {
            if (board[0][c] == 0) availCols.add(c)
        }
        if (availCols.isEmpty()) return this

        val botPiece = if (isRedTurn) 1 else 2
        val humanPiece = if (isRedTurn) 2 else 1

        // 1. Win immediately if possible (human-like: never misses a win)
        for (c in availCols) {
            val test = board.map { it.toMutableList() }
            if (dropPiece(test, c, botPiece) && this.copy(board = test).checkWinner().winner.isNotEmpty()) {
                return this.copy(board = test, isRedTurn = !isRedTurn).checkWinner()
            }
        }
        // 2. Block immediate loss
        for (c in availCols) {
            val test = board.map { it.toMutableList() }
            if (dropPiece(test, c, humanPiece) && this.copy(board = test).checkWinner().winner.isNotEmpty()) {
                val real = board.map { it.toMutableList() }
                dropPiece(real, c, botPiece)
                return this.copy(board = real, isRedTurn = !isRedTurn).checkWinner()
            }
        }

        // 3. 4-ply minimax with positional scoring — plays like a decent casual player.
        // Center-first move ordering (human-like preference).
        val order = listOf(3, 2, 4, 1, 5, 0, 6).filter { it in availCols }
        var bestScore = Int.MIN_VALUE
        var bestCol = order.first()
        val alphaInit = Int.MIN_VALUE
        for (c in order) {
            val test = board.map { it.toMutableList() }
            dropPiece(test, c, botPiece)
            val score = c4Minimax(test, 3, false, botPiece, humanPiece, alphaInit, Int.MAX_VALUE)
            // Small center bias + jitter so it doesn't play identically every game
            val jitter = (Math.random() * 4).toInt()
            val total = score + (3 - Math.abs(3 - c)) * 2 + jitter
            if (total > bestScore) {
                bestScore = total
                bestCol = c
            }
        }
        val next = board.map { it.toMutableList() }
        dropPiece(next, bestCol, botPiece)
        return this.copy(board = next, isRedTurn = !isRedTurn).checkWinner()
    }

    private fun dropPiece(board: List<MutableList<Int>>, col: Int, piece: Int): Boolean {
        for (r in 5 downTo 0) {
            if (board[r][col] == 0) {
                board[r][col] = piece
                return true
            }
        }
        return false
    }

    private fun c4Minimax(board: List<MutableList<Int>>, depth: Int, isMax: Boolean, bot: Int, human: Int, alpha: Int, beta: Int): Int {
        // Terminal check via temp state
        val cols = (0..6).filter { board[0][it] == 0 }
        if (depth == 0 || cols.isEmpty()) return c4Score(board, bot, human)
        var a = alpha
        var b = beta
        return if (isMax) {
            var best = Int.MIN_VALUE
            for (c in cols) {
                val test = board.map { it.toMutableList() }
                dropPiece(test, c, bot)
                if (c4Wins(test, bot)) return 100000
                best = maxOf(best, c4Minimax(test, depth - 1, false, bot, human, a, b))
                a = maxOf(a, best)
                if (b <= a) break
            }
            best
        } else {
            var best = Int.MAX_VALUE
            for (c in cols) {
                val test = board.map { it.toMutableList() }
                dropPiece(test, c, human)
                if (c4Wins(test, human)) return -100000
                best = minOf(best, c4Minimax(test, depth - 1, true, bot, human, a, b))
                b = minOf(b, best)
                if (b <= a) break
            }
            best
        }
    }

    private fun c4Wins(board: List<MutableList<Int>>, piece: Int): Boolean {
        for (r in 0..5) for (c in 0..3)
            if (board[r][c] == piece && board[r][c+1] == piece && board[r][c+2] == piece && board[r][c+3] == piece) return true
        for (r in 0..2) for (c in 0..6)
            if (board[r][c] == piece && board[r+1][c] == piece && board[r+2][c] == piece && board[r+3][c] == piece) return true
        for (r in 0..2) for (c in 0..3)
            if (board[r][c] == piece && board[r+1][c+1] == piece && board[r+2][c+2] == piece && board[r+3][c+3] == piece) return true
        for (r in 3..5) for (c in 0..3)
            if (board[r][c] == piece && board[r-1][c+1] == piece && board[r-2][c+2] == piece && board[r-3][c+3] == piece) return true
        return false
    }

    private fun c4Score(board: List<MutableList<Int>>, bot: Int, human: Int): Int {
        // Count open 2s and 3s for both sides; center control bonus
        var score = 0
        for (r in 0..5) for (c in 0..6) {
            if (board[r][c] == bot && c in 2..4) score += 2
        }
        score += (c4CountWindows(board, bot, 3) - c4CountWindows(board, human, 3)) * 50
        score += (c4CountWindows(board, bot, 2) - c4CountWindows(board, human, 2)) * 8
        return score
    }

    private fun c4CountWindows(board: List<MutableList<Int>>, piece: Int, n: Int): Int {
        var count = 0
        fun windowScore(cells: List<Int>): Boolean {
            return cells.count { it == piece } == n && cells.count { it == 0 } == 4 - n
        }
        for (r in 0..5) for (c in 0..3)
            if (windowScore(listOf(board[r][c], board[r][c+1], board[r][c+2], board[r][c+3]))) count++
        for (r in 0..2) for (c in 0..6)
            if (windowScore(listOf(board[r][c], board[r+1][c], board[r+2][c], board[r+3][c]))) count++
        for (r in 0..2) for (c in 0..3)
            if (windowScore(listOf(board[r][c], board[r+1][c+1], board[r+2][c+2], board[r+3][c+3]))) count++
        for (r in 3..5) for (c in 0..3)
            if (windowScore(listOf(board[r][c], board[r-1][c+1], board[r-2][c+2], board[r-3][c+3]))) count++
        return count
    }

    fun checkWinner(): Connect4State {
        // Horizontal
        for (r in 0..5) {
            for (c in 0..3) {
                if (board[r][c] != 0 && board[r][c] == board[r][c+1] && board[r][c] == board[r][c+2] && board[r][c] == board[r][c+3]) {
                    return this.copy(winner = if(board[r][c] == 1) "Red" else "Yellow")
                }
            }
        }
        // Vertical
        for (r in 0..2) {
            for (c in 0..6) {
                if (board[r][c] != 0 && board[r][c] == board[r+1][c] && board[r][c] == board[r+2][c] && board[r][c] == board[r+3][c]) {
                    return this.copy(winner = if(board[r][c] == 1) "Red" else "Yellow")
                }
            }
        }
        // Diagonal /
        for (r in 3..5) {
            for (c in 0..3) {
                if (board[r][c] != 0 && board[r][c] == board[r-1][c+1] && board[r][c] == board[r-2][c+2] && board[r][c] == board[r-3][c+3]) {
                    return this.copy(winner = if(board[r][c] == 1) "Red" else "Yellow")
                }
            }
        }
        // Diagonal \
        for (r in 0..2) {
            for (c in 0..3) {
                if (board[r][c] != 0 && board[r][c] == board[r+1][c+1] && board[r][c] == board[r+2][c+2] && board[r][c] == board[r+3][c+3]) {
                    return this.copy(winner = if(board[r][c] == 1) "Red" else "Yellow")
                }
            }
        }
        
        // Draw
        if (board[0].all { it != 0 }) return this.copy(winner = "Draw")
        return this
    }

    fun toJson(): String {
        val obj = JSONObject()
        val boardArr = JSONArray()
        for (r in 0..5) {
            val rowArr = JSONArray()
            for (c in 0..6) {
                rowArr.put(board[r][c])
            }
            boardArr.put(rowArr)
        }
        obj.put("board", boardArr)
        obj.put("isRedTurn", isRedTurn)
        obj.put("redPlayerId", redPlayerId)
        obj.put("yellowPlayerId", yellowPlayerId)
        obj.put("winner", winner)
        return obj.toString()
    }

    companion object {
        fun fromJson(json: String): Connect4State {
            val obj = JSONObject(json)
            val boardArr = obj.getJSONArray("board")
            val board = mutableListOf<List<Int>>()
            for (r in 0..5) {
                val rowArr = boardArr.getJSONArray(r)
                val row = mutableListOf<Int>()
                for (c in 0..6) {
                    row.add(rowArr.getInt(c))
                }
                board.add(row)
            }
            return Connect4State(
                board = board,
                isRedTurn = obj.getBoolean("isRedTurn"),
                redPlayerId = obj.getString("redPlayerId"),
                yellowPlayerId = obj.getString("yellowPlayerId"),
                winner = obj.getString("winner")
            )
        }
    }
}
