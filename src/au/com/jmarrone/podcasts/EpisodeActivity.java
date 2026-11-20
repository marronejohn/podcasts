package au.com.jmarrone.podcasts;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
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
    private ArrayList<Episode> eps;
    private EpAdapter adapter;
    private Handler h = new Handler();
    private String showTitle, showUrl, slug;
    private int playingPos = -1;

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
        setContentView(lv);
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
                if (active) opts.add("Stop playback");
                if (e.local != null) opts.add("Delete downloaded file");
                new AlertDialog.Builder(EpisodeActivity.this)
                    .setTitle(e.title)
                    .setItems(opts.toArray(new String[0]), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            String which = opts.get(w);
                            if ("Play".equals(which)) tap(pos);
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
        return true;
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
            String mark = "";
            if (e.local != null) {
                SharedPreferences sp = getSharedPreferences(PlaybackService.PREFS, MODE_PRIVATE);
                String npPath = sp.getString("np_path", "");
                String st = sp.getString("np_state", "stopped");
                String fpath = new File(Pod.audioDir(EpisodeActivity.this), e.local).getAbsolutePath();
                if (fpath.equals(npPath)) {
                    if ("playing".equals(st)) mark = "\u25B6 ";
                    else if ("paused".equals(st)) mark = "\u23F8 ";
                }
            }
            t1.setText(mark + e.title);
            String when = e.dateMs > 0 ? new SimpleDateFormat("EEE d MMM yyyy", Locale.ENGLISH).format(new Date(e.dateMs)) : e.date;
            t2.setText(when + (e.local != null ? "  |  saved - tap to play, tap again to pause" : "  |  not saved"));
            t2.setTextColor(e.local != null ? 0xff1b7a2b : 0xff777777);
            return cv;
        }
    }

    @Override
    protected void onResume() { super.onResume(); load(); }
}
