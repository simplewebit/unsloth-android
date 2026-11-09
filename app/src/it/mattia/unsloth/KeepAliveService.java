package it.mattia.unsloth;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

// Service in primo piano di breve durata (pochi secondi): una volta ogni ora
// rinnova il token di auth di Studio usando il refresh token salvato nei
// prefs, cosi la sessione non scade mentre l'app e chiusa. Un solo POST di
// un kilobyte: consumo trascurabile.
public class KeepAliveService extends Service {

    private static final String CHAN = "keepalive";

    @Override
    public int onStartCommand(Intent intent, int flags, int id) {
        createChannel();
        startForeground(1, buildNotification());
        new Thread(() -> {
            try {
                android.content.SharedPreferences p = getSharedPreferences("unsloth", MODE_PRIVATE);
                String host = p.getString("host", "");
                String port = p.getString("port", "8888");
                String rt = p.getString("auth_refresh", "");
                if (host.isEmpty() || rt.isEmpty()) {
                    App.log("KEEPALIVE: host o refresh token assente, salto");
                    stopSelf();
                    return;
                }
                String base = host.startsWith("http://") || host.startsWith("https://")
                        ? host + (port.isEmpty() ? "" : ":" + port) : "http://" + host + ":" + port;
                java.net.URL u = new java.net.URL(base + "/api/auth/refresh");
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                c.setDoOutput(true);
                c.getOutputStream().write(("{\"refresh_token\":\"" + rt + "\"}").getBytes("UTF-8"));
                int code = c.getResponseCode();
                if (code == 200) {
                    java.io.InputStream in = c.getInputStream();
                    StringBuilder sb = new StringBuilder();
                    java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(in, "UTF-8"));
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    org.json.JSONObject j = new org.json.JSONObject(sb.toString());
                    String at = j.optString("access_token");
                    String nrt = j.optString("refresh_token");
                    if (!at.isEmpty() && !nrt.isEmpty()) {
                        p.edit().putString("auth_access", at).putString("auth_refresh", nrt)
                                .putLong("auth_synced_ts", System.currentTimeMillis()).apply();
                        App.log("KEEPALIVE: token rinnovato ok");
                    } else {
                        App.log("KEEPALIVE: 200 ma risposta senza token");
                    }
                } else {
                    App.log("KEEPALIVE: HTTP " + code + " (refresh fallito; al prossimo avvio l'app mostrera il login)");
                }
            } catch (Exception e) {
                App.log("KEEPALIVE: errore " + e.getMessage());
            } finally {
                stopForeground(true);
                stopSelf();
            }
        }).start();
        return START_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(CHAN, "Unsloth", NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("Rinnova la sessione di Studio");
            nm.createNotificationChannel(ch);
        } catch (Exception e) {
            App.log("KEEPALIVE: createChannel errore " + e.getMessage());
        }
    }

    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHAN);
        } else {
            b = new Notification.Builder(this).setSmallIcon(android.R.drawable.ic_dialog_info);
        }
        b.setContentTitle("Unsloth");
        b.setContentText(getString(R.string.keepalive_text));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) b.setOngoing(true);
        return b.build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
