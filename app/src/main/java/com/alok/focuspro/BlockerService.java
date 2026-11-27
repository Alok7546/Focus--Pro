package com.alok.focuspro;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;
import java.util.Locale;
import java.util.Map;

public class BlockerService extends Service {

    private Handler handler = new Handler();
    private TextToSpeech tts;
    private SharedPreferences prefs;
    private WindowManager windowManager;
    private View blockView;

    private String currentApp = "";
    private String lastSpokenApp = "";

    private final Runnable checkRunnable = new Runnable() {
        @Override
        public void run() {
            checkForegroundApp();
            handler.postDelayed(this, 1000); // Har 1 second mein fast check
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("FocusProPrefs", MODE_PRIVATE);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

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

        // 8 hours (480 mins) pure hone par block hata dein
        if (getTodayStudyMinutes(usm) >= 480) {
            removeBlockScreen();
            return;
        }

        // Exact current app pata karne ke liye UsageEvents
        long now = System.currentTimeMillis();
        UsageEvents events = usm.queryEvents(now - 3000, now);
        UsageEvents.Event event = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                currentApp = event.getPackageName();
            }
        }

        if (currentApp.isEmpty() || currentApp.equals(getPackageName())) {
            removeBlockScreen();
            lastSpokenApp = "";
            return;
        }

        String cat = prefs.getString("cat_" + currentApp,
                (currentApp.contains("instagram") || currentApp.contains("facebook")) ? "distract" : "normal");

        if (cat.equals("distract")) {
            showBlockScreen();
            goToHome();

            // Sirf 1 baar bolega jab app open hoga
            if (!lastSpokenApp.equals(currentApp) && tts != null) {
                lastSpokenApp = currentApp;
                tts.speak("Alok, padhne par dhyan do! Abhi 8 ghante pure nahi hue hain.",
                        TextToSpeech.QUEUE_FLUSH, null, "FOCUS_ALERT");
            }
        } else {
            // Dusra app ya Home screen aate hi sab turant band
            removeBlockScreen();
            lastSpokenApp = "";
        }
    }

    private void showBlockScreen() {
        if (blockView != null || !Settings.canDrawOverlays(this)) return;

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setBackgroundColor(Color.parseColor("#F20B0D12"));
        layout.setPadding(60, 60, 60, 60);

        TextView title = new TextView(this);
        title.setText("APP BLOCKED");
        title.setTextColor(Color.parseColor("#EF4444"));
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);

        TextView msg = new TextView(this);
        msg.setText("\nAlok, padhne par dhyan do!\n8 hours study puri hone ke baad hi yeh app khulega.\n");
        msg.setTextColor(Color.WHITE);
        msg.setTextSize(16);
        msg.setGravity(Gravity.CENTER);

        Button btnHome = new Button(this);
        btnHome.setText("PADHAI PAR WAPAS JAO");
        btnHome.setBackgroundColor(Color.parseColor("#F5B942"));
        btnHome.setTextColor(Color.BLACK);
        btnHome.setPadding(40, 20, 40, 20);
        btnHome.setOnClickListener(v -> {
            removeBlockScreen();
            goToHome();
        });

        layout.addView(title);
        layout.addView(msg);
        layout.addView(btnHome);

        int layoutFlag = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );

        blockView = layout;
        windowManager.addView(blockView, params);
    }

    private void removeBlockScreen() {
        if (blockView != null) {
            try {
                windowManager.removeView(blockView);
            } catch (Exception ignored) {}
            blockView = null;
        }
    }

    private void goToHome() {
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(homeIntent);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(checkRunnable);
        removeBlockScreen();
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
