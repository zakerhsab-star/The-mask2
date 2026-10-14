package com.example.appfreezer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** ألوان "ذا ماسك": ستارة مسرح داكنة، بورسلين القناع، ونحاس الترس. */
object Palette {
    val Ink = Color(0xFF14101F)
    val Velvet = Color(0xFF1F1830)
    val VelvetHi = Color(0xFF2B2142)
    val Porcelain = Color(0xFFF4EFE7)
    val Brass = Color(0xFFE2B455)
    val Mist = Color(0xFFB9B2C9)
    val Ember = Color(0xFFE5776B)
    val Sage = Color(0xFF8FC9A5)
}

private val Scheme = darkColorScheme(
    primary = Palette.Brass,
    onPrimary = Palette.Ink,
    secondary = Palette.Porcelain,
    onSecondary = Palette.Ink,
    background = Palette.Ink,
    onBackground = Palette.Porcelain,
    surface = Palette.Velvet,
    onSurface = Palette.Porcelain,
    surfaceVariant = Palette.VelvetHi,
    onSurfaceVariant = Palette.Mist,
    outline = Palette.Mist,
    outlineVariant = Palette.VelvetHi,
    error = Palette.Ember
)

@Composable
fun MaskTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme) {
        // السطح ده بيحدد لون النص الافتراضي لكل الشاشات. من غيره النصوص بتطلع سوداء.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Transparent,
            contentColor = Palette.Porcelain,
            content = content
        )
    }
}

val InkBackdrop = Brush.verticalGradient(listOf(Color(0xFF241A3D), Palette.Ink, Palette.Ink))

/** شعار التطبيق: نفس رسمة الأيقونة داخل مربع بخلفية الستارة. */
@Composable
fun MaskLogo(size: Dp = 48.dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(Brush.linearGradient(listOf(Color(0xFF2E2150), Palette.Ink))),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.requiredSize(size * 1.7f)
        )
    }
}

/** أيقونات التطبيقات بتتحفظ كملفات، عشان تفضل ظاهرة حتى بعد ما التطبيق يختفي من النظام. */
class IconCache(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "icons").apply { mkdirs() }
    private val mem = LruCache<String, Bitmap>(150)

    fun get(pkg: String): Bitmap? {
        mem.get(pkg)?.let { return it }
        val file = File(dir, "$pkg.png")
        val fresh = fromSystem(pkg)
        val bmp = if (fresh != null) {
            runCatching { file.outputStream().use { fresh.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            fresh
        } else if (file.exists()) {
            BitmapFactory.decodeFile(file.path)
        } else null
        if (bmp != null) mem.put(pkg, bmp)
        return bmp
    }

    private fun fromSystem(pkg: String): Bitmap? = try {
        val d = ctx.packageManager.getApplicationIcon(pkg)
        val size = 112
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(bmp))
        bmp
    } catch (_: Throwable) {
        null
    }
}

private val Grayscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/** veiled = التطبيق مجمّد، فنعرض أيقونته بلا ألوان وكأن عليها قناع. */
@Composable
fun AppIcon(pkg: String, label: String, cache: IconCache, veiled: Boolean, size: Dp = 46.dp) {
    val bmp by produceState<Bitmap?>(null, pkg) {
        value = withContext(Dispatchers.IO) { cache.get(pkg) }
    }
    val b = bmp
    val shape = RoundedCornerShape(size * 0.3f)
    if (b != null) {
        Image(
            bitmap = b.asImageBitmap(),
            contentDescription = null,
            colorFilter = if (veiled) Grayscale else null,
            modifier = Modifier.size(size).clip(shape).alpha(if (veiled) 0.6f else 1f)
        )
    } else {
        Box(
            Modifier.size(size).clip(shape).background(Palette.VelvetHi),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label.firstOrNull()?.uppercase() ?: "?",
                color = Palette.Mist,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
