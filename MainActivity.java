package com.jarvis.assistant;
import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity {
    static final String[] P = {Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE,
        Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS};
    TextView tv; boolean askedBattery;

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(40, 100, 40, 40);
        tv = new TextView(this); tv.setTextSize(18);
        final EditText et = new EditText(this); et.setHint("Gemini API key (free, from aistudio.google.com)"); et.setSingleLine(true);
        Button bt = new Button(this); bt.setText("Save key");
        bt.setOnClickListener(v -> { getSharedPreferences("j", 0).edit().putString("key", et.getText().toString().trim()).apply(); tv.setText("Key saved. Say Hey Jarvis and ask anything."); });
        l.addView(tv); l.addView(et); l.addView(bt); setContentView(l);
    }

    protected void onResume() {
        super.onResume();
        List<String> need = new ArrayList<>();
        for (String p : P) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!need.isEmpty()) { tv.setText("Please allow all permissions."); requestPermissions(need.toArray(new String[0]), 1); return; }
        if (!Settings.canDrawOverlays(this)) {
            tv.setText("Turn ON \"Display over other apps\" for Jarvis, then press back.");
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); return;
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (!askedBattery && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            askedBattery = true;
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))); return;
        }
        startForegroundService(new Intent(this, JarvisService.class));
        tv.setText("Jarvis is running.\n\nJust say \"Hey Jarvis\".\nYou can close this screen.");
    }
}
