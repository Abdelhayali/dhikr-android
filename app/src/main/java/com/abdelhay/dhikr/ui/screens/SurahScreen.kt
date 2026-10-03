package com.abdelhay.dhikr.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.StayCurrentLandscape
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.filled.Headphones
import com.abdelhay.dhikr.audio.PlaybackBus
import com.abdelhay.dhikr.audio.Reciters
import com.abdelhay.dhikr.ui.components.ReciterSheet
import com.abdelhay.dhikr.ui.components.ListenControls
import com.abdelhay.dhikr.ui.components.MushafPageView
import com.abdelhay.dhikr.ui.components.PlaybackBar
import com.abdelhay.dhikr.util.ForceOrientation
import com.abdelhay.dhikr.util.toArabicDigits
import com.abdelhay.dhikr.vm.QuranViewModel
import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat


@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SurahScreen(
    vm: QuranViewModel,
    surahId: Int,
    /** آية يُفتح عندها المصحف — تأتي من الفاصل. */
    startAyah: Int? = null,
    onBack: () -> Unit
) {
    val qcfPages by vm.qcfPages.collectAsStateWithLifecycle()
    val index by vm.index.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val tafsir by vm.tafsir.collectAsStateWithLifecycle()
    val playingRef by PlaybackBus.current.collectAsStateWithLifecycle()
    val isPlaying by PlaybackBus.playing.collectAsStateWithLifecycle()
    val round by PlaybackBus.round.collectAsStateWithLifecycle()
    var showReciters by remember { mutableStateOf(false) }

    ForceOrientation(settings.mushafLandscape)
    val ar = settings.arabicNumerals
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(surahId) { vm.open(surahId) }

    // قبل الصفحات عنصران في القائمة: تنبيه التفسير ورأس السورة.
    // بدون طرحهما يسبق رقم الصفحة المعروض موضعَ القارئ بصفحتين.
    // عنصر واحد قبل الصفحات: تنبيه التفسير. رأس السورة صار داخل الصفحة نفسها.
    val headerItems = 1
    fun pageAt(index: Int) = qcfPages.getOrNull(index - headerItems)
    val currentPage = pageAt(listState.firstVisibleItemIndex)

    // المصحف متصل: السورة الجارية هي سورة الصفحة المعروضة، لا السورة التي فُتح عندها.
    // ومن الضحى فما بعدها تتشارك السور الصفحة، فالجارية آخر سورة بلغ عنوانُها أعلى الشاشة.
    val surahTops = remember { mutableStateMapOf<Pair<Int, Int>, Int>() }
    // derivedStateOf: موضع التمرير يتغيّر كل إطار، والشاشة لا تُعاد إلا إذا تبدّلت السورة
    val currentSurah by remember(qcfPages, surahId) {
        derivedStateOf {
            val p = pageAt(listState.firstVisibleItemIndex) ?: return@derivedStateOf surahId
            val scrolled = listState.firstVisibleItemScrollOffset
            p.lines.filter { it.surahStart != 0 }
                .lastOrNull { l -> (surahTops[p.page to l.surahStart] ?: Int.MAX_VALUE) <= scrolled + 2 }
                ?.surahStart
                ?: p.firstAyah?.surah
                ?: surahId
        }
    }
    /** أول آية من السورة الجارية في الصفحة المعروضة — بها يُحفظ الموضع ويُبدأ التشغيل. */
    val currentAyah = currentPage?.ayahs?.firstOrNull { it.surah == currentSurah }?.ayah ?: 1
    val playingAyah = playingRef?.takeIf { it.surah == currentSurah }?.ayah

    /**
     * فتح الصفحة التي تحوي آيةً بعينها.
     * الفاصل يحفظ أول آية في الصفحة، فالعودة إليه تفتح الصفحة نفسها لا آيةً في وسطها.
     *
     * القائمة تتبدّل عند الفتح مرات: قائمة الفتح السابق، ثم فارغة، ثم صفحات السورة،
     * ثم يُلحق ما بعدها وما قبلها. فيُعاد التموضع عند كل تبدّل ما لم يمرّر القارئ بنفسه —
     * ولو تمّ مرةً واحدة لبقيت القائمة عند أولها، أي عند الفاتحة.
     */
    val target = remember(surahId, startAyah) {
        startAyah ?: settings.lastVerse.takeIf { settings.lastSurah == surahId && it > 0 } ?: 1
    }
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    var userMoved by remember(surahId, startAyah) { mutableStateOf(false) }
    LaunchedEffect(dragging) { if (dragging) userMoved = true }

    val targetIndex = qcfPages.indexOfFirst { p -> p.ayahs.any { it.ayah >= target && it.surah == surahId } }
    val targetPage = qcfPages.getOrNull(targetIndex)
    // السورة تبدأ في وسط صفحتها: ننزل إلى عنوانها، ويُعرف موضعه بعد رسم الصفحة
    val targetTop: Int? = when {
        targetPage == null -> null
        targetPage.firstAyah?.surah == surahId -> 0
        else -> surahTops[targetPage.page to surahId]
    }

    LaunchedEffect(qcfPages, target, targetTop) {
        if (targetIndex < 0 || userMoved) return@LaunchedEffect
        listState.scrollToItem(targetIndex + headerItems, targetTop ?: 0)
    }

    // الموضع لا يُحفظ إلا بعد أن يقرأ القارئ ويمرّر — لا أثناء تبدّل القائمة عند الفتح
    LaunchedEffect(listState.firstVisibleItemIndex, qcfPages, currentSurah) {
        if (!userMoved || currentPage == null) return@LaunchedEffect
        vm.rememberPosition(currentSurah, currentAyah)
    }

    /** الفاصل يحفظ أول آية من السورة الجارية في الصفحة، لا الآية التي وقعت عليها العين. */
    fun visibleAyah(): Int = currentAyah

    /**
     * القراءة بملء الشاشة: الأدوات وأشرطة النظام تختفي، وتظهر بلمسة على الصفحة.
     * وتختفي من تلقاء نفسها حين يبدأ القارئ بالتمرير.
     */
    var chrome by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(dragging) { if (dragging) chrome = false }
    ImmersiveMode(enabled = !chrome)
    val toggleChrome: () -> Unit = remember { { chrome = !chrome } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        // الأشرطة تطفو فوق الصفحة ولا تزاحمها — فلا تقفز السطور عند إظهارها وإخفائها
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut()) {
                FloatingActionButton(
                    onClick = { vm.addBookmark(currentSurah, visibleAyah()) },
                    modifier = Modifier.navigationBarsPadding()
                ) {
                    Icon(Icons.Filled.Bookmark, contentDescription = "ضع فاصلًا هنا")
                }
            }
        }
    ) { _ ->
        Box(Modifier.fillMaxSize()) {
            if (qcfPages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    state = listState,
                    // حاشية شريط الحالة ثابتة ولو أُخفي، فلا تتحرك الصفحة حين يظهر
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                        .windowInsetsPadding(WindowInsets.displayCutout),
                    contentPadding = PaddingValues(18.dp, 4.dp, 18.dp, 96.dp)
                ) {
                    item {
                        Text(
                            "المس الصفحة لإظهار الأدوات، واضغط مطوّلًا على آية لقراءة تفسيرها",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        )
                    }

                    items(qcfPages, key = { it.page }) { qp ->
                        MushafPageView(
                            page = qp,
                            fontFamily = vm.fontOf(qp.page),
                            arabicDigits = ar,
                            juz = vm.juzOfPage(qp.page),
                            zoom = settings.quranFontScale,
                            highlight = playingRef,
                            surahNameOf = { id -> index.firstOrNull { it.id == id }?.name.orEmpty() },
                            onAyahTap = { a -> vm.showTafsir(a.surah, a.ayah) },
                            onTap = toggleChrome,
                            onSurahTop = { s, y ->
                                if (surahTops[qp.page to s] != y) surahTops[qp.page to s] = y
                            }
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = chrome,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it }
            ) {
                Column {
                    TopAppBar(
                        title = {
                            Column {
                                Text(vm.surahName(currentSurah).let { if (it.isEmpty()) "" else "سورة $it" })
                                currentPage?.let { p ->
                                    Text(
                                        "الجزء ${vm.juzOfPage(p.page).toArabicDigits(ar)} • صفحة ${p.page.toArabicDigits(ar)}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                            }
                        },
                        actions = {
                            IconButton(onClick = {
                                if (playingAyah != null) vm.togglePlayback()
                                else vm.playFrom(currentSurah, currentAyah)
                            }) {
                                Icon(
                                    if (playingAyah != null && isPlaying) Icons.Filled.Pause
                                    else Icons.Filled.PlayArrow,
                                    contentDescription = "تلاوة السورة"
                                )
                            }
                            IconButton(onClick = { vm.setMushafLandscape(!settings.mushafLandscape) }) {
                                Icon(
                                    if (settings.mushafLandscape) Icons.Filled.StayCurrentPortrait
                                    else Icons.Filled.StayCurrentLandscape,
                                    contentDescription = "تدوير الصفحة"
                                )
                            }
                            IconButton(onClick = { showReciters = true }) {
                                Icon(Icons.Filled.Headphones, contentDescription = "اختيار القارئ")
                            }
                            IconButton(onClick = { vm.setFontScale(settings.quranFontScale - 0.25f) }) {
                                Icon(Icons.Filled.TextDecrease, contentDescription = "تصغير الخط")
                            }
                            IconButton(onClick = { vm.setFontScale(settings.quranFontScale + 0.25f) }) {
                                Icon(Icons.Filled.TextIncrease, contentDescription = "تكبير الخط")
                            }
                        }
                    )

                    if (playingRef != null) {
                        PlaybackBar(
                            label = "الآية ${(playingRef?.ayah ?: 1).toArabicDigits(ar)}",
                            reciterName = Reciters.from(settings.reciter).name,
                            round = round,
                            playing = isPlaying,
                            onToggle = { vm.togglePlayback() },
                            onStop = { vm.stopPlayback() },
                            onPickReciter = { showReciters = true }
                        )
                    }
                }
            }
        }
    }


    if (showReciters) {
        ReciterSheet(
            selectedKey = settings.reciter,
            onPick = { vm.setReciter(it) },
            onDismiss = { showReciters = false }
        )
    }

    // لوحة التفسير
    val t = tafsir
    if (t != null) {
        val tSurah = t.first
        ModalBottomSheet(onDismissRequest = { vm.hideTafsir() }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    "سورة ${vm.surahName(tSurah)} — الآية ${t.second.toArabicDigits(ar)}",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(12.dp))
                // الآية برسم المصحف نفسه، لا بالحروف — فلا يتكرّر خطأ التركيب
                val qp = qcfPages.firstOrNull { p ->
                    p.ayahs.any { it.surah == t.first && it.ayah == t.second }
                }
                val glyphs = qp?.ayahs?.firstOrNull { it.surah == t.first && it.ayah == t.second }
                if (qp != null && glyphs != null) {
                    Text(
                        text = glyphs.glyphs,
                        style = TextStyle(
                            fontFamily = vm.fontOf(qp.page),
                            fontSize = (20 * settings.quranFontScale).sp,
                            lineHeight = (44 * settings.quranFontScale).sp,
                            textAlign = TextAlign.Justify
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(14.dp))
                ListenControls(
                    onListenFromHere = { vm.playFrom(tSurah, t.second); vm.hideTafsir() },
                    onRepeatAyah = { n -> vm.repeatAyah(tSurah, t.second, n); vm.hideTafsir() },
                    onRepeatPage = { n ->
                        val page = qcfPages.firstOrNull { p ->
                            p.ayahs.any { it.surah == tSurah && it.ayah == t.second }
                        }
                        val f = page?.firstAyah
                        val l = page?.lastAyah
                        if (f != null && l != null) {
                            vm.repeatRange(f.surah, f.ayah, l.surah, l.ayah, "تكرار صفحة", n)
                        }
                        vm.hideTafsir()
                    },
                    onListenToEnd = {
                        vm.playFrom(tSurah, t.second, toEndOfQuran = true); vm.hideTafsir()
                    }
                )
                Spacer(Modifier.height(14.dp))
                HorizontalDivider()
                Spacer(Modifier.height(14.dp))
                Text(
                    t.third.ifBlank { "لا يوجد تفسير لهذه الآية في هذه النسخة." },
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 30.sp
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    "التفسير الميسّر — مجمع الملك فهد لطباعة المصحف الشريف",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * إخفاء شريط الحالة وشريط التنقل ما دام [enabled].
 * يظهران مؤقتًا بالسحب من حافة الشاشة، ويعودان كما كانا عند مغادرة الشاشة.
 */
@Composable
private fun ImmersiveMode(enabled: Boolean) {
    val view = LocalView.current
    val window = remember(view) {
        var c = view.context
        while (c is ContextWrapper && c !is Activity) c = c.baseContext
        (c as? Activity)?.window
    } ?: return
    val controller = remember(window) { WindowCompat.getInsetsController(window, view) }

    DisposableEffect(enabled) {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (enabled) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
