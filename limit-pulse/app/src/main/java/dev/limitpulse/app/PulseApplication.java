package dev.limitpulse.app;

import android.app.Application;
import android.content.Context;

public final class PulseApplication extends Application {
    private AccountRepository repository;
    private ClaudeRepository claude;
    @Override public void onCreate() {
        super.onCreate();
        repository = new AccountRepository(this, new OpenAiApi());
        claude = new ClaudeRepository(this, new ClaudeApi());
        if (!repository.connected()) ResetReminders.clearAccount(this);
        if (!claude.connected()) ResetReminders.clearAccount(this, ResetReminders.CLAUDE);
        ResetReminders.reconcileAll(this, System.currentTimeMillis());
        WidgetTicker.schedule(this);
    }
    static ClaudeRepository claude(Context context) {
        return ((PulseApplication) context.getApplicationContext()).claude;
    }
    static AccountRepository repository(Context context) {
        return ((PulseApplication) context.getApplicationContext()).repository;
    }
}
