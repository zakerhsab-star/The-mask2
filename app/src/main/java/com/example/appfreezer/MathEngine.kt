package com.example.appfreezer

import kotlin.math.*

/**
 * محرك حسابي بسيط (recursive descent) بدون أي مكتبة خارجية.
 * يدعم: + - * / % ^ والأقواس، وsin cos tan log ln sqrt، وπ.
 * الزوايا بالدرجات (sin/cos/tan).
 */
object MathEngine {

    class MathError(msg: String) : Exception(msg)

    fun evaluate(raw: String): Double {
        val tokens = tokenize(raw)
        if (tokens.isEmpty()) throw MathError("فاضي")
        val (value, rest) = parseExpression(tokens, 0)
        if (rest != tokens.size) throw MathError("صيغة غير صحيحة")
        if (value.isNaN() || value.isInfinite()) throw MathError("خطأ")
        return value
    }

    private sealed class Tok {
        data class Num(val v: Double) : Tok()
        data class Op(val c: Char) : Tok()
        data class Fn(val name: String) : Tok()
        object LParen : Tok()
        object RParen : Tok()
    }

    private fun tokenize(s: String): List<Tok> {
        val out = mutableListOf<Tok>()
        val src = s.replace("π", "pi").replace("×", "*").replace("÷", "/").replace("−", "-")
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || c == '.' -> {
                    val start = i
                    while (i < src.length && (src[i].isDigit() || src[i] == '.')) i++
                    out += Tok.Num(src.substring(start, i).toDoubleOrNull() ?: throw MathError("رقم غير صحيح"))
                }
                c == '(' -> { out += Tok.LParen; i++ }
                c == ')' -> { out += Tok.RParen; i++ }
                c in "+-*/%^" -> { out += Tok.Op(c); i++ }
                c.isLetter() -> {
                    val start = i
                    while (i < src.length && src[i].isLetter()) i++
                    val word = src.substring(start, i)
                    if (word == "pi") out += Tok.Num(PI) else out += Tok.Fn(word)
                }
                else -> throw MathError("رمز غير معروف")
            }
        }
        return out
    }

    // expr := term (('+'|'-') term)*
    private fun parseExpression(t: List<Tok>, pos: Int): Pair<Double, Int> {
        var (left, p) = parseTerm(t, pos)
        while (p < t.size) {
            val tok = t[p]
            if (tok is Tok.Op && (tok.c == '+' || tok.c == '-')) {
                val (right, np) = parseTerm(t, p + 1)
                left = if (tok.c == '+') left + right else left - right
                p = np
            } else break
        }
        return left to p
    }

    // term := power (('*'|'/'|'%') power)*
    private fun parseTerm(t: List<Tok>, pos: Int): Pair<Double, Int> {
        var (left, p) = parsePower(t, pos)
        while (p < t.size) {
            val tok = t[p]
            if (tok is Tok.Op && tok.c in "*/%") {
                val (right, np) = parsePower(t, p + 1)
                left = when (tok.c) {
                    '*' -> left * right
                    '/' -> { if (right == 0.0) throw MathError("قسمة على صفر"); left / right }
                    else -> left % right
                }
                p = np
            } else break
        }
        return left to p
    }

    // power := unary ('^' power)?  (يمين لشمال)
    private fun parsePower(t: List<Tok>, pos: Int): Pair<Double, Int> {
        val (base, p) = parseUnary(t, pos)
        val tok = t.getOrNull(p)
        if (tok is Tok.Op && tok.c == '^') {
            val (exp, np) = parsePower(t, p + 1)
            return base.pow(exp) to np
        }
        return base to p
    }

    // unary := '-' unary | postfix
    private fun parseUnary(t: List<Tok>, pos: Int): Pair<Double, Int> {
        val tok = t.getOrNull(pos)
        if (tok is Tok.Op && tok.c == '-') {
            val (v, p) = parseUnary(t, pos + 1)
            return (-v) to p
        }
        return parsePostfix(t, pos)
    }

    // postfix := atom '%'?  (نسبة مئوية: تقسم على 100)
    private fun parsePostfix(t: List<Tok>, pos: Int): Pair<Double, Int> {
        var (v, p) = parseAtom(t, pos)
        if (t.getOrNull(p).let { it is Tok.Op && it.c == '%' }) {
            v /= 100.0
            p++
        }
        return v to p
    }

    // atom := number | fn '(' expr ')' | '(' expr ')'
    private fun parseAtom(t: List<Tok>, pos: Int): Pair<Double, Int> {
        val tok = t.getOrNull(pos) ?: throw MathError("نهاية غير متوقعة")
        return when (tok) {
            is Tok.Num -> tok.v to pos + 1
            is Tok.LParen -> {
                val (v, p) = parseExpression(t, pos + 1)
                if (t.getOrNull(p) !is Tok.RParen) throw MathError("قوس ناقص")
                v to p + 1
            }
            is Tok.Fn -> {
                if (t.getOrNull(pos + 1) !is Tok.LParen) throw MathError("قوس بعد الدالة")
                val (v, np) = parseExpression(t, pos + 2)
                if (t.getOrNull(np) !is Tok.RParen) throw MathError("قوس ناقص")
                val result = when (tok.name) {
                    "sin" -> sin(Math.toRadians(v))
                    "cos" -> cos(Math.toRadians(v))
                    "tan" -> tan(Math.toRadians(v))
                    "log" -> log10(v)
                    "ln" -> ln(v)
                    "sqrt" -> { if (v < 0) throw MathError("جذر عدد سالب") else sqrt(v) }
                    else -> throw MathError("دالة غير معروفة")
                }
                result to np + 1
            }
            is Tok.RParen -> throw MathError("قوس زيادة")
            is Tok.Op -> throw MathError("عملية ناقصة طرف")
        }
    }
}
