package com.jarvis.assistant;
import android.animation.*;
import android.app.*;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.*;
import android.widget.TextView;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.*;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.speech.*;
import android.speech.tts.*;
import android.telephony.SmsManager;
import java.util.*;
import java.util.regex.*;

public class JarvisService extends Service implements RecognitionListener {
    SpeechRecognizer sr; TextToSpeech tts; AudioManager am;
    boolean speaking, ready; long awakeUntil;
    Handler h = new Handler(Looper.getMainLooper());

    public IBinder onBind(Intent i) { return null; }
    public int onStartCommand(Intent i, int f, int s) { return START_STICKY; }

    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("j", "Jarvis", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "j").setContentTitle("Jarvis is listening")
            .setContentText("Say \"Hey Jarvis\"").setSmallIcon(android.R.drawable.ic_btn_speak_now).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else startForeground(1, n);
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        tts = new TextToSpeech(this, st -> {
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                public void onStart(String id) {}
                public void onDone(String id) { speaking = false; h.post(() -> listen()); }
                public void onError(String id) { onDone(id); }
            });
            tts.setLanguage(Locale.UK);
            try {
                Voice best = null;
                for (Voice v : tts.getVoices()) {
                    String n = v.getName();
                    if (Locale.UK.equals(v.getLocale()) && (n.contains("rjs") || n.contains("gbd") || n.contains("gbb"))) { best = v; break; }
                }
                if (best == null) for (Voice v : tts.getVoices()) if (Locale.UK.equals(v.getLocale())) { best = v; break; }
                if (best != null) tts.setVoice(best);
            } catch (Exception e) {}
            tts.setPitch(0.8f); tts.setSpeechRate(0.95f);
            ready = true; speak("At your service, sir.");
        });
    }

    void mute(boolean m) {
        try { for (int s : new int[]{AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM})
            am.adjustStreamVolume(s, m ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0); } catch (Exception e) {}
    }

    void listen() {
        if (speaking) return;
        if (System.currentTimeMillis() >= awakeUntil) hideOrb();
        try {
            if (sr != null) sr.destroy();
            sr = SpeechRecognizer.createSpeechRecognizer(this); sr.setRecognitionListener(this);
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN");
            mute(true); sr.startListening(i);
        } catch (Exception e) { h.postDelayed(this::listen, 1500); }
    }

    void speak(String t) {
        if (!ready) return;
        speaking = true; if (sr != null) sr.cancel(); mute(false);
        tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "u");
    }

    // ---- RecognitionListener ----
    public void onError(int e) { if (!speaking) h.postDelayed(this::listen, e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 1500 : 300); }
    public void onResults(Bundle b) {
        ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        String t = (r != null && !r.isEmpty()) ? r.get(0) : "";
        if (!process(t) && !speaking) listen();
    }
    public void onReadyForSpeech(Bundle b) {} public void onBeginningOfSpeech() {}
    public void onRmsChanged(float v) {} public void onBufferReceived(byte[] b) {}
    public void onEndOfSpeech() {} public void onPartialResults(Bundle b) {} public void onEvent(int a, Bundle b) {}

    // ---- Wake word ----
    boolean process(String raw) {
        String t = raw.toLowerCase().replace(".", "").trim();
        if (t.isEmpty()) return false;
        boolean awake = System.currentTimeMillis() < awakeUntil;
        int k = t.indexOf("jarvis");
        if (k < 0 && !awake) return false;
        showOrb();
        String cmd = k >= 0 ? t.substring(k + 6).replaceFirst("^[ ,]+", "") : t;
        if (cmd.isEmpty()) { awakeUntil = System.currentTimeMillis() + 8000; speak("Yes, sir?"); return true; }
        awakeUntil = 0; handle(cmd); return true;
    }

    // ---- AI brain (rule based) ----
    void handle(String c) {
        Matcher m;
        try {
            if ((m = Pattern.compile("(?:text|sms|message) (.+?) (?:saying|that) (.+)").matcher(c)).find()) {
                String num = number(m.group(1));
                if (num == null) { speak("I could not find " + m.group(1)); return; }
                SmsManager.getDefault().sendTextMessage(num, null, m.group(2), null, null);
                speak("Message sent to " + m.group(1));
            } else if ((m = Pattern.compile("(?:call|dial) (.+)").matcher(c)).find()) {
                String num = number(m.group(1));
                if (num == null) { speak("I could not find " + m.group(1)); return; }
                speak("Calling " + m.group(1)); go(new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + num)));
            } else if (c.contains("whatsapp")) { speak("Opening WhatsApp"); open("com.whatsapp");
            } else if (c.contains("instagram")) { speak("Opening Instagram"); open("com.instagram.android");
            } else if (c.contains("email") || c.contains("mail")) { speak("Opening email"); go(new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")));
            } else if ((m = Pattern.compile("alarm.*?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?").matcher(c)).find()) {
                int hr = Integer.parseInt(m.group(1)), mn = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
                if ("pm".equals(m.group(3)) && hr < 12) hr += 12;
                if ("am".equals(m.group(3)) && hr == 12) hr = 0;
                speak("Alarm set for " + hr + ":" + String.format("%02d", mn));
                go(new Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hr)
                    .putExtra(AlarmClock.EXTRA_MINUTES, mn).putExtra(AlarmClock.EXTRA_SKIP_UI, true));
            } else if ((m = Pattern.compile("remind me (?:to )?(.+?) in (\\d+) (second|minute|hour)").matcher(c)).find()) {
                long ms = Long.parseLong(m.group(2)) * (m.group(3).equals("second") ? 1000 : m.group(3).equals("minute") ? 60000 : 3600000);
                final String what = m.group(1);
                h.postDelayed(() -> speak("Reminder: " + what), ms); speak("Okay, I will remind you");
            } else if (c.contains("read") && c.contains("message")) { readSms();
            } else if (c.contains("routine")) { speak("Your routine: seven, wake up. Eight, breakfast. Nine, work. One, lunch. Ten, sleep.");
            } else if (c.contains("time")) { speak("It is " + new java.text.SimpleDateFormat("h mm a").format(new Date()));
            } else if ((m = Pattern.compile("(?:search|google)(?: for)? (.+)").matcher(c)).find()) {
                speak("Searching Google"); go(new Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, m.group(1)));
            } else if (c.contains("google") || c.contains("assistant")) {
                speak("Opening Google"); go(new Intent(Intent.ACTION_VOICE_COMMAND));
            } else ask(c);
        } catch (Exception e) { speak("Something went wrong"); }
    }



    // ---- Floating orb: appears only after "Hey Jarvis" ----
    View orb; WindowManager wm;
    void showOrb() {
        if (orb != null || !Settings.canDrawOverlays(this)) return;
        try {
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            TextView t = new TextView(this); t.setText("J"); t.setTextColor(0xFFFFFFFF); t.setTextSize(28); t.setGravity(Gravity.CENTER);
            GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(0xFF16245A); g.setStroke(8, 0xFF9EC1FF);
            t.setBackground(g);
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(220, 220, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT);
            p.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL; p.y = 220;
            wm.addView(t, p); orb = t;
            ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(t, PropertyValuesHolder.ofFloat("scaleX", 1f, 1.2f), PropertyValuesHolder.ofFloat("scaleY", 1f, 1.2f));
            a.setRepeatCount(ValueAnimator.INFINITE); a.setRepeatMode(ValueAnimator.REVERSE); a.setDuration(600); a.start();
        } catch (Exception e) { orb = null; }
    }
    void hideOrb() { if (orb != null) { try { wm.removeView(orb); } catch (Exception e) {} orb = null; } }

    // ---- Ask anything: Gemini (free key) with Google Search, else plain Google search ----
    void ask(String q) {
        final String key = getSharedPreferences("j", 0).getString("key", "");
        final Intent web = new Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q);
        if (key.isEmpty()) { speak("Let me search that on Google"); go(web); return; }
        new Thread(() -> {
            String ans = null;
            try {
                java.net.HttpURLConnection u = (java.net.HttpURLConnection) new java.net.URL(
                    "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent").openConnection();
                u.setRequestMethod("POST"); u.setConnectTimeout(15000); u.setReadTimeout(40000); u.setDoOutput(true);
                u.setRequestProperty("x-goog-api-key", key); u.setRequestProperty("Content-Type", "application/json");
                org.json.JSONObject b = new org.json.JSONObject();
                b.put("system_instruction", new org.json.JSONObject().put("parts", new org.json.JSONArray().put(new org.json.JSONObject().put("text",
                    "You are Jarvis, a calm British butler-style voice assistant. Answer in at most three short spoken sentences. No markdown, no lists."))));
                b.put("contents", new org.json.JSONArray().put(new org.json.JSONObject().put("role", "user")
                    .put("parts", new org.json.JSONArray().put(new org.json.JSONObject().put("text", q)))));
                b.put("tools", new org.json.JSONArray().put(new org.json.JSONObject().put("google_search", new org.json.JSONObject())));
                u.getOutputStream().write(b.toString().getBytes("UTF-8"));
                if (u.getResponseCode() < 400) {
                    String r = new Scanner(u.getInputStream(), "UTF-8").useDelimiter("\\A").next();
                    org.json.JSONArray parts = new org.json.JSONObject(r).getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts");
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < parts.length(); i++) sb.append(parts.getJSONObject(i).optString("text")).append(" ");
                    ans = sb.toString().replaceAll("[*#_`]", "").trim();
                }
            } catch (Exception e) {}
            final String f = ans;
            h.post(() -> { if (f != null && !f.isEmpty()) speak(f); else { speak("I could not reach my brain. Searching Google."); go(web); } });
        }).start();
    }

    void go(Intent i) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); }
    void open(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) go(i);
    }

    String number(String n) {
        String d = n.replace(" ", "");
        if (d.matches("\\+?\\d{6,}")) return d;
        try (Cursor c = getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER},
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", new String[]{"%" + n + "%"}, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception e) {}
        return null;
    }

    void readSms() {
        StringBuilder sb = new StringBuilder(); int k = 0;
        try (Cursor c = getContentResolver().query(Uri.parse("content://sms/inbox"), new String[]{"address", "body"}, null, null, "date DESC")) {
            while (c != null && c.moveToNext() && k++ < 3) sb.append("From ").append(c.getString(0)).append(": ").append(c.getString(1)).append(". ");
        } catch (Exception e) {}
        speak(sb.length() == 0 ? "No messages found" : sb.toString());
    }

    public void onDestroy() {
        hideOrb(); mute(false); if (sr != null) sr.destroy(); if (tts != null) tts.shutdown(); super.onDestroy();
    }
}
