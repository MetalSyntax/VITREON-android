package com.metalsyntax.vitreon.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.metalsyntax.vitreon.R

/** Temas de la web: claro (#f8fafc), oscuro (#030712 con degradado índigo) y negro OLED (#000). */
enum class Mode(val bg: Long, val surface: Long, val surface2: Long, val ink: Long, val muted: Long, val dark: Boolean) {
    LIGHT(0xFFF8FAFC, 0xB3FFFFFF, 0xFFE2E8F0, 0xFF1E293B, 0xFF64748B, false),
    DARK(0xFF030712, 0x1AFFFFFF, 0x26FFFFFF, 0xFFF1F5F9, 0xFF94A3B8, true),
    BLACK(0xFF000000, 0x14FFFFFF, 0x22FFFFFF, 0xFFFFFFFF, 0xFFA1A1AA, true),
}

enum class Sort { NEWEST, OLDEST, AZ, ZA }
enum class Layout { GRID, LIST, CARD }
enum class Lang(val code: String) { ES("es"), EN("en"), PT("pt") }

/** Paleta de acento como en Parity (Tailwind 500/600); el primero es el índigo de Vitreon (por defecto). */
val ACCENTS = listOf(0xFF6366F1, 0xFF7C3AED, 0xFF9333EA, 0xFFC026D3, 0xFFDB2777, 0xFFE11D48, 0xFFDC2626, 0xFFEA580C, 0xFFD97706,
    0xFFCA8A04, 0xFF65A30D, 0xFF16A34A, 0xFF059669, 0xFF0D9488, 0xFF0891B2, 0xFF0284C7, 0xFF2563EB, 0xFF475569).map { Color(it) }

/** Preferencias de interfaz (localStorage en la web). Estado de Compose: cambiar recompone toda la app (idioma incluido). */
object Prefs {
    private lateinit var sp: android.content.SharedPreferences
    var mode by mutableStateOf(Mode.DARK); private set
    var accent by mutableStateOf(ACCENTS[0]); private set
    var lang by mutableStateOf(Lang.ES); private set
    var sort by mutableStateOf(Sort.NEWEST); private set
    var layout by mutableStateOf(Layout.GRID); private set
    var pinnedCarousel by mutableStateOf(true); private set
    var onboarded by mutableStateOf(false); private set

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)
        mode = Mode.entries.firstOrNull { it.name == sp.getString("mode", null) } ?: Mode.DARK
        accent = Color(sp.getLong("accent", ACCENTS[0].value.toLong()).toULong())
        lang = Lang.entries.firstOrNull { it.name == sp.getString("lang", null) }
            ?: Lang.entries.firstOrNull { it.code == java.util.Locale.getDefault().language } ?: Lang.EN
        sort = Sort.entries.firstOrNull { it.name == sp.getString("sort", null) } ?: Sort.NEWEST
        layout = Layout.entries.firstOrNull { it.name == sp.getString("layout", null) } ?: Layout.GRID
        pinnedCarousel = sp.getBoolean("pinnedCarousel", true)
        onboarded = sp.getBoolean("onboarded", false)
    }

    private fun put(k: String, v: Any) = sp.edit().apply { when (v) { is Boolean -> putBoolean(k, v); is Long -> putLong(k, v); else -> putString(k, v.toString()) } }.apply()
    fun applyMode(m: Mode) { mode = m; put("mode", m.name) }
    fun applyAccent(c: Color) { accent = c; put("accent", c.value.toLong()) }
    fun applyLang(l: Lang) { lang = l; put("lang", l.name) }
    fun cycleSort() { sort = Sort.entries[(sort.ordinal + 1) % Sort.entries.size]; put("sort", sort.name) }
    fun cycleLayout() { layout = Layout.entries[(layout.ordinal + 1) % Layout.entries.size]; put("layout", layout.name) }
    fun togglePinnedCarousel() { pinnedCarousel = !pinnedCarousel; put("pinnedCarousel", pinnedCarousel) }
    fun finishOnboarding() { onboarded = true; put("onboarded", true) }
}

// tokens (leen el tema actual)
val Bg get() = Color(Prefs.mode.bg)
val Surface1 get() = Color(Prefs.mode.surface)
val Surface2 get() = Color(Prefs.mode.surface2)
val Ink get() = Color(Prefs.mode.ink)
val Muted get() = Color(Prefs.mode.muted)
val Brand get() = Prefs.accent        // acento elegido (por defecto índigo-500 de la web)
val BrandDeep get() = androidx.compose.ui.graphics.lerp(Brand, Color.Black, 0.2f)
val Purple = Color(0xFF9333EA)
val Danger = Color(0xFFF43F5E)
val Success = Color(0xFF10B981)
val Warning = Color(0xFFF59E0B)
val FabGradient get() = Brush.linearGradient(listOf(androidx.compose.ui.graphics.lerp(Brand, Color.White, 0.15f), Brand, BrandDeep))  // solo tonos del acento
/** Fondo del tema oscuro: degradado radial del acento (índigo por defecto ≈ #1e1b4b de la web) → #030712. */
val Backdrop get() = if (Prefs.mode == Mode.DARK) Brush.radialGradient(listOf(androidx.compose.ui.graphics.lerp(Brand, Color(0xFF030712), 0.78f), Color(0xFF030712)), radius = 1600f) else Brush.linearGradient(listOf(Bg, Bg))

fun catColor(name: String) = Color(com.metalsyntax.vitreon.data.CATEGORY_COLORS[name] ?: 0xFF64748B)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private val Inter = FontFamily(
    Font(R.font.inter, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.inter, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.inter, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.inter, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.inter, FontWeight.Black, variationSettings = FontVariation.Settings(FontVariation.weight(900))),
)

@Composable
fun VitreonTheme(content: @Composable () -> Unit) {
    val m = Prefs.mode
    val scheme = if (m.dark) darkColorScheme(primary = Brand, background = Bg, surface = Bg, onBackground = Ink, onSurface = Ink, surfaceVariant = Surface2)
    else lightColorScheme(primary = Brand, background = Bg, surface = Bg, onBackground = Ink, onSurface = Ink, surfaceVariant = Surface2)
    val base = TextStyle(fontFamily = Inter)
    MaterialTheme(colorScheme = scheme, typography = Typography(
        displaySmall = base.copy(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp),
        headlineMedium = base.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
        headlineSmall = base.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        titleLarge = base.copy(fontSize = 19.sp, fontWeight = FontWeight.Bold),
        titleMedium = base.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = base.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = base.copy(fontSize = 16.sp, lineHeight = 25.sp),
        bodyMedium = base.copy(fontSize = 14.sp, lineHeight = 21.sp),
        bodySmall = base.copy(fontSize = 12.sp, lineHeight = 17.sp),
        labelLarge = base.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = base.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
        labelSmall = base.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp),
    ), content = content)
}
