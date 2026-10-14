package com.example.appfreezer

import rikka.shizuku.Shizuku

data class ShellResult(val code: Int, val out: String, val err: String) {
    val ok get() = code == 0
}

/** تنفيذ أوامر shell عن طريق Shizuku (بصلاحيات ADB). */
object ShizukuShell {

    fun isReady(): Boolean = try {
        Shizuku.pingBinder() && !Shizuku.isPreV11()
    } catch (_: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun exec(vararg cmd: String): ShellResult = try {
        // newProcess بقت private في Shizuku 13+، فبنناديها بالـ reflection
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        ).apply { isAccessible = true }
        val p = m.invoke(null, arrayOf(*cmd), null, null) as Process
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        ShellResult(p.waitFor(), out.trim(), err.trim())
    } catch (t: Throwable) {
        ShellResult(-1, "", t.message ?: t.toString())
    }
}
