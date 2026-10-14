package com.example.appfreezer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class CalcHistory(val expr: String, val result: String)

/**
 * آلة حاسبة شغالة فعلاً. تكتب الكود السري وتدوس "=" فيفتح التطبيق الحقيقي،
 * بدل ما يظهر نتيجة. أي حساب عادي يشتغل زي أي آلة حاسبة طبيعية.
 */
@Composable
fun Calculator(prefs: Prefs, onUnlock: () -> Unit) {
    var expr by remember { mutableStateOf("") }
    var display by remember { mutableStateOf("0") }
    var error by remember { mutableStateOf(false) }
    val history = remember { mutableStateListOf<CalcHistory>() }

    fun append(s: String) {
        error = false
        expr += s
        display = expr
    }

    fun clear() {
        expr = ""; display = "0"; error = false
    }

    fun backspace() {
        if (error) { clear(); return }
        if (expr.isNotEmpty()) expr = expr.dropLast(1)
        display = expr.ifEmpty { "0" }
    }

    fun equals() {
        if (expr.isBlank()) return
        // الكود السري: أرقام فقط ومطابق تماماً للكود المحفوظ في الإعدادات
        if (expr.all { it.isDigit() } && expr == prefs.secretCode) {
            expr = ""; display = "0"; error = false
            onUnlock()
            return
        }
        try {
            val value = MathEngine.evaluate(expr)
            val shown = formatResult(value)
            history.add(0, CalcHistory(expr, shown))
            display = shown
            expr = shown
            error = false
        } catch (e: Exception) {
            display = "خطأ"
            error = true
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF14161C))
            .padding(horizontal = 16.dp, vertical = 24.dp)
    ) {
        if (history.isNotEmpty()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true) {
                items(history) { h ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(h.expr, color = Color(0xFF7D828C), fontSize = 13.sp, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
                        Text("= ${h.result}", color = Color(0xFFC6CAD2), fontSize = 16.sp, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }

        Text(
            display,
            color = if (error) Color(0xFFE05B4F) else Color.White,
            fontSize = 46.sp,
            fontWeight = FontWeight.Light,
            textAlign = TextAlign.End,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("sin(", "cos(", "log(", "sqrt(", "^", "π").forEach { key ->
                CalcKey(key.trimEnd('('), Modifier.weight(1f), small = true) { append(key) }
            }
        }
        Spacer(Modifier.height(6.dp))

        val rows = listOf(
            listOf("AC", "(", ")", "%"),
            listOf("7", "8", "9", "÷"),
            listOf("4", "5", "6", "×"),
            listOf("1", "2", "3", "−"),
            listOf("0", ".", "⌫", "+")
        )
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { key ->
                    CalcKey(key, Modifier.weight(1f)) {
                        when (key) {
                            "AC" -> clear()
                            "⌫" -> backspace()
                            else -> append(key)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        CalcKey("=", Modifier.fillMaxWidth(), accent = true) { equals() }
    }
}

@Composable
private fun CalcKey(
    label: String,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    accent: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier
            .clip(CircleShape)
            .background(if (accent) Color(0xFFF5A623) else Color(0xFF23262E))
            .clickable { onClick() }
            .padding(vertical = if (small) 10.dp else 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (accent) Color(0xFF14161C) else Color.White,
            fontSize = if (small) 14.sp else 19.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun formatResult(v: Double): String {
    if (v.isNaN() || v.isInfinite()) return "خطأ"
    return if (v == v.toLong().toDouble()) v.toLong().toString()
    else {
        val s = "%.8f".format(v).trimEnd('0').trimEnd('.')
        s
    }
}
