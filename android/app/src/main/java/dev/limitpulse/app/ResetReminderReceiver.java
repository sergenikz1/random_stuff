package dev.limitpulse.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ResetReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        ResetReminders.deliver(context, PulseApplication.repository(context).data,
                intent.getIntExtra(ResetReminders.SLOT, -1), intent.getLongExtra(ResetReminders.RESET, 0), System.currentTimeMillis());
        ResetReminders.reconcile(context, PulseApplication.repository(context).data, System.currentTimeMillis());
        LimitsWidget.updateAll(context);
        RefreshJob.requestNow(context);
    }
}
