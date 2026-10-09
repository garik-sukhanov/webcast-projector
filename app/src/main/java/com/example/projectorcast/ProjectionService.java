package com.example.projectorcast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Фоновый сервис трансляции:
 *  - показывает выбранную страницу на проекторе через {@link BrowserPresentation}
 *    с типом окна OVERLAY, чтобы показ продолжался, даже когда приложение свёрнуто;
 *  - рисует плавающую кнопку «ТВ» поверх всех приложений;
 *  - по тапу кнопки затемняет планшет (чёрный оверлей + минимальная яркость),
 *    выход из затемнения — долгое нажатие по чёрному экрану.
 *
 * Требует разрешения SYSTEM_ALERT_WINDOW.
 */
public class ProjectionService extends Service {

    private static final String TAG = "ProjectionService";

    public static final String ACTION_PROJECT = "com.example.projectorcast.PROJECT";
    public static final String ACTION_STOP = "com.example.projectorcast.STOP";
    public static final String ACTION_TOGGLE_BLACKOUT = "com.example.projectorcast.TOGGLE_BLACKOUT";
    public static final String EXTRA_URL = "url";

    private static final String CHANNEL_ID = "projection";
    private static final int NOTIF_ID = 42;

    private WindowManager wm;
    private DisplayManager dm;

    private BrowserPresentation presentation;
    private View floatingButton;
    private View blackoutView;
    private boolean blackedOut = false;
    private String currentUrl;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        dm.registerDisplayListener(displayListener, new Handler(Looper.getMainLooper()));
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundNow();

        String action = intent != null ? intent.getAction() : null;
        if (ACTION_PROJECT.equals(action)) {
            currentUrl = intent.getStringExtra(EXTRA_URL);
            startProjection(currentUrl);
            if (presentation != null) showFloatingButton();
        } else if (ACTION_TOGGLE_BLACKOUT.equals(action)) {
            toggleBlackout();
        } else if (ACTION_STOP.equals(action)) {
            stopEverything();
        }
        return START_STICKY;
    }

    // ---- Проектор (второй экран) --------------------------------------------

    private int overlayType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private Display targetDisplay() {
        Display[] displays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        return displays.length > 0 ? displays[0] : null;
    }

    private void startProjection(String url) {
        Display display = targetDisplay();
        if (display == null) {
            toast(getString(R.string.no_projector_message));
            return;
        }
        if (presentation != null) {
            presentation.loadUrl(url); // уже показываем — просто меняем страницу
            return;
        }
        // Presentation с application-контекстом сам создаётся как окно типа
        // TYPE_PRESENTATION (2037) и живёт, пока живёт сервис. Свой тип окна ему
        // навязывать нельзя (на Android 12+ будет "Window type mismatch").
        BrowserPresentation p = new BrowserPresentation(getApplicationContext(), display);
        try {
            p.setOnDismissListener(d -> presentation = null);
            p.show();
            p.loadUrl(url);
            presentation = p;
            Log.i(TAG, "projection started on " + display.getName());
        } catch (Exception e) {
            Log.e(TAG, "presentation.show() failed", e);
            toast("Не удалось вывести на проектор: " + e.getMessage());
            presentation = null;
        }
    }

    // ---- Плавающая кнопка «ТВ» ----------------------------------------------

    private void showFloatingButton() {
        if (floatingButton != null) {
            floatingButton.setVisibility(View.VISIBLE);
            return;
        }
        floatingButton = LayoutInflater.from(this).inflate(R.layout.floating_button, null);

        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = 40;
        lp.y = 320;

        floatingButton.setOnTouchListener(new View.OnTouchListener() {
            private int initX, initY;
            private float downX, downY;
            private boolean moved;
            private long downTime;
            private final int slop = ViewConfiguration.get(ProjectionService.this).getScaledTouchSlop();
            private final long longPressMs = ViewConfiguration.getLongPressTimeout();
            private final Handler h = new Handler(Looper.getMainLooper());
            private final Runnable longPress = () -> {
                if (!moved) {
                    moved = true; // блокируем последующий тап
                    stopEverything();
                }
            };

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        initX = lp.x;
                        initY = lp.y;
                        downX = e.getRawX();
                        downY = e.getRawY();
                        downTime = e.getEventTime();
                        moved = false;
                        h.postDelayed(longPress, longPressMs);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - downX);
                        int dy = (int) (e.getRawY() - downY);
                        if (!moved && Math.hypot(dx, dy) > slop) {
                            moved = true;
                            h.removeCallbacks(longPress);
                        }
                        if (moved) {
                            lp.x = initX + dx;
                            lp.y = initY + dy;
                            wm.updateViewLayout(floatingButton, lp);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        h.removeCallbacks(longPress);
                        boolean wasTap = !moved && (e.getEventTime() - downTime) < longPressMs;
                        if (wasTap) {
                            enableBlackout(); // тап — затемнить планшет
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        h.removeCallbacks(longPress);
                        return true;
                }
                return false;
            }
        });

        try {
            wm.addView(floatingButton, lp);
        } catch (Exception e) {
            Log.e(TAG, "addView floatingButton failed", e);
            floatingButton = null;
        }
    }

    private void hideFloatingButton() {
        if (floatingButton != null) floatingButton.setVisibility(View.GONE);
    }

    private void removeFloatingButton() {
        if (floatingButton != null) {
            try {
                wm.removeView(floatingButton);
            } catch (Exception ignored) {
            }
            floatingButton = null;
        }
    }

    // ---- Затемнение планшета (поверх всех приложений) ------------------------

    private void toggleBlackout() {
        if (blackedOut) disableBlackout();
        else enableBlackout();
    }

    private void enableBlackout() {
        if (blackedOut) return;

        FrameLayout black = new FrameLayout(this);
        black.setBackgroundColor(Color.BLACK);

        TextView hint = new TextView(this);
        hint.setText(R.string.blackout_overlay_hint);
        hint.setTextColor(0xFF222222);
        hint.setTextSize(12f);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        hp.gravity = Gravity.CENTER;
        black.addView(hint, hp);

        black.setOnLongClickListener(v -> {
            disableBlackout();
            return true;
        });
        black.setOnClickListener(v ->
                toast(getString(R.string.blackout_overlay_hint)));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE);
        lp.screenBrightness = 0.02f; // почти в ноль (экран технически включён)

        try {
            wm.addView(black, lp);
            blackoutView = black;
            blackedOut = true;
            hideFloatingButton();
        } catch (Exception e) {
            Log.e(TAG, "addView blackout failed", e);
        }
    }

    private void disableBlackout() {
        if (!blackedOut) return;
        if (blackoutView != null) {
            try {
                wm.removeView(blackoutView);
            } catch (Exception ignored) {
            }
            blackoutView = null;
        }
        blackedOut = false;
        if (floatingButton != null) floatingButton.setVisibility(View.VISIBLE);
    }

    // ---- Остановка всего -----------------------------------------------------

    private void stopEverything() {
        disableBlackout();
        removeFloatingButton();
        if (presentation != null) {
            presentation.setOnDismissListener(null);
            presentation.destroyWeb();
            try {
                presentation.dismiss();
            } catch (Exception ignored) {
            }
            presentation = null;
        }
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        dm.unregisterDisplayListener(displayListener);
        disableBlackout();
        removeFloatingButton();
        if (presentation != null) {
            presentation.setOnDismissListener(null);
            presentation.destroyWeb();
            try {
                presentation.dismiss();
            } catch (Exception ignored) {
            }
            presentation = null;
        }
        super.onDestroy();
    }

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            if (presentation != null && targetDisplay() == null) {
                toast(getString(R.string.display_lost));
                stopEverything();
            }
        }

        @Override
        public void onDisplayChanged(int displayId) {
        }
    };

    // ---- Foreground / уведомление -------------------------------------------

    private void startForegroundNow() {
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed", e);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;

        PendingIntent stopPi = PendingIntent.getService(this, 1,
                new Intent(this, ProjectionService.class).setAction(ACTION_STOP), piFlags);
        PendingIntent blackPi = PendingIntent.getService(this, 2,
                new Intent(this, ProjectionService.class).setAction(ACTION_TOGGLE_BLACKOUT), piFlags);
        PendingIntent openPi = PendingIntent.getActivity(this, 3,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), piFlags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return b.setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(0, getString(R.string.blackout), blackPi)
                .addAction(0, getString(R.string.stop), stopPi)
                .build();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
