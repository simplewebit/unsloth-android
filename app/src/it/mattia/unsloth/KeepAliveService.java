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
                String ip = p.getString("host", "");
                String ddns = p.getString("ddns", "");
                String port = p.getString("port", "8888");
                if (ip.isEmpty() && ddns.isEmpty()) {
                    App.log("KEEPALIVE: host assente, salto");
                    stopSelf();
                    return;
                }
                // v2.8: la pagina e la fonte unica del refresh token (single-use,
                // rotazione monouso). Se la pagina non e viva (app chiusa) NON
                // ruoto: ruotare consumerebbe il token che la SPA tiene ancora
                // in localStorage e al prossimo avvio cadrebbe sul login.
                // Senza nessuno che lo usa, il token della SPA resta valido per
                // 7 giorni e la SPA si riparte da sola al prossimo avvio.
                if (!MainActivity.isPageAlive()) {
                    App.log("KEEPALIVE: pagina non viva, salto (token della SPA resta vivo)");
                    stopSelf();
                    return;
                }
                // Leggo il token fresco dal localStorage della pagina: e la
                // copia piu' recente (l'SPA lo ruota al suo 401, i prefs si
                // sincronizzano solo al prossimo load).
                String rt = p.getString("auth_refresh", "");
                String pageTok = MainActivity.readPageTokensBlocking(8000L);
                if (!pageTok.isEmpty()) {
                    String prt = pageTok.substring(pageTok.indexOf('|') + 1);
                    if (!prt.equals(rt)) rt = prt;
                }
                if (rt.isEmpty()) {
                    App.log("KEEPALIVE: refresh token assente, salto");
                    stopSelf();
                    return;
                }
                // v2.9: IP prima, DDNS se l'IP non risponde (probe 10 s).
                String base = App.baseUrl(App.resolveHost(ip, ddns, port), port);
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
                        // v2.8: riscrivo i token freschi nella pagina,
                        // altrimenti l'SPA resterebbe con il refresh token
                        // appena consumato (401 al suo prossimo refresh).
                        MainActivity.injectAuthTokensToPage(at, nrt);
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
