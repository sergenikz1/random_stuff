package dev.limitpulse.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

public final class RefreshJob extends JobService {
    private static final int ID = 7001;
    private static final int IMMEDIATE_ID = 7002;
    static void requestNow(Context context) {
        if (!PulseApplication.repository(context).connected()) { LimitsWidget.updateAll(context); return; }
        context.getSystemService(JobScheduler.class).schedule(new JobInfo.Builder(IMMEDIATE_ID, new ComponentName(context, RefreshJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(0).build());
    }
    static void schedule(Context context) {
        if (!PulseApplication.repository(context).connected()) return;
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(ID) != null) return;
        scheduler.schedule(new JobInfo.Builder(ID, new ComponentName(context, RefreshJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(30 * 60 * 1000L)
                .setPersisted(true).build());
    }
    static void cancel(Context context) {
        context.getSystemService(JobScheduler.class).cancel(ID);
        context.getSystemService(JobScheduler.class).cancel(IMMEDIATE_ID);
    }
    @Override public boolean onStartJob(JobParameters parameters) {
        PulseApplication.repository(this).refresh(() -> jobFinished(parameters, false));
        return true;
    }
    @Override public boolean onStopJob(JobParameters parameters) { return true; }
}
