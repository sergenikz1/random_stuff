package com.voicereader

import android.content.Context
import androidx.annotation.StringRes

/** Строки из ресурсов для кода без Compose: плеер, парсеры, уведомления. Язык — как у телефона. */
object AppText {
    private var context: Context? = null

    fun init(context: Context) { this.context = context.applicationContext }

    fun get(@StringRes id: Int, vararg args: Any): String =
        context?.getString(id, *args) ?: ""
}
