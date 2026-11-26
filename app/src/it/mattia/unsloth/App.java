package it.mattia.unsloth;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Environment;
import android.util.Log;
import androidx.appcompat.app.AppCompatDelegate;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

public class App extends Application {

    public static final String TAG = "Unsloth";
    // Nome del file SharedPreferences condiviso (stesso valore di
    // MainActivity.PREFS).
    public static final String PREFS_NAME = "unsloth";
    private static File logFile;
    // Opzione "Log" nelle impostazioni: se e' off, App.log non scrive nulla.
    // Il crash handler (writeLine) scrive SEMPRE, anche con il log off.
    public static volatile boolean logEnabled = true;

    // v3.0: tema dell'app ("light"/"dark"/"system"). Deve essere applicato
    // PRIMA che qualsiasi activity venga creata, cosi la prima window ha
    // gia' la configuration night giusta (values-night/colors.xml).
    public static int nightModeFor(String theme) {
        if ("dark".equals(theme)) return AppCompatDelegate.MODE_NIGHT_YES;
        if ("light".equals(theme)) return AppCompatDelegate.MODE_NIGHT_NO;
        return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            String theme = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getString("theme", "system");
            AppCompatDelegate.setDefaultNightMode(nightModeFor(theme));
            Log.i(TAG, "App.onCreate: night mode " + nightModeFor(theme));
        } catch (Exception e) {
            Log.e(TAG, "App.onCreate night mode failed", e);
        }
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            logFile = new File(dir, "crash.log");
            log("App.onCreate: inizio");
            log("App.onCreate: SDK=" + android.os.Build.VERSION.SDK_INT
                    + " device=" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
        } catch (Exception e) {
            Log.e(TAG, "App.onCreate failed", e);
        }

        Thread.setDefaultUncaughtExceptionHandler((t, ex) -> {
            // writeLine (non log): il crash va registrato anche con l'opzione log off
            writeLine("=== UNCAUGHT EXCEPTION su thread " + t.getName() + " ===");
            StringWriter sw = new StringWriter();
            ex.printStackTrace(new PrintWriter(sw));
            writeLine(sw.toString());
            writeLine("=== FINE CRASH LOG ===");
            // Provo anche a mostrare un Toast
            try {
                android.widget.Toast toast = android.widget.Toast.makeText(
                        this, getString(R.string.crash_toast), android.widget.Toast.LENGTH_LONG);
                toast.show();
            } catch (Exception ignored) {}
        });
    }

    public static void log(String msg) {
        if (!logEnabled) return;
        writeLine(msg);
    }

    private static void writeLine(String msg) {
        try {
            if (logFile == null) return;
            String ts = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
            FileWriter fw = new FileWriter(logFile, true);
            fw.write(ts + "  " + msg + "\n");
            fw.close();
        } catch (Exception e) {
            Log.e(TAG, "log write failed", e);
        }
    }

    public static File getLogFile() {
        return logFile;
    }

    // ===== Connessione IP/DDNS (v2.9, 26/11 v3.3) =====
    // Timeout del probe di raggiungibilita': 10 s.
    // Regole di selezione (scelte di Mattia, 26/11):
    // - rete mobile attiva: si usa il DDNS. Se il DDNS non risponde non si
    //   prova nemmeno l'IP (il DDNS e' comunque l'unico host raggiungibile).
    // - WiFi/altra rete: si prova l'IP per primo; se non risponde si usa il
    //   DDNS come fallback.
    private static final int PROBE_CONNECT_MS = 10000;
    private static final int PROBE_READ_MS = 5000;

    // True se la rete attiva e' mobile (dati mobili / 5G). Legge lo stato
    // della rete corrente dall'Activity/Service che chiama resolveHost.
    public static boolean isMobile(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                android.net.Network network = cm.getActiveNetwork();
                if (network != null) {
                    NetworkCapabilities nc = cm.getNetworkCapabilities(network);
                    if (nc != null) {
                        return nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
                    }
                    // Capabilities mancanti: fallback al tipo della rete.
                    NetworkInfo netInfo = cm.getNetworkInfo(network);
                    if (netInfo != null) {
                        return netInfo.getType() == ConnectivityManager.TYPE_MOBILE;
                    }
                }
                // getActiveNetwork null (VPN, nessuna rete attiva):
                // fallback al NetworkInfo tradizionale, unico segnale utile.
                NetworkInfo activeInfo = cm.getActiveNetworkInfo();
                return activeInfo != null && activeInfo.getType() == ConnectivityManager.TYPE_MOBILE;
            } else {
                NetworkInfo ni = cm.getActiveNetworkInfo();
                return ni != null && ni.getType() == ConnectivityManager.TYPE_MOBILE;
            }
        } catch (Exception e) {
            log("isMobile: errore lettura rete (" + e.getClass().getSimpleName() + ": " + e.getMessage() + "), assumo non mobile");
            return false;
        }
    }

    // Base URL http://host:port da host e port (host gia' con scheme resta).
    public static String baseUrl(String host, String port) {
        if (host == null || host.isEmpty()) return "";
        if (host.startsWith("http://") || host.startsWith("https://")) {
            return host + (port == null || port.isEmpty() ? "" : ":" + port);
        }
        return "http://" + host + ":" + (port == null || port.isEmpty() ? "8888" : port);
    }

    // Probe host:porta: se il server HTTP risponde entro i timeout, il host
    // e' raggiungibile. Qualsiasi risposta HTTP conta (anche 404).
    public static boolean reachable(String host, String port) {
        try {
            java.net.HttpURLConnection c =
                    (java.net.HttpURLConnection) new java.net.URL(baseUrl(host, port)).openConnection();
            c.setConnectTimeout(PROBE_CONNECT_MS);
            c.setReadTimeout(PROBE_READ_MS);
            c.getResponseCode();
            c.disconnect();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // Risolve l'host da usare:
    // - rete mobile attiva: se il DDNS risponde lo si usa; se non risponde
    //   non si prova l'IP (il DDNS resta l'unico candidato).
    // - altrimenti: se l'IP risponde lo si usa; se non risponde si prova il
    //   DDNS come fallback; se anche il DDNS non risponde si ritorna l'IP
    //   (o il solo DDNS, se e' l'unico compilato): il chiamante lo usa cosi
    //   il collegamento va avanti e l'errore si vede nel WebView.
    public static String resolveHost(Context context, String ip, String ddns, String port) {
        String h = ip == null ? "" : ip.trim();
        String d = ddns == null ? "" : ddns.trim();
        boolean mobile = isMobile(context);
        String chosen;
        if (mobile) {
            if (!d.isEmpty() && reachable(d, port)) chosen = d;
            else chosen = !d.isEmpty() ? d : h;
        } else {
            if (!h.isEmpty() && reachable(h, port)) chosen = h;
            else if (!d.isEmpty() && reachable(d, port)) chosen = d;
            else chosen = !h.isEmpty() ? h : d;
        }
        log("RESOLVE: mobile=" + mobile + " ip=" + h + " ddns=" + d + " -> " + chosen);
        return chosen;
    }
}
