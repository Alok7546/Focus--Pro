package com.alok.focuspro;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.speech.tts.TextToSpeech;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class BlockerService extends Service {

    private Handler handler = new Handler();
    private TextToSpeech tts;
    private SharedPreferences prefs;
    private long lastAlertTime = 0;

    private final Runnable checkRunnable = new Runnable() {
        @Override
        public void run() {
            checkForegroundApp();
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("FocusProPrefs", MODE_PRIVATE);

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(new Locale("hi", "IN"));
            }
        });

        startForegroundNotification();
        handler.post(checkRunnable);
    }

    private void startForegroundNotification() {
        String channelId = "focus_pro_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId, "Focus Pro Blocker", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
            Notification notification = new Notification.Builder(this, channelId)
                    .setContentTitle("Focus Pro Active")
                    .setContentText("8 hours padhai puri hone tak distraction block hai")
                    .setSmallIcon(R.drawable.app_logo)
                    .build();
            startForeground(1, notification);
        }
    }

    private long getTodayStudyMinutes(UsageStatsManager usm) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        long startTime = cal.getTimeInMillis();
        long endTime = System.currentTimeMillis();

        Map<String, UsageStats> statsMap = usm.queryAndAggregateUsageStats(startTime, endTime);
        if (statsMap == null) return 0;

        long studyMins = 0;
        for (UsageStats us : statsMap.values()) {
            String pkg = us.getPackageName();
            String cat = prefs.getString("cat_" + pkg, "normal");
            if (cat.equals("study")) {
                studyMins += us.getTotalTimeInForeground() / 60000;
            }
        }
        return studyMins;
    }

    private void checkForegroundApp() {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);

        // Agar 8 hours (480 minutes) study puri ho gayi, toh block mat karo
        if (getTodayStudyMinutes(usm) >= 480) {
            return;
        }

        long now = System.currentTimeMillis();
        List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 10000, now);
        if (stats == null || stats.isEmpty()) return;

        UsageStats recent = null;
        for (UsageStats us : stats) {
            if (recent == null || us.getLastTimeUsed() > recent.getLastTimeUsed()) {
                recent = us;
            }
        }

        if (recent != null) {
            String pkg = recent.getPackageName();
            String cat = prefs.getString("cat_" + pkg,
                    (pkg.contains("instagram") || pkg.contains("facebook")) ? "distract" : "normal");

            if (cat.equals("distract")) {
                // App block karke Home screen par bhejein
                Intent homeIntent = new Intent(Intent.ACTION_MAIN);
                homeIntent.addCategory(Intent.CATEGORY_HOME);
                homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(homeIntent);

                // TTS Voice Warning (har 5 second mein max 1 baar)
                if (now - lastAlertTime > 5000 && tts != null) {
                    lastAlertTime = now;
                    tts.speak("Alok, padhne par dhyan do! Abhi aapke 8 ghante pure nahi hue hain.",
                            TextToSpeech.QUEUE_FLUSH, null, "FOCUS_ALERT");
                }
            }
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(checkRunnable);
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
