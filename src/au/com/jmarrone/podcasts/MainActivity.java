package au.com.jmarrone.podcasts;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.ArrayList;

public class MainActivity extends Activity {
    private ListView list;
    private ShowAdapter adapter;
    private ArrayList<String[]> shows;
    private boolean syncing = false;
    private View miniBar;
    private android.os.Handler bh = new android.os.Handler();
    private Runnable barTick;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Sync.reschedule(this);
        if (Build.VERSION.SDK_INT >= 33) {
            try { requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1); } catch (Throwable t) { }
        }
        list = new ListView(this);
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.addView(list, new android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        miniBar = Pod.miniBar(this);
        root.addView(miniBar, new android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        adapter = new ShowAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                String[] s = shows.get(pos);
                Intent i = new Intent(MainActivity.this, EpisodeActivity.class);
                i.putExtra("title", s[0]);
                i.putExtra("url", s[2]);
                startActivity(i);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> p, View v, final int pos, long id) {
                final String[] s = shows.get(pos);
                ArrayList<String> opts = new ArrayList<String>();
                opts.add("Sync this show now");
                if (Pod.isCustom(MainActivity.this, s[2])) opts.add("Remove this show");
                final String[] arr = opts.toArray(new String[0]);
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle(s[0])
                    .setItems(arr, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int which) {
                            if (arr[which].startsWith("Remove")) {
                                Pod.removeCustomShow(MainActivity.this, s[2]);
                                refresh();
                            } else {
                                Thread t = new Thread(new Runnable() {
                                    public void run() {
                                        try {
                                            final String st = Sync.syncShow(MainActivity.this, s[0], s[2]);
                                            runOnUiThread(new Runnable() { public void run() { Toast.makeText(MainActivity.this, s[0] + ": " + st, Toast.LENGTH_LONG).show(); refresh(); } });
                                        } catch (Throwable t) {
                                            runOnUiThread(new Runnable() { public void run() { Toast.makeText(MainActivity.this, s[0] + ": failed", Toast.LENGTH_SHORT).show(); } });
                                        }
                                    }
                                });
                                t.start();
                            }
                        }
                    }).show();
                return true;
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        // one-time re-sync after the duration-parser fix so episode lengths fill in
        if (Pod.prefs(this).getString("lastSync", "").length() > 0 && !Pod.prefs(this).getBoolean("durMig", false)) {
            Pod.prefs(this).edit().putBoolean("durMig", true).commit();
            startSync(true);
        }
        // first-ever open: schedule weekly alarm + try a first sync when on wifi
        if (Pod.prefs(this).getString("lastSync", "").length() == 0 && !syncing) startSync(true);
        if (barTick == null) barTick = new Runnable() {
            public void run() { if (miniBar != null) Pod.updateMiniBar(MainActivity.this, miniBar); bh.postDelayed(this, 2000); }
        };
        if (miniBar != null) Pod.updateMiniBar(this, miniBar);
        bh.removeCallbacks(barTick); bh.postDelayed(barTick, 2000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        bh.removeCallbacks(barTick);
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) {
            try { startService(new Intent(this, PlaybackService.class).setAction(PlaybackService.ACTION_STOP)); } catch (Throwable t) { }
        }
        super.onDestroy();
    }

    private void refresh() {
        shows = Pod.allShows(this);
        adapter.notifyDataSetChanged();
    }

    private void startSync(final boolean silentIfNoWifi) {
        if (syncing) return;
        if (!Pod.networkOk(this)) {
            if (!silentIfNoWifi) Toast.makeText(this, "Waiting for WiFi - downloads only run on WiFi", Toast.LENGTH_LONG).show();
            return;
        }
        syncing = true;
        Toast.makeText(this, "Checking all shows for new episodes...", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            public void run() {
                final ArrayList<Sync.ShowResult> res = Sync.run(MainActivity.this);
                runOnUiThread(new Runnable() {
                    public void run() {
                        syncing = false;
                        refresh();
                        StringBuilder sb = new StringBuilder();
                        for (Sync.ShowResult r : res) sb.append(r.title).append(": ").append(r.status).append('\n');
                        Toast.makeText(MainActivity.this, sb.toString(), Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Sync all shows now");
        menu.add(0, 2, 0, "Add a show by RSS link");
        menu.add(0, 3, 0, "Allow downloads on mobile data: " + (Pod.mobileData(this) ? "ON" : "OFF"));
        menu.add(0, 4, 0, "Stop playback");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) { startSync(false); return true; }
        if (item.getItemId() == 2) {
            final EditText et = new EditText(this);
            et.setHint("Paste the podcast RSS link");
            et.setSingleLine(false);
            new AlertDialog.Builder(this)
                .setTitle("Add a show")
                .setMessage("Paste the RSS feed URL of the podcast. The app will download its new episodes on the weekly WiFi sync.")
                .setView(et)
                .setPositiveButton("Add", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String url = et.getText().toString().trim();
                        if (url.startsWith("http")) {
                            Pod.addCustomShow(MainActivity.this, url, "Your shows", url);
                            refresh();
                        }
                    }
                })
                .setNegativeButton("Cancel", null).show();
            return true;
        }
        if (item.getItemId() == 3) {
            boolean now = !Pod.mobileData(MainActivity.this);
            Pod.setMobileData(MainActivity.this, now);
            invalidateOptionsMenu();
            Toast.makeText(MainActivity.this, now ? "Downloads allowed on mobile data" : "Downloads on WiFi only", Toast.LENGTH_LONG).show();
            return true;
        }
        if (item.getItemId() == 4) {
            startService(new Intent(MainActivity.this, PlaybackService.class).setAction(PlaybackService.ACTION_STOP));
            Toast.makeText(MainActivity.this, "Playback stopped", Toast.LENGTH_SHORT).show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private class ShowAdapter extends BaseAdapter {
        public int getCount() { return shows == null ? 0 : shows.size(); }
        public Object getItem(int p) { return shows.get(p); }
        public long getItemId(int p) { return p; }
        public View getView(int p, View cv, ViewGroup parent) {
            if (cv == null) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                int pad = (int) (getResources().getDisplayMetrics().density * 14);
                row.setPadding(pad, pad, pad, pad);
                TextView t1 = new TextView(MainActivity.this);
                t1.setId(1); t1.setTextSize(17); t1.setTextColor(0xff101010);
                TextView t2 = new TextView(MainActivity.this);
                t2.setId(2); t2.setTextSize(13); t2.setTextColor(0xff555555);
                row.addView(t1); row.addView(t2);
                cv = row;
            }
            String[] s = shows.get(p);
            TextView t1 = (TextView) cv.findViewById(1);
            TextView t2 = (TextView) cv.findViewById(2);
            t1.setText(s[0]);
            String last = Pod.prefs(MainActivity.this).getString("lastSync", "");
            int n = 0;
            java.util.ArrayList<Integer> durs = new java.util.ArrayList<Integer>();
            for (Episode e : Sync.readIndex(MainActivity.this, Pod.slug(s[0]))) {
                if (e.local != null) n++;
                if (e.seconds > 0) durs.add(e.seconds);
            }
            String dur = "";
            if (durs.size() > 0) {
                java.util.Collections.sort(durs);
                int med = durs.get(durs.size() / 2);
                dur = "  |  ~" + durLabel(med) + " episodes";
            }
            t2.setText(s[1] + dur + "  |  " + n + " downloaded" + (last.length() > 0 ? "  |  last sync: " + last : ""));
            return cv;
        }
    }

    private static String durLabel(int secs) {
        if (secs >= 5400) {
            long h = secs / 3600, m = (secs % 3600) / 60;
            return (m == 0 ? h + " hr" : h + "h " + m + "m");
        }
        return ((secs + 30) / 60) + " min";
    }

    // tiny static import holder so the file has no extra dependency
    public static class LinearLayout extends android.widget.LinearLayout {
        public LinearLayout(Context c) { super(c); }
    }
}
