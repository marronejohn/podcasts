package au.com.jmarrone.podcasts;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.util.ArrayList;

public class Pod {
    // BUNDLED_SHOWS: title | category | feed url
    public static final String[][] BUNDLED_SHOWS = {
        {"Politics Now", "ABC - federal politics, incl. The Party Room", "https://www.abc.net.au/feeds/7288214/podcast.xml"},
        {"If You're Listening", "ABC - news explainers", "https://www.abc.net.au/feeds/9593214/podcast.xml"},
        {"ABC News Daily", "ABC - daily news", "https://www.abc.net.au/feeds/9443166/podcast.xml"},
        {"Background Briefing", "ABC - investigations", "https://www.abc.net.au/feeds/2890982/podcast.xml"},
        {"Download This Show", "ABC RN - tech & digital life", "https://www.abc.net.au/radionational/feed/3777916/podcast.xml"},
        {"Not Stupid", "ABC - the week's news", "https://www.abc.net.au/feeds/13499150/podcast.xml"},
        {"Australian Politics", "Guardian Australia - politics", "https://www.theguardian.com/australia-news/series/australian-politics-live/podcast.xml"},
        {"Full Story", "Guardian Australia - daily news", "https://www.theguardian.com/australia-news/series/full-story/podcast.xml"},
        {"Hard Fork", "New York Times - AI & tech", "https://feeds.simplecast.com/l2i9YnTd"},
        {"DataFramed", "DataCamp - data & AI", "https://feeds.captivate.fm/dataframed/"},
        {"HBR IdeaCast", "Harvard Business Review - management", "http://feeds.harvardbusiness.org/harvardbusiness/ideacast"},
        {"The Property Couch", "Australian property investing", "https://rss.buzzsprout.com/2094365.rss"},
        {"ABC COMEDY Presents", "ABC - stand-up comedy specials", "https://www.abc.net.au/feeds/10981668/podcast.xml"},
        {"32 Thoughts", "Sportsnet - NHL hockey", "https://feeds.simplecast.com/fYqFr5h_"},
        {"Politics with Michelle Grattan", "The Conversation - federal politics", "https://feeds.captivate.fm/politics-with-michellegrattan/"},
        {"Australia in the World", "ANU - international affairs & strategy", "https://feed.podbean.com/australiaintheworld/feed.xml"},
        {"The National Security Podcast", "ANU NSC - national security", "https://rss.acast.com/the-national-security-podcast"},
        {"Stop the World", "ASPI - defence & security", "https://anchor.fm/s/f2bce8a8/podcast/rss"},
        {"SBS News In Depth", "SBS - daily news analysis", "https://feeds.sbs.com.au/sbs-world-news-radio"},
        {"SBS On the Money", "SBS - economics & finance", "https://www.sbs.com.au/feed/news/podcast-rss/sbs-on-the-money"}

    };
    public static final int KEEP_EPISODES = 8;      // files kept per show
    public static final int MAX_DOWNLOADS_PER_SYNC = 10; // per show per sync

    public static SharedPreferences prefs(Context c) { return c.getSharedPreferences("pods", 0); }

    public static ArrayList<String[]> allShows(Context c) {
        ArrayList<String[]> out = new ArrayList<String[]>();
        for (String[] s : BUNDLED_SHOWS) out.add(s);
        String custom = prefs(c).getString("customFeeds", "");
        if (custom.length() > 0) {
            for (String part : custom.split(";;")) {
                String[] bits = part.split("\\|");
                if (bits.length == 3) out.add(new String[]{bits[0], bits[1], bits[2]});
            }
        }
        return out;
    }

    public static void addCustomShow(Context c, String title, String cat, String url) {
        SharedPreferences p = prefs(c);
        String cur = p.getString("customFeeds", "");
        String add = title + "|" + cat + "|" + url;
        p.edit().putString("customFeeds", cur.length() == 0 ? add : cur + ";;" + add).commit();
    }

    public static void removeCustomShow(Context c, String url) {
        SharedPreferences p = prefs(c);
        String cur = p.getString("customFeeds", "");
        StringBuilder sb = new StringBuilder();
        for (String part : cur.split(";;")) {
            if (!part.contains("|" + url)) {
                if (sb.length() > 0) sb.append(";;");
                sb.append(part);
            }
        }
        p.edit().putString("customFeeds", sb.toString()).commit();
    }

    public static boolean isCustom(Context c, String url) {
        return prefs(c).getString("customFeeds", "").contains("|" + url);
    }

    public static File audioDir(Context c) {
        File f = c.getExternalFilesDir(null);
        if (f == null) f = c.getFilesDir();
        File d = new File(f, "podcasts");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static String slug(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    // downloads enabled only on unmetered (wifi) network
    public static boolean mobileData(Context c) { return prefs(c).getBoolean("mobileData", false); }
    public static void setMobileData(Context c, boolean v) { prefs(c).edit().putBoolean("mobileData", v).commit(); }
    public static boolean networkOk(Context c) {
        if (mobileData(c)) {
            try {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm == null) return false;
                android.net.Network n = cm.getActiveNetwork();
                if (n == null) return false;
                android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                return caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
            } catch (Throwable t) { return false; }
        }
        return onWifi(c);
    }

    public static boolean onWifi(Context c) {
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        android.net.Network n = null;
        try { n = cm.getActiveNetwork(); } catch (Throwable t) { n = null; }
        boolean connected = false;
        if (n != null) {
            android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            connected = caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }
        if (!connected) {
            @SuppressWarnings("deprecation")
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            connected = ni != null && ni.isConnected();
        }
        return connected && !cm.isActiveNetworkMetered();
    }

    // in-app mini player bar: title + time left on the left, Pause/Play, +30 sec, Stop on the right
    public static android.view.View miniBar(final android.app.Activity a) {
        int pad = (int) (a.getResources().getDisplayMetrics().density * 8);
        android.widget.LinearLayout bar = new android.widget.LinearLayout(a);
        bar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setPadding(pad, pad / 2, pad, pad / 2);
        bar.setBackgroundColor(0xff202124);
        bar.setId(8);
        final android.widget.TextView tv = new android.widget.TextView(a);
        tv.setId(7);
        tv.setTextColor(0xffffffff);
        tv.setTextSize(13);
        tv.setMaxLines(2);
        bar.addView(tv, new android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        String[] labels = {"Play", "-10 sec", "+10 sec", "Stop"};
        for (int i = 0; i < 4; i++) {
            android.widget.Button b = new android.widget.Button(a);
            b.setText(labels[i]);
            b.setTextSize(12);
            b.setMinHeight(0);
            b.setMinWidth(0);
            final int which = i;
            b.setOnClickListener(new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) {
                    try {
                        android.content.SharedPreferences sp = a.getSharedPreferences(PlaybackService.PREFS, android.content.Context.MODE_PRIVATE);
                        String act;
                        if (which == 0) act = "playing".equals(sp.getString("np_state", "stopped")) ? PlaybackService.ACTION_PAUSE : PlaybackService.ACTION_RESUME;
                        else if (which == 1 || which == 2) act = PlaybackService.ACTION_SKIP;
                        else act = PlaybackService.ACTION_STOP;
                        android.content.Intent i2 = new android.content.Intent(a, PlaybackService.class).setAction(act);
                        if (which == 1) i2.putExtra(PlaybackService.SKIP, -10);
                        if (which == 2) i2.putExtra(PlaybackService.SKIP, 10);
                        a.startService(i2);
                        updateMiniBar(a, (android.view.View) v.getParent());
                    } catch (Throwable t) { }
                }
            });
            if (i == 0) b.setId(9);
            bar.addView(b);
        }
        bar.setVisibility(android.view.View.GONE);
        return bar;
    }

    public static void updateMiniBar(android.app.Activity a, android.view.View bar) {
        try {
            android.content.SharedPreferences sp = a.getSharedPreferences(PlaybackService.PREFS, android.content.Context.MODE_PRIVATE);
            String st = sp.getString("np_state", "stopped");
            android.widget.TextView tv = (android.widget.TextView) bar.findViewById(7);
            if (tv == null) return;
            if ("stopped".equals(st)) { bar.setVisibility(android.view.View.GONE); return; }
            bar.setVisibility(android.view.View.VISIBLE);
            String rem = PlaybackService.remainingFrom(sp.getLong("dur:" + sp.getString("np_path", ""), 0), sp.getLong("pos:" + sp.getString("np_path", ""), 0));
            tv.setText(("playing".equals(st) ? "\u25B6 " : "\u23F8 ") + sp.getString("np_title", "") + (rem.length() > 0 ? "   " + rem + " left" : ""));
            android.widget.Button pb = (android.widget.Button) bar.findViewById(9);
            if (pb != null) pb.setText("playing".equals(st) ? "Pause" : "Play");
        } catch (Throwable t) { }
    }
}
