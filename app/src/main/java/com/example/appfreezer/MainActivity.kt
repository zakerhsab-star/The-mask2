package com.example.appfreezer

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

private data class AppEntry(val label: String, val pkg: String)

private const val RELOCK_AFTER_MS = 30_000L

/** خيارات تمويه الأيقونة والاسم على الشاشة الرئيسية. */
private enum class Disguise(val alias: String, val title: String, val fg: Int, val bg: Color) {
    CALC("AliasCalc", "Calculator", R.drawable.ic_calc_fg, Color(0xFF3C4043)),
    NOTES("AliasNotes", "Notes", R.drawable.ic_notes_fg, Color(0xFFF5C242)),
    CLOCK("AliasClock", "Clock", R.drawable.ic_clock_fg, Color(0xFF1F2A44)),
    MASK("AliasMask", "The Mask", R.drawable.ic_launcher_foreground, Color(0xFF2E2150))
}

class MainActivity : FragmentActivity() {

    private val prefs by lazy { Prefs(this) }

    /** بتزيد كل ما حالة Shizuku تتغير، فالواجهة تعيد فحصها لوحدها. */
    private var shizukuTick by mutableIntStateOf(0)
    private var unlocked by mutableStateOf(true)
    private var stoppedAt = 0L

    private val binderReceived = Shizuku.OnBinderReceivedListener { shizukuTick++ }
    private val binderDead = Shizuku.OnBinderDeadListener { shizukuTick++ }
    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ -> shizukuTick++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permListener)

        unlocked = !prefs.lockEnabled
        applySecureFlag()

        val store = FrozenStore(this)
        val freezer = AppFreezer(store)
        val icons = IconCache(this)

        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaskTheme {
                    Box(Modifier.fillMaxSize().background(InkBackdrop)) {
                        if (unlocked) Root(store, freezer, prefs, icons)
                        else LockScreen()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (prefs.lockEnabled && stoppedAt != 0L &&
            SystemClock.elapsedRealtime() - stoppedAt > RELOCK_AFTER_MS
        ) unlocked = false
    }

    override fun onResume() {
        super.onResume()
        shizukuTick++ // نرجع للتطبيق بعد تشغيل Shizuku، فنعيد الفحص فوراً
    }

    override fun onStop() {
        stoppedAt = SystemClock.elapsedRealtime()
        super.onStop()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permListener)
        super.onDestroy()
    }

    // ───────────── التمويه ─────────────

    private fun applyDisguise(d: Disguise) {
        val pm = packageManager
        // نفعّل الجديد الأول، وبعدين نعطّل الباقي، عشان ما يبقاش في لحظة من غير أيقونة
        pm.setComponentEnabledSetting(
            ComponentName(this, "com.example.appfreezer.${d.alias}"),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        Disguise.values().filter { it != d }.forEach {
            pm.setComponentEnabledSetting(
                ComponentName(this, "com.example.appfreezer.${it.alias}"),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
        prefs.disguise = d.alias
    }

    // ───────────── القفل ─────────────

    private fun applySecureFlag() {
        // مع القفل: نمنع لقطات الشاشة ومعاينة التطبيق في قائمة الأخيرة
        if (prefs.lockEnabled) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun canUseLock(): Boolean =
        BiometricManager.from(this).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) ==
            BiometricManager.BIOMETRIC_SUCCESS

    private fun authenticate(onSuccess: () -> Unit) {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("The Mask")
            .setSubtitle("أكّد هويتك للمتابعة")
            .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(info)
    }

    @Composable
    private fun LockScreen() {
        LaunchedEffect(Unit) { authenticate { unlocked = true } }
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            MaskLogo(96.dp)
            Spacer(Modifier.height(20.dp))
            Text("The Mask", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("التطبيق مقفول", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { authenticate { unlocked = true } },
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("فتح", fontWeight = FontWeight.SemiBold)
            }
        }
    }

    // ───────────── التنقل ─────────────

    @Composable
    private fun Root(store: FrozenStore, freezer: AppFreezer, prefs: Prefs, icons: IconCache) {
        var showSettings by remember { mutableStateOf(false) }
        BackHandler(enabled = showSettings) { showSettings = false }
        Crossfade(targetState = showSettings, label = "screen") { settings ->
            if (settings) SettingsScreen(prefs) { showSettings = false }
            else HomeScreen(store, freezer, prefs, icons) { showSettings = true }
        }
    }

    // ───────────── الشاشة الرئيسية ─────────────

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    @Composable
    private fun HomeScreen(
        store: FrozenStore,
        freezer: AppFreezer,
        prefs: Prefs,
        icons: IconCache,
        onSettings: () -> Unit
    ) {
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        val mode = remember { prefs.mode }
        var tab by remember { mutableIntStateOf(0) }
        var query by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var frozen by remember { mutableStateOf(store.all()) }
        var installed by remember { mutableStateOf<List<AppEntry>?>(null) }
        var selected by remember { mutableStateOf(setOf<String>()) }
        var tick by remember { mutableIntStateOf(0) }
        var showAbout by remember { mutableStateOf(false) }
        val ready = remember(shizukuTick) { ShizukuShell.isReady() }
        val granted = remember(shizukuTick) { ready && ShizukuShell.hasPermission() }

        LaunchedEffect(tick) {
            val frozenNow = store.all()
            frozen = frozenNow
            installed = withContext(Dispatchers.IO) { loadInstalled(frozenNow.map { it.pkg }.toSet()) }
        }

        fun message(r: ShellResult, ok: String) =
            if (r.ok && !r.out.contains("Failure", true)) ok
            else "تعذّر التنفيذ: ${(r.out + " " + r.err).trim().take(120)}"

        fun run(block: () -> String) {
            if (busy) return
            busy = true
            scope.launch {
                val msg = withContext(Dispatchers.IO) { block() }
                busy = false
                selected = emptySet()
                tick++
                snackbar.showSnackbar(msg)
            }
        }

        Scaffold(
            containerColor = Color.Transparent,
            contentColor = Palette.Porcelain,
            contentWindowInsets = WindowInsets.systemBars,
            snackbarHost = {
                SnackbarHost(snackbar) {
                    Snackbar(
                        it,
                        containerColor = Palette.VelvetHi,
                        contentColor = Palette.Porcelain,
                        shape = RoundedCornerShape(14.dp)
                    )
                }
            }
        ) { pad ->
            val q = query.trim()
            val frozenShown = frozen.filter { q.isEmpty() || it.label.contains(q, true) || it.pkg.contains(q, true) }
            val installedShown = installed.orEmpty()
                .filter { q.isEmpty() || it.label.contains(q, true) || it.pkg.contains(q, true) }

            LazyColumn(
                contentPadding = PaddingValues(
                    start = 20.dp, end = 20.dp,
                    top = pad.calculateTopPadding() + 8.dp,
                    bottom = pad.calculateBottomPadding() + 24.dp
                ),
                modifier = Modifier.fillMaxSize()
            ) {
                // الترويسة
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MaskLogo(46.dp, Modifier.clickable { showAbout = true })
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("The Mask", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "اخفِ أي تطبيق وابقِ على بياناته",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = onSettings,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Palette.Velvet)
                        ) {
                            Icon(Icons.Filled.Settings, contentDescription = "الإعدادات", tint = Palette.Brass)
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }

                // حالة Shizuku
                item {
                    StatusStrip(
                        ready = ready,
                        granted = granted,
                        modeLabel = mode.label,
                        frozenCount = frozen.size
                    )
                    Spacer(Modifier.height(20.dp))
                }

                // التبويبات والبحث
                item {
                    Row(Modifier.fillMaxWidth()) {
                        TabLabel("المخفية", frozen.size, tab == 0, Modifier.weight(1f)) { tab = 0 }
                        TabLabel("كل التطبيقات", null, tab == 1, Modifier.weight(1f)) { tab = 1 }
                    }
                    HorizontalDivider(color = Palette.VelvetHi)
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("ابحث باسم التطبيق", color = Palette.Mist) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Palette.Mist) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Palette.Porcelain,
                            unfocusedTextColor = Palette.Porcelain,
                            focusedContainerColor = Palette.Velvet,
                            unfocusedContainerColor = Palette.Velvet,
                            focusedBorderColor = Palette.Brass,
                            unfocusedBorderColor = Color.Transparent,
                            cursorColor = Palette.Brass
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(14.dp))
                }

                if (tab == 0) {
                    if (frozenShown.isEmpty()) {
                        item {
                            EmptyState(
                                if (frozen.isEmpty()) "لا توجد تطبيقات مخفية" else "لا نتائج",
                                if (frozen.isEmpty()) "افتح \"كل التطبيقات\" واضغط إخفاء على أي تطبيق." else "جرّب اسماً آخر."
                            )
                        }
                    }
                    items(frozenShown, key = { "f" + it.pkg }) { app ->
                        FrozenCard(app, icons, granted && !busy, Modifier.animateItem()) {
                            run { message(freezer.restore(app), "تمت استعادة ${app.label}") }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                } else {
                    if (selected.isNotEmpty()) {
                        item {
                            SelectionBar(
                                count = selected.size,
                                enabled = granted && !busy,
                                onHide = {
                                    val targets = installed.orEmpty().filter { it.pkg in selected }
                                    run {
                                        var ok = 0
                                        var firstError = ""
                                        targets.forEach { app ->
                                            val r = freezer.freeze(app.pkg, app.label, mode)
                                            if (r.ok && !r.out.contains("Failure", true)) ok++
                                            else if (firstError.isEmpty()) firstError = "${app.label}: ${(r.out + " " + r.err).trim().take(80)}"
                                        }
                                        "تم إخفاء $ok من ${targets.size}" +
                                            if (firstError.isNotEmpty()) " · تعذّر: $firstError" else ""
                                    }
                                },
                                onCancel = { selected = emptySet() }
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                    if (installed == null) {
                        item { EmptyState("جارٍ التحميل", "ثوانٍ ونعرض تطبيقاتك.") }
                    } else if (installedShown.isEmpty()) {
                        item { EmptyState("لا نتائج", "جرّب اسماً آخر.") }
                    }
                    items(installedShown, key = { "i" + it.pkg }) { app ->
                        InstalledRow(
                            app = app,
                            icons = icons,
                            enabled = granted && !busy,
                            selectionMode = selected.isNotEmpty(),
                            isSelected = app.pkg in selected,
                            modifier = Modifier.animateItem(),
                            onToggle = {
                                selected = if (app.pkg in selected) selected - app.pkg else selected + app.pkg
                            },
                            onHide = {
                                run { message(freezer.freeze(app.pkg, app.label, mode), "تم إخفاء ${app.label}") }
                            }
                        )
                    }
                }
            }
        }

        if (showAbout) AboutDialog { showAbout = false }
    }

    @Composable
    private fun StatusStrip(ready: Boolean, granted: Boolean, modeLabel: String, frozenCount: Int) {
        val (dot, title, sub) = when {
            !ready -> Triple(Palette.Ember, "Shizuku غير شغال", "شغّله من تطبيق Shizuku عبر Wireless debugging.")
            !granted -> Triple(Palette.Brass, "نحتاج إذن Shizuku", "اسمح للتطبيق بتنفيذ الأوامر.")
            else -> Triple(Palette.Sage, "جاهز للعمل", "الطريقة: $modeLabel · المخفية: $frozenCount")
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Palette.Velvet)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Palette.Porcelain)
                Text(sub, fontSize = 12.sp, color = Palette.Mist)
            }
            when {
                !ready -> OutlinedButton(
                    onClick = {
                        packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                            ?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    },
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Palette.Brass),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Brass)
                ) { Text("فتح Shizuku") }
                !granted -> Button(
                    onClick = { Shizuku.requestPermission(1) },
                    shape = RoundedCornerShape(12.dp)
                ) { Text("السماح") }
            }
        }
    }

    @Composable
    private fun TabLabel(text: String, count: Int?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
        Column(
            modifier.clickable(onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text,
                    fontSize = 15.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) Palette.Porcelain else Palette.Mist
                )
                if (count != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "$count",
                        fontSize = 12.sp,
                        color = Palette.Ink,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (selected) Palette.Brass else Palette.Mist)
                            .padding(horizontal = 7.dp, vertical = 1.dp)
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(if (selected) Palette.Brass else Color.Transparent)
            )
        }
    }

    /** شريط يظهر لما نحدد أكتر من تطبيق للإخفاء مرة واحدة. */
    @Composable
    private fun SelectionBar(count: Int, enabled: Boolean, onHide: () -> Unit, onCancel: () -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Palette.VelvetHi)
                .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("$count محدد", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = Palette.Porcelain)
            TextButton(onClick = onCancel) { Text("إلغاء", color = Palette.Mist) }
            Button(onClick = onHide, enabled = enabled, shape = RoundedCornerShape(12.dp)) {
                Text("إخفاء المحدد", fontWeight = FontWeight.SemiBold)
            }
        }
    }

    /** التطبيق المخفي: بطاقة بإطار نحاسي خفيف وأيقونة بلا ألوان. */
    @Composable
    private fun FrozenCard(app: FrozenApp, icons: IconCache, enabled: Boolean, modifier: Modifier, onRestore: () -> Unit) {
        Row(
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Palette.Velvet)
                .border(1.dp, Palette.Brass.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(app.pkg, app.label, icons, veiled = true, size = 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    app.label,
                    fontWeight = FontWeight.SemiBold,
                    color = Palette.Porcelain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text("مخفي · ${app.mode.label}", fontSize = 12.sp, color = Palette.Brass)
            }
            Button(
                onClick = onRestore,
                enabled = enabled,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Palette.Porcelain,
                    contentColor = Palette.Ink,
                    disabledContainerColor = Palette.VelvetHi,
                    disabledContentColor = Palette.Mist
                )
            ) { Text("استعادة", fontWeight = FontWeight.SemiBold) }
        }
    }

    /** التطبيق العادي: صف مسطح. ضغطة طويلة تبدأ التحديد المتعدد. */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun InstalledRow(
        app: AppEntry,
        icons: IconCache,
        enabled: Boolean,
        selectionMode: Boolean,
        isSelected: Boolean,
        modifier: Modifier,
        onToggle: () -> Unit,
        onHide: () -> Unit
    ) {
        Column(modifier) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { if (selectionMode) onToggle() },
                        onLongClick = onToggle
                    )
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon(app.pkg, app.label, icons, veiled = false, size = 44.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        app.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Medium,
                        color = Palette.Porcelain
                    )
                    Text(
                        app.pkg,
                        fontSize = 11.sp,
                        color = Palette.Mist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (selectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggle() },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Palette.Brass,
                            checkmarkColor = Palette.Ink,
                            uncheckedColor = Palette.Mist
                        )
                    )
                } else {
                    OutlinedButton(
                        onClick = onHide,
                        enabled = enabled,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, if (enabled) Palette.Brass else Palette.VelvetHi),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Palette.Brass,
                            disabledContentColor = Palette.Mist
                        )
                    ) { Text("إخفاء") }
                }
            }
            HorizontalDivider(color = Palette.VelvetHi.copy(alpha = 0.6f))
        }
    }

    @Composable
    private fun EmptyState(title: String, hint: String) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            MaskLogo(64.dp)
            Spacer(Modifier.height(14.dp))
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Palette.Porcelain)
            Spacer(Modifier.height(4.dp))
            Text(hint, fontSize = 13.sp, color = Palette.Mist, textAlign = TextAlign.Center)
        }
    }

    // ───────────── About ─────────────

    @Composable
    private fun AboutBlock(logo: Dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MaskLogo(logo)
            Spacer(Modifier.width(14.dp))
            Column {
                Text("The Mask", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.Porcelain)
                Text("Version 0.1", fontSize = 13.sp, color = Palette.Mist)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Developed by Ahmed Alaa",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Palette.Brass
                )
            }
        }
    }

    @Composable
    private fun AboutDialog(onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Palette.Velvet,
            titleContentColor = Palette.Porcelain,
            textContentColor = Palette.Porcelain,
            title = { Text("About") },
            text = { AboutBlock(56.dp) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق", color = Palette.Brass) } }
        )
    }

    // ───────────── شاشة الإعدادات ─────────────

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SettingsScreen(prefs: Prefs, onBack: () -> Unit) {
        var mode by remember { mutableStateOf(prefs.mode) }
        var pendingMode by remember { mutableStateOf<FreezeMode?>(null) }
        var lockOn by remember { mutableStateOf(prefs.lockEnabled) }
        var lockError by remember { mutableStateOf("") }
        var disguise by remember { mutableStateOf(Disguise.values().firstOrNull { it.alias == prefs.disguise } ?: Disguise.CALC) }
        val ready = remember(shizukuTick) { ShizukuShell.isReady() }
        val granted = remember(shizukuTick) { ready && ShizukuShell.hasPermission() }

        fun pick(m: FreezeMode) {
            if (m == FreezeMode.UNINSTALL_KEEP && mode != m) pendingMode = m
            else { mode = m; prefs.mode = m }
        }

        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع", tint = Palette.Porcelain)
                }
                Text("الإعدادات", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Porcelain)
            }

            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SectionTitle("Shizuku")
                StatusStrip(ready = ready, granted = granted, modeLabel = mode.label, frozenCount = 0)

                Spacer(Modifier.height(6.dp))
                SectionTitle("الأمان")
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Palette.Velvet)
                        .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("قفل التطبيق", fontWeight = FontWeight.SemiBold, color = Palette.Porcelain)
                        Text(
                            "بصمة أو قفل الشاشة، ويمنع لقطات الشاشة داخل التطبيق.",
                            fontSize = 12.sp,
                            color = Palette.Mist
                        )
                    }
                    Switch(
                        checked = lockOn,
                        onCheckedChange = { want ->
                            lockError = ""
                            if (want && !canUseLock()) {
                                lockError = "فعّل بصمة أو قفل شاشة للهاتف من إعدادات النظام أولاً."
                            } else {
                                authenticate {
                                    prefs.lockEnabled = want
                                    lockOn = want
                                    applySecureFlag()
                                }
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = Palette.Brass,
                            checkedThumbColor = Palette.Ink,
                            uncheckedTrackColor = Palette.VelvetHi,
                            uncheckedThumbColor = Palette.Mist,
                            uncheckedBorderColor = Palette.Mist
                        )
                    )
                }
                if (lockError.isNotEmpty()) Text(lockError, fontSize = 12.sp, color = Palette.Ember)

                Spacer(Modifier.height(6.dp))
                SectionTitle("تمويه التطبيق")
                Text(
                    "غيّر شكل واسم التطبيق على الشاشة الرئيسية. قد تحتاج ثواني ليتحدث اللانشر، وأي اختصار ثبّتّه قبل التغيير هتحتاج تثبته من جديد.",
                    fontSize = 12.sp,
                    color = Palette.Mist
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Disguise.values().forEach { d ->
                        val sel = disguise == d
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (sel) Palette.VelvetHi else Palette.Velvet)
                                .border(
                                    1.dp,
                                    if (sel) Palette.Brass else Color.Transparent,
                                    RoundedCornerShape(16.dp)
                                )
                                .clickable { disguise = d; applyDisguise(d) }
                                .padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                Modifier.size(48.dp).clip(CircleShape).background(d.bg),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(d.fg),
                                    contentDescription = null,
                                    modifier = Modifier.requiredSize(86.dp)
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(d.title, fontSize = 12.sp, color = Palette.Porcelain, maxLines = 1)
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
                SectionTitle("طريقة الإخفاء")
                FreezeMode.values().forEach { m ->
                    val selected = mode == m
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (selected) Palette.VelvetHi else Palette.Velvet)
                            .border(
                                1.dp,
                                if (selected) Palette.Brass else Color.Transparent,
                                RoundedCornerShape(16.dp)
                            )
                            .clickable { pick(m) }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = { pick(m) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = Palette.Brass,
                                unselectedColor = Palette.Mist
                            )
                        )
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(m.label, fontWeight = FontWeight.SemiBold, color = Palette.Porcelain)
                            Text(m.desc, fontSize = 12.sp, color = Palette.Mist)
                        }
                    }
                }
                Text(
                    "على ريلمي: لو ظهر خطأ Permission Denial، فعّل \"Disable permission monitoring\" من خيارات المطور.",
                    fontSize = 12.sp,
                    color = Palette.Mist
                )

                Spacer(Modifier.height(6.dp))
                SectionTitle("About")
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Palette.Velvet)
                        .border(1.dp, Palette.Brass.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
                        .padding(16.dp)
                ) { AboutBlock(56.dp) }
                Spacer(Modifier.height(16.dp))
            }
        }

        pendingMode?.let { m ->
            AlertDialog(
                onDismissRequest = { pendingMode = null },
                containerColor = Palette.Velvet,
                titleContentColor = Palette.Porcelain,
                textContentColor = Palette.Mist,
                title = { Text("تحذير") },
                text = {
                    Text(
                        "هذه الطريقة تشيل التطبيق من النظام فعلاً. على بعض الأجهزة قد تُحذف ملفات OBB " +
                            "للألعاب الكبيرة أو تفشل الاستعادة. جرّبها على تطبيق صغير أولاً، ولا تبدأ بلعبة."
                    )
                },
                confirmButton = {
                    TextButton(onClick = { mode = m; prefs.mode = m; pendingMode = null }) {
                        Text("أفهم، اختر هذه الطريقة", color = Palette.Ember)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingMode = null }) { Text("إلغاء", color = Palette.Mist) }
                }
            )
        }
    }

    @Composable
    private fun SectionTitle(text: String) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.Mist)
    }

    private fun loadInstalled(frozenPkgs: Set<String>): List<AppEntry> {
        val pm = packageManager
        return pm.getInstalledApplications(0)
            .filter {
                it.flags and ApplicationInfo.FLAG_SYSTEM == 0 &&
                    it.packageName != packageName &&
                    it.packageName !in frozenPkgs
            }
            .map { AppEntry(pm.getApplicationLabel(it).toString(), it.packageName) }
            .sortedBy { it.label.lowercase() }
    }
}
