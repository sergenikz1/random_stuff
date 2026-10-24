package org.scryertv.client

import android.app.Application
import org.scryertv.client.data.Prefs
import org.scryertv.client.data.ScryerApi

class ScryerTvApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Система может поднять процесс сразу на внутреннем экране — адрес и ключ нужны всем.
        val prefs = Prefs.get(this)
        ScryerApi.configure(prefs.serverUrl, prefs.token)
    }
}
