package com.voicereader.ui.rewards

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicereader.R
import com.voicereader.rewards.RanksCatalog
import com.voicereader.rewards.Rarity
import com.voicereader.rewards.Reward
import com.voicereader.rewards.RewardEvent
import com.voicereader.rewards.RewardKind
import com.voicereader.rewards.Rewards
import com.voicereader.player.PlayerEngine
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

private fun c01(v: Float) = v.coerceIn(0f, 1f)
private fun sg(t: Float, a: Float, b: Float) = c01((t - a) / (b - a))
private fun out3(k: Float) = 1 - (1 - k).pow(3)
private fun inOut3(k: Float) = if (k < .5f) 4 * k * k * k else 1 - (-2 * k + 2).pow(3) / 2
private fun back(k: Float): Float { val c = 1.7f; return 1 + (c + 1) * (k - 1).pow(3) + c * (k - 1).pow(2) }

/**
 * Праздники во время чтения: новый ранг — окно на весь экран, рекорд и засчитанный день — плашкой сверху.
 * На время окна чтение ставится на паузу ([pauseReading] возвращает, шло ли оно), «Читать дальше» его продолжает.
 */
@Composable
fun RewardCelebrations(
    onApply: (Reward) -> Unit,
    celebrate: String? = null,
    pauseReading: () -> Boolean = { val was = PlayerEngine.state.value.isPlaying; PlayerEngine.pause(); was },
    resumeReading: () -> Unit = { PlayerEngine.play() }
) {
    var rankUp by remember { mutableStateOf<Int?>(null) }
    var wasReading by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val recordText = stringResource(R.string.toast_record)
    val dayText = stringResource(R.string.toast_day)
    LaunchedEffect(Unit) {
        Rewards.events.collect { e ->
            if (!Rewards.state.value.celebrations) return@collect
            when (e) {
                is RewardEvent.RankUp -> {
                    if (rankUp == null) wasReading = pauseReading()
                    rankUp = maxOf(rankUp ?: -1, e.rank)
                }
                is RewardEvent.Record -> toast = recordText.format(e.seconds / 60)
                is RewardEvent.DayCounted -> toast = dayText.format(e.growthDays)
                is RewardEvent.MedalUp -> Unit
            }
        }
    }
    LaunchedEffect(toast) { if (toast != null) { delay(3500); toast = null } }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = toast != null && rankUp == null,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp)
        ) {
            Text(
                toast.orEmpty(),
                color = Color(0xFFDFF7E5), fontSize = 14.sp,
                modifier = Modifier
                    .background(Color(0xF5162C1E), RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0xFF4F8A5E), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
        rankUp?.let { rank ->
            key(rank) {
                RankUpOverlay(rank, celebrate, onApply) {
                    rankUp = null
                    if (wasReading) resumeReading()
                }
            }
        }
    }
}

/** Окно нового ранга: зарядка, вспышка и переворот значка, имя по буквам, карточка награды рубашкой вверх. */
@Composable
private fun RankUpOverlay(rank: Int, celebrate: String?, onApply: (Reward) -> Unit, onClose: () -> Unit) {
    val clock = rememberClock()
    val reward = RanksCatalog.rewardFor(rank)
    val legend = reward?.rarity == Rarity.LEGENDARY
    val era = eraColor(rank)
    var closeAt by remember { mutableStateOf<Float?>(null) }
    var applied by remember { mutableStateOf(false) }
    var badgeCenter by remember { mutableStateOf(Offset.Zero) }
    var cardCenter by remember { mutableStateOf(Offset.Zero) }
    var rootOffset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(closeAt) { if (closeAt != null) { delay(650); onClose() } }
    DisposableEffect(Unit) { Rewards.celebrating = true; onDispose { Rewards.celebrating = false } }
    BackHandler { if (closeAt == null) closeAt = clock.value }

    val tau = clock.value
    val close = closeAt?.let { inOut3(sg(tau, it, it + .6f)) } ?: 0f
    val dim = sg(tau, 0f, .5f) * (1 - close)
    val a = 1 - close
    val name = rankName(rank)
    val nextName = if (rank + 1 < RanksCatalog.COUNT) rankName(rank + 1) else null
    // Карточка переворачивается чуть позже у легендарной: сначала она дрожит.
    val flipStart = if (legend) 5.0f else 4.6f
    val flipEnd = flipStart + .6f

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootOffset = it.boundsInRoot().topLeft }
            .graphicsLayer { alpha = c01(dim * 2) }
            .background(
                Brush.radialGradient(
                    listOf(era.copy(alpha = .2f * sg(tau, 1.5f, 2.4f)), Color(0xE005060A), Color(0xF205060A)),
                    center = if (badgeCenter == Offset.Zero) Offset.Unspecified else badgeCenter, radius = 1400f
                )
            )
            .clickable(remember { MutableInteractionSource() }, indication = null) { }
    ) {
        // Эффекты на весь экран: лучи, зарядка, вспышка, волна, пыль, салют.
        Canvas(Modifier.fillMaxSize()) {
            val c = badgeCenter
            if (c == Offset.Zero) return@Canvas
            val d = density
            val rayA = sg(tau, 1.6f, 2.3f) * a
            if (rayA > 0f) {
                val n = if (legend) 20 else 16
                for (j in 0 until n) rotate(j * 360f / n + tau * (if (legend) 16f else 10f), c) {
                    val len = (if (legend) 210f else 160f) * d * (if (j % 2 == 1) .75f else 1f)
                    val hw = (if (legend) 9f else 7f) * d
                    drawPath(Path().apply { moveTo(c.x - hw, c.y - 40 * d); lineTo(c.x, c.y - len); lineTo(c.x + hw, c.y - 40 * d); close() }, era.copy(alpha = .16f * rayA))
                }
            }
            val ch = sg(tau, .6f, 1.5f)
            if (ch > 0f && tau < 1.75f) {
                val fade = 1 - sg(tau, 1.5f, 1.75f)
                val r = 86 * d
                drawCircle(Color(0x14FFFFFF), r, c, style = Stroke(3 * d))
                drawArc(era.copy(alpha = .5f * fade), -90f, 360f * inOut3(ch), false, Offset(c.x - r, c.y - r), androidx.compose.ui.geometry.Size(r * 2, r * 2), style = Stroke(8 * d, cap = StrokeCap.Round))
                drawArc(Color.White.copy(alpha = fade), -90f, 360f * inOut3(ch), false, Offset(c.x - r, c.y - r), androidx.compose.ui.geometry.Size(r * 2, r * 2), style = Stroke(1.8f * d, cap = StrokeCap.Round))
                for (j in 0 until 18) {
                    val k = c01((ch - rnd(j) * .4f) / .6f)
                    if (k <= 0f || k >= 1f) continue
                    val ang = rnd(j * 3) * 2 * PI.toFloat(); val dist = (170 - 110 * inOut3(k)) * d
                    drawCircle((if (j % 3 != 0) era else Color.White).copy(alpha = sin(k * PI.toFloat())), (1.4f + rnd(j * 5) * 1.6f) * d, Offset(c.x + cos(ang) * dist, c.y + sin(ang) * dist))
                }
            }
            val fl = sg(tau, 1.55f, 2.05f)
            if (fl > 0f && fl < 1f) {
                val r = (40 + 120 * out3(fl)) * d
                drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = .95f * (1 - fl)), era.copy(alpha = .5f * (1 - fl)), Color.Transparent), c, r), r, c)
            }
            val sw = sg(tau, 1.55f, 2.4f)
            if (sw > 0f && sw < 1f) drawCircle(era.copy(alpha = .8f * (1 - sw)), (70 + 230 * out3(sw)) * d, c, style = Stroke((5 * (1 - sw) + .5f) * d))
            val dustA = sg(tau, 1.7f, 2.4f) * a
            if (dustA > 0f) for (j in 0 until (if (legend) 46 else 30)) {
                val life = 3.2f + rnd(j) * 2; val k = (((tau - 1.7f) + rnd(j * 9) * life) % life) / life
                val x = c.x + (rnd(j * 2) - .5f) * 300 * d + sin(tau + j) * 8 * d
                val y = c.y + 120 * d - k * 300 * d
                drawCircle((if (j % 4 != 0) era else Color.White).copy(alpha = dustA * sin(k * PI.toFloat()) * .8f), (1 + rnd(j * 4) * 1.6f) * d, Offset(x, y))
            }
            drawCelebrate(celebrate, sg(tau, 1.6f, 3.4f), c, era, if (legend) 36 else 24)
            // Вспышка редкости в момент переворота карточки.
            val rb = sg(tau, flipStart + .25f, flipStart + 1.2f)
            if (reward != null && cardCenter != Offset.Zero && rb > 0f && rb < 1f) {
                val rc = rarityColor(reward.rarity)
                val n = if (legend) 34 else 20
                for (j in 0 until n) {
                    val ang = j.toFloat() / n * 2 * PI.toFloat()
                    val dist = (90 + (if (legend) 120 else 70) * out3(rb) * (.7f + .3f * rnd(j))) * d
                    drawCircle((if (j % 3 != 0) rc else Color.White).copy(alpha = 1 - rb), 2.6f * (1 - rb * .6f) * d,
                        Offset(cardCenter.x + cos(ang) * dist * 1.25f, cardCenter.y + sin(ang) * dist * .8f - 30 * d * rb * rb))
                }
                if (legend) drawOval(rc.copy(alpha = 1 - rb), Offset(cardCenter.x - 170 * d * out3(rb), cardCenter.y - 130 * d * out3(rb)),
                    androidx.compose.ui.geometry.Size(340 * d * out3(rb), 260 * d * out3(rb)), style = Stroke((4 * (1 - rb) + .5f) * d))
            }
        }

        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val pz = sg(tau, .3f, .8f) * a
            Text(
                stringResource(R.string.reading_paused), fontSize = 12.sp, color = Color(0xFFC9D1DE),
                modifier = Modifier.padding(top = 12.dp).graphicsLayer { alpha = pz; translationY = (1 - pz) * -8 * density }
                    .background(Color(0xEB1E222B), RoundedCornerShape(14.dp)).border(1.dp, Color(0xFF3A4150), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
            Spacer(Modifier.weight(.6f))
            // Значок: старый появляется, заряжается и на вспышке переворачивается в новый.
            val app = back(sg(tau, .2f, .75f))
            val flip = sg(tau, 1.45f, 1.85f)
            val showNew = flip >= .5f
            val sx = abs(cos(flip * PI.toFloat()))
            val ch = sg(tau, .6f, 1.5f)
            val pop = if (showNew) 1 + .22f * sin(sg(tau, 1.65f, 2.35f) * PI.toFloat()) else 1 + .04f * sin(ch * PI.toFloat() * 6) * ch
            val bScale = (.6f + .4f * app) * pop * (1 - .15f * close)
            val stars = if (showNew) (sg(tau, 1.95f, 1.95f + .15f * (RanksCatalog.stepOf(rank) + 1)) * (RanksCatalog.stepOf(rank) + 1) + .001f).toInt() else null
            Box(
                Modifier.size(160.dp, 146.dp)
                    .onGloballyPositioned { val b = it.boundsInRoot(); badgeCenter = b.center - rootOffset }
                    .graphicsLayer { alpha = c01(app * 1.5f) * a; scaleX = bScale * sx; scaleY = bScale }
            ) { RankBadge(if (showNew) rank else (rank - 1).coerceAtLeast(0), Modifier.fillMaxSize(), stars = stars) }

            val kk = sg(tau, 2.0f, 2.5f) * a
            Text(
                stringResource(R.string.new_rank).uppercase(), color = era, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = (14 - 9 * out3(kk)).sp, modifier = Modifier.padding(top = 4.dp).graphicsLayer { alpha = kk }
            )
            // Имя ранга появляется по буквам, потом по нему пробегает блик цвета эпохи.
            val sweep = sg(tau, 2.9f, 3.7f)
            val letters = buildAnnotatedString {
                name.forEachIndexed { k, chr ->
                    val la = sg(tau, 2.2f + k * .045f, 2.5f + k * .045f)
                    val near = (1 - abs(k.toFloat() / name.length - (sweep * 1.4f - .2f)) * 6).coerceAtLeast(0f)
                    withStyle(SpanStyle(color = lerp(Color(0xFFF4F6FB), era, near * .9f).copy(alpha = la))) { append(chr) }
                }
            }
            Text(letters, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.graphicsLayer { alpha = a })
            val pk = sg(tau, 2.8f, 3.6f)
            val from = RanksCatalog.POINTS[(rank - 1).coerceAtLeast(0)]; val to = RanksCatalog.POINTS[rank]
            val pts = formatPoints(from + ((to - from) * out3(pk)).toLong())
            Text(
                if (nextName != null) stringResource(R.string.rank_points_next, pts, formatPoints(RanksCatalog.POINTS[rank + 1] - to), nextName)
                else stringResource(R.string.rank_points_max, pts),
                fontSize = 13.sp, color = Color(0xFFB4BCCB), textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp).graphicsLayer { alpha = c01(pk * 2) * a }
            )

            Spacer(Modifier.height(18.dp))
            if (reward != null) {
                val cardIn = sg(tau, 3.4f, 4.1f)
                val k = back(cardIn)
                val shake = if (legend) sin(tau * 42) * 3.2f * sg(tau, 4.1f, 4.6f) * (1 - sg(tau, 4.8f, 5.0f))
                else sin(tau * 14) * 2 * sg(tau, 4.1f, 4.3f) * (1 - sg(tau, 4.4f, 4.6f))
                val glow = if (legend) sg(tau, 4.1f, 5.0f) else sg(tau, 4.1f, 4.6f) * .6f
                val fk = sg(tau, flipStart, flipEnd); val ang = inOut3(fk) * 180
                val lift = sin(fk * PI.toFloat()) * .12f
                val front = ang >= 90
                Box(
                    Modifier.size(250.dp, 196.dp)
                        .onGloballyPositioned { cardCenter = it.boundsInRoot().center - rootOffset }
                        .graphicsLayer {
                            alpha = c01(cardIn * 2) * a; translationY = (1 - k) * 160 * density
                            rotationZ = shake; scaleX = (1 + lift) * (1 - .1f * close); scaleY = scaleX
                            rotationY = if (front) ang - 180 else ang; cameraDistance = 14 * density
                        }
                ) {
                    if (legend && front) Box(Modifier.matchParentSize().graphicsLayer { scaleX = 1.4f; scaleY = 1.4f; alpha = .55f + .2f * sin(tau * 3) }
                        .background(Brush.radialGradient(listOf(Color(0x59FFC04D), Color.Transparent))))
                    if (front) RewardFront(reward) else RewardBack(reward, glow)
                }
            }
            Spacer(Modifier.weight(1f))
            val bk = sg(tau, flipEnd + .2f, flipEnd + .7f) * a
            Row(
                Modifier.fillMaxWidth().graphicsLayer { alpha = bk; translationY = (1 - out3(bk)) * 16 * density },
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(onClick = { if (closeAt == null) closeAt = tau }, modifier = Modifier.weight(1f).height(50.dp)) {
                    Text(stringResource(R.string.read_on), fontSize = 15.sp, color = Color(0xFFE6EAF2))
                }
                if (reward != null) Button(
                    onClick = { if (!applied) { onApply(reward); applied = true } },
                    modifier = Modifier.weight(1f).height(50.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (applied) Color(0xFF2F6B45) else if (legend) Color(0xFFFFC04D) else Color(0xFF8AB4F8),
                        contentColor = if (applied) Color(0xFFC9F5D6) else Color(0xFF0B1220)
                    )
                ) { Text(stringResource(if (applied) R.string.applied else R.string.apply), fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            }
            Text(
                stringResource(R.string.reward_saved_hint), fontSize = 11.5.sp, color = Color(0xFF8B95A7),
                modifier = Modifier.padding(top = 8.dp, bottom = 14.dp).graphicsLayer { alpha = sg(tau, flipEnd + .5f, flipEnd + 1f) * a }
            )
        }
    }
}

private fun rarityBack(r: Rarity) = when (r) {
    Rarity.COMMON -> listOf(Color(0xFF2A303B), Color(0xFF1A1E26))
    Rarity.RARE -> listOf(Color(0xFF3A2A78), Color(0xFF1C1640))
    Rarity.LEGENDARY -> listOf(Color(0xFF6B4310), Color(0xFF2A1A06))
}
private fun rarityEdge(r: Rarity) = when (r) {
    Rarity.COMMON -> Color(0xFF4A5263); Rarity.RARE -> Color(0xFF7D62E0); Rarity.LEGENDARY -> Color(0xFFFFB340)
}

/** Рубашка карточки: ромбы и «?», цвет — по редкости, чем ближе переворот, тем ярче. */
@Composable
private fun RewardBack(reward: Reward, glow: Float) {
    val edge = rarityEdge(reward.rarity); val rc = rarityColor(reward.rarity)
    Box(
        Modifier.fillMaxSize()
            .background(Brush.radialGradient(rarityBack(reward.rarity)), RoundedCornerShape(20.dp))
            .border(1.5.dp, edge, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.matchParentSize()) {
            for (i in 0 until 9) {
                val x = i * size.width / 8f
                drawLine(edge.copy(alpha = .35f), Offset(x - 30 * density, 0f), Offset(x + 30 * density, size.height / 2), 1f)
                drawLine(edge.copy(alpha = .35f), Offset(x + 30 * density, size.height / 2), Offset(x - 30 * density, size.height), 1f)
                drawLine(edge.copy(alpha = .35f), Offset(x + 30 * density, 0f), Offset(x - 30 * density, size.height / 2), 1f)
                drawLine(edge.copy(alpha = .35f), Offset(x - 30 * density, size.height / 2), Offset(x + 30 * density, size.height), 1f)
            }
            drawCircle(Brush.radialGradient(listOf(rc.copy(alpha = .25f + .35f * glow), Color.Transparent)), size.minDimension * .45f)
        }
        Text("?", fontSize = 72.sp, fontWeight = FontWeight.Bold, color = rc, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif)
        Text(stringResource(reward.rarity.title), fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, color = rc,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp))
    }
}

/** Лицевая сторона: вид награды, живое превью, название, редкость и что она даёт. */
@Composable
private fun RewardFront(reward: Reward) {
    val edge = rarityEdge(reward.rarity); val rc = rarityColor(reward.rarity)
    Box(
        Modifier.fillMaxSize()
            .background(Brush.linearGradient(listOf(lerp(rarityBack(reward.rarity)[0], Color(0xFF101216), .45f), Color(0xFF15171D))), RoundedCornerShape(20.dp))
            .border(1.5.dp, edge, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp, 22.dp, 14.dp, 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            RewardPreview(reward, Modifier.fillMaxWidth().height(84.dp))
            Spacer(Modifier.height(8.dp))
            Text(stringResource(reward.title), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 1)
            Text(stringResource(reward.rarity.title), fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, color = rc)
            Text(
                stringResource(
                    when (reward.kind) {
                        RewardKind.ANIM -> reward.anim!!.hint
                        RewardKind.VOICE -> R.string.voice_reward_desc
                        RewardKind.BACKGROUND -> R.string.bg_reward_desc
                        RewardKind.TEXT -> R.string.text_reward_desc
                        RewardKind.FONT -> R.string.font_reward_desc
                        RewardKind.GROW -> R.string.grow_reward_desc
                    }
                ),
                fontSize = 11.sp, color = Color(0xFF9AA3B2), textAlign = TextAlign.Center, lineHeight = 14.sp, modifier = Modifier.padding(top = 3.dp)
            )
        }
        Text(
            (if (reward.kind == RewardKind.ANIM) stringResource(R.string.kind_anim) + " · " + stringResource(reward.anim!!.title) else stringResource(reward.kind.title)).uppercase(),
            fontSize = 9.5.sp, letterSpacing = .8.sp, fontWeight = FontWeight.Bold,
            color = if (reward.rarity == Rarity.LEGENDARY) Color(0xFF2A1A05) else Color(0xFFF2ECFF),
            modifier = Modifier.align(Alignment.TopCenter).background(edge, RoundedCornerShape(bottomStart = 9.dp, bottomEnd = 9.dp)).padding(horizontal = 10.dp, vertical = 3.dp)
        )
        if (reward.kind == RewardKind.VOICE && reward.voiceId != null) Text(
            stringResource(R.string.reward_listen), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF2A1A05),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 20.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFFFC04D))
                .clickable { PlayerEngine.previewVoice(reward.voiceId) }.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
