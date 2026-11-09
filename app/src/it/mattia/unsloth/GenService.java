package it.mattia.unsloth;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import org.json.JSONArray;
import org.json.JSONObject;

// Service in primo piano che mantiene la generazione di chat viva anche con
// l'app in background e schermo spento. Fa tre cose:
// 1) tiene aperta una connessione SSE nativa su /chat-runs/<id>/events?after=0:
//    cosi c'e' sempre un client "attento" al run e Studio non lo interrompe
//    quando lo stream della web view muore in background.
// 2) fa polling di /chat-runs/active?threadId=X ogni 5 s: quando runs diventa
//    vuoto la generazione e' finita.
// 3) a fine genera una notifica "Risposta pronta" (toccabile, apre l'app) e
//    si ferma da solo. In caso di 401 rinnova il token via /api/auth/refresh
//    (stesso flusso di KeepAliveService).
public class GenService extends Service {

    // Channel della notifica ongoing "generazione in corso".
    private static final String CHAN = "gen_keepalive";
    // Channel della notifica "risposta pronta": id nuovo, creato DEFAULT.
    // Non si riusa gen_keepalive: su Android 14 NMS non aggiorna l'importance
    // di un channel gia' presente (cancellazione + ricreazione ripristina lo
    // stato precedente, mOriginalImp), quindi il channel vecchio resta LOW e
    // la notifica finale andrebbe in silenzio.
    private static final String CHAN_DONE = "gen_done";
    private static final long MAX_MS = 2 * 60 * 60 * 1000L;
    private static final long POLL_MS = 5000;

    private volatile boolean mHadRun = false;
    private String mHost = "", mToken = "", mThreadId = "";
    // Titolo della chat per le notifiche (letto da /api/chat/threads/<id>).
    private String mThreadTitle = "";

    private volatile boolean mStop = false;
    private Thread mWorker;

    @Override
    public int onStartCommand(Intent intent, int flags, int id) {
        createChannel();
        startForeground(1, buildOngoing());
        App.log("GEN: keep-alive avviato");
        if (mWorker != null && mWorker.isAlive()) {
            return START_STICKY;
        }
        final String host = extraOr(intent, "host", prefsHost());
        final String token0 = extraOr(intent, "token", prefsToken());
        final String threadId = extraOr(intent, "threadId", "");
        if (host.isEmpty() || token0.isEmpty()) {
            App.log("GEN: host o token assente, stop");
            stopSelf();
            return START_NOT_STICKY;
        }
        mStop = false;
        mHadRun = false;
        mHost = host; mToken = token0; mThreadId = threadId;
        mWorker = new Thread(new Runnable() {
            @Override
            public void run() {
                loop(host, threadId);
            }
        });
        mWorker.start();
        return START_STICKY;
    }

    private void loop(final String host, final String threadId) {
        // Legge il titolo della chat per le notifiche (una sola volta; se
        // fallisce resta il testo generico).
        if (threadId != null && !threadId.isEmpty()) {
            try {
                JSONObject t = getJson(host + "/api/chat/threads/"
                        + java.net.URLEncoder.encode(threadId, "UTF-8"));
                mThreadTitle = t.optString("title", "");
                App.log("GEN: titolo thread letto " + (mThreadTitle.isEmpty() ? "(assente)" : mThreadTitle));
            } catch (Exception e) {
                App.log("GEN: titolo non letto (" + e.getMessage() + ")");
            }
        }
        if (!mThreadTitle.isEmpty()) updateOngoing();
        long start = System.currentTimeMillis();
        boolean hadRun = false;
        int emptyStreak = 0;
        String sseRunId = null;
        while (!mStop && System.currentTimeMillis() - start < MAX_MS) {
            try {
                JSONObject active = getJson(host + "/api/inference/chat-runs/active?threadId="
                        + java.net.URLEncoder.encode(threadId, "UTF-8"));
                if (active != null) {
                    JSONArray runs = active.optJSONArray("runs");
                    if (runs != null && runs.length() > 0) {
                        hadRun = true;
                        mHadRun = true;
                        emptyStreak = 0;
                        String rid = runs.getJSONObject(0).optString("id", "");
                        if (!rid.equals(sseRunId)) {
                            sseRunId = rid;
                            openSse(host, mToken, rid);
                        }
                    } else if (hadRun) {
                        App.log("GEN: run completato, notifica e stop");
                        postDone();
                        mStop = true;
                        break;
                    } else {
                        emptyStreak++;
                        if (emptyStreak >= 3) {
                            App.log("GEN: nessuna generazione attiva, stop");
                            mStop = true;
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                App.log("GEN: polling errore " + e.getMessage());
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
        if (!mStop) {
            App.log("GEN: timeout max, stop");
        }
        stopSelf();
    }

    // Apre l'SSE nativo in un thread dedicato e lo tiene aperto: e' questo che
    // mantiene il run vivo. Se la connessione muore, al prossimo polling viene
    // riaperta (stesso runId finche' il run esiste).
    private void openSse(final String host, final String token, final String runId) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    java.net.URL u = new java.net.URL(host + "/api/inference/chat-runs/" + runId + "/events?after=0");
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();
                    c.setRequestMethod("POST");
                    c.setRequestProperty("Authorization", "Bearer " + token);
                    c.setRequestProperty("Accept", "text/event-stream");
                    c.setRequestProperty("Content-Type", "application/json");
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(600000);
                    c.setDoOutput(true);
                    c.getOutputStream().write("{}".getBytes("UTF-8"));
                    int code = c.getResponseCode();
                    App.log("GEN: SSE aperto " + code);
                    if (code != 200) return;
                    java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
                    String line;
                    int n = 0;
                    while ((line = r.readLine()) != null) {
                        if (line.startsWith("event:")) {
                            n++;
                            if (n <= 5) App.log("GEN: SSE event " + line.substring(6).trim());
                        }
                    }
                } catch (Exception e) {
                    App.log("GEN: SSE chiuso (" + e.getMessage() + ")");
                }
            }
        });
        t.start();
    }

    // Usa mToken (campo, non parametro): dopo un 401 refreshToken() aggiorna
    // mToken e i polling successivi riprendono col token nuovo. Prima il loop
    // passava sempre il token0 originale: un rinnovo riuscito non veniva mai
    // usato e ogni ciclo rifaceva 401 + refresh fallito (bug del 8/11).
    private JSONObject getJson(String url) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setRequestProperty("Authorization", "Bearer " + mToken);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        int code = c.getResponseCode();
        if (code == 401) {
            String t2 = refreshToken();
            if (t2 == null || t2.isEmpty()) throw new Exception("token scaduto e refresh fallito");
            return getJson(url);
        }
        if (code != 200) throw new Exception("HTTP " + code);
        java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        return new JSONObject(sb.toString());
    }

    private String refreshToken() {
        try {
            SharedPreferences p = getSharedPreferences("unsloth", MODE_PRIVATE);
            String rt = p.getString("auth_refresh", "");
            if (rt.isEmpty()) return "";
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL(prefsHost() + "/api/auth/refresh").openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setDoOutput(true);
            c.getOutputStream().write(("{\"refresh_token\":\"" + rt + "\"}").getBytes("UTF-8"));
            if (c.getResponseCode() != 200) return "";
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            JSONObject j = new JSONObject(sb.toString());
            String at = j.optString("access_token");
            String nrt = j.optString("refresh_token");
            if (at.isEmpty()) return "";
            // Il refresh token e' single-use: il backend lo consuma e ne emette uno
            // nuovo. Se non lo salvo qui, al prossimo 401 riprovare con quello
            // vecchio nei prefs fallisce per sempre (stesso bug gia' visto il
            // 8/11: dopo un rinnovo ok il polling e' rimasto senza token valido).
            p.edit().putString("auth_access", at).putString("auth_refresh", nrt)
                    .putLong("auth_synced_ts", System.currentTimeMillis()).apply();
            mToken = at;
            App.log("GEN: token rinnovato");
            return at;
        } catch (Exception e) {
            App.log("GEN: refresh errore " + e.getMessage());
            return "";
        }
    }

    private String extraOr(Intent i, String k, String d) {
        try {
            return i != null && i.hasExtra(k) ? i.getStringExtra(k) : d;
        } catch (Exception e) {
            return d;
        }
    }

    private String prefsHost() {
        try {
            SharedPreferences p = getSharedPreferences("unsloth", MODE_PRIVATE);
            String h = p.getString("host", "");
            String port = p.getString("port", "8888");
            if (h.isEmpty()) return "";
            return h.startsWith("http://") || h.startsWith("https://")
                    ? h + (port.isEmpty() ? "" : ":" + port) : "http://" + h + ":" + port;
        } catch (Exception e) {
            return "";
        }
    }

    private String prefsToken() {
        try {
            return getSharedPreferences("unsloth", MODE_PRIVATE).getString("auth_access", "");
        } catch (Exception e) {
            return "";
        }
    }

    private void postDone() {
        // Segno il completamento: al rientro in primo piano MainActivity
        // confronta gen_done_ts con il timestamp del suspend e, se la
        // generazione e' finita mentre eravamo via, ri-sincronizza la chat
        // dal server (telefono e PC vedono gli stessi messaggi).
        try {
            getSharedPreferences("unsloth", MODE_PRIVATE).edit()
                    .putLong("gen_done_ts", System.currentTimeMillis()).apply();
        } catch (Exception e) {
        }
        postResponseReady(this, mThreadId, mThreadTitle);
    }

    // Notifica "risposta pronta" (toccabile). E' static perche' la manda anche
    // MainActivity quando rileva una generazione finita in background che il
    // service non ha mai segnato (non era partito). threadId e title sono
    // opzionali: con il tap si apre la chat corrispondente e il testo mostra
    // il titolo della chat; se assenti si apre l'app normale.
    public static void postResponseReady(Context ctx, String threadId, String title) {
        try {
            ensureChannels(ctx);
            NotificationManager nm = (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            Intent open = new Intent(ctx, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (threadId != null && !threadId.isEmpty()) open.putExtra("threadId", threadId);
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                    Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                b = new Notification.Builder(ctx, CHAN_DONE);
            } else {
                b = new Notification.Builder(ctx, "Unsloth");
            }
            b.setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(ctx.getString(R.string.notif_ready_title))
                    .setContentText((title == null || title.isEmpty())
                            ? ctx.getString(R.string.notif_ready_text_default)
                            : "\u00AB" + title + "\u00BB" + ctx.getString(R.string.notif_ready_suffix))
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            nm.notify(2, b.build());
        } catch (Exception e) {
            App.log("GEN: notifica errore " + e.getMessage());
        }
    }

    private void createChannel() {
        ensureChannels(this);
    }

    // Crea i channel notifiche. gen_done (id nuovo) nasce DEFAULT: la
    // notifica "risposta pronta" cosi ha banner e suono. gen_keepalive resta
    // quello esistente: serve solo alla notifica ongoing "generazione in
    // corso", che e' sempre visibile nella barra di stato.
    public static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(CHAN, ctx.getString(R.string.channel_gen), NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription(ctx.getString(R.string.channel_gen_desc));
            nm.createNotificationChannel(ch);
            NotificationChannel ch2 = new NotificationChannel(CHAN_DONE, ctx.getString(R.string.channel_done), NotificationManager.IMPORTANCE_DEFAULT);
            ch2.setDescription(ctx.getString(R.string.channel_done_desc));
            nm.createNotificationChannel(ch2);
        } catch (Exception e) {
            App.log("GEN: createChannel errore " + e.getMessage());
        }
    }

    private Notification buildOngoing() {
        try {
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                b = new Notification.Builder(this, CHAN);
            } else {
                b = new Notification.Builder(this, "Unsloth");
            }
            // Al tap apre la chat del thread in generazione (extra "threadId");
            // il testo mostra il titolo della chat quando e' stato letto.
            return b.setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(getString(R.string.notif_gen_title))
                    .setContentText(mThreadTitle.isEmpty()
                            ? getString(R.string.notif_gen_text_default)
                            : "\u00AB" + mThreadTitle + "\u00BB" + getString(R.string.notif_gen_suffix))
                    .setContentIntent(openChatIntent())
                    .setOngoing(true)
                    .build();
        } catch (Exception e) {
            return new Notification.Builder(this, "Unsloth").build();
        }
    }

    // PendingIntent che apre l'app sulla chat giusta: al tap sulla notifica
    // MainActivity riceve l'extra "threadId" e naviga la WebView alla chat.
    private PendingIntent openChatIntent() {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (mThreadId != null && !mThreadId.isEmpty()) open.putExtra("threadId", mThreadId);
        return PendingIntent.getActivity(this, 0, open,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    // Ri-publica la notifica ongoing (stesso id 1) dopo aver letto il titolo,
    // cosi nella shade compare il nome della chat.
    private void updateOngoing() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(1, buildOngoing());
        } catch (Exception e) {
        }
    }

    @Override
    public void onDestroy() {
        mStop = true;
        if (mWorker != null) mWorker.interrupt();
        try {
            stopForeground(true);
        } catch (Exception e) {
        }
        App.log("GEN: keep-alive fermato");
        // Se abbiamo visto un run, controlliamo una ultima volta se e' finito:
        // cosi la notifica "risposta pronta" parte anche quando il service viene
        // fermato da onResume appena dopo il completamento (race).
        if (mHadRun && mHost != null && !mHost.isEmpty()) {
            final String h = mHost, tid = (mThreadId == null ? "" : mThreadId);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        JSONObject active = getJson(h + "/api/inference/chat-runs/active?threadId="
                                + java.net.URLEncoder.encode(tid, "UTF-8"));
                        JSONArray runs = (active != null) ? active.optJSONArray("runs") : null;
                        if (runs == null || runs.length() == 0) {
                            App.log("GEN: completato rilevato allo stop, notifica risposta pronta");
                            postDone();
                        }
                    } catch (Exception e) {
                        App.log("GEN: check finale errore " + e.getMessage());
                    }
                }
            }).start();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
