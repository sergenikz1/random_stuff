package com.voicereader.ui.rewards

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicereader.R
import com.voicereader.rewards.DailyCatalog
import com.voicereader.rewards.FocusCatalog
import com.voicereader.rewards.RanksCatalog
import com.voicereader.rewards.Rewards
import com.voicereader.rewards.RewardsState
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val Surface = Color(0xFF171A20)
private val Muted = Color(0xFFB4BCCB)
private val Green = Color(0xFF7BD88F)
private val GoldC = Color(0xFFFFD86B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AwardsScreen(initialTab: Int, onBack: () -> Unit, onAppearance: () -> Unit) {
    val state by Rewards.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = { Text(stringResource(R.string.my_rewards)) },
                actions = { IconButton(onClick = onAppearance) { Icon(Icons.Filled.Palette, stringResource(R.string.appearance)) } }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 14.dp).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(R.string.tab_focus, R.string.tab_daily, R.string.tab_ranks).forEachIndexed { i, title ->
                    val on = i == tab
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (on) Color(0xFF33415A) else Surface)
                            .clickableNoRipple { tab = i }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(stringResource(title), fontSize = 13.sp, color = if (on) Color.White else Muted, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal) }
                }
            }
            when (tab) {
                0 -> FocusTab(state)
                1 -> DailyTab(state)
                else -> RanksTab(state)
            }
        }
    }
}

// --- Без перерыва ---

@Composable
private fun FocusTab(state: RewardsState) {
    val best = state.bestMedal
    LazyColumn(contentPadding = PaddingValues(14.dp, 0.dp, 14.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surface).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MedalArt(best.coerceAtLeast(0), Modifier.size(86.dp), locked = best < 0)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        if (best < 0) stringResource(R.string.focus_no_record) else stringResource(R.string.focus_record, medalName(best)),
                        fontSize = 18.sp, fontWeight = FontWeight.Medium
                    )
                    Text(
                        stringResource(R.string.focus_record_details, state.bestSessionSeconds / 60, state.bestSessionDay?.let { formatDay(it) } ?: "—"),
                        fontSize = 12.sp, color = Muted
                    )
                    if (state.sessionSeconds > 0) Text(
                        stringResource(R.string.focus_now, state.sessionSeconds / 60, state.sessionSeconds % 60),
                        fontSize = 12.sp, color = Color(0xFFFF9A3D)
                    )
                    Text(stringResource(R.string.focus_rule), fontSize = 12.sp, color = Muted)
                }
            }
        }
        items(FocusCatalog.TIER_UPPER.size) { tier ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface).padding(10.dp, 8.dp, 10.dp, 10.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(stringResource(FocusCatalog.TIER_NAMES[tier]), fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.focus_tier_minutes, FocusCatalog.tierLower(tier), FocusCatalog.TIER_UPPER[tier]), fontSize = 11.sp, color = Muted)
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (r in 0 until FocusCatalog.RANKS_PER_TIER) {
                        val index = tier * FocusCatalog.RANKS_PER_TIER + r
                        Box(
                            Modifier.size(48.dp).then(
                                if (index == best) Modifier.shadow(10.dp, CircleShape, ambientColor = Color.White, spotColor = Color.White) else Modifier
                            )
                        ) { MedalArt(index, Modifier.fillMaxSize(), locked = index > best) }
                    }
                }
            }
        }
    }
}

private fun formatDay(day: String): String =
    runCatching { LocalDate.parse(day).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }.getOrDefault(day)

// --- Каждый день ---

@Composable
private fun DailyTab(state: RewardsState) {
    val stages = stringArrayResource(state.growKind.stagesRes)
    val todaySeconds = state.dayLog[Rewards.today().toString()] ?: 0
    LazyColumn(contentPadding = PaddingValues(14.dp, 0.dp, 14.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surface).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GrowthArt(state.growKind, state.stage, Modifier.size(96.dp), wilted = state.wilted)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.daily_title, stringResource(state.growKind.title), state.growthDays), fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    val goal = DailyCatalog.DAY_GOAL_SECONDS / 60
                    DailyRow("⏱", if (todaySeconds >= DailyCatalog.DAY_GOAL_SECONDS)
                        stringResource(R.string.daily_today_done, todaySeconds / 60, goal)
                    else stringResource(R.string.daily_today_left, todaySeconds / 60, goal))
                    DailyCatalog.daysToNextStage(state.growthDays)?.let {
                        DailyRow("🌱", stringResource(R.string.daily_next_stage, stages[(state.stage + 1).coerceAtMost(stages.size - 1)], it))
                    }
                    DailyRow("💧", stringResource(R.string.daily_drops, state.drops))
                }
            }
        }
        item { YearHeatmap(state) }
        item { StageCircles(state, stages) }
    }
}

@Composable
private fun DailyRow(icon: String, text: String) {
    Row(Modifier.padding(top = 5.dp)) {
        Text(icon, fontSize = 12.sp); Spacer(Modifier.width(6.dp)); Text(text, fontSize = 12.sp, color = Muted)
    }
}

/** Сетка за год, как вклад на GitHub: неделя — столбец, чем дольше читали, тем ярче. */
@Composable
private fun YearHeatmap(state: RewardsState) {
    val today = Rewards.today()
    val weeks = 52
    val start = today.minusDays((today.dayOfWeek.value - 1).toLong()).minusWeeks((weeks - 1).toLong())
    val daysRead = state.dayLog.count { it.value >= DailyCatalog.DAY_GOAL_SECONDS }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surface).padding(10.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.heatmap_title), fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.heatmap_days, daysRead), fontSize = 11.sp, color = Muted)
        }
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.fillMaxWidth().aspectRatio(weeks / 7f * 0.98f)) {
            val cell = size.width / weeks
            val gap = cell * 0.22f
            for (w in 0 until weeks) for (d in 0 until 7) {
                val day = start.plusDays((w * 7 + d).toLong())
                if (day.isAfter(today)) continue
                val key = day.toString()
                val seconds = state.dayLog[key] ?: 0
                val color = when {
                    key in state.savedDays -> Color(0xFF4A6FA5)
                    seconds == 0 -> Color(0xFF242A34)
                    seconds < 10 * 60 -> Color(0xFF1F3B29)
                    seconds < 25 * 60 -> Color(0xFF2B6A3E)
                    seconds < 50 * 60 -> Color(0xFF3FA35A)
                    else -> Color(0xFF7BE38F)
                }
                val tl = Offset(w * cell, d * cell)
                drawRoundRect(color, tl, androidx.compose.ui.geometry.Size(cell - gap, cell - gap), androidx.compose.ui.geometry.CornerRadius(cell * .2f))
                if (day == today) drawRoundRect(Color(0xFFE6EAF2), tl, androidx.compose.ui.geometry.Size(cell - gap, cell - gap),
                    androidx.compose.ui.geometry.CornerRadius(cell * .2f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.2f))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(Color(0xFF4A6FA5), RoundedCornerShape(2.dp)))
            Text(" " + stringResource(R.string.heatmap_saved), fontSize = 10.sp, color = Muted, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.heatmap_less) + " ", fontSize = 10.sp, color = Muted)
            listOf(0xFF242A34, 0xFF1F3B29, 0xFF2B6A3E, 0xFF3FA35A, 0xFF7BE38F).forEach {
                Box(Modifier.padding(horizontal = 1.dp).size(8.dp).background(Color(it), RoundedCornerShape(2.dp)))
            }
            Text(" " + stringResource(R.string.heatmap_more), fontSize = 10.sp, color = Muted)
        }
    }
}

/** Этапы роста кружками, плавно листаются; открывается на текущей стадии. */
@Composable
private fun StageCircles(state: RewardsState, stages: Array<String>) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (state.stage - 1).coerceAtLeast(0))
    LazyRow(state = list, contentPadding = PaddingValues(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items(DailyCatalog.STAGE_DAYS.size) { i ->
            val cur = i == state.stage
            val done = i < state.stage
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(100.dp)) {
                Box(
                    Modifier
                        .size(96.dp)
                        .then(if (cur) Modifier.shadow(16.dp, CircleShape, ambientColor = Green, spotColor = Green) else Modifier)
                        .clip(CircleShape)
                        .background(if (cur) Color(0xFF1D2E23) else if (done) Color(0xFF1A2420) else Color(0xFF171B21))
                        .border(3.dp, if (cur) Green else if (done) Color(0xFF2C3A31) else Color(0xFF2A303B), CircleShape),
                    contentAlignment = Alignment.Center
                ) { GrowthArt(state.growKind, i, Modifier.size(84.dp), locked = i > state.stage) }
                Spacer(Modifier.height(6.dp))
                Text(stages[i], fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = if (i > state.stage) Muted else Color.White, textAlign = TextAlign.Center)
                Text(stringResource(R.string.stage_days, DailyCatalog.STAGE_DAYS[i]), fontSize = 10.5.sp, color = Muted)
            }
        }
    }
}

// --- Звания: снизу вверх, посередине линия с номерами ---

@Composable
private fun RanksTab(state: RewardsState) {
    val rank = state.rank
    val next = (rank + 1).coerceAtMost(RanksCatalog.COUNT - 1)
    val from = RanksCatalog.POINTS[rank]; val to = RanksCatalog.POINTS[next]
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 14.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface).padding(14.dp, 10.dp)) {
            Text(
                if (rank == next) stringResource(R.string.rank_max, rankName(rank))
                else stringResource(R.string.rank_progress, rankName(rank), formatPoints(state.points), formatPoints(to), rankName(next)),
                fontSize = 13.sp
            )
            Spacer(Modifier.height(7.dp))
            LinearProgressIndicator(
                progress = { if (to == from) 1f else ((state.points - from).toFloat() / (to - from)).coerceIn(0f, 1f) },
                color = GoldC, trackColor = Color(0xFF2A303B), modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
            )
        }
        val list = rememberLazyListState(initialFirstVisibleItemIndex = (rank - 1).coerceAtLeast(0))
        LazyColumn(state = list, reverseLayout = true, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp, top = 12.dp)) {
            items(RanksCatalog.COUNT) { i -> RankRow(i, rank) }
        }
    }
}

@Composable
private fun RankRow(i: Int, rank: Int) {
    val got = i <= rank
    Row(Modifier.fillMaxWidth().height(170.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            RankBadge(i, Modifier.size(84.dp, 100.dp), locked = !got)
            Text(rankName(i), fontSize = 13.5.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, color = if (got) Color.White else Muted)
            Text(
                if (got) stringResource(R.string.reward_unlocked) else stringResource(R.string.rank_from, formatPoints(RanksCatalog.POINTS[i])),
                fontSize = 10.5.sp, color = Muted
            )
        }
        Box(Modifier.width(46.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            // Линия: снизу вверх; пройденная часть — золотая.
            Canvas(Modifier.fillMaxSize()) {
                val x = size.width / 2; val w = 4.dp.toPx()
                val lower = if (i <= rank) GoldC else Color(0xFF2A303B)
                val upper = if (i < rank) GoldC else Color(0xFF2A303B)
                if (i > 0) drawLine(lower, Offset(x, size.height / 2), Offset(x, size.height), w)
                if (i < RanksCatalog.COUNT - 1) drawLine(upper, Offset(x, 0f), Offset(x, size.height / 2), w)
            }
            Box(
                Modifier
                    .size(34.dp)
                    .then(if (i == rank) Modifier.shadow(14.dp, CircleShape, ambientColor = GoldC, spotColor = GoldC) else Modifier)
                    .clip(CircleShape)
                    .background(if (got) GoldC else Color(0xFF171A20))
                    .then(if (got) Modifier else Modifier.border(2.dp, Color(0xFF3A414F), CircleShape)),
                contentAlignment = Alignment.Center
            ) { Text((i + 1).toString(), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (got) Color(0xFF3A2A05) else Muted) }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            val reward = RanksCatalog.rewardFor(i)
            if (reward != null) RewardCard(reward, got)
            else Column(
                Modifier.width(140.dp).clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(listOf(Color(0xFF232A38), Color(0xFF191D26)))).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) { Text(stringResource(R.string.rank_start_reward), fontSize = 12.sp, color = Muted, textAlign = TextAlign.Center) }
        }
    }
}
