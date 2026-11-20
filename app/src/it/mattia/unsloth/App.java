package it.mattia.unsloth;

import android.app.Application;
import android.content.SharedPreferences;
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

    // ===== Connessione IP/DDNS (v2.9) =====
    // Timeout del probe di raggiungibilita': 10 s (scelta di Mattia: provare
    // sempre l'IP per primo, poi DDNS se l'IP non risponde).
    private static final int PROBE_CONNECT_MS = 10000;
    private static final int PROBE_READ_MS = 5000;

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

    // Risolve l'host da usare: prova l'IP per primo (timeout 10 s); se non
    // risponde prova il DDNS (timeout 10 s). Se nessuno risponde restituisce
    // l'IP (o il solo DDNS, se e' l'unico compilato): il chiamante lo usa
    // cosi il collegamento va avanti e l'errore si vede nel WebView.
    public static String resolveHost(String ip, String ddns, String port) {
        String h = ip == null ? "" : ip.trim();
        String d = ddns == null ? "" : ddns.trim();
        if (!h.isEmpty() && reachable(h, port)) return h;
        if (!d.isEmpty() && reachable(d, port)) return d;
        return !h.isEmpty() ? h : d;
    }
}
