package com.example.appfreezer

import android.content.Context

/** طرق التجميد. هنجرب كل واحدة على الجهاز ونشوف أنسبها. */
enum class FreezeMode(val label: String, val desc: String) {
    DISABLE("تعطيل", "الأكثر ثباتاً: يختفي من الشاشة ويتوقف تماماً (pm disable-user)"),
    HIDE("إخفاء", "يخفيه بالكامل، وقد لا يعمل على بعض الأجهزة (pm hide)"),
    UNINSTALL_KEEP("إلغاء تثبيت مؤقت", "يشيله مع الاحتفاظ بالبيانات. جرّبه على تطبيق صغير أولاً")
}

class AppFreezer(private val store: FrozenStore) {

    fun freeze(pkg: String, label: String, mode: FreezeMode): ShellResult {
        val r = when (mode) {
            FreezeMode.HIDE -> ShizukuShell.exec("pm", "hide", "--user", "0", pkg)
            FreezeMode.DISABLE -> ShizukuShell.exec("pm", "disable-user", "--user", "0", pkg)
            FreezeMode.UNINSTALL_KEEP -> ShizukuShell.exec("pm", "uninstall", "-k", "--user", "0", pkg)
        }
        if (r.ok && !r.out.contains("Failure", true)) store.add(FrozenApp(pkg, label, mode))
        return r
    }

    fun restore(app: FrozenApp): ShellResult {
        val r = when (app.mode) {
            FreezeMode.HIDE -> ShizukuShell.exec("pm", "unhide", "--user", "0", app.pkg)
            FreezeMode.DISABLE -> ShizukuShell.exec("pm", "enable", "--user", "0", app.pkg)
            FreezeMode.UNINSTALL_KEEP ->
                ShizukuShell.exec("cmd", "package", "install-existing", "--user", "0", app.pkg)
        }
        if (r.ok && !r.out.contains("Failure", true)) store.remove(app.pkg)
        return r
    }
}

data class FrozenApp(val pkg: String, val label: String, val mode: FreezeMode)

/** قائمة التطبيقات المجمّدة محفوظة محلياً، لأنها بتختفي من قائمة النظام. */
class FrozenStore(context: Context) {
    private val prefs = context.getSharedPreferences("frozen", Context.MODE_PRIVATE)

    fun all(): List<FrozenApp> = prefs.getStringSet("apps", emptySet()).orEmpty().mapNotNull {
        val p = it.split("|")
        if (p.size != 3) null else FrozenApp(p[0], p[1], runCatching { FreezeMode.valueOf(p[2]) }.getOrDefault(FreezeMode.DISABLE))
    }.sortedBy { it.label }

    fun add(app: FrozenApp) = save(all().filter { it.pkg != app.pkg } + app)
    fun remove(pkg: String) = save(all().filter { it.pkg != pkg })

    private fun save(list: List<FrozenApp>) {
        prefs.edit().putStringSet("apps", list.map { "${it.pkg}|${it.label.replace("|", " ")}|${it.mode.name}" }.toSet()).apply()
    }
}
