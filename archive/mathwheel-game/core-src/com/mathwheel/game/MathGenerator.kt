package com.mathwheel.game

import kotlin.random.Random

enum class Op { ADD, SUB, MUL, DIV, PERCENT, SQUARE, PATTERN }

data class Equation(
    val text: String,
    val answer: Int,
    val options: List<Int>
)

object MathGenerator {

    fun generate(section: Int, level: Int, optionCount: Int, rng: Random): Equation {
        val difficulty = (section - 1) + (level - 1) / 3
        val op = chooseOp(difficulty, rng)
        return build(op, difficulty, optionCount, rng)
    }

    private fun chooseOp(difficulty: Int, rng: Random): Op {
        return when {
            difficulty < 1 -> pick(rng, Op.ADD, Op.SUB)
            difficulty < 2 -> pick(rng, Op.ADD, Op.SUB, Op.MUL)
            difficulty < 3 -> pick(rng, Op.ADD, Op.SUB, Op.MUL, Op.DIV)
            else -> pick(rng, Op.ADD, Op.SUB, Op.MUL, Op.DIV, Op.PERCENT, Op.SQUARE, Op.PATTERN)
        }
    }

    private fun pick(rng: Random, vararg ops: Op): Op = ops[rng.nextInt(ops.size)]

    private fun build(op: Op, difficulty: Int, optionCount: Int, rng: Random): Equation {
        val maxVal = when {
            difficulty < 1 -> 20
            difficulty < 2 -> 50
            difficulty < 3 -> 100
            else -> 200
        }
        val (text, answer) = when (op) {
            Op.ADD -> {
                val a = rng.nextInt(1, maxVal + 1)
                val b = rng.nextInt(1, maxVal + 1)
                "$a + $b = ?" to (a + b)
            }
            Op.SUB -> {
                val a = rng.nextInt(2, maxVal + 1)
                val b = rng.nextInt(1, a + 1)
                "$a \u2212 $b = ?" to (a - b)
            }
            Op.MUL -> {
                val a = rng.nextInt(2, (maxVal / 2).coerceAtLeast(3) + 1)
                val b = rng.nextInt(2, 13)
                "$a \u00d7 $b = ?" to (a * b)
            }
            Op.DIV -> {
                val b = rng.nextInt(2, 13)
                val q = rng.nextInt(2, (maxVal / b).coerceAtLeast(3) + 1)
                val a = b * q
                "$a \u00f7 $b = ?" to q
            }
            Op.PERCENT -> {
                val p = listOf(10, 20, 25, 50, 75).random(rng)
                val n = rng.nextInt(1, (maxVal / 20).coerceAtLeast(2) + 1) * 20
                "$p% of $n = ?" to (n * p / 100)
            }
            Op.SQUARE -> {
                val n = rng.nextInt(2, 16)
                "$n\u00b2 = ?" to (n * n)
            }
            Op.PATTERN -> {
                val step = rng.nextInt(2, 10)
                val start = rng.nextInt(1, 30)
                "$start, ${start + step}, ${start + 2 * step}, ?" to (start + 3 * step)
            }
        }
        val options = buildOptions(answer, optionCount, maxVal, rng)
        return Equation(text, answer, options)
    }

    private fun buildOptions(answer: Int, optionCount: Int, maxVal: Int, rng: Random): List<Int> {
        val distractors = LinkedHashSet<Int>()
        val spread = (maxVal / 12).coerceAtLeast(4)
        var attempts = 0
        while (distractors.size < optionCount - 1 && attempts < 400) {
            val delta = rng.nextInt(-spread, spread + 1)
            val v = answer + delta
            if (v >= 0 && v != answer) distractors.add(v)
            attempts++
        }
        var off = 1
        while (distractors.size < optionCount - 1) {
            if (answer + off >= 0 && answer + off != answer) distractors.add(answer + off)
            if (distractors.size >= optionCount - 1) break
            if (answer - off >= 0 && answer - off != answer) distractors.add(answer - off)
            off++
        }
        return (distractors + answer).shuffled(rng)
    }
}
