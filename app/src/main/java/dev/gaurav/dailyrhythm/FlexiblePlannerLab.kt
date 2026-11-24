package dev.gaurav.dailyrhythm

import android.Manifest
import android.os.Build
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.*
import java.time.format.TextStyle
import java.util.Locale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import java.time.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.delay

/** Routes and shared state for the main reminder activity. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun DailyRhythmApp(openOccurrence: String? = null, amoledBlack: Boolean = false,
                            openCreator: Intent? = null, notificationRequest: Intent? = null, onIntentOpened: () -> Unit = {},
                            onAmoledChange: ((Boolean) -> Unit)? = null) {
    val context = LocalContext.current
    val pickDay = LocalPickDay.current
    val loaded = remember { runCatching { FlexiblePlanner(Preferences(context)) { LocalDate.now() } } }
    if (loaded.isFailure) {
        Column(Modifier.padding(24.dp)) { Text(loaded.exceptionOrNull()?.message ?: "Planning data unavailable; nothing reset."); Text("Close and reopen after checking device storage; no automatic reset is offered.") }
        return
    }
    val planner = loaded.getOrThrow()
    val bridge=remember { LiveReminders(context) }
    val nav = rememberNavController()
    val currentRoute by nav.currentBackStackEntryAsState()
    val tab = when {
        currentRoute?.destination?.hasRoute<RemindersRoute>() == true -> 1
        currentRoute?.destination?.hasRoute<HistoryRoute>() == true -> 2
        else -> 0
    }
    var alarmOpen by remember {mutableStateOf(false)}
    var revision by remember { mutableIntStateOf(0) }
    var dateString by rememberSaveable { mutableStateOf<String?>(null) }
    var localToday by remember { mutableStateOf(LocalDate.now()) }
    var localZone by remember { mutableStateOf(ZoneId.systemDefault()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var now by remember { mutableStateOf(Instant.now()) }
    val preferences = remember { Preferences(context) }
    val storageRevision by preferences.changes.collectAsStateWithLifecycle()
    LaunchedEffect(storageRevision) {
        planner.refresh()
        revision++
    }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = Instant.now()
                val nextDay = LocalDate.now()
                val nextZone = ZoneId.systemDefault()
                if (nextDay != localToday || nextZone != localZone) runCatching { bridge.sync() }
                localToday = nextDay
                localZone = nextZone
                delay(1000)
            }
        }
    }
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) runCatching { bridge.sync(); planner.refresh(); revision++ }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val date = dateString?.let(LocalDate::parse) ?: localToday
    LaunchedEffect(openCreator) {
        if (openCreator != null) {
            if (nav.currentDestination?.hasRoute<EditReminderRoute>() != true) nav.editReminder(null, localToday)
            onIntentOpened()
        }
    }
    var showSamples by rememberSaveable { mutableStateOf(false) }
    var dayTypeMenu by remember { mutableStateOf(false) }
    var officeDialog by remember { mutableStateOf(false) }
    var officePattern by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf<Pair<String,String>?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val mutate: (() -> Unit) -> Boolean = { block ->
        try {
            planner.refresh(); block(); revision++
            runCatching {bridge.sync();planner.refresh()}.onFailure {notice="Saved. Reminder health needs attention; review Settings."}
            CalendarSync.publishSoon(context)
            true
        } catch(_:Exception) { notice="Couldn't save. Your previous data is still here."; false }
    }
    var snoozeAlert by remember { mutableStateOf<LiveAlert?>(null) }
    var detail by rememberSaveable {mutableStateOf<String?>(null)}
    var archive by rememberSaveable { mutableStateOf<String?>(null) }
    var delete by rememberSaveable { mutableStateOf<String?>(null) }
    var reminderQuery by rememberSaveable { mutableStateOf("") }
    var reminderFilter by rememberSaveable { mutableStateOf(ReminderFilter.ALL) }
    var historyQuery by rememberSaveable { mutableStateOf("") }
    var historyFilter by rememberSaveable { mutableStateOf(ReminderFilter.ALL) }
    var reorderToday by rememberSaveable(date) { mutableStateOf(false) }
    var showWeek by rememberSaveable { mutableStateOf(false) }
    fun markDone(id: String): Boolean {
        val logged = Entry(Outcome.DONE, loggedAt = Instant.now())
        if (!mutate { planner.report(id, logged) }) return false
        scope.launch {
            if (snackbar.showSnackbar("Marked done", "Undo") == SnackbarResult.ActionPerformed) {
                mutate { if (planner.entry(id) == logged) planner.report(id, Entry()) }
            }
        }
        return true
    }
    fun openReading(id:String) {
        try { planner.ritual(id); revision++; nav.navigate(RitualRoute(id)); detail = null }
        catch(_:Exception) {notice="Couldn't open the ritual. Nothing was reset."}
    }
    val legacyStore = remember { RhythmStore(preferences) }
    val boardOrder = remember { ReminderOrder(preferences) }
    var boardViewport by remember { mutableStateOf(Rect.Zero) }
    val activeSession = remember(now, revision) { RingingService.snapshot() != null }
    val todayScroll = rememberScrollState()
    val planScroll = rememberScrollState()
    val historyScroll = rememberScrollState()
    val rows = remember(revision,date) { planner.occurrences(date) }
    val live = remember(revision) { bridge.state() }
    var historyDate by rememberSaveable { mutableStateOf<String?>(null) }
    var historyDetail by remember { mutableStateOf<String?>(null) }
    var actionDate by remember { mutableStateOf<LocalDate?>(null) }
    val latestActivityDay = remember(revision, localToday, localZone) {
        val alertDates = live.events.asSequence().filter { QuietPresentation.activityLabel(it) != null }
            .map { it.at.atZone(localZone).toLocalDate() }
        val outcomeDates = planner.state.reports.asSequence().filter { it.value.entry.outcome != Outcome.UNLOGGED }
            .map { (id, _) -> planner.state.changes[id]?.lastOrNull()?.date ?: FlexiblePlanner.parts(id).second }
        (alertDates + outcomeDates + preferences.legacyReportDates().asSequence()).filter { it <= localToday }.maxOrNull()
    }
    val selectedHistory = historyDate?.let(LocalDate::parse) ?: latestActivityDay ?: localToday
    val activity = remember(revision, selectedHistory, localZone) {
        QuietPresentation.reminderActivity(live.events, selectedHistory, localZone).groupBy { it.first.id }
    }
    val historyRows = remember(revision, selectedHistory, localToday) {
        planner.occurrences(selectedHistory).filter {
            it.state in setOf(HistoryState.DONE, HistoryState.LATE, HistoryState.SKIPPED) ||
                selectedHistory < localToday && it.scheduled && !it.version.legacyUnknownSchedule
        }
            .associateBy { it.id }
    }
    val historyScheduled = remember(revision, selectedHistory, tab) {
        if (tab == 2) planner.occurrences(selectedHistory).filter { it.scheduled }.map { it.id }.toSet() else emptySet()
    }
    val manager = remember { context.getSystemService(android.app.NotificationManager::class.java) }
    val notificationsAllowed = remember(revision) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val exactAllowed = remember(revision) { bridge.exactAllowed() }
    val notificationPromptShown = rememberSaveable { mutableStateOf(false) }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationPromptShown.value = true
        mutate { }
    }
    val exportHistory = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
        if (uri != null) scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val storage = Preferences(context)
                    val snapshot = FlexiblePlanner(storage) { localToday }
                    val records = calendarHistory(snapshot, LiveStore(storage).read()?.events.orEmpty(),
                        storage.legacyReportDates(), localZone)
                    val stream = requireNotNull(context.contentResolver.openOutputStream(uri))
                    stream.bufferedWriter(Charsets.UTF_8).use { CalendarExport.write(it, records, Instant.now()) }
                }
            }
            snackbar.showSnackbar(if (result.isSuccess) "History exported" else "Couldn’t export history")
        }
    }

    fun allowNotifications() {
        if (notificationsAllowed) return
        if (Build.VERSION.SDK_INT >= 33 && !notificationPromptShown.value) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else openSystem(context, notificationSettingsIntent(context, null))
    }

    LaunchedEffect(openOccurrence, notificationRequest) {
        if (openOccurrence != null && openOccurrence.length < 256) runCatching {
            val (_, anchor) = FlexiblePlanner.parts(openOccurrence)
            dateString = (planner.state.changes[openOccurrence]?.lastOrNull()?.date ?: anchor).toString()
            val occurrence = planner.occurrences(LocalDate.parse(requireNotNull(dateString))).firstOrNull { it.id == openOccurrence }
            if (occurrence != null) {
                when {
                    notificationRequest?.action == AndroidLivePlatform.OPEN_SNOOZE -> {
                        snoozeAlert = bridge.state().active[openOccurrence]
                    }
                    bridge.config(occurrence.itemId).ringing && openOccurrence in bridge.state().active -> alarmOpen = true
                    occurrence.version.kind == ItemKind.RITUAL -> openReading(openOccurrence)
                    else -> detail = openOccurrence
                }
            }
            if (notificationRequest != null) onIntentOpened()
        }
    }

    val items = remember(revision, localToday) {
        planner.state.items.values.filter { it.id !in planner.state.deleted }.mapNotNull { item ->
            (item.at(localToday) ?: item.versions.minByOrNull { it.from })?.let { item to it }
        }.sortedBy { it.second.title.lowercase(Locale.getDefault()) }
    }
    val alertItems = items.filter { (item, version) ->
        !planner.isArchived(item, version) && (version.rule.oneDate?.let { it >= localToday } ?: true) &&
            live.configs[item.id]?.enabled == true
    }
    val blockedChannel = remember(revision, alertItems) {
        alertItems.firstNotNullOfOrNull { (item, _) ->
            val channel = AndroidLivePlatform.channel(item.id)
            channel.takeIf { manager.getNotificationChannel(it)?.importance == android.app.NotificationManager.IMPORTANCE_NONE }
                ?: if (live.configs[item.id]?.ringing == true) RingingService.mutedChannel(context) else null
        }
    }

    val dashboard: @Composable (Int) -> Unit = { tab ->
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            floatingActionButton = {
                if (tab != 2) FloatingActionButton(onClick = { nav.editReminder(null, if (tab == 0) date.coerceAtLeast(localToday) else localToday) },
                    containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.semantics { contentDescription = "Add reminder" }) { ReminderIcon(ReminderGlyph.ADD) }
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    listOf("Today", "Reminders", "History").forEachIndexed { i, label ->
                        NavigationBarItem(selected = tab == i, onClick = {
                            if (i == 0 && tab != 0) dateString = null
                            if (i != tab) nav.showTab(i)
                        }, icon = { ReminderIcon(listOf(ReminderGlyph.ALARM, ReminderGlyph.LIST, ReminderGlyph.HISTORY)[i]) }, label = { Text(label) })
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(when (tab) { 0 -> "Today"; 1 -> "Reminders"; else -> "History" },
                        Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
                    if (tab == 2 && latestActivityDay != null) {
                        TextButton(onClick = { exportHistory.launch("Daily Reminders history.ics") }) { Text("Export") }
                    }
                    IconButton(onClick = { nav.navigate(SettingsRoute) }, modifier = Modifier.semantics { contentDescription = "Settings" }) { ReminderIcon(ReminderGlyph.SETTINGS) }
                }
                if (activeSession) AlertAccess { alarmOpen = true }
                val scroll = when (tab) { 0 -> todayScroll; 1 -> planScroll; else -> historyScroll }
                Column(Modifier.fillMaxSize().onGloballyPositioned { boardViewport = it.boundsInRoot() }
                    .verticalScroll(scroll).padding(horizontal = 20.dp)) {
                    if (tab != 1) {
                        val shown = if (tab == 2) selectedHistory else date
                        val setDate: (LocalDate) -> Unit = { next ->
                            if (tab == 2) historyDate = next.coerceAtMost(localToday).toString() else dateString = next.toString()
                        }
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { pickDay(shown, setDate) },
                                modifier = Modifier.semantics { contentDescription = "Choose date · ${dayLabel(shown)}" }) {
                                Text(shortDate(shown))
                                Spacer(Modifier.width(4.dp))
                                ReminderIcon(ReminderGlyph.EXPAND)
                            }
                            if (tab == 0) {
                                val type = planner.dayType(shown)
                                if (shown >= localToday && type != DayType.WEEKEND) {
                                    Box {
                                        TextButton(onClick = { dayTypeMenu = true }, modifier = Modifier.semantics {
                                            contentDescription = "Day type · ${type.label}"
                                        }) {
                                            Text(type.label)
                                            ReminderIcon(ReminderGlyph.EXPAND)
                                        }
                                        DropdownMenu(expanded = dayTypeMenu, onDismissRequest = { dayTypeMenu = false }) {
                                            DayType.entries.filter { it != DayType.WEEKEND }.forEach { next ->
                                                DropdownMenuItem(text = { Text(next.label) }, onClick = {
                                                    dayTypeMenu = false
                                                    if (next != type) mutate { planner.setDayType(shown, next) }
                                                })
                                            }
                                            if (planner.state.officeChanges[shown].orEmpty().isNotEmpty()) {
                                                DropdownMenuItem(text = { Text("Use usual day") }, onClick = {
                                                    dayTypeMenu = false
                                                    mutate { planner.undoOffice(shown) }
                                                })
                                            }
                                        }
                                    }
                                } else Text(type.label, modifier = Modifier.padding(vertical = 16.dp),
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (shown != localToday) TextButton(onClick = { setDate(localToday) }) { Text("Back to today") }
                        }
                    }
                    if (tab == 0) WeekStrip(date, localToday) { dateString = it.toString() }
                    if (tab != 2 && alertItems.isNotEmpty()) {
                        when {
                            !legacyStore.remindersEnabled -> SetupAction("Alerts are paused", "Resume alerts") { mutate { legacyStore.remindersEnabled = true } }
                            !notificationsAllowed -> SetupAction("Allow notifications to receive reminders", "Allow notifications", ::allowNotifications)
                            alertItems.any { live.configs[it.first.id]?.precise == true } && !exactAllowed ->
                                SetupAction("Alarms need permission", "Allow exact alarms") { openAlarmAccess(context) }
                            blockedChannel != null -> SetupAction("An alert is muted in Android settings", "Open settings") {
                                openSystem(context, notificationSettingsIntent(context, blockedChannel))
                            }
                        }
                    }

                    when (tab) {
                        0 -> {
                            val agenda = rows.filter { row ->
                                row.visibleOnAgenda(planner.state.deleted[row.itemId])
                            }
                            if (agenda.isEmpty()) {
                                EmptyLabel("Nothing scheduled", "Create a reminder or start from a sample.")
                                TextButton(onClick = { showSamples = true }) { Text("Sample routines") }
                            }
                            if (agenda.isNotEmpty()) {
                                val board = "today/$date"
                                if (agenda.size > 1 || reorderToday) {
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                                        TextButton(onClick = { reorderToday = !reorderToday }) {
                                            Text(if (reorderToday) "Finish reordering" else "Reorder")
                                        }
                                    }
                                }
                                if (reorderToday) {
                                    val tiles = agenda.map { row -> ReminderTile(row.id, row.title, clockLabel(row.time), null, null, null,
                                        onOpen = { detail = row.id }) }
                                    ReminderBoard(tiles, board, boardOrder, scroll, boardViewport) { notice = "Couldn’t save this order." }
                                } else {
                                    val byId = agenda.associateBy { it.id }
                                    val ordered = orderTiles(agenda.map { it.id }, boardOrder.read(board)).map(byId::getValue)
                                    TodayAgenda(ordered, now, localToday, localZone, live,
                                        onOpen = { detail = it }, onDone = { markDone(it) },
                                        onSnooze = { snoozeAlert = it })
                                }
                            }
                        }
                        1 -> {
                            ReminderSearch(reminderQuery, { reminderQuery = it }, reminderFilter,
                                { reminderFilter = it }, "Search reminders")
                            val matches = items.filter { (item, version) ->
                                reminderFilter.matches(version, live.configs[item.id], planner.isArchived(item, version)) &&
                                    version.matchesSearch(version.title, reminderQuery)
                            }
                            if (matches.isEmpty()) {
                                if (items.isEmpty()) EmptyLabel("No reminders yet", "Tap + to add a reminder.")
                                else EmptyLabel("No matching reminders", "Try another search or filter.")
                            }
                            val tiles = matches.map { (item, version) ->
                                val alert = live.configs[item.id]?.takeIf { it.enabled }?.let(ReminderDraft::label)
                                val archived = planner.isArchived(item, version)
                                val summary = if (archived) "Archived" else
                                    listOfNotNull(ReminderDraft.repeat(version.rule), alert).joinToString(" · ")
                                val archiveAction = if (!archived) TileAction("Archive") { archive = item.id } else null
                                val deleteAction = TileAction("Delete", destructive = true) { delete = item.id }
                                val menu = buildList {
                                    add(TileAction("Clone reminder") {
                                        nav.navigate(EditReminderRoute(null, localToday.toString(), cloneOf = item.id))
                                    })
                                    archiveAction?.let(::add)
                                    if (version.archived && !planner.isCompletedOneTime(item, version)) {
                                        add(TileAction("Restore reminder") {
                                            if (mutate { planner.restore(item.id, maxOf(item.created, localToday)) }) {
                                                reminderFilter = ReminderFilter.ALL
                                                scope.launch { snackbar.showSnackbar("Reminder restored") }
                                            }
                                        })
                                    }
                                    add(deleteAction)
                                }
                                ReminderTile(item.id, version.title, clockLabel(version.time), summary,
                                    primary = archiveAction, secondary = deleteAction,
                                    onOpen = { nav.editReminder(item.id, localToday) }, category = version.category, menuActions = menu)
                            }
                            if (tiles.isNotEmpty()) {
                                Spacer(Modifier.height(10.dp))
                                ReminderBoard(tiles, "reminders", boardOrder, scroll, boardViewport,
                                    allowReorder = reminderQuery.isBlank() && reminderFilter == ReminderFilter.ALL) {
                                    notice = "Couldn’t save this order."
                                }
                            }
                            TextButton(onClick = { showSamples = true }) { Text("Sample routines") }
                        }
                        else -> {
                            ReminderSearch(historyQuery, { historyQuery = it }, historyFilter,
                                { historyFilter = it }, "Search history")
                            if (historyQuery.isNotBlank()) {
                                val matches = remember(revision, historyQuery, historyFilter, localToday, localZone) {
                                    searchHistory(planner, live, preferences.legacyReportDates(), historyQuery, historyFilter, localZone)
                                }
                                if (matches.isEmpty()) EmptyLabel("No matching history", "Try another search or filter.")
                                matches.forEach { match ->
                                    AgendaRow(match.title, match.label, shortDate(match.date)) {
                                        historyDate = match.date.toString()
                                        historyDetail = match.id
                                    }
                                }
                            } else {
                            TextButton(onClick = { showWeek = true }) { Text("7-day summary") }
                            val entries = (activity.keys + historyRows.keys).sortedWith(
                                compareByDescending<String> { id ->
                                    val row = historyRows[id]
                                    row?.actual?.let { selectedHistory.atTime(it).atZone(localZone).toInstant() }
                                        ?: planner.entry(id).loggedAt ?: activity[id]?.firstOrNull()?.first?.at ?: row?.dueAt(localZone)
                                }
                            ).filter { id ->
                                val (item, anchor) = FlexiblePlanner.parts(id)
                                val record = planner.state.items[item]
                                historyFilter == ReminderFilter.ALL || historyFilter.matches(record?.at(anchor), live.configs[item],
                                    planner.isArchived(item, localToday))
                            }
                            if (entries.isEmpty()) EmptyLabel("No activity", "Alerts and completed routines will appear here.")
                            entries.forEach { id ->
                                val logged = historyRows[id]
                                val event = activity[id]?.firstOrNull()
                                val item = runCatching { FlexiblePlanner.parts(id) }.getOrNull()
                                val title = logged?.title ?: item?.let { (key, day) -> planner.state.items[key]?.at(day)?.title } ?: "Reminder"
                                val status = if (logged != null) {
                                    if (logged.state == HistoryState.UNLOGGED) "No record" else QuietPresentation.stateLabel(logged.state)
                                } else if (id in historyScheduled) {
                                    if (selectedHistory == localToday) "Pending" else "No record"
                                } else event?.second
                                val time = logged?.actual ?: planner.entry(id).loggedAt?.atZone(localZone)?.toLocalTime()
                                    ?: event?.first?.at?.atZone(localZone)?.toLocalTime() ?: logged?.time
                                AgendaRow(title, status, time?.let { clockLabel(it) }) { historyDetail = id }
                            }
                            }
                        }
                    }
                    Spacer(Modifier.height(if (tab == 2) 20.dp else 88.dp))
                }
            }
        }
    }
    if (showWeek) {
        val end = historyDate?.let(LocalDate::parse) ?: localToday
        val weekRows = remember(revision, end) {
            (0L..6L).flatMap { planner.occurrences(end.minusDays(it)) }
                .filter { it.state != HistoryState.NOT_SCHEDULED && !it.version.legacyUnknownSchedule }
        }
        HabitWeekSheet(end, weekRows, now, localZone) { showWeek = false }
    }

    if (showSamples) ModalBottomSheet(onDismissRequest = { showSamples = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            QuietHeading("Sample routines")
            ReminderSample.entries.forEach { sample ->
                val repeat = ReminderDraft.repeat(RepeatRule(sample.repeat))
                val summary = if (sample.steps.isEmpty()) repeat else "$repeat · ${sample.steps.size} steps"
                AgendaRow(sample.title, summary, clockLabel(sample.time)) {
                    showSamples = false
                    nav.editReminder(null, if (tab == 0) date.coerceAtLeast(localToday) else localToday, sample)
                }
            }
        }
    }
    historyDetail?.let { id ->
        val events = activity[id].orEmpty()
        val report = historyRows[id]
        val title = report?.title ?: runCatching {
            val (item, anchor) = FlexiblePlanner.parts(id)
            planner.state.items[item]?.at(anchor)?.title
        }.getOrNull() ?: "Reminder"
        ModalBottomSheet(onDismissRequest = { historyDetail = null }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
                QuietHeading(title, dayLabel(selectedHistory))
                events.forEach { (event, label) ->
                    LabeledRow(label, clockLabel(event.at.atZone(localZone).toLocalTime()))
                }
                if (report != null) LabeledRow("Outcome", if (report.state == HistoryState.UNLOGGED) "No record"
                    else QuietPresentation.stateLabel(report.state))
                planner.entry(id).loggedAt?.atZone(localZone)?.let { logged ->
                    val time = clockLabel(logged.toLocalTime())
                    LabeledRow("Logged at", if (logged.toLocalDate() == selectedHistory) time else "${shortDate(logged.toLocalDate())} · $time")
                }
                report?.actual?.let { LabeledRow("Completed at", clockLabel(it)) }
                if (report != null || planner.occurrences(selectedHistory).any { it.id == id }) {
                    TextButton(onClick = { historyDetail = null; actionDate = selectedHistory; action = id to "report" }) { Text("Log outcome") }
                }
            }
        }
    }
    detail?.let { id -> rows.firstOrNull { it.id == id }?.let { row ->
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        val config = live.configs[row.itemId]
        val canEdit = row.itemId in planner.state.items && row.itemId !in planner.state.deleted
        val active = id in live.active
        val completed = row.state in setOf(HistoryState.DONE, HistoryState.LATE)
        val canComplete = row.date == localToday && row.state == HistoryState.UNLOGGED &&
            row.version.kind == ItemKind.ROUTINE
        ModalBottomSheet(
            onDismissRequest = { detail = null },
            sheetState = sheet,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            dragHandle = {
                val expanded = sheet.currentValue == SheetValue.Expanded
                Box(Modifier.fillMaxWidth().height(48.dp)
                    .clickable(role = Role.Button) {
                        scope.launch { if (expanded && sheet.hasPartiallyExpandedState) sheet.partialExpand() else sheet.expand() }
                    }
                    .semantics { contentDescription = if (expanded) "Collapse reminder details" else "Expand reminder details" },
                    contentAlignment = Alignment.Center) {
                    BottomSheetDefaults.DragHandle(width = 34.dp, height = 4.dp)
                }
            }
        ) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.95f).verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text(row.title, style = MaterialTheme.typography.headlineMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                val status = when {
                    active && config?.ringing == true -> "Alarm ringing"
                    active -> "Notification active"
                    completed || row.state == HistoryState.SKIPPED -> QuietPresentation.stateLabel(row.state)
                    config?.enabled == true -> ReminderDraft.label(config)
                    else -> null
                }
                Text(listOfNotNull(clockLabel(row.time), status).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    when {
                        active && config?.ringing == true -> Button(onClick = { detail = null; alarmOpen = true }) { Text("Alarm controls") }
                        row.version.kind == ItemKind.RITUAL -> Button(onClick = { openReading(id) }) { Text("Open ritual") }
                        canComplete -> Button(onClick = {
                            if (markDone(id)) detail = null
                        }) { Text("Mark done") }
                        completed -> FilledTonalButton(onClick = { if (mutate { planner.report(id, Entry()) }) detail = null }) {
                            Text("Undo completion")
                        }
                    }
                    if (canEdit) OutlinedButton(onClick = {
                        scope.launch {
                            sheet.hide()
                            detail = null
                            nav.editReminder(row.itemId, localToday)
                        }
                    }) { Text("Edit reminder") }
                }
                row.version.category?.let { CategoryLabel(it, Modifier.padding(top = 12.dp)) }
                if (row.version.checklist.isNotEmpty()) {
                    Text("Checklist", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
                    val checked = planner.state.checkedSteps[id].orEmpty()
                    row.version.checklist.forEach { step ->
                        val enabled = canComplete && row.scheduled
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .toggleable(value = step in checked, enabled = enabled, role = Role.Checkbox) { value ->
                                var finished = false
                                if (mutate { finished = planner.setStep(id, step, value, Instant.now()) } && finished) {
                                    detail = null
                                    scope.launch {
                                        if (snackbar.showSnackbar("Checklist complete", "Undo") == SnackbarResult.ActionPerformed) {
                                            mutate { planner.report(id, Entry()) }
                                        }
                                    }
                                }
                            }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = step in checked, enabled = enabled, onCheckedChange = null)
                            Text(step, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (row.version.note.isNotBlank()) Text(row.version.note, Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(18.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (active && config?.ringing != true) {
                    LabeledRow("Dismiss notification", onClick = { if (mutate { bridge.action(id, "stop") }) detail = null })
                    LabeledRow("Snooze…", onClick = { snoozeAlert = live.active[id]; detail = null })
                }
                if (canEdit && row.date >= localToday) {
                    listOf("move" to "Move this occurrence", "skip" to "Skip this occurrence").forEach { (kind, label) ->
                        LabeledRow(label, onClick = { detail = null; action = id to kind })
                    }
                    if (row.scheduled) LabeledRow("Add another occurrence", onClick = { detail = null; action = id to "extra" })
                    if (planner.state.changes[id].orEmpty().isNotEmpty()) {
                        LabeledRow("Restore schedule", onClick = { if (mutate { planner.undoItem(id) }) detail = null })
                    }
                }
                if (row.date <= localToday) LabeledRow("Log outcome", onClick = { detail = null; action = id to "report" })
            }
        }
    } }
    snoozeAlert?.let { alert ->
        ReminderDialog("Snooze reminder", onDismiss = { snoozeAlert = null }, footer = {
            TextButton(onClick = { snoozeAlert = null }) { Text("Cancel") }
        }) {
            SnoozeOptions { until ->
                runCatching { bridge.snooze(alert, until) }.onSuccess {
                    revision++
                    snoozeAlert = null
                }.onFailure { notice = it.message ?: "Couldn’t snooze reminder." }
            }
        }
    }
    if(alarmOpen) AlarmControls(bridge,onDismiss={alarmOpen=false},onNotice={notice=it},onChange={revision++},onOpen={alert ->
        planner.refresh()
        val row=planner.occurrences(alert.day).first {it.id==alert.id}
        nav.showTab(0)
        if (row.version.kind == ItemKind.RITUAL) openReading(row.id) else detail = row.id
        dateString = alert.day.toString()
        revision++
        alarmOpen = false
    })
    val settingsScreen: @Composable () -> Unit = {
        QuietRoute("Settings", { nav.popBackStack() }, activeSession, { alarmOpen = true }) {
            EditorGroup("Alerts") {
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Pause alerts", Modifier.weight(1f))
                    Switch(checked = preferences.get(RhythmStore.REMINDERS_KEY) == "false",
                        onCheckedChange = { paused -> mutate { legacyStore.remindersEnabled = !paused } },
                        modifier = Modifier.semantics { contentDescription = "Pause alerts" })
                }
                var snoozeChoice by remember { mutableStateOf(false) }
                Box {
                    val selected = preferences.get(SNOOZE_PREFERENCE)
                    val options = listOf("5" to "5 minutes", "10" to "10 minutes", ASK_SNOOZE to "Choose each time")
                    LabeledRow("Default snooze", if (selected == ASK_SNOOZE) "Choose each time" else "${preferredSnooze(preferences)} min",
                        onClick = { snoozeChoice = true })
                    DropdownMenu(snoozeChoice, onDismissRequest = { snoozeChoice = false }) {
                        options.forEach { (value, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                snoozeChoice = false
                                mutate { preferences.put(SNOOZE_PREFERENCE, value) }
                            })
                        }
                    }
                }
                LabeledRow("Android notifications", onClick = { openSystem(context, notificationSettingsIntent(context, null)) })
                if (Build.VERSION.SDK_INT >= 34) LabeledRow("Lock screen alarms",
                    if (manager.canUseFullScreenIntent()) "Allowed" else "Setup needed", onClick = {
                        openSystem(context, alarmAccessIntent(context, Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT))
                    })
                LabeledRow("Alarms over other apps", if (Settings.canDrawOverlays(context)) "Allowed" else "Off", onClick = {
                    openSystem(context, alarmAccessIntent(context, Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                })
                LabeledRow("Alarm diagnostics", onClick = { nav.popBackStack(); alarmOpen = true })
            }
            if (onAmoledChange != null) EditorGroup("Appearance") {
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Pure black")
                        Text("Uses black surfaces in dark mode", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(amoledBlack, { enabled -> mutate { onAmoledChange(enabled) } },
                        modifier = Modifier.semantics { contentDescription = "Pure black" })
                }
            }
            EditorGroup("Alarm reliability") {
                val power = context.getSystemService(android.os.PowerManager::class.java)
                LabeledRow("Battery optimization",
                    if (power.isIgnoringBatteryOptimizations(context.packageName)) "Unrestricted" else "Managed by Android",
                    onClick = { openSystem(context, systemActivity(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) })
                Text("If alarms arrive late, review this app’s battery settings and any device-specific background restrictions.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            EditorGroup("Schedule") {
                LabeledRow("Usual office days", onClick = { nav.popBackStack(); officePattern = true })
                LabeledRow("Change day types for dates", onClick = {
                    pickDay(localToday) { chosen ->
                        if (chosen >= localToday && chosen.dayOfWeek.value <= 5) {
                            dateString = chosen.toString(); nav.showTab(0); officeDialog = true
                        } else notice = "Choose today or a future weekday."
                    }
                })
                CalendarSettings(revision) { notice = it }
            }
            BackupSettings {
                planner.refresh()
                revision++
                onAmoledChange?.invoke(preferences.get("theme.amoled") == "true")
                CalendarSync.publishSoon(context)
            }
        }
    }
    val editorScreen: @Composable (EditReminderRoute) -> Unit = { route ->
        val itemId = route.item
        val sourceId = itemId ?: route.cloneOf
        val source = sourceId?.let { planner.state.items[it] }
        val newDay = LocalDate.parse(route.date).coerceAtLeast(localToday)
        val sample = route.sample?.let(ReminderSample::valueOf)
        val sourceVersion = source?.let { it.at(localToday) ?: it.versions.minByOrNull { version -> version.from } }
        val initial = remember(route, sourceVersion, newDay) {
            when {
                route.cloneOf != null -> sourceVersion?.let { ReminderDraft.clone(it, LocalDateTime.now()) }
                else -> sourceVersion ?: sample?.draft(newDay)
            }
        }
        val savedAlert = sourceId?.let { bridge.config(it) } ?: GentleConfig(enabled = sample == null)
        ReminderEditorScreen(initial, itemId != null, newDay, savedAlert, bridge, activeSession, { alarmOpen = true },
            onDismiss = { nav.popBackStack() },
            onAndroidSound = itemId?.let { id -> { openSystem(context, notificationSettingsIntent(context, AndroidLivePlatform.channel(id))) } },
            onSave = { version, reminder ->
                val result = bridge.savePlanned(version, reminder, itemId, enableFirstAlert = true)
                if (route.cloneOf != null) {
                    reminderFilter = ReminderFilter.ALL
                    if (!version.matchesSearch(version.title, reminderQuery)) reminderQuery = ""
                }
                nav.popBackStack()
                revision++
                planner.refresh()
                CalendarSync.publishSoon(context)
                val blocking = reminder.enabled && (!notificationsAllowed || reminder.precise && !exactAllowed || !legacyStore.remindersEnabled)
                if (reminder.enabled && !notificationsAllowed && legacyStore.remindersEnabled) allowNotifications()
                scope.launch { snackbar.showSnackbar(when {
                    blocking -> "Saved · alert needs setup"
                    result.warning != null -> "Saved · check alert settings"
                    route.cloneOf != null -> "Copy saved"
                    else -> "Reminder saved"
                }) }
            })
    }

    val destructiveId = delete ?: archive
    destructiveId?.let { id ->
        val deleting = delete != null
        val item = planner.state.items[id]
        val name = item?.let { it.at(localToday) ?: it.versions.minByOrNull { version -> version.from } }?.title
        val close = { delete = null; archive = null }
        val verb = if (deleting) "Delete" else "Archive"
        ReminderDialog("$verb reminder?", onDismiss = close, footer = {
            TextButton(onClick = close) { Text("Cancel") }
            Button(onClick = {
                if (mutate {
                    if (deleting) planner.delete(id)
                    else planner.archive(id, maxOf(requireNotNull(item).created, localToday))
                }) {
                    close()
                    scope.launch { snackbar.showSnackbar(if (deleting) "Reminder deleted" else "Reminder archived") }
                }
            }, colors = if (deleting) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors()) { Text(verb) }
        }) {
            Text(if (deleting) "${name ?: "This reminder"} and its upcoming alerts will be removed. Past activity stays in History."
                else "${name ?: "This reminder"} will leave your active reminders. Logged activity stays visible.")
        }
    }

    if(officePattern) OfficePatternDialog(planner,onDismiss={officePattern=false},onSave={days -> if(mutate { planner.editOffice(days) }) officePattern=false})
    if(officeDialog) OfficeExceptionDialog(date,onDismiss={officeDialog=false},
        onSave={end,choice,reason -> if(mutate { planner.changeOffice(date,end,choice,reason, weekdaysOnly = true) }) officeDialog=false},
        onUndo = if (planner.state.officeChanges[date].orEmpty().isEmpty()) null else {
            { if (mutate { planner.undoOffice(date) }) officeDialog = false }
        })
    action?.let { (id,kind) -> ItemActionDialog(planner,id,actionDate ?: date,kind,onDismiss={action=null;actionDate=null},onApply={block -> mutate(block).also {if(it){action=null;actionDate=null}}}) }
    val ritualScreen: @Composable (String) -> Unit = { id ->
        planner.state.rituals[id]?.let { content ->
            RitualReading(content, planner.state.changes[id]?.lastOrNull()?.date ?: FlexiblePlanner.parts(id).second,
                onBack = { nav.popBackStack() }, onRound = { delta -> mutate {
                    planner.refresh()
                    val latest = planner.state.rituals.getValue(id)
                    planner.setRounds(id, (latest.rounds + delta).coerceIn(0, latest.target))
                } }, alarmActive = activeSession, onAlarm = { alarmOpen = true })
        }
    }
    val dashboardContent by rememberUpdatedState(dashboard)
    val settingsContent by rememberUpdatedState(settingsScreen)
    val editorContent by rememberUpdatedState(editorScreen)
    val ritualContent by rememberUpdatedState(ritualScreen)
    NavHost(nav, startDestination = TodayRoute) {
        composable<TodayRoute> { dashboardContent(0) }
        composable<RemindersRoute> { dashboardContent(1) }
        composable<HistoryRoute> { dashboardContent(2) }
        composable<SettingsRoute> { settingsContent() }
        composable<EditReminderRoute> { editorContent(it.toRoute()) }
        composable<RitualRoute> { ritualContent(it.toRoute<RitualRoute>().occurrence) }
    }
    notice?.let { AlertDialog(onDismissRequest={notice=null},text={Text(it)},confirmButton={TextButton(onClick={notice=null}) { Text("OK") }}) }
}

private fun openAlarmAccess(context: android.content.Context) {
    if (Build.VERSION.SDK_INT >= 31) openSystem(context, systemActivity(context,
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))))
}

@Composable
private fun OfficePatternDialog(planner: FlexiblePlanner, onDismiss: () -> Unit, onSave: (Set<DayOfWeek>) -> Unit) {
    var chosen by remember {
        mutableStateOf(planner.state.office.filter { it.from <= LocalDate.now() }.maxByOrNull { it.from }!!.weekdays)
    }
    ReminderDialog("Usual office days", onDismiss, footer = {
        TextButton(onClick = onDismiss) { Text("Cancel") }
        Button(onClick = { onSave(chosen) }) { Text("Save weekdays") }
    }) {
        Text("Pick the weekdays you usually work at the office.")
        DayOfWeek.entries.filter { it.value <= 5 }.forEach { day ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(day in chosen, role = Role.Checkbox, onValueChange = {
                chosen = if (it) chosen + day else chosen - day
            }), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Checkbox(day in chosen, onCheckedChange = null)
                Text(day.getDisplayName(TextStyle.FULL, Locale.getDefault()))
            }
        }
    }
}

@Composable
private fun OfficeExceptionDialog(date: LocalDate, onDismiss: () -> Unit,
                                  onSave: (LocalDate, OfficeChoice, String) -> Unit, onUndo: (() -> Unit)?) {
    var choice by remember { mutableStateOf(OfficeChoice.OFF) }
    var end by remember { mutableStateOf(date) }
    var reason by rememberSaveable { mutableStateOf("") }
    val pickDay = LocalPickDay.current
    val valid = end >= date && end <= date.plusDays(365)
    val count = if (valid) (date.toEpochDay()..end.toEpochDay()).count { LocalDate.ofEpochDay(it).dayOfWeek.value <= 5 } else 0
    ReminderDialog("Change day types", onDismiss, footer = {
        TextButton(onClick = onDismiss) { Text("Cancel") }
        Button(enabled = valid && count > 0, onClick = { onSave(end, choice, reason) }) { Text("Apply") }
    }) {
        Text("Temporarily change which routines appear on these weekdays.")
        val choices = listOf(OfficeChoice.ON to "At the office", OfficeChoice.OFF to "Work from home",
            OfficeChoice.LEAVE to "Leave", OfficeChoice.USUAL to "Use usual day")
        for ((value, label) in choices) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(choice == value,
                onClick = { choice = value }, role = Role.RadioButton), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RadioButton(choice == value, onClick = null)
                Text(label)
            }
        }
        Text("From ${shortDate(date)}", style = MaterialTheme.typography.labelLarge)
        EditorButton("Through", shortDate(end), Modifier.fillMaxWidth(), showChevron = true) { pickDay(end) { end = it } }
        OutlinedTextField(reason, { reason = it.take(256) }, label = { Text("Reason (optional)") }, modifier = Modifier.fillMaxWidth())
        Text(if (!valid) "Choose an end date within a year of the start."
            else if (count == 1) "Applies to 1 weekday" else "Applies to $count weekdays",
            style = MaterialTheme.typography.bodySmall)
        onUndo?.let { TextButton(onClick = it) { Text("Restore usual schedule") } }
    }
}

@Composable
private fun ItemActionDialog(planner: FlexiblePlanner, id: String, date: LocalDate, kind: String,
                             onDismiss: () -> Unit, onApply: (() -> Unit) -> Boolean) {
    val occurrence = planner.occurrences(date).firstOrNull { it.id == id }
    var target by rememberSaveable { mutableStateOf(date.toString()) }
    val prior = planner.entry(id)
    var time by rememberSaveable {
        mutableStateOf((if (kind == "report") prior.actual else occurrence?.time)?.toString().orEmpty())
    }
    var reason by rememberSaveable { mutableStateOf("") }
    var outcome by rememberSaveable { mutableStateOf(prior.outcome) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var clear by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf(false) }
    val pickDay = LocalPickDay.current
    val title = when (kind) {
        "move" -> "Move occurrence"
        "extra" -> "Add occurrence"
        "skip" -> "Skip occurrence"
        else -> "Log outcome"
    }
    ReminderDialog(title, onDismiss, footer = {
        TextButton(onClick = onDismiss) { Text("Cancel") }
        Button(enabled = kind != "report" || outcome != Outcome.UNLOGGED, onClick = {
            val actual = time.takeIf(String::isNotBlank)?.let(LocalTime::parse)
            if (kind == "report" && outcome == Outcome.LATE && actual == null) {
                error = "Choose the completion time."
            } else onApply {
                when (kind) {
                    "skip" -> planner.skip(id, reason)
                    "move" -> planner.move(id, LocalDate.parse(target), actual)
                    "extra" -> planner.addExtra(FlexiblePlanner.parts(id).first, LocalDate.parse(target), actual)
                    "report" -> planner.report(id, Entry(outcome, actual, prior.loggedAt ?: Instant.now()))
                }
            }
        }) { Text(if (kind == "report") "Save outcome" else title.substringBefore(' ')) }
    }) {
        if (kind == "report") {
            listOf(Outcome.DONE to "Done", Outcome.LATE to "Done late", Outcome.SKIPPED to "Skipped").forEach { (value, label) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(outcome == value, onClick = { outcome = value; error = null }, role = Role.RadioButton),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RadioButton(outcome == value, onClick = null)
                    Text(label)
                }
            }
            if (prior.outcome != Outcome.UNLOGGED) TextButton(onClick = { clear = true }) { Text("Clear outcome") }
        }
        if (kind == "move" || kind == "extra") {
            val day = LocalDate.parse(target)
            EditorButton("Date", shortDate(day), Modifier.fillMaxWidth(), showChevron = true) {
                pickDay(day) { target = it.toString() }
            }
        }
        if (kind == "move" || kind == "extra" || kind == "report" && outcome in setOf(Outcome.DONE, Outcome.LATE)) {
            val label = if (kind == "report") "Completed at" else "Time"
            val value = time.takeIf(String::isNotBlank)?.let(LocalTime::parse)
            EditorButton(label, if (value == null && kind == "report") "No time recorded" else clockLabel(value),
                Modifier.fillMaxWidth(), showChevron = true) { pickTime = true }
        }
        if (kind == "skip") OutlinedTextField(reason, { reason = it.take(256) }, label = { Text("Reason (optional)") },
            modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (pickTime) ReminderTimePicker(time.takeIf(String::isNotBlank)?.let(LocalTime::parse) ?: LocalTime.now(),
        onDismiss = { pickTime = false }, onAnytime = { time = ""; pickTime = false },
        onTime = { time = it.toString(); pickTime = false })
    if (clear) ReminderDialog("Clear this outcome?", { clear = false }, footer = {
        TextButton(onClick = { clear = false }) { Text("Cancel") }
        Button(onClick = { if (onApply { planner.report(id, Entry()) }) clear = false }) { Text("Clear outcome") }
    }) { Text("This clears the recorded outcome and completion time.") }
}

@Composable
internal fun FlexiblePlannerLab(onExit:()->Unit) { ReminderDateHost { DailyRhythmApp() } }
