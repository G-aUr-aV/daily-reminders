package dev.gaurav.dailyrhythm

import androidx.navigation.NavHostController
import java.time.LocalDate
import kotlinx.serialization.Serializable

@Serializable internal object TodayRoute
@Serializable internal object RemindersRoute
@Serializable internal object HistoryRoute
@Serializable internal object SettingsRoute
@Serializable internal data class EditReminderRoute(val item: String?, val date: String, val sample: String? = null)
@Serializable internal data class RitualRoute(val occurrence: String)

internal fun NavHostController.showTab(index: Int) {
    val options: androidx.navigation.NavOptionsBuilder.() -> Unit = {
        popUpTo<TodayRoute>()
        launchSingleTop = true
    }
    when (index) {
        0 -> navigate(TodayRoute, options)
        1 -> navigate(RemindersRoute, options)
        2 -> navigate(HistoryRoute, options)
    }
}

internal fun NavHostController.editReminder(id: String?, date: LocalDate, sample: ReminderSample? = null) {
    navigate(EditReminderRoute(id, date.toString(), sample?.name)) { launchSingleTop = true }
}
