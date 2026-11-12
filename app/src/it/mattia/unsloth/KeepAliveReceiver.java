package it.mattia.unsloth;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

// Riceve l'allarme orario e avvia il service di breve durata che rinnova
// il token di auth. Dichiarato nel manifest: cosi lo riceve anche se il
// processo era stato ucciso da Android.
public class KeepAliveReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        App.log("KEEPALIVE: allarme ricevuto");
        Intent i = new Intent(context, KeepAliveService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
            else context.startService(i);
        } catch (Exception e) {
            // Allarme con l'app in background (schermo spento): Android 12+
            // vieta startForegroundService dal receiver -> senza catch il
            // processo crasha. Salto il ciclo: al rientro onResume riprogramma
            // l'allarme e il heal di avvio recupera il token se serve.
            App.log("KEEPALIVE: avvio service fallito " + e.getMessage());
        }
    }
}
