package com.abdelhay.dhikr.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.abdelhay.dhikr.util.DateUtil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.adhkarStore by preferencesDataStore("adhkar_sessions")

/** أوراد جاهزة يفتحها المستخدم من شاشة الورد. */
enum class AdhkarSet(
    val key: String,
    val title: String,
    val subtitle: String,
    /** نوع عدّاد الأشواط إن كان للورد عدّاد. */
    val laps: LapKind? = null
) {
    MORNING("morning", "أذكار الصباح", "تُقال بعد الفجر إلى الضحى"),
    EVENING("evening", "أذكار المساء", "تُقال بعد العصر إلى المغرب"),
    DUA("dua", "أدعية مأثورة", "من القرآن والسنّة"),
    AFTER_PRAYER("after_prayer", "أذكار بعد الصلاة", "عقب كل صلاة مكتوبة"),
    UMRAH("umrah", "أدعية العمرة", "الإحرام والتلبية ودخول المسجد الحرام"),
    TAWAF("tawaf", "أدعية الطواف", "مع عدّاد الأشواط السبعة", LapKind.TAWAF),
    SAI("sai", "أدعية السعي", "مع عدّاد الأشواط بين الصفا والمروة", LapKind.SAI);

    fun presets(): List<Preset> = when (this) {
        MORNING -> Presets.morning
        EVENING -> Presets.evening
        DUA -> Presets.duas
        AFTER_PRAYER -> Presets.afterPrayerAdhkar
        UMRAH -> Presets.umrahDuas
        TAWAF -> Presets.tawafDuas
        SAI -> Presets.saiDuas
    }

    companion object {
        /** أشواط الطواف والسعي سبعة. */
        const val LAPS = 7

        fun from(key: String?): AdhkarSet =
            entries.firstOrNull { it.key == key } ?: MORNING
    }
}

enum class LapKind { TAWAF, SAI }

/**
 * تقدّم الورد الجاهز محفوظ ليومه فقط.
 *
 * لو انقطعتَ في منتصف أذكار الصباح ثم عدت، تجد ما أتممتَه كما تركته؛
 * وفي اليوم التالي يبدأ الورد من جديد بلا تدخّل منك — التاريخ مخزون مع العدّات،
 * فاختلافه عن اليوم يعني تصفيرًا تلقائيًا.
 */
class AdhkarSessionStore(private val context: Context) {

    private companion object {
        const val LAPS_TTL_MS = 12 * 60 * 60 * 1000L
    }

    private fun key(set: AdhkarSet) = stringPreferencesKey("session_${set.key}")

    fun observe(set: AdhkarSet, dayStartHour: Int): Flow<List<Int>> =
        context.adhkarStore.data.map { p -> decode(p[key(set)], dayStartHour) }

    suspend fun setCounts(set: AdhkarSet, counts: List<Int>, dayStartHour: Int) {
        context.adhkarStore.edit { p ->
            p[key(set)] = DateUtil.today(dayStartHour) + "|" + counts.joinToString(",")
        }
    }

    suspend fun reset(set: AdhkarSet, dayStartHour: Int) = setCounts(set, emptyList(), dayStartHour)

    private fun lapsKey(set: AdhkarSet) = stringPreferencesKey("laps_${set.key}")

    /**
     * الأشواط لا تُصفَّر بتبدّل اليوم كالأذكار — فقد يمتدّ الطواف بعد منتصف الليل —
     * بل بمرور [LAPS_TTL_MS] على آخر شوط، أي في نسك جديد.
     */
    fun observeLaps(set: AdhkarSet): Flow<Int> =
        context.adhkarStore.data.map { p -> decodeLaps(p[lapsKey(set)]) }

    suspend fun setLaps(set: AdhkarSet, laps: Int) {
        context.adhkarStore.edit { p ->
            p[lapsKey(set)] = "${System.currentTimeMillis()}|$laps"
        }
    }

    private fun decodeLaps(raw: String?): Int {
        val parts = raw?.split("|", limit = 2) ?: return 0
        val at = parts.getOrNull(0)?.toLongOrNull() ?: return 0
        if (System.currentTimeMillis() - at > LAPS_TTL_MS) return 0
        return parts.getOrNull(1)?.toIntOrNull() ?: 0
    }

    private fun decode(raw: String?, dayStartHour: Int): List<Int> {
        if (raw.isNullOrBlank()) return emptyList()
        val parts = raw.split("|", limit = 2)
        if (parts.size != 2) return emptyList()
        if (parts[0] != DateUtil.today(dayStartHour)) return emptyList()   // يوم جديد
        return parts[1].split(",").mapNotNull { it.trim().toIntOrNull() }
    }
}
