package au.com.jmarrone.podcasts;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
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
    public static final String ACTION_SKIP = "au.com.jmarrone.podcasts.SKIP";
    public static final String ACTION_SPEED = "au.com.jmarrone.podcasts.SPEED";
    public static final String ACTION_SLEEP = "au.com.jmarrone.podcasts.SLEEP";
    public static final String PATH = "path";
    public static final String TITLE = "title";
    public static final String SKIP = "skip";
    public static final String SPEEDV = "speed";
    public static final String SLEEP = "sleep";
    public static final String PREFS = "playback";
    private MediaPlayer mp;
    private PowerManager.WakeLock wl;
    private String curPath;
    private boolean fg = false;
    private boolean transientLoss = false;   // lost focus to a short sound -> auto-resume when it's free
    private AudioManager am;
    private AudioFocusRequest afr;
    private Handler h = new Handler();
    private Runnable saver = new Runnable() {
        public void run() { savePos(); h.postDelayed(this, 5000); }
    };
    // keeps the "time remaining" on the notification current while playing
    private Runnable tick = new Runnable() {
        public void run() {
            try {
                if (mp != null && mp.isPlaying()) {
                    goFg(prefs().getString("np_title", ""));
                    h.postDelayed(this, 30000);
                }
            } catch (Throwable t) { }
        }
    };
    private Runnable sleeper = new Runnable() {
        public void run() { pauseNow(); }
    };

    private BroadcastReceiver noisy = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction())) pauseNow();
        }
    };

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        try {
            registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        } catch (Throwable t) { }
    }

    @Override
    public void onDestroy() {
        try { unregisterReceiver(noisy); } catch (Throwable t) { }
        stop();
        super.onDestroy();
    }

    private AudioManager.OnAudioFocusChangeListener focusL = new AudioManager.OnAudioFocusChangeListener() {
        public void onAudioFocusChange(int what) {
            if (what == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    || what == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                // another app (watch alert, navigation, ringtone...) needs the audio: pause and wait
                if (mp != null && isPlayingSafe()) {
                    transientLoss = true;
                    pauseNow();
                }
            } else if (what == AudioManager.AUDIOFOCUS_LOSS) {
                transientLoss = false;
                pauseNow();
            } else if (what == AudioManager.AUDIOFOCUS_GAIN) {
                if (transientLoss && mp != null && !isPlayingSafe()) {
                    transientLoss = false;
                    try {
                        mp.start(); setState("playing");
                        h.removeCallbacks(saver); h.postDelayed(saver, 5000);
                        h.removeCallbacks(tick); h.postDelayed(tick, 1000);
                        goFg(prefs().getString("np_title", ""));
                    } catch (Throwable t) { }
                }
            }
        }
    };

    private boolean isPlayingSafe() { try { return mp != null && mp.isPlaying(); } catch (Throwable t) { return false; } }

    private void requestFocus() {
        try {
            if (am == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                if (afr == null) {
                    afr = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                        .setOnAudioFocusChangeListener(focusL)
                        .build();
                }
                am.requestAudioFocus(afr);
            } else {
                am.requestAudioFocus(focusL, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            }
        } catch (Throwable t) { }
    }

    private void abandonFocus() {
        try {
            if (am == null) return;
            if (Build.VERSION.SDK_INT >= 26 && afr != null) am.abandonAudioFocusRequest(afr);
            else am.abandonAudioFocus(focusL);
        } catch (Throwable t) { }
    }

    // pause, save position, no auto-resume flag cleared (manual pause)
    private void pauseNow() {
        try {
            if (mp != null && mp.isPlaying()) {
                mp.pause(); savePos(); setState("paused");
                h.removeCallbacks(saver);
                goFg(prefs().getString("np_title", ""));
            }
        } catch (Throwable t) { }
    }

    private void applySpeed() {
        try {
            if (mp != null && Build.VERSION.SDK_INT >= 23) {
                float sp = prefs().getFloat("speed", 1.0f);
                if (sp != 1.0f) mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(sp));
            }
        } catch (Throwable t) { }
    }

    private void goFg(String title) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel("pb", "Playback", NotificationManager.IMPORTANCE_LOW));
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "pb") : new Notification.Builder(this);
            boolean playing = isPlayingSafe();
            String npTitle = prefs().getString("np_title", "");
            String t = (title == null || title.length() == 0) ? npTitle : title;
            String rem = remainingText();
            String speed = prefs().getFloat("speed", 1.0f) != 1.0f
                ? ("  " + prefs().getFloat("speed", 1.0f) + "x") : "";
            Intent open = new Intent(this, MainActivity.class);
            PendingIntent piOpen = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Intent pa = new Intent(this, PlaybackService.class).setAction(playing ? ACTION_PAUSE : ACTION_RESUME);
            PendingIntent piPa = PendingIntent.getService(this, 2, pa, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Intent sk = new Intent(this, PlaybackService.class).setAction(ACTION_SKIP).putExtra(SKIP, 30);
            PendingIntent piSk = PendingIntent.getService(this, 4, sk, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Intent st = new Intent(this, PlaybackService.class).setAction(ACTION_STOP);
            PendingIntent piSt = PendingIntent.getService(this, 3, st, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            String text = (playing ? "Playing" : "Paused") + (rem.length() > 0 ? " - " + rem + " left" : "") + speed;
            Notification n = b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(t == null || t.length() == 0 ? "Podcasts" : t)
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(piOpen)
                .addAction(0, playing ? "Pause" : "Play", piPa)
                .addAction(0, "+30 sec", piSk)
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

    // remaining playback time, from the live player or the saved prefs when paused
    public static String remainingFrom(long durMs, long posMs) {
        if (durMs <= 0) return "";
        long rem = durMs - posMs;
        if (rem < 0) rem = 0;
        long s = rem / 1000;
        if (s >= 3600) return String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
        return String.format("%d:%02d", s / 60, s % 60);
    }

    private String remainingText() {
        try {
            long dur = 0, pos = 0;
            if (mp != null) { dur = mp.getDuration(); pos = mp.getCurrentPosition(); }
            SharedPreferences p = prefs();
            String np = curPath != null ? curPath : p.getString("np_path", "");
            if (dur <= 0 && np != null && np.length() > 0) {
                dur = p.getLong("dur:" + np, 0);
                pos = p.getLong("pos:" + np, 0);
            }
            return remainingFrom(dur, pos);
        } catch (Throwable t) { return ""; }
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
                    requestFocus();
                    mp.start(); applySpeed(); setState("playing");
                    h.removeCallbacks(saver); h.postDelayed(saver, 5000);
                    h.removeCallbacks(tick); h.postDelayed(tick, 1000);
                    goFg(prefs().getString("np_title", ""));
                }
            } catch (Throwable t) { }
            return START_STICKY;
        }
        if (ACTION_SKIP.equals(a)) {
            try {
                if (mp != null) {
                    int sec = intent.getIntExtra(SKIP, 30);
                    int dur = mp.getDuration();
                    int target = mp.getCurrentPosition() + sec * 1000;
                    if (target < 0) target = 0;
                    if (dur > 0 && target > dur - 500) target = dur - 500;
                    mp.seekTo(target);
                    savePos();
                    goFg(prefs().getString("np_title", ""));
                }
            } catch (Throwable t) { }
            return START_STICKY;
        }
        if (ACTION_SPEED.equals(a)) {
            try {
                float sp = intent.getFloatExtra(SPEEDV, 1.0f);
                prefs().edit().putFloat("speed", sp).commit();
                if (mp != null && Build.VERSION.SDK_INT >= 23) {
                    mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(sp));
                }
                goFg(prefs().getString("np_title", ""));
            } catch (Throwable t) { }
            return START_STICKY;
        }
        if (ACTION_SLEEP.equals(a)) {
            h.removeCallbacks(sleeper);
            int mins = intent.getIntExtra(SLEEP, 0);
            if (mins > 0 && isPlayingSafe()) {
                h.postDelayed(sleeper, mins * 60L * 1000L);
            }
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
                p.edit().putLong("dur:" + path, mp.getDuration()).commit();
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JMarronePods");
                wl.acquire();
                curPath = path;
                p.edit().putString("np_path", path).putString("np_title", title == null ? "" : title).putString("np_state", "playing").commit();
                mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                    public void onCompletion(MediaPlayer m) {
                        try { prefs().edit().putLong("pos:" + curPath, 0).putString("np_state", "stopped").commit(); } catch (Throwable t) { }
                        h.removeCallbacks(saver); h.removeCallbacks(tick); h.removeCallbacks(sleeper);
                        abandonFocus();
                        try { if (mp != null) { mp.release(); } } catch (Throwable t) { }
                        mp = null;
                        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Throwable t) { }
                        wl = null;
                        endFg();
                        stopSelf();
                    }
                });
                requestFocus();
                mp.start();
                applySpeed();
                goFg(title);
                h.removeCallbacks(saver); h.postDelayed(saver, 5000);
                h.removeCallbacks(tick); h.postDelayed(tick, 1000);
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
        h.removeCallbacks(saver); h.removeCallbacks(tick); h.removeCallbacks(sleeper);
        abandonFocus();
        try { if (mp != null) { if (mp.isPlaying()) mp.stop(); mp.release(); } } catch (Throwable t) { }
        mp = null;
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Throwable t) { }
        wl = null;
        endFg();
        try { prefs().edit().putString("np_state", "stopped").commit(); } catch (Throwable t) { }
    }
}
