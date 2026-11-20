package au.com.jmarrone.podcasts;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;

public class PlaybackService extends Service {
    public static final String ACTION_PLAY = "au.com.jmarrone.podcasts.PLAY";
    public static final String ACTION_PAUSE = "au.com.jmarrone.podcasts.PAUSE";
    public static final String ACTION_RESUME = "au.com.jmarrone.podcasts.RESUME";
    public static final String ACTION_STOP = "au.com.jmarrone.podcasts.STOP";
    public static final String PATH = "path";
    public static final String TITLE = "title";
    public static final String PREFS = "playback";
    private MediaPlayer mp;
    private PowerManager.WakeLock wl;
    private String curPath;
    private boolean fg = false;
    private Handler h = new Handler();
    private Runnable saver = new Runnable() {
        public void run() { savePos(); h.postDelayed(this, 5000); }
    };

    @Override
    public IBinder onBind(Intent i) { return null; }

    private void goFg(String title) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel("pb", "Playback", NotificationManager.IMPORTANCE_LOW));
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "pb") : new Notification.Builder(this);
            boolean playing = false;
            try { playing = mp != null && mp.isPlaying(); } catch (Throwable t) { }
            Intent open = new Intent(this, MainActivity.class);
            PendingIntent piOpen = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Intent pa = new Intent(this, PlaybackService.class).setAction(playing ? ACTION_PAUSE : ACTION_RESUME);
            PendingIntent piPa = PendingIntent.getService(this, 2, pa, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Intent st = new Intent(this, PlaybackService.class).setAction(ACTION_STOP);
            PendingIntent piSt = PendingIntent.getService(this, 3, st, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title == null || title.length() == 0 ? "Podcasts" : title)
                .setContentText(playing ? "Playing - tap to open" : "Paused - tap to open")
                .setOngoing(true)
                .setContentIntent(piOpen)
                .addAction(0, playing ? "Pause" : "Play", piPa)
                .addAction(0, "Stop", piSt)
                .build();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(1, n);
            }
            fg = true;
        } catch (Throwable t) { }
    }

    private void endFg() {
        try { stopForeground(true); } catch (Throwable t) { }
        fg = false;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stop(); stopSelf(); return START_NOT_STICKY; }
        String a = intent.getAction();
        if (ACTION_STOP.equals(a)) { stop(); stopSelf(); return START_NOT_STICKY; }
        if (ACTION_PAUSE.equals(a)) {
            try { if (mp != null && mp.isPlaying()) { mp.pause(); savePos(); setState("paused"); h.removeCallbacks(saver); goFg(prefs().getString("np_title", "")); } } catch (Throwable t) { }
            return START_STICKY;
        }
        if (ACTION_RESUME.equals(a)) {
            try {
                if (mp != null && !mp.isPlaying()) {
                    mp.start(); setState("playing");
                    h.removeCallbacks(saver); h.postDelayed(saver, 5000);
                    goFg(prefs().getString("np_title", ""));
                }
            } catch (Throwable t) { }
            return START_STICKY;
        }
        if (ACTION_PLAY.equals(a)) {
            String path = intent.getStringExtra(PATH);
            String title = intent.getStringExtra(TITLE);
            if (path == null) { stopSelf(); return START_NOT_STICKY; }
            stop();
            try {
                mp = new MediaPlayer();
                mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
                mp.setDataSource(path);
                mp.prepare();
                SharedPreferences p = prefs();
                long saved = p.getLong("pos:" + path, 0);
                if (saved > 1000 && saved < mp.getDuration() - 2000) mp.seekTo((int) saved);
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JMarronePods");
                wl.acquire();
                curPath = path;
                p.edit().putString("np_path", path).putString("np_title", title == null ? "" : title).putString("np_state", "playing").commit();
                mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                    public void onCompletion(MediaPlayer m) {
                        try { prefs().edit().putLong("pos:" + curPath, 0).putString("np_state", "stopped").commit(); } catch (Throwable t) { }
                        h.removeCallbacks(saver);
                        try { if (mp != null) { mp.release(); } } catch (Throwable t) { }
                        mp = null;
                        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Throwable t) { }
                        wl = null;
                        endFg();
                        stopSelf();
                    }
                });
                mp.start();
                goFg(title);
                h.removeCallbacks(saver);
                h.postDelayed(saver, 5000);
            } catch (Throwable t) {
                stop(); stopSelf();
            }
            return START_STICKY;
        }
        return START_NOT_STICKY;
    }

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    private void savePos() {
        try { if (mp != null && curPath != null) prefs().edit().putLong("pos:" + curPath, mp.getCurrentPosition()).commit(); } catch (Throwable t) { }
    }

    private void setState(String s) {
        try { prefs().edit().putString("np_state", s).commit(); } catch (Throwable t) { }
    }

    private void stop() {
        savePos();
        h.removeCallbacks(saver);
        try { if (mp != null) { if (mp.isPlaying()) mp.stop(); mp.release(); } } catch (Throwable t) { }
        mp = null;
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Throwable t) { }
        wl = null;
        endFg();
        try { prefs().edit().putString("np_state", "stopped").commit(); } catch (Throwable t) { }
    }

    @Override
    public void onDestroy() { stop(); super.onDestroy(); }
}
