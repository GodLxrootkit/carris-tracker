package pt.carrismetropolitana.tracker

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Time helpers, kept free of Android APIs so they can be unit tested. */
object Schedule {

    /** "agora" | "3 min" | "1 h 05" for a target unix time. */
    fun countdown(target: Long, now: Long): String {
        val minutes = (target - now + 30) / 60
        return when {
            minutes <= 0 -> "agora"
            minutes < 60 -> "$minutes min"
            else -> "${minutes / 60} h ${String.format(Locale.US, "%02d", minutes % 60)}"
        }
    }

    fun hhMm(unix: Long): String =
        SimpleDateFormat("HH:mm", Locale.US).format(Date(unix * 1000))
}
