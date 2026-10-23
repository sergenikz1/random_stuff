package dev.limitpulse.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ResetReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        int slot = intent.getIntExtra(ResetReminders.SLOT, -1);
        ResetReminders.deliver(context, ResetReminders.data(context, slot / 2), slot,
                intent.getLongExtra(ResetReminders.RESET, 0), System.currentTimeMillis());
        ResetReminders.reconcileAll(context, System.currentTimeMillis());
        LimitsWidget.updateAll(context); ClaudeWidget.updateAll(context);
        RefreshJob.requestNow(context);
    }
}
