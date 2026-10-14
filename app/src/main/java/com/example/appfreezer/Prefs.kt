package com.example.appfreezer

import android.content.Context

/** إعدادات التطبيق البسيطة. الافتراضي disable-user لأنه الأكثر ثباتاً على الأجهزة المعدّلة. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var mode: FreezeMode
        get() = runCatching { FreezeMode.valueOf(p.getString("mode", null) ?: "") }
            .getOrDefault(FreezeMode.DISABLE)
        set(v) { p.edit().putString("mode", v.name).apply() }

    /** قفل التطبيق نفسه ببصمة أو قفل الشاشة. */
    var lockEnabled: Boolean
        get() = p.getBoolean("lock", false)
        set(v) { p.edit().putBoolean("lock", v).apply() }

    /** اسم الـ alias المفعّل حالياً (التمويه). الافتراضي الآلة الحاسبة. */
    var disguise: String
        get() = p.getString("disguise", "AliasCalc") ?: "AliasCalc"
        set(v) { p.edit().putString("disguise", v).apply() }
}
