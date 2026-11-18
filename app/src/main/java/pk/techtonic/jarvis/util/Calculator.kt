package pk.techtonic.jarvis.util

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Safe calculator evaluator (W3). Tokenises the expression and evaluates
 * it with correct precedence (+ - * /, parentheses) via a small recursive
 * descent parser. User input is NEVER executed as code.
 *
 * - Throws [CalculatorException]("divzero") on divide-by-zero or a
 *   non-finite result.
 * - Throws [CalculatorException]("error") on malformed input.
 * - Result formatting: 12 significant digits, trailing zeros stripped
 *   ("2+3*4" -> "14", "0.1+0.2" -> "0.3").
 */
object Calculator {

    class CalculatorException(val code: String) : Exception(code)

    fun evaluate(expr: String): String {
        val parser = Parser(expr)
        val value = parser.parse()
        if (value.isNaN() || value.isInfinite()) {
            throw CalculatorException("divzero")
        }
        return format(value)
    }

    /**
     * 12 significant digits, trailing zeros stripped, plain (non-scientific)
     * output so the result always reads like a normal number.
     */
    private fun format(value: Double): String {
        if (value == 0.0) return "0"
        val rounded = BigDecimal.valueOf(value)
            .round(MathContext(12, RoundingMode.HALF_UP))
            .stripTrailingZeros()
        return rounded.toPlainString()
    }

    /** Grammar: expr := term (('+'|'-') term)* ; term := factor (('*'|'/') factor)*
        factor := number | '(' expr ')' | ('+'|'-') factor */
    private class Parser(input: String) {
        private val s: String = input.replace("\\s".toRegex(), "")
        private var pos = 0

        fun parse(): Double {
            if (s.isEmpty()) throw CalculatorException("error")
            val value = parseExpr()
            if (pos != s.length) throw CalculatorException("error")
            return value
        }

        private fun parseExpr(): Double {
            var value = parseTerm()
            while (pos < s.length) {
                when (s[pos]) {
                    '+' -> {
                        pos++
                        value += parseTerm()
                    }
                    '-' -> {
                        pos++
                        value -= parseTerm()
                    }
                    else -> return value
                }
            }
            return value
        }

        private fun parseTerm(): Double {
            var value = parseFactor()
            while (pos < s.length) {
                when (s[pos]) {
                    '*' -> {
                        pos++
                        value *= parseFactor()
                    }
                    '/' -> {
                        pos++
                        val divisor = parseFactor()
                        if (divisor == 0.0) throw CalculatorException("divzero")
                        value /= divisor
                    }
                    else -> return value
                }
            }
            return value
        }

        private fun parseFactor(): Double {
            if (pos >= s.length) throw CalculatorException("error")
            return when (val ch = s[pos]) {
                '+' -> {
                    pos++
                    parseFactor()
                }
                '-' -> {
                    pos++
                    -parseFactor()
                }
                '(' -> {
                    pos++
                    val value = parseExpr()
                    if (pos >= s.length || s[pos] != ')') {
                        throw CalculatorException("error")
                    }
                    pos++
                    value
                }
                else -> {
                    if (!ch.isDigit() && ch != '.') throw CalculatorException("error")
                    parseNumber()
                }
            }
        }

        private fun parseNumber(): Double {
            val start = pos
            var seenDot = false
            var seenDigit = false
            while (pos < s.length) {
                val ch = s[pos]
                when {
                    ch.isDigit() -> {
                        seenDigit = true
                        pos++
                    }
                    ch == '.' && !seenDot -> {
                        seenDot = true
                        pos++
                    }
                    else -> break
                }
            }
            if (!seenDigit) throw CalculatorException("error")
            val text = s.substring(start, pos)
            return try {
                text.toDouble()
            } catch (e: NumberFormatException) {
                throw CalculatorException("error")
            }
        }
    }
}
