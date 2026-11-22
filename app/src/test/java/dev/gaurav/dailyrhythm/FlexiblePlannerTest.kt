package dev.gaurav.dailyrhythm

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class FlexiblePlannerTest {
    private class Memory : KeyValue {
        val data = mutableMapOf<String,String>()
        var fail = false
        override fun get(key: String) = data[key]
        override fun put(key: String, value: String?) { check(!fail); if (value == null) data.remove(key) else data[key] = value }
    }
    private val monday = LocalDate.of(2026,11,9)
    private var day = monday
    private val kv = Memory()
    private fun planner() = FlexiblePlanner(kv) { day }
    private fun id(item: String, date: LocalDate = day) = FlexiblePlanner.identity(item,date)
    private fun item(p: FlexiblePlanner, item: String, date: LocalDate = day) = p.occurrences(date).single { it.id == id(item,date) }
    private fun custom(p: FlexiblePlanner, rule: RepeatRule, title: String = "Custom", time: LocalTime? = null) = p.create(PlanVersion(day,title,time=time,rule=rule))

    @Test fun migrationKeepsEveryExistingPreferenceUntouchedAndAddsOneValue() {
        val old = RhythmStore(kv)
        old.remindersEnabled=true
        old.setSchedule("wake",Schedule(LocalTime.of(6,13),false))
        old.setEntry(monday.minusDays(2),"wake",Entry(Outcome.LATE,LocalTime.of(6,30)))
        old.setEntry(monday,"depart",Entry(Outcome.DONE,LocalTime.of(7,28)))
        old.setRounds(monday,2)
        kv.put("alarm_v2.pack","existing-token-and-trigger")
        val before=kv.data.toMap(); val p=planner()
        assertEquals(before,kv.data.filterKeys { it!=FlexiblePlanner.KEY })
        assertEquals(LocalTime.of(6,13),item(p,"wake").time)
        assertFalse(item(p,"wake").version.gentleEnabled)
        assertEquals(Entry(Outcome.LATE,LocalTime.of(6,30)),p.entry(id("wake",monday.minusDays(2))))
        assertEquals(HistoryState.DONE,item(p,"depart").state)
        assertEquals(2,p.ritual(id(FlexiblePlanner.AFFIRMATIONS)).rounds)
        assertTrue(old.remindersEnabled)
        assertEquals("existing-token-and-trigger",kv.get("alarm_v2.pack"))
    }
    @Test fun migrationIsIdempotentAcrossRestartAndDateRollover() {
        val p=planner(); val before=kv.data.toMap(); day=day.plusDays(1)
        val reloaded=planner()
        assertEquals(monday,reloaded.state.migratedOn)
        assertEquals(before,kv.data)
        assertEquals(p.state,reloaded.state)
    }
    @Test fun officeStartsMondayWednesdayFridayAndPersonalItemsStayIndependent() {
        val p=planner()
        assertTrue(p.office(monday)); assertFalse(p.office(monday.plusDays(1))); assertTrue(p.office(monday.plusDays(2)))
        for (name in listOf("pack","depart","shuttle")) assertFalse(item(p,name,monday.plusDays(1)).scheduled)
        for (name in listOf("wake","exercise","bed",FlexiblePlanner.AFFIRMATIONS)) assertTrue(item(p,name,monday.plusDays(1)).scheduled)
    }
    @Test fun leaveWednesdayOnlyRemovesLinkedRoutinesAndFollowingWednesdayUnchanged() {
        val p=planner(); val wed=monday.plusDays(2)
        val preview=p.previewOffice(wed,OfficeChoice.OFF,"Leave")
        assertEquals(3,preview.removed.size); assertTrue(preview.added.isEmpty())
        p.changeOffice(wed,wed,OfficeChoice.OFF,"Leave")
        assertFalse(item(p,"pack",wed).scheduled); assertTrue(item(p,"exercise",wed).scheduled)
        assertTrue(item(p,FlexiblePlanner.AFFIRMATIONS,wed).scheduled)
        assertTrue(item(p,"pack",wed.plusWeeks(1)).scheduled)
    }
    @Test fun extraOfficeTuesdayAddsEachLinkedItemOnceEvenIfAppliedTwice() {
        val p=planner(); val tue=monday.plusDays(1)
        repeat(2) { p.changeOffice(tue,tue,OfficeChoice.ON) }
        assertEquals(3,p.occurrences(tue).count { it.scheduled && it.itemId in setOf("pack","depart","shuttle") })
        assertFalse(p.office(tue.plusWeeks(1)))
    }
    @Test fun skipPackingDoesNotSkipDepartureAndUndoRestoresOnlyPacking() {
        val p=planner(); p.skip(id("pack"),"No bag")
        assertEquals(HistoryState.SKIPPED,item(p,"pack").state); assertTrue(item(p,"depart").scheduled)
        p.undoItem(id("pack")); assertEquals(HistoryState.UNLOGGED,item(p,"pack").state)
    }
    @Test fun groupChangePreservesIndividualDoneAndExplicitSkipWithPreview() {
        val p=planner(); p.report(id("depart"),Entry(Outcome.DONE,LocalTime.of(7,25))); p.skip(id("pack"),"Already packed")
        val preview=p.previewOffice(day,OfficeChoice.OFF)
        assertTrue(preview.existingExceptions.any { it.contains("Start packing") })
        assertEquals(2,preview.preservedReports.size)
        p.changeOffice(day,day,OfficeChoice.OFF)
        assertEquals(HistoryState.DONE,item(p,"depart").state); assertEquals(HistoryState.SKIPPED,item(p,"pack").state)
        assertFalse(item(p,"shuttle").scheduled)
    }
    @Test fun officeRangeUndoRestoresThePredecessorForThatDateOnly() {
        val p=planner(); p.changeOffice(day,day.plusDays(2),OfficeChoice.OFF,"Holiday")
        p.changeOffice(day,day,OfficeChoice.ON); p.undoOffice(day)
        assertFalse(p.office(day)); assertFalse(p.office(day.plusDays(2)))
        p.undoOffice(day); assertTrue(p.office(day)); assertFalse(p.office(day.plusDays(2)))
    }
    @Test fun itemExtraWinsOnHolidayAndDoesNotAlterTheNormalOfficeRule() {
        val p=planner(); p.changeOffice(day,day,OfficeChoice.OFF)
        val extra=p.addExtra("pack",day,LocalTime.of(9,0))
        val rows=p.occurrences(day)
        assertFalse(item(p,"pack").scheduled); assertTrue(rows.single { it.id==extra }.scheduled)
        assertEquals(1,rows.count { it.itemId=="pack" && it.scheduled })
    }
    @Test fun oneOffIsAllowedOnHolidayAndDoesNotRepeat() {
        val p=planner(); p.changeOffice(day,day,OfficeChoice.OFF)
        val custom=custom(p,RepeatRule(RepeatKind.ONCE,oneDate=day))
        assertTrue(item(p,custom).scheduled); assertFalse(item(p,custom,day.plusDays(1)).scheduled)
    }
    @Test fun monthlyPolicyIsRequiredFor29Through31() {
        for (n in 29..31) assertThrows(IllegalArgumentException::class.java) { RepeatRule(RepeatKind.MONTHLY,monthDays=setOf(n)) }
    }
    @Test fun februarySkipAndMonthEndPoliciesAreExplicit() {
        val skip=RepeatRule(RepeatKind.MONTHLY,monthDays=setOf(31),missing=MissingDate.SKIP)
        val end=skip.copy(missing=MissingDate.MONTH_END)
        assertTrue(skip.monthDates(YearMonth.of(2027,2)).isEmpty())
        assertEquals(setOf(LocalDate.of(2027,2,28)),end.monthDates(YearMonth.of(2027,2)))
    }
    @Test fun monthlyMultipleDatesAndLastDayDeduplicateInLeapFebruary() {
        val r=RepeatRule(RepeatKind.MONTHLY,monthDays=setOf(1,29,30,31),lastDay=true,missing=MissingDate.MONTH_END)
        assertEquals(setOf(LocalDate.of(2028,2,1),LocalDate.of(2028,2,29)),r.monthDates(YearMonth.of(2028,2)))
    }
    @Test fun monthLengthsAndCenturyLeapRulesDoNotInventDates() {
        val r=RepeatRule(RepeatKind.MONTHLY,monthDays=setOf(29),missing=MissingDate.SKIP)
        assertEquals(emptySet<LocalDate>(),r.monthDates(YearMonth.of(2100,2)))
        assertEquals(setOf(LocalDate.of(2000,2,29)),r.monthDates(YearMonth.of(2000,2)))
        assertEquals(setOf(LocalDate.of(2026,4,30)),r.copy(monthDays=setOf(31),missing=MissingDate.MONTH_END).monthDates(YearMonth.of(2026,4)))
    }
    @Test fun selectedWeekdaysAndNextThreeUseCalendarNotCompletionDate() {
        val p=planner(); val custom=custom(p,RepeatRule(RepeatKind.WEEKDAYS,weekdays=setOf(DayOfWeek.TUESDAY,DayOfWeek.THURSDAY)))
        assertEquals(listOf(monday.plusDays(1),monday.plusDays(3),monday.plusDays(8)),p.nextDates(custom))
        p.report(id(custom,monday.plusDays(1)),Entry(Outcome.DONE))
        assertEquals(listOf(monday.plusDays(1),monday.plusDays(3),monday.plusDays(8)),p.nextDates(custom))
    }
    @Test fun futurePatternChangeDoesNotRewritePriorRuleTitleOrReport() {
        val p=planner(); val custom=custom(p,RepeatRule(RepeatKind.DAILY),"Original")
        p.report(id(custom),Entry(Outcome.DONE,LocalTime.NOON)); day=day.plusDays(1)
        val version=p.state.items.getValue(custom).at(day)!!
        p.edit(custom,version.copy(from=day,title="New",rule=RepeatRule(RepeatKind.WEEKDAYS,weekdays=setOf(DayOfWeek.FRIDAY))))
        assertEquals("Original",item(p,custom,monday).title); assertEquals(HistoryState.DONE,item(p,custom,monday).state)
        assertTrue(item(p,custom,monday).scheduled); assertFalse(item(p,custom,day).scheduled)
    }
    @Test fun officeNormalPatternEditIsEffectiveForwardOnly() {
        val p=planner(); day=day.plusDays(2); p.editOffice(setOf(DayOfWeek.TUESDAY))
        assertTrue(p.office(monday)); assertFalse(p.office(day)); assertTrue(p.office(monday.plusWeeks(1).plusDays(1)))
    }
    @Test fun moveRetainsIdentityAndReportButSuppressesOriginalAndDoesNotDoubleCount() {
        val p=planner(); val old=id("pack"); p.report(old,Entry(Outcome.DONE,LocalTime.of(8,0)))
        p.move(old,day.plusDays(1),LocalTime.of(8,0))
        assertEquals(HistoryState.NOT_SCHEDULED,item(p,"pack").state)
        val moved=p.occurrences(day.plusDays(1)).single { it.id==old }
        assertTrue(moved.scheduled); assertEquals(HistoryState.DONE,moved.state); assertEquals(LocalTime.of(8,0),moved.actual)
        assertEquals(1,(p.occurrences(day)+p.occurrences(day.plusDays(1))).count { it.id==old && it.state==HistoryState.DONE })
    }
    @Test fun moveToAnytimeDoesNotFallBackToOldTime() {
        val p=planner(); p.move(id("pack"),day.plusDays(1),null)
        assertNull(p.occurrences(day.plusDays(1)).single { it.id==id("pack") }.time)
    }
    @Test fun undoRestoresSkipPredecessorNotTheNormalRule() {
        val p=planner(); p.skip(id("pack"),"Reason"); p.move(id("pack"),day.plusDays(1),LocalTime.NOON)
        p.undoItem(id("pack")); assertEquals(HistoryState.SKIPPED,item(p,"pack").state)
        p.undoItem(id("pack")); assertEquals(HistoryState.UNLOGGED,item(p,"pack").state)
    }
    @Test fun skipMovedOccurrenceStaysAtMovedDateAndUndoKeepsTheMove() {
        val p=planner(); val original=id("pack"); p.move(original,day.plusDays(1),LocalTime.NOON); p.skip(original)
        assertEquals(HistoryState.NOT_SCHEDULED,item(p,"pack").state)
        assertEquals(HistoryState.SKIPPED,p.occurrences(day.plusDays(1)).single { it.id==original }.state)
        p.undoItem(original); assertTrue(p.occurrences(day.plusDays(1)).single { it.id==original }.scheduled)
    }
    @Test fun archiveNeverDeletesPastHistory() {
        val p=planner(); p.report(id("pack"),Entry(Outcome.LATE,LocalTime.of(7,12))); day=day.plusDays(1); p.archive("pack")
        assertEquals(HistoryState.LATE,item(p,"pack",monday).state); assertFalse(item(p,"pack",monday.plusDays(2)).scheduled)
        assertEquals(Entry(Outcome.LATE,LocalTime.of(7,12)),planner().entry(id("pack",monday)))
    }
    @Test fun legacyHistoryDoesNotInventPastReminderTimes() {
        val p=planner(); val past=item(p,"wake",day.minusDays(1))
        assertNull(past.time); assertTrue(past.version.legacyUnknownSchedule); assertTrue(past.explanation.contains("unavailable"))
    }
    @Test fun legacyCompletedTitleSurvivesNewTitleEdit() {
        RhythmStore(kv).setEntry(day,"wake",Entry(Outcome.DONE)); val p=planner()
        p.edit("wake",item(p,"wake").version.copy(title="Changed wake"))
        assertEquals("Wake up",item(p,"wake").title)
    }
    @Test fun unloggedTombstoneMasksLegacyWithoutDeletingIt() {
        RhythmStore(kv).setEntry(day,"wake",Entry(Outcome.DONE)); val p=planner(); p.report(id("wake"),Entry())
        assertEquals(HistoryState.UNLOGGED,item(p,"wake").state)
        assertEquals(Outcome.DONE,RhythmStore(kv).entry(day,"wake").outcome)
        assertEquals(Entry(),planner().entry(id("wake")))
    }
    @Test fun routineStatesDistinguishMissingSkippedAndNotScheduled() {
        val p=planner(); assertEquals(HistoryState.UNLOGGED,item(p,"pack").state)
        p.skip(id("pack")); assertEquals(HistoryState.SKIPPED,item(p,"pack").state)
        assertEquals(HistoryState.NOT_SCHEDULED,item(p,"depart",day.plusDays(1)).state)
    }
    @Test fun migrationRetainsLegacyRoundsWithGenericFallbackContent() {
        RhythmStore(kv).setRounds(day,2); val p=planner(); val r=p.ritual(id(FlexiblePlanner.AFFIRMATIONS))
        assertEquals(ReminderSample.MORNING.steps,r.lines); assertEquals(3,r.target); assertEquals(2,r.rounds)
        assertEquals(2,planner().ritual(id(FlexiblePlanner.AFFIRMATIONS)).rounds)
    }
    @Test fun ritualContentAndRoundTargetSnapshotPerDateSurvivesEditsAndExit() {
        val p=planner(); val custom=p.create(PlanVersion(day,"Read",kind=ItemKind.RITUAL,content=listOf("One","Two"),target=2))
        p.setRounds(id(custom),1)
        p.edit(custom,p.state.items.getValue(custom).at(day)!!.copy(content=listOf("Changed"),target=3))
        assertEquals(listOf("One","Two"),planner().ritual(id(custom)).lines)
        assertEquals(1,planner().ritual(id(custom)).rounds)
        assertEquals(listOf("Changed"),planner().ritual(id(custom,day.plusDays(1))).lines)
    }
    @Test fun ritualRoundReportDoesNotAutoLogOutcomeAndNewDateStartsZero() {
        val p=planner(); val ritual=id(FlexiblePlanner.AFFIRMATIONS); p.setRounds(ritual,3)
        assertEquals(Entry(),p.entry(ritual)); assertEquals(0,p.ritual(id(FlexiblePlanner.AFFIRMATIONS,day.plusDays(1))).rounds)
        p.setRounds(ritual,2); assertEquals(2,p.ritual(ritual).rounds)
        assertThrows(IllegalArgumentException::class.java) { p.setRounds(ritual,4) }
    }
    @Test fun plannerRestartRoundTripKeepsOverridesExceptionsLogsAndVersions() {
        val p=planner(); p.changeOffice(day,day.plusDays(2),OfficeChoice.OFF,"Leave"); val extra=p.addExtra("pack",day,LocalTime.NOON)
        p.report(extra,Entry(Outcome.DONE,LocalTime.NOON)); p.setRounds(id(FlexiblePlanner.AFFIRMATIONS),1)
        assertEquals(p.state,planner().state)
    }
    @Test fun corruptPlanningStateIsNotSilentlyReset() {
        kv.put(FlexiblePlanner.KEY,"not-base64"); val before=kv.data.toMap()
        assertThrows(IllegalStateException::class.java) { planner() }; assertEquals(before,kv.data)
    }
    @Test fun failedCommitDoesNotMutateTheInMemoryPlan() {
        val p=planner(); val before=p.state; kv.fail=true
        assertThrows(IllegalStateException::class.java) { p.changeOffice(day,day,OfficeChoice.OFF) }
        assertEquals(before,p.state)
    }
    @Test fun historicalPlanEditsAreRejectedButHistoricalReportsRemainEditable() {
        val p=planner(); assertThrows(IllegalArgumentException::class.java) { p.changeOffice(day.minusDays(1),day,OfficeChoice.OFF) }
        p.report(id("wake",day.minusDays(1)),Entry(Outcome.LATE,LocalTime.of(6,20)))
        assertEquals(Outcome.LATE,p.entry(id("wake",day.minusDays(1))).outcome)
    }
    @Test fun midDayGroupChangesOnlyProjectRemainingAlertsAndNeverReplayPastMorning() {
        val p=planner(); val zone=ZoneId.of("Asia/Kolkata"); val now=day.atTime(12,0).atZone(zone).toInstant()
        p.changeOffice(day,day,OfficeChoice.OFF); p.changeOffice(day,day,OfficeChoice.ON)
        val delta=p.gentleDelta(day,now,zone,emptyMap())
        assertFalse(delta.added.containsKey(id("pack"))); assertTrue(delta.suppressedPast.contains(id("pack")))
        assertTrue(delta.added.containsKey(id("bed")))
    }
    @Test fun unchangedDueAlertIsRetainedButSkipAndMoveInvalidateItInProjection() {
        val p=planner(); val zone=ZoneId.of("Asia/Kolkata"); val due=day.atTime(7,0).atZone(zone).toInstant(); val pending=mapOf(id("pack") to due)
        assertEquals(pending,p.gentleDelta(day,due.plusSeconds(5),zone,pending).retained)
        p.skip(id("pack")); assertEquals(pending.keys,p.gentleDelta(day,due.plusSeconds(5),zone,pending).cancelled)
        p.undoItem(id("pack")); p.move(id("pack"),day,LocalTime.of(8,0))
        val moved=p.gentleDelta(day,due.plusSeconds(5),zone,pending)
        assertTrue(moved.cancelled.contains(id("pack"))); assertEquals(day.atTime(8,0).atZone(zone).toInstant(),moved.added[id("pack")])
    }
    @Test fun malformedOccurrenceReferencesAreRejectedWithoutResettingStorage() {
        val p=planner(); val invalid=p.state.copy(reports=mapOf("missing@$monday#base" to Report(Entry(Outcome.DONE),"Missing")))
        kv.put(FlexiblePlanner.KEY,FlexCodec.encode(invalid)); val before=kv.data.toMap()
        assertThrows(IllegalStateException::class.java) { planner() }; assertEquals(before,kv.data)
    }
    @Test fun oversizeSnapshotIsRefusedBeforeAnUnreadableValueCanBeSaved() {
        val p=planner(); val version=PlanVersion(day,"Large",kind=ItemKind.RITUAL,content=List(64) { "x".repeat(4096) })
        val many=(1..25).associate { "large$it" to PlanItem("large$it",day,listOf(version)) }
        assertThrows(IllegalArgumentException::class.java) { FlexCodec.encode(p.state.copy(items=many)) }
    }
    @Test fun reviewPreviewUsesItemExceptionsWithoutMutatingPersistedState() {
        val p=planner(); p.skip(id("pack")); val before=kv.data.toMap()
        val dates=p.previewDates("pack",item(p,"pack").version.copy(from=day,title="Updated"))
        assertEquals(monday.plusDays(2),dates.first()); assertEquals(before,kv.data)
    }
    @Test fun newOneOffPreviewRespectsFutureEffectiveDateAndDoesNotCreateItem() {
        val p=planner(); val before=p.state
        assertEquals(listOf(day.plusDays(2)),p.previewDates(null,PlanVersion(day.plusDays(1),"One off",rule=RepeatRule(RepeatKind.ONCE,oneDate=day.plusDays(2)))))
        assertEquals(before,p.state)
    }
    @Test fun movedFutureOccurrenceCanBeSkippedAfterItsOriginalDayPasses() {
        val p=planner(); val original=id("pack"); p.move(original,day.plusDays(1),LocalTime.NOON)
        day=day.plusDays(1); p.skip(original,"Skip moved occurrence")
        assertEquals(HistoryState.SKIPPED,p.occurrences(day).single { it.id==original }.state)
        p.undoItem(original); assertTrue(p.occurrences(day).single { it.id==original }.scheduled)
        assertThrows(IllegalArgumentException::class.java) { p.undoItem(original) }
    }
    @Test fun newLateRequiresActualTimeButExistingLegacyLateWithoutTimeIsRetained() {
        RhythmStore(kv).setEntry(day,"wake",Entry(Outcome.LATE)); val p=planner()
        assertEquals(HistoryState.LATE,item(p,"wake").state)
        assertThrows(IllegalArgumentException::class.java) { p.report(id("depart"),Entry(Outcome.LATE)) }
    }
    @Test fun cannotAddAnExtraToAnArchivedItem() {
        val p=planner(); p.archive("pack")
        assertThrows(IllegalArgumentException::class.java) { p.addExtra("pack",day,LocalTime.NOON) }
    }
    @Test fun planProjectionResolvesDstGapsAndDeviceTimezoneWithoutFixed24Hours() {
        day=LocalDate.of(2027,3,14); val p=planner(); val custom=p.create(PlanVersion(day,"DST",time=LocalTime.of(2,30),gentleEnabled=true))
        val zone=ZoneId.of("America/New_York"); val before=day.atStartOfDay(zone).toInstant()
        assertEquals(Instant.parse("2027-03-14T07:30:00Z"),p.gentleDelta(day,before,zone,emptyMap()).added[id(custom)])
        assertNotEquals(p.gentleDelta(day,before,zone,emptyMap()).added[id(custom)],p.gentleDelta(day,before,ZoneId.of("Asia/Kolkata"),emptyMap()).added[id(custom)])
    }
}
