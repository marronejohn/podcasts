package au.com.jmarrone.podcasts;

import java.io.Serializable;

public class Episode implements Serializable {
    private static final long serialVersionUID = 1L;
    public String title;
    public String date;   // pubDate raw string
    public long dateMs;   // parsed, 0 if unknown
    public String url;    // enclosure mp3 url
    public String local;  // local file name once downloaded (null if not)
    public int seconds;   // itunes:duration in seconds, 0 if the feed doesn't give one
}
