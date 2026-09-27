package cu.codigos.ussd;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

import java.util.Locale;

/**
 * Burbuja flotante con la cuenta regresiva del tiempo restante del paquete
 * (WiFi ETECSA / datos móviles). Se puede arrastrar por la pantalla y al
 * tocarla abre la app. Mientras corre, muestra una notificación persistente.
 */
public class FloatService extends Service {

    public static final String EXTRA_END = "end_millis";
    public static final String EXTRA_LABEL = "label";
    private static final String CH_ID = "float_timer";

    private static boolean running = false;
    private WindowManager wm;
    private View bubble;
    private TextView tv;
    private CountDownTimer cdt;
    private long endMillis;
    private String label;

    public static boolean isRunning() { return running; }

    /** Guarda el fin en Store y arranca la burbuja. */
    public static void startWith(Context c, long endMillis, String label) {
        Store s = new Store(c);
        s.setFloatEnd(endMillis);
        s.setFloatLabel(label);
        Intent it = new Intent(c, FloatService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(it);
        else c.startService(it);
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, FloatService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Store s = new Store(this);
        endMillis = s.getFloatEnd();
        label = s.getFloatLabel();
        if (endMillis <= 0) endMillis = System.currentTimeMillis() + 3600_000L;
        createChannel();
        startForeground(4201, buildNotif());
        showBubble();
        running = true;
    }

    @Override
    public int onStartCommand(Intent it, int flags, int startId) {
        if ("stop".equals(it != null ? it.getAction() : null)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (it != null && it.hasExtra(EXTRA_END)) {
            endMillis = it.getLongExtra(EXTRA_END, endMillis);
            label = it.getStringExtra(EXTRA_LABEL) != null ? it.getStringExtra(EXTRA_LABEL) : label;
            Store s = new Store(this);
            s.setFloatEnd(endMillis);
            s.setFloatLabel(label);
            restartTimer();
            updateText();
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onDestroy() {
        running = false;
        if (cdt != null) cdt.cancel();
        if (wm != null && bubble != null) wm.removeView(bubble);
        super.onDestroy();
    }

    // ---------- burbuja ----------
    @SuppressLint("ClickableViewAccessibility")
    private void showBubble() {
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        tv = new TextView(this);
        tv.setTextSize(12f);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(0xFFFFFFFF);
        tv.setBackgroundResource(R.drawable.bg_bubble);
        int pad = dp(10);
        tv.setPadding(pad, pad, pad, pad);
        updateText();

        bubble = tv;
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(20);
        lp.y = dp(160);

        tv.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy; int sx, sy; boolean moved;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = e.getRawX(); dy = e.getRawY();
                        sx = lp.x; sy = lp.y; moved = false; return true;
                    case MotionEvent.ACTION_MOVE:
                        float mx = e.getRawX() - dx, my = e.getRawY() - dy;
                        if (Math.abs(mx) > 12 || Math.abs(my) > 12) moved = true;
                        if (moved) { lp.x = sx + (int) mx; lp.y = sy + (int) my; wm.updateViewLayout(bubble, lp); }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            Intent i = new Intent(FloatService.this, MainActivity.class);
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(i);
                        }
                        return true;
                }
                return false;
            }
        });

        try {
            wm.addView(bubble, lp);
        } catch (Exception e) {
            stopSelf();
        }
    }

    private void updateText() {
        long left = Math.max(0, endMillis - System.currentTimeMillis());
        long h = left / 3_600_000L;
        long m = (left % 3_600_000L) / 60_000L;
        long sec = (left % 60_000L) / 1000L;
        String t = String.format(Locale.US, "%02d:%02d:%02d", h, m, sec);
        tv.setText((label == null || label.isEmpty() ? "RESTANTE" : label.toUpperCase(Locale.US)) + "\n" + t);
        tv.setBackgroundResource(left <= 0 ? R.drawable.bg_bubble_end : R.drawable.bg_bubble);
    }

    private void restartTimer() {
        if (cdt != null) cdt.cancel();
        long total = Math.max(1000, endMillis - System.currentTimeMillis());
        cdt = new CountDownTimer(total + 1000, 1000) {
            @Override public void onTick(long ms) {
                updateText();
                notifyMgr().notify(4201, buildNotif());
            }
            @Override public void onFinish() {
                updateText();
                notifyMgr().notify(4201, buildNotif());
            }
        };
        cdt.start();
    }

    // ---------- notificación ----------
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CH_ID, "Contador flotante",
                    NotificationManager.IMPORTANCE_LOW);
            notifyMgr().createNotificationChannel(ch);
        }
    }

    private NotificationManager notifyMgr() {
        return (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private Notification buildNotif() {
        long left = Math.max(0, endMillis - System.currentTimeMillis());
        long h = left / 3_600_000L, m = (left % 3_600_000L) / 60_000L;
        String text = left <= 0 ? "Tiempo agotado" : String.format(Locale.US, "Restan %dh %02dm", h, m);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                        : PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, FloatService.class).setAction("stop");
        PendingIntent ps = PendingIntent.getService(this, 2, stop,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                        : PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CH_ID)
                .setSmallIcon(R.drawable.ic_timer)
                .setContentTitle((label == null || label.isEmpty() ? "Tiempo restante" : label))
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi)
                .addAction(0, "Detener", ps)
                .build();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
