package dev.gaurav.dailyrhythm

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class QuietPresentationTest {
    private class Memory:KeyValue {
        val values=mutableMapOf<String,String>()
        var writes=0
        override fun get(key:String)=values[key]
        override fun put(key:String,value:String?) {writes++;if(value==null)values.remove(key) else values[key]=value}
    }
    private val date=LocalDate.of(2026,11,16)
    private val kv=Memory()
    private fun planner()=FlexiblePlanner(kv){date}
    private fun next(p:FlexiblePlanner,d:LocalDate=date)=QuietPresentation.next(p.occurrences(d),p.state,d,date)

    @Test fun activityUsesLocalDeliveryDayAcrossMidnightAndOmitsSchedulingNoise() {
        val id = FlexiblePlanner.identity("wake", date.minusDays(1))
        val zone = ZoneId.of("Asia/Kolkata")
        val midnight = date.atStartOfDay(zone).toInstant()
        val events = listOf(
            AlertEvent(id, "QUEUED", "Upcoming reminder", midnight.minusSeconds(120)),
            AlertEvent(id, "DELIVERED_LATE", "Ringing requested; no outcome recorded", midnight),
            AlertEvent(id, "DISMISSED", "No outcome or reading report", midnight.plusSeconds(5))
        )
        assertEquals(listOf("Stopped", "Alarm triggered"), QuietPresentation.reminderActivity(events, date, zone).map { it.second })
        assertTrue(QuietPresentation.reminderActivity(events, date.minusDays(1), zone).isEmpty())
    }

    @Test fun nextIsEarliestUnloggedNotLatestElapsedTime() {
        val p=planner()
        assertEquals("wake",next(p)?.itemId)
        p.report(FlexiblePlanner.identity("wake",date),Entry(Outcome.DONE,LocalTime.of(6,1)))
        assertEquals("pack",next(p)?.itemId)
    }
    @Test fun pastAndFutureSelectionsNeverShowLiveHero() {
        val p=planner();assertNull(next(p,date.minusDays(1)));assertNull(next(p,date.plusDays(1)))
    }
    @Test fun sameDayTimedExtraIsEligible() {
        val p=planner();val id=p.addExtra("exercise",date,LocalTime.of(5,0))
        assertEquals(id,next(p)?.id)
    }
    @Test fun movedAwayAndSkippedAreNotNext() {
        val p=planner();p.move(FlexiblePlanner.identity("wake",date),date.plusDays(1),LocalTime.of(6,0));p.skip(FlexiblePlanner.identity("pack",date))
        assertEquals("depart",next(p)?.itemId)
    }
    @Test fun listProgressAndHeroDoNotFreezeRitualOrWrite() {
        val p=planner();val before=kv.writes;val row=p.occurrences(date).single {it.itemId==FlexiblePlanner.AFFIRMATIONS}
        repeat(5){assertEquals(0 to 3,QuietPresentation.rounds(p.state,row));next(p)}
        assertEquals(before,kv.writes);assertTrue(p.state.rituals.isEmpty())
    }
    @Test fun completeRitualIsNotHeroAndDoesNotReportDone() {
        val p=planner();val item=p.create(PlanVersion(date,"Read",kind=ItemKind.RITUAL,time=LocalTime.of(5,0),content=listOf("One"),target=3))
        val id=FlexiblePlanner.identity(item,date);p.setRounds(id,2);assertEquals(id,next(p)?.id)
        p.setRounds(id,3);assertNotEquals(id,next(p)?.id);assertEquals(Outcome.UNLOGGED,p.entry(id).outcome)
        assertEquals(3,planner().state.rituals.getValue(id).rounds)
    }
    @Test fun legacyProgressReadsWithoutCreatingSnapshot() {
        val p=planner();val row=p.occurrences(date).single {it.itemId==FlexiblePlanner.AFFIRMATIONS};val before=kv.writes
        assertEquals(2 to 3,QuietPresentation.rounds(p.state,row,2));assertEquals(before,kv.writes)
        val extra=p.addExtra(FlexiblePlanner.AFFIRMATIONS,date,LocalTime.of(6,30));val r=p.occurrences(date).single {it.id==extra}
        assertEquals(0 to 3,QuietPresentation.rounds(p.state,r,2))
    }
    @Test fun heroTieBreakIsStableAcrossInputOrder() {
        val p=planner();val a=p.addExtra("exercise",date,LocalTime.of(5,0));val b=p.addExtra("shuttle",date,LocalTime.of(5,0));val rows=p.occurrences(date)
        assertEquals(minOf(a,b),QuietPresentation.next(rows.reversed(),p.state,date,date)?.id)
    }
    @Test fun emptyHeroDoesNotCallCompletedRitualRoundsAReport() {
        val p=planner()
        p.occurrences(date).filter {it.scheduled && it.time!=null}.forEach {p.report(it.id,Entry(Outcome.DONE,it.time))}
        val item=p.create(PlanVersion(date,"Read",kind=ItemKind.RITUAL,time=LocalTime.of(5,0),content=listOf("One"),target=1))
        val id=FlexiblePlanner.identity(item,date);p.setRounds(id,1)
        assertNull(next(p));assertEquals(Outcome.UNLOGGED,p.entry(id).outcome)
        assertTrue(QuietPresentation.noFocusLabel(p.occurrences(date)).contains("still unreported"))
    }

}
