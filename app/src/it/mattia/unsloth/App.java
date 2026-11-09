package it.mattia.unsloth;

import android.app.Application;
import android.os.Environment;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

public class App extends Application {

    public static final String TAG = "Unsloth";
    private static File logFile;
    // Opzione "Log" nelle impostazioni: se e' off, App.log non scrive nulla.
    // Il crash handler (writeLine) scrive SEMPRE, anche con il log off.
    public static volatile boolean logEnabled = true;

    @Override
    public void onCreate() {
        super.onCreate();
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
}
