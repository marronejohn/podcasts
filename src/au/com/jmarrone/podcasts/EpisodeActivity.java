package au.com.jmarrone.podcasts;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.Settings;
import android.os.Bundle;
import android.os.Handler;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class EpisodeActivity extends Activity {
    public static final String SHOW_TITLE = "title";
    public static final String SHOW_URL = "url";
    public static final float[] SPEEDS = {0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
    private ArrayList<Episode> eps;
    private EpAdapter adapter;
    private Handler h = new Handler();
    private String showTitle, showUrl, slug;
    private int playingPos = -1;
    private View miniBar;
    private Runnable barTick;

    // keeps the "time remaining" line fresh while the screen is open
    private Runnable uiTick = new Runnable() {
        public void run() {
            SharedPreferences sp = getSharedPreferences(PlaybackService.PREFS, MODE_PRIVATE);
            String st = sp.getString("np_state", "stopped");
            if ("playing".equals(st) || "paused".equals(st)) adapter.notifyDataSetChanged();
            h.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        showTitle = getIntent().getStringExtra(SHOW_TITLE);
        showUrl = getIntent().getStringExtra(SHOW_URL);
        slug = Pod.slug(showTitle);
        setTitle(showTitle);
        adapter = new EpAdapter();
        ListView lv = new ListView(this);
        lv.setAdapter(adapter);
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.addView(lv, new android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        miniBar = Pod.miniBar(this);
        root.addView(miniBar, new android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) { tap(pos); }
        });
        lv.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> p, View v, final int pos, long id) {
                final Episode e = eps.get(pos);
                final SharedPreferences sp = getSharedPreferences(PlaybackService.PREFS, MODE_PRIVATE);
                final boolean active = !"stopped".equals(sp.getString("np_state", "stopped"));
                java.util.List<String> opts = new ArrayList<String>();
                opts.add("Play");
                if (active) opts.add("Skip forward 30 sec");
                if (active) opts.add("Skip back 30 sec");
                if (active) opts.add("Stop playback");
                if (e.local != null) opts.add("Delete downloaded file");
                new AlertDialog.Builder(EpisodeActivity.this)
                    .setTitle(e.title)
                    .setItems(opts.toArray(new String[0]), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            String which = opts.get(w);
                            if ("Play".equals(which)) tap(pos);
                            else if ("Skip forward 30 sec".equals(which)) sendSkip(30);
                            else if ("Skip back 30 sec".equals(which)) sendSkip(-30);
                            else if ("Stop playback".equals(which)) {
                                Intent i = new Intent(EpisodeActivity.this, PlaybackService.class);
                                i.setAction(PlaybackService.ACTION_STOP);
                                startService(i);
                                adapter.notifyDataSetChanged();
                            } else if ("Delete downloaded file".equals(which)) {
                                new File(Pod.audioDir(EpisodeActivity.this), e.local).delete();
                                e.local = null; adapter.notifyDataSetChanged();
                            }
                        }
                    }).show();
                return true;
            }
        });
        load();
    }

    private void sendSkip(int sec) {
        Intent i = new Intent(this, PlaybackService.class);
        i.setAction(PlaybackService.ACTION_SKIP);
        i.putExtra(PlaybackService.SKIP, sec);
        startService(i);
        adapter.notifyDataSetChanged();
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
        h.post(uiTick);
        if (barTick == null) barTick = new Runnable() {
            public void run() { if (miniBar != null) Pod.updateMiniBar(EpisodeActivity.this, miniBar); h.postDelayed(this, 2000); }
        };
        if (miniBar != null) Pod.updateMiniBar(this, miniBar);
        h.removeCallbacks(barTick); h.postDelayed(barTick, 2000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(uiTick);
        if (barTick != null) h.removeCallbacks(barTick);
    }

    private void load() {
        eps = Sync.readIndex(this, slug);
        adapter.notifyDataSetChanged();
    }

    private void tap(int pos) {
        final Episode e = eps.get(pos);
        if (e.local != null) {
            File f = new File(Pod.audioDir(this), e.local);
            if (f.exists() && f.length() > 1000) {
                String path = f.getAbsolutePath();
                SharedPreferences sp = getSharedPreferences(PlaybackService.PREFS, MODE_PRIVATE);
                String npPath = sp.getString("np_path", "");
                String state = sp.getString("np_state", "stopped");
                Intent i = new Intent(this, PlaybackService.class);
                if (path.equals(npPath) && "playing".equals(state)) {
                    i.setAction(PlaybackService.ACTION_PAUSE);
                } else if (path.equals(npPath) && "paused".equals(state)) {
                    i.setAction(PlaybackService.ACTION_RESUME);
                } else {
                    i.setAction(PlaybackService.ACTION_PLAY);
                    i.putExtra(PlaybackService.PATH, path);
                    i.putExtra(PlaybackService.TITLE, showTitle + " - " + e.title);
                }
                startService(i);
                playingPos = pos;
                adapter.notifyDataSetChanged();
                return;
            } else { e.local = null; adapter.notifyDataSetChanged(); }
        }
        if (e.url == null) return;
        Toast.makeText(this, "Downloading...", Toast.LENGTH_SHORT).show();
        final int p = pos;
        new Thread(new Runnable() {
            public void run() {
                boolean ok = false;
                try {
                    byte[] audio = Sync.httpGet(e.url, 120000);
                    if (audio != null && audio.length > 1000) {
                        String fn = slug + "_" + stamp(e.dateMs) + "_" + Pod.slug(e.title.length() > 42 ? e.title.substring(0, 42) : e.title) + ".mp3";
                        java.io.FileOutputStream fo = new java.io.FileOutputStream(new File(Pod.audioDir(EpisodeActivity.this), fn));
                        fo.write(audio); fo.close();
                        e.local = fn; ok = true;
                    }
                } catch (Throwable t) { }
                final boolean fok = ok;
                h.post(new Runnable() {
                    public void run() {
                        if (!fok) Toast.makeText(EpisodeActivity.this, "Download failed - check connection", Toast.LENGTH_SHORT).show();
                        adapter.notifyDataSetChanged();
                    }
                });
            }
        }).start();
    }

    private static String stamp(long ms) {
        if (ms <= 0) return String.valueOf(System.currentTimeMillis() / 1000);
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(ms));
    }

    private void downloadAll() {
        int queued = 0;
        for (final Episode e : eps) {
            if (e.local != null) continue;
            if (queued >= 10) break;
            queued++;
            new Thread(new Runnable() {
                public void run() {
                    try {
                        byte[] audio = Sync.httpGet(e.url, 120000);
                        if (audio != null && audio.length > 1000) {
                            String fn = slug + "_" + stamp(e.dateMs) + "_" + Pod.slug(e.title.length() > 42 ? e.title.substring(0, 42) : e.title) + ".mp3";
                            java.io.FileOutputStream fo = new java.io.FileOutputStream(new File(Pod.audioDir(EpisodeActivity.this), fn));
                            fo.write(audio); fo.close();
                            e.local = fn;
                            h.post(new Runnable() { public void run() { adapter.notifyDataSetChanged(); } });
                        }
                    } catch (Throwable t) { }
                }
            }).start();
        }
        Toast.makeText(this, queued == 0 ? "All caught up" : "Downloading " + queued + " episodes", Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Download all not-yet-saved");
        menu.add(0, 2, 0, "Refresh from feed now");
        float sp = getSharedPreferences(PlaybackService.PREFS, MODE_PRIVATE).getFloat("speed", 1.0f);
        menu.add(0, 3, 0, "Playback speed: " + speedLabel(sp) + "x");
        menu.add(0, 4, 0, "Sleep timer: " + sleepLabel());
        menu.add(0, 5, 0, "Floating mini-player: " + (Pod.prefs(this).getBoolean("miniPlayer", false) ? "ON" : "OFF"));
        return true;
    }

    private static String speedLabel(float sp) {
        if (sp == (long) sp) return String.valueOf((long) sp) + ".0";
        return String.valueOf(sp);
    }

    private String sleepLabel() {
        int m = Pod.prefs(this).getInt("sleepMinutes", 0);
        return m <= 0 ? "off" : (m + " min");
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) { downloadAll(); return true; }
        if (item.getItemId() == 2) {
            Toast.makeText(this, "Refreshing...", Toast.LENGTH_SHORT).show();
            new Thread(new Runnable() {
                public void run() {
                    try { Sync.syncShow(EpisodeActivity.this, showTitle, showUrl); } catch (Throwable t) { }
                    h.post(new Runnable() { public void run() { load(); } });
                }
            }).start();
            return true;
        }
        if (item.getItemId() == 3) {
            // cycle 0.75 -> 1.0 -> 1.25 -> 1.5 -> 2.0 -> 0.75
            SharedPreferences sp = getSharedPreferences(PlaybackService.PREFS, MODE_PRIVATE);
            float cur = sp.getFloat("speed", 1.0f);
            int idx = 1;
            for (int i = 0; i < SPEEDS.length; i++) if (SPEEDS[i] == cur) { idx = (i + 1) % SPEEDS.length; break; }
            float next = SPEEDS[idx];
            sp.edit().putFloat("speed", next).commit();
            Intent i = new Intent(this, PlaybackService.class);
            i.setAction(PlaybackService.ACTION_SPEED);
            i.putExtra(PlaybackService.SPEEDV, next);
            startService(i);
            invalidateOptionsMenu();
            Toast.makeText(this, "Speed " + speedLabel(next) + "x", Toast.LENGTH_SHORT).show();
            return true;
        }
        if (item.getItemId() == 4) {
            // cycle off -> 30 -> 60 -> 90 -> off
            int m = Pod.prefs(this).getInt("sleepMinutes", 0);
            int next = m <= 0 ? 30 : (m == 30 ? 60 : (m == 60 ? 90 : 0));
            Pod.prefs(this).edit().putInt("sleepMinutes", next).commit();
            Intent i = new Intent(this, PlaybackService.class);
            i.setAction(PlaybackService.ACTION_SLEEP);
            i.putExtra(PlaybackService.SLEEP, next);
            startService(i);
            invalidateOptionsMenu();
            Toast.makeText(this, next <= 0 ? "Sleep timer off" : "Sleep timer " + next + " min", Toast.LENGTH_SHORT).show();
            return true;
        }
        if (item.getItemId() == 5) {
            boolean now = !Pod.prefs(this).getBoolean("miniPlayer", false);
            if (now && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "One-time setup: on the next screen allow 'Display over other apps' for Podcasts, then come back and switch the mini-player on.", Toast.LENGTH_LONG).show();
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                } catch (Throwable t) {
                    Toast.makeText(this, "Couldn't open the settings screen", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
            Pod.prefs(this).edit().putBoolean("miniPlayer", now).commit();
            Intent i = new Intent(this, PlaybackService.class);
            i.setAction(PlaybackService.ACTION_MINI);
            startService(i);
            invalidateOptionsMenu();
            Toast.makeText(this, now ? "Mini-player on while something is playing" : "Mini-player off", Toast.LENGTH_SHORT).show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private class EpAdapter extends BaseAdapter {
        public int getCount() { return eps == null ? 0 : eps.size(); }
        public Object getItem(int p) { return eps.get(p); }
        public long getItemId(int p) { return p; }
        public View getView(int p, View cv, ViewGroup parent) {
            if (cv == null) {
                android.widget.LinearLayout row = new android.widget.LinearLayout(EpisodeActivity.this);
                row.setOrientation(android.widget.LinearLayout.VERTICAL);
                int pad = (int) (getResources().getDisplayMetrics().density * 14);
                row.setPadding(pad, pad, pad, pad);
                TextView t1 = new TextView(EpisodeActivity.this);
                t1.setId(1); t1.setTextSize(15); t1.setTextColor(0xff101010);
                TextView t2 = new TextView(EpisodeActivity.this);
                t2.setId(2); t2.setTextSize(12);
                row.addView(t1); row.addView(t2);
                cv = row;
            }
            Episode e = eps.get(p);
            TextView t1 = (TextView) cv.findViewById(1);
            TextView t2 = (TextView) cv.findViewById(2);
            SharedPreferences sp = getSharedPreferences(PlaybackService.PREFS, MODE_PRIVATE);
            String mark = "";
            String live = "";
            String played = "";
            if (e.local != null) {
                String fpath = new File(Pod.audioDir(EpisodeActivity.this), e.local).getAbsolutePath();
                String npPath = sp.getString("np_path", "");
                String st = sp.getString("np_state", "stopped");
                if (fpath.equals(npPath)) {
                    if ("playing".equals(st)) mark = "\u25B6 ";
                    else if ("paused".equals(st)) mark = "\u23F8 ";
                    if (!"stopped".equals(st)) {
                        long dur = sp.getLong("dur:" + npPath, 0);
                        long pos = sp.getLong("pos:" + npPath, 0);
                        String rem = PlaybackService.remainingFrom(dur, pos);
                        if (rem.length() > 0) live = "  |  " + rem + " left";
                    }
                }
                if (sp.getBoolean("done:" + fpath, false)) played = "  |  played";
            }
            t1.setText(mark + e.title);
            String when = e.dateMs > 0 ? new SimpleDateFormat("EEE d MMM yyyy", Locale.ENGLISH).format(new Date(e.dateMs)) : e.date;
            String dur = e.seconds > 0 ? "  |  " + (e.est ? "~" : "") + ((e.seconds + 30) / 60) + " min" : "";
            String tail = e.local == null ? "  |  not saved"
                : (played.length() > 0 ? played : (mark.length() == 0 ? "  |  saved - tap to play, tap again to pause" : ""));
            t2.setText(when + dur + live + tail);
            t2.setTextColor(e.local != null ? (played.length() > 0 ? 0xff4a6fb0 : 0xff1b7a2b) : 0xff777777);
            return cv;
        }
    }
}
