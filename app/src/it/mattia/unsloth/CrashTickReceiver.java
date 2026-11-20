package it.mattia.unsloth;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.FileReader;

// v3.0: tick del crash guard. Programmato da un allarme impreciso ripetuto
// (setRepeating, ~30 s, request code 2) attivo solo finche l'app e' in uso
// (onResume lo programma, onStop lo cancella). Quando il processo principale
// muore (crash) l'allarme persiste nel sistema: alla scadenza Android
// riparte il processo principale per consegnare questo broadcast.
// Il receiver NON lancia l'activity direttamente: su Android 14 ogni via di
// startActivity dal background e' chiusa da BAL (verificato: dal broadcast
// "Background activity launch blocked"; anche l'allarme one-shot con activity
// PendingIntent + BAL-allow, perche AlarmManagerService iniettta
// getAlarmOperationBundle -> setPendingIntentBackgroundActivityLaunchAllowed(false),
// che sovrascrive le opzioni del PendingIntent: log "allowsBackgroundActivityStarts=false").
// La via che resta e' la notifica FULL-SCREEN INTENT: il receiver posta una
// notifica con setFullScreenIntent(lancio). Schermo OFF -> il sistema lancia
// l'activity da solo; schermo ON -> notifica heads-up e un tap la apre.
// Il receiver agisce solo se:
//   - il marker crashguard_marker NON e' "closed" (app chiusa a
//     proposito: onStop/onDestroy lo scrivono; un marker di timestamp
//     vuol dire che l'ultimo stato e' stato primo piano),
//   - l'activity non e' viva (MainActivity.sActivityAlive): se il
//     processo e' ripreso ORA dall'allarme, l'activity non esiste e
//     l'app era morta in primo piano (l'allarme esiste solo finche
//     l'app e' in uso: onResume lo programma, onStop lo cancella).
// Un contatore in crashguard_state limita i riavvii (max in una finestra),
// per evitare un ciclo di crash.
public class CrashTickReceiver extends BroadcastReceiver {

    static final String ACTION = "it.mattia.unsloth.CRASHGUARD_TICK";

    private static final long MIN_RESTART_INTERVAL_MS = 10000;
    private static final int MAX_RESTARTS = 5;
    private static final long RESTART_WINDOW_COUNT_MS = 180000;

    // Notifica FSI di riavvio (id fisso: se un altro tick riposta, sostituisce).
    static final int RESTART_NOTIF_ID = 77;
    static final String RESTART_CHANNEL = "crashguard";

    // Posta la notifica FSI che riapre l'app: schermo off -> lancio
    // automatico; schermo on -> heads-up, un tap apre l'activity.
    static void sendRestartNotification(Context context) {
        Intent li = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (li == null) return;
        li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(RESTART_CHANNEL,
                context.getString(R.string.channel_crashguard),
                NotificationManager.IMPORTANCE_HIGH);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        // FSI via PendingIntent (l'overload Intent non esiste nell'SDK 34).
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(context,
                RESTART_NOTIF_ID, li,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(context, RESTART_CHANNEL)
                .setSmallIcon(R.drawable.ic_settings)
                .setContentTitle(context.getString(R.string.crashguard_title))
                .setContentText(context.getString(R.string.crashguard_text))
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_MAX)
                .build();
        nm.notify(RESTART_NOTIF_ID, n);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            File dir = context.getFilesDir();
            String marker = readFile(new File(dir, "crashguard_marker"));
            if (marker.isEmpty() || marker.charAt(0) == 'c') return; // closed/assente
            if (MainActivity.sActivityAlive) return; // l'app e' viva
            long now = System.currentTimeMillis();
            if (!restartAllowed(now, dir)) return;
            App.log("CRASHGUARD: processo principale era morto -> riavvio (notifica FSI)");
            sendRestartNotification(context);
        } catch (Exception e) {
            App.log("CRASHGUARD: errore tick " + e.getMessage());
        }
    }

    private static String readFile(File f) {
        if (!f.exists()) return "";
        FileReader r = null;
        try {
            r = new FileReader(f);
            char[] buf = new char[Math.max(1, (int) f.length())];
            int n = r.read(buf);
            return (n > 0 ? new String(buf, 0, n) : "").trim();
        } catch (Exception e) {
            return "";
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignored) {}
        }
    }

    private boolean restartAllowed(long now, File dir) {
        try {
            File state = new File(dir, "crashguard_state");
            int count = 0;
            long last = 0;
            String s = readFile(state);
            String[] parts = s.split(" ");
            if (parts.length == 2) {
                count = Integer.parseInt(parts[0]);
                last = Long.parseLong(parts[1]);
            }
            if (count >= MAX_RESTARTS && now - last < RESTART_WINDOW_COUNT_MS) {
                writeFile(state, "0 0");
                App.log("CRASHGUARD: troppi riavvii di recente, mi fermo (max " + MAX_RESTARTS + ")");
                return false;
            }
            if (now - last < MIN_RESTART_INTERVAL_MS) {
                App.log("CRASHGUARD: intervallo minimo tra riavvii non rispettato, attendo");
                return false;
            }
            writeFile(state, (count + 1) + " " + now);
            return true;
        } catch (Exception e) {
            App.log("CRASHGUARD: errore contatore " + e.getMessage());
            return true;
        }
    }

    private static void writeFile(File f, String content) {
        try {
            java.io.FileWriter fw = new java.io.FileWriter(f);
            fw.write(content);
            fw.close();
        } catch (Exception e) {
            // ignora
        }
    }
}
