package org.intergalactictv.client

import android.app.Application
import org.intergalactictv.client.data.Prefs
import org.intergalactictv.client.data.ScryerApi

class IntergalacticTvApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Система может поднять процесс сразу на внутреннем экране — адрес и ключ нужны всем.
        val prefs = Prefs.get(this)
        ScryerApi.configure(prefs.serverUrl, prefs.token)
    }
}
