package org.scryertv.client.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import org.scryertv.client.R
import org.scryertv.client.data.CalendarItem
import org.scryertv.client.data.MediaItem
import org.scryertv.client.data.Prefs
import org.scryertv.client.data.QueueItem
import org.scryertv.client.data.ScryerException

/** Переходы между экранами и общая реакция на ошибки. */
object Nav {

    fun open(context: Context, item: Any?) {
        when (item) {
            is MediaItem -> context.startActivity(DetailsActivity.intent(context, item))
            is QueueItem -> {
                val titleId = item.titleId
                if (titleId != null) {
                    context.startActivity(
                        DetailsActivity.intent(context, stub(titleId, item.titleName))
                    )
                } else {
                    context.startActivity(Intent(context, QueueActivity::class.java))
                }
            }
            is CalendarItem -> context.startActivity(
                DetailsActivity.intent(context, stub(item.titleId, item.titleName))
            )
        }
    }

    /** Заготовка карточки, пока экран тайтла не дотянул остальное с сервера. */
    private fun stub(titleId: String, name: String) =
        MediaItem(key = "title:$titleId", titleId = titleId, name = name, facet = null)

    /** Экран входа поверх всего стека. */
    fun toSetup(activity: Activity) {
        activity.startActivity(
            Intent(activity, SetupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        activity.finish()
    }

    /**
     * Текст ошибки для пользователя. Если ключ перестал действовать — сбрасывает
     * сессию и уводит на вход; тогда возвращает null.
     */
    fun errorText(activity: Activity?, error: Throwable): String? {
        if (activity == null) return null
        if (error is ScryerException && error.needsLogin) {
            Prefs.get(activity).clearSession()
            Toast.makeText(activity, R.string.session_expired, Toast.LENGTH_LONG).show()
            toSetup(activity)
            return null
        }
        return error.message?.takeIf { it.isNotBlank() }
            ?: activity.getString(R.string.error_network)
    }
}
