package au.com.jmarrone.podcasts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class SyncReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context c, Intent i) {
        final PendingResult pr = goAsync();
        new Thread(new Runnable() {
            public void run() {
                try {
                    if (Pod.networkOk(c)) Sync.run(c);
                } catch (Throwable t) { }
                try { pr.finish(); } catch (Throwable t2) { }
            }
        }).start();
    }
}
