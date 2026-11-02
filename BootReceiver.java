package com.jarvis.assistant;
import android.content.*;
public class BootReceiver extends BroadcastReceiver {
    public void onReceive(Context c, Intent i) {
        try { c.startForegroundService(new Intent(c, JarvisService.class)); } catch (Exception e) {}
    }
}
