package au.com.jmarrone.podcasts;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

public class Sync {

    public static class ShowResult {
        public String title;
        public String status;   // "3 new", "up to date", "skipped (no wifi)"
    }

    // Runs the full pass. Returns per-show results. Call on a background thread only.
    public static ArrayList<ShowResult> run(android.content.Context c) {
        ArrayList<ShowResult> out = new ArrayList<ShowResult>();
        ArrayList<String[]> shows = Pod.allShows(c);
        for (String[] s : shows) {
            ShowResult r = new ShowResult();
            r.title = s[0];
            try {
                r.status = syncShow(c, s[0], s[2]);
            } catch (Throwable t) {
                r.status = "failed: " + String.valueOf(t.getMessage());
                if (r.status.length() > 60) r.status = "failed (network)";
            }
            out.add(r);
        }
        Pod.prefs(c).edit().putString("lastSync", new SimpleDateFormat("EEE d MMM, h:mm a", Locale.ENGLISH).format(new Date())).commit();
        return out;
    }

    public static String syncShow(android.content.Context c, String title, String feedUrl) throws Exception {
        byte[] xml = httpGet(feedUrl, 15000);
        if (xml == null || xml.length < 50) return "failed (empty response)";
        FeedParser fp;
        try {
            fp = FeedParser.parse(new java.io.ByteArrayInputStream(xml));
        } catch (Throwable t) {
            return "failed (bad feed)";
        }
        ArrayList<Episode> items = fp.items;
        if (items.size() == 0) return "failed (no episodes)";
        // newest first
        Collections.sort(items, new Comparator<Episode>() {
            public int compare(Episode a, Episode b) { return Long.compare(b.dateMs, a.dateMs); }
        });
        File dir = Pod.audioDir(c);
        String slug = Pod.slug(title);

        // which episodes already have files
        ArrayList<Episode> existing = readIndex(c, slug);
        ArrayList<String> have = new ArrayList<String>();
        for (Episode e : existing) if (e.local != null) have.add(e.url);

        // keep-list from this feed
        ArrayList<Episode> keep = new ArrayList<Episode>();
        int added = 0, downloaded = 0;
        for (Episode e : items) {
            if (keep.size() >= Pod.KEEP_EPISODES + 2) break;
            keep.add(e);
        }
        StringBuilder indexLines = new StringBuilder();
        for (Episode e : keep) {
            String fn = null;
            if (have.contains(e.url)) {
                // find old filename
                for (Episode old : existing) if (old.url.equals(e.url) && old.local != null) fn = old.local;
            }
            if (fn == null) fn = slug + "_" + stamp(e.dateMs) + "_" + Pod.slug(e.title.length() > 42 ? e.title.substring(0, 42) : e.title) + ext(e.url);
            File f = new File(dir, fn);
            if (!f.exists() && added < Pod.MAX_DOWNLOADS_PER_SYNC) {
                try {
                    byte[] audio = httpGet(e.url, 120000);
                    if (audio != null && audio.length > 1000) {
                        FileOutputStream fo = new FileOutputStream(f);
                        fo.write(audio); fo.close();
                        added++; downloaded++;
                        e.local = fn;
                    } else {
                        e.local = null;
                    }
                } catch (Throwable t) { e.local = null; }
            } else {
                e.local = f.exists() ? fn : null;
            }
            indexLines.append(e.title).append('\t').append(e.date).append('\t').append(e.url).append('\t').append(e.local == null ? "" : e.local).append('\n');
        }
        // write index (tab-separated, simple)
        FileOutputStream fo = new FileOutputStream(new File(dir, slug + ".index"));
        fo.write(indexLines.toString().getBytes("UTF-8")); fo.close();

        // prune: delete files not referenced in keep set
        ArrayList<String> keepFiles = new ArrayList<String>();
        for (Episode e : keep) if (e.local != null) keepFiles.add(e.local);
        File[] all = dir.listFiles();
        if (all != null) for (File f : all) {
            String n = f.getName();
            if (n.startsWith(slug + "_") && !keepFiles.contains(n)) f.delete();
        }
        if (downloaded == 0) return "up to date";
        return downloaded + " new downloaded";
    }

    private static String ext(String url) {
        String u = url.toLowerCase(Locale.US);
        if (u.contains(".m4a")) return ".m4a";
        if (u.contains(".aac")) return ".aac";
        if (u.contains(".mp4")) return ".mp4";
        return ".mp3";
    }

    private static String stamp(long ms) {
        if (ms <= 0) return String.valueOf(System.currentTimeMillis() / 1000);
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(ms));
    }

    public static ArrayList<Episode> readIndex(android.content.Context c, String slug) {
        ArrayList<Episode> out = new ArrayList<Episode>();
        try {
            File f = new File(Pod.audioDir(c), slug + ".index");
            if (!f.exists()) return out;
            FileInputStream fi = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = fi.read(buf)) > 0) bo.write(buf, 0, n);
            fi.close();
            String txt = new String(bo.toByteArray(), "UTF-8");
            for (String line : txt.split("\n")) {
                String[] parts = line.split("\t", -1);
                if (parts.length >= 4 && parts[0].length() > 0 && parts[2].length() > 0) {
                    Episode e = new Episode();
                    e.title = parts[0]; e.date = parts[1]; e.url = parts[2];
                    e.local = parts[3].length() == 0 ? null : parts[3];
                    e.dateMs = FeedParser.parseDate(parts[1]);
                    out.add(e);
                }
            }
        } catch (Throwable t) { }
        return out;
    }

    public static byte[] httpGet(String urlStr, int timeoutMs) throws Exception {
        URL u = new URL(urlStr);
        HttpURLConnection con = (HttpURLConnection) u.openConnection();
        con.setConnectTimeout(timeoutMs);
        con.setReadTimeout(timeoutMs);
        con.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) JMarronePodcasts/1.0");
        con.setInstanceFollowRedirects(true);
        int code = con.getResponseCode();
        if (code < 200 || code >= 300) throw new Exception("http " + code);
        InputStream in = con.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384]; int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }

    public static void reschedule(android.content.Context c) {
        android.app.AlarmManager am = (android.app.AlarmManager) c.getSystemService(android.content.Context.ALARM_SERVICE);
        android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(c, 11,
            new android.content.Intent(c, SyncReceiver.class), android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        long week = 7L * 24 * 3600 * 1000;
        am.setInexactRepeating(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
            android.os.SystemClock.elapsedRealtime() + week, week, pi);
    }
}
