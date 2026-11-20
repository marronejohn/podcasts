package au.com.jmarrone.podcasts;

import org.xmlpull.v1.XmlPullParser;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class FeedParser {
    public String feedTitle = "";
    public ArrayList<Episode> items = new ArrayList<Episode>();

    public static FeedParser parse(InputStream in) throws Exception {
        FeedParser fp = new FeedParser();
        XmlPullParser p = org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser();
        p.setInput(in, null);
        int ev = p.getEventType();
        String channelTitleDone = "no";
        boolean inItem = false;
        String curTitle = null, curDate = null, curUrl = null;
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                String name = p.getName().toLowerCase(Locale.US);
                if ("item".equals(name)) { inItem = true; curTitle = null; curDate = null; curUrl = null; }
                else if ("title".equals(name)) { String t = readText(p); if (!inItem && channelTitleDone.equals("no")) { fp.feedTitle = t; channelTitleDone = "yes"; } else if (inItem) curTitle = t; }
                else if ("pubdate".equals(name) || "published".equals(name)) { if (inItem) curDate = readText(p); }
                else if ("enclosure".equals(name)) {
                    if (inItem) {
                        String u = p.getAttributeValue(null, "url");
                        String ty = p.getAttributeValue(null, "type");
                        if (u != null && (ty == null || ty.contains("audio") || ty.contains("video") || u.contains(".mp3") || u.contains(".m4a") || u.contains(".aac"))) curUrl = u;
                    }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                String name = p.getName().toLowerCase(Locale.US);
                if ("item".equals(name)) {
                    inItem = false;
                    if (curUrl != null && curTitle != null && curTitle.trim().length() > 0) {
                        Episode e = new Episode();
                        e.title = unescape(curTitle.trim());
                        e.date = curDate == null ? "" : curDate.trim();
                        e.dateMs = parseDate(e.date);
                        e.url = curUrl.trim();
                        fp.items.add(e);
                    }
                }
            }
            ev = p.next();
        }
        return fp;
    }

    private static String readText(XmlPullParser p) throws Exception {
        String r = "";
        if (p.getEventType() == XmlPullParser.START_TAG) {
            r = p.nextText();
        }
        return r == null ? "" : r;
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
    }

    private static final String[] FMTS = {
        "EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEE, dd MMM yyyy HH:mm Z", "yyyy-MM-dd'T'HH:mm:ssZ", "yyyy-MM-dd'T'HH:mm:ss'Z'"
    };
    public static long parseDate(String s) {
        if (s == null || s.length() == 0) return 0;
        for (String f : FMTS) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(f, Locale.US);
                if (f.endsWith("'Z'")) sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date d = sdf.parse(s);
                if (d != null) return d.getTime();
            } catch (Exception e) { }
        }
        return 0;
    }
}
