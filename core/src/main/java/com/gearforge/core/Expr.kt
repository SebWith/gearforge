package com.gearforge.core

import kotlin.math.PI

/**
 * Tiny expression evaluator for parameter fields. Supports the usual arithmetic
 * (`+ - * /`), parentheses and the shorthand identifiers `m`/`module`, `z`/`teeth`
 * and `pi`/`π`, so fields can be driven by expressions like `0.38*m` or `pi*m/2`.
 */
object Expr {

    fun eval(text: String, p: GearParams): Double? = try {
        Parser(text, p).parseExpression()
    } catch (e: Exception) {
        null
    }

    private class Parser(val src: String, val p: GearParams) {
        var pos = 0

        fun parseExpression(): Double {
            var v = parseTerm()
            while (true) {
                skipWs()
                when {
                    peek('+') -> { pos++; v += parseTerm() }
                    peek('-') -> { pos++; v -= parseTerm() }
                    else -> return v
                }
            }
        }

        private fun parseTerm(): Double {
            var v = parseFactor()
            while (true) {
                skipWs()
                when {
                    peek('*') -> { pos++; v *= parseFactor() }
                    peek('/') -> {
                        pos++
                        val d = parseFactor()
                        if (d == 0.0) throw ArithmeticException("division by zero") // audit H6
                        v /= d
                    }
                    else -> return v
                }
            }
        }

        private fun parseFactor(): Double {
            skipWs()
            if (peek('-')) { pos++; return -parseFactor() }
            if (peek('+')) { pos++; return parseFactor() }
            if (peek('(')) {
                pos++
                val v = parseExpression()
                skipWs()
                if (peek(')')) pos++ else throw IllegalArgumentException("missing )")
                return v
            }
            if (peek('π')) { pos++; return PI }
            if (peek('p') && src.startsWith("pi", pos)) { pos += 2; return PI }
            if (peek('m') && (pos + 1 >= src.length || !src[pos + 1].isLetterOrDigit())) {
                pos++; return p.module
            }
            if (src.startsWith("module", pos)) { pos += 6; return p.module }
            if (peek('z') && (pos + 1 >= src.length || !src[pos + 1].isLetterOrDigit())) {
                pos++; return p.teeth.toDouble()
            }
            if (src.startsWith("teeth", pos)) { pos += 5; return p.teeth.toDouble() }
            return parseNumber()
        }

        private fun parseNumber(): Double {
            skipWs()
            val start = pos
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.' || src[pos] == ',')) pos++
            if (start == pos) throw IllegalArgumentException("expected number at $pos")
            val s = src.substring(start, pos).replace(',', '.')
            return s.toDouble()
        }

        private fun skipWs() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun peek(c: Char): Boolean = pos < src.length && src[pos] == c
    }
}
