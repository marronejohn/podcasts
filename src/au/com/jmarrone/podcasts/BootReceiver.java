package au.com.jmarrone.podcasts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (android.content.Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) {
            try { Sync.reschedule(c); } catch (Throwable t) { }
        }
    }
}
