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
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
        else context.startService(i);
    }
}
