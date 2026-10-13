package dev.limitpulse.app;

import android.app.Application;
import android.content.Context;

public final class PulseApplication extends Application {
    private AccountRepository repository;
    @Override public void onCreate() {
        super.onCreate();
        repository = new AccountRepository(this, new OpenAiApi());
    }
    static AccountRepository repository(Context context) {
        return ((PulseApplication) context.getApplicationContext()).repository;
    }
}
