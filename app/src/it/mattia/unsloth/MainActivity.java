package it.mattia.unsloth;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Environment;
import android.os.LocaleList;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.Manifest;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ArrayAdapter;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.drawerlayout.widget.DrawerLayout;

import java.io.File;
import java.io.FileReader;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final String PREFS = "unsloth";
    private static final int HIDE_SCROLL_Y = 100;
    private static final int SHOW_SCROLL_Y = 20;

    private DrawerLayout drawerLayout;
    private LinearLayout bar;
    private ScrollTrackingWebView webView;
    private EditText editHost;
    private EditText editPort;
    private ScrollView optionsPanel;
    private Switch switchScreenOn;
    private boolean mScreenOnEnabled = true;
    private LinearLayout errorPanel;
    private TextView errorText;
    private ScrollView logPanel;
    private TextView logText;
    private Switch switchLog;
    private Spinner spinnerLang;
    private View btnLogRow;
    private ScrollView creditsPanel;
    // Lingua scelta nelle opzioni: "auto" (sistema), "it", "en".
    private String mLang = "auto";
    // Context con le Resources configurate nella lingua scelta (se non auto):
    // l'android.jar di build non ha updateConfiguration, quindi uso
    // createConfigurationContext + override di getResources.
    private Context mLocaleContext;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        try {
            mLang = getSharedPreferences(PREFS, MODE_PRIVATE).getString("lang", "auto");
            if (mLang == null) mLang = "auto";
            if (!"auto".equals(mLang)) {
                Locale loc = "it".equals(mLang) ? Locale.ITALIAN : Locale.ENGLISH;
                Configuration c = base.getApplicationContext().getResources().getConfiguration();
                c.setLocale(loc);
                c.setLocales(new LocaleList(loc));
                mLocaleContext = base.createConfigurationContext(c);
            }
        } catch (Exception e) {
        }
    }

    @Override
    public android.content.res.Resources getResources() {
        if (mLocaleContext != null) return mLocaleContext.getResources();
        return super.getResources();
    }
    private View gestureStrip;
    private ImageView btnReload;
    private String currentUrl;
    private boolean mErrorActive = false;
    // Ultimo thread visto in un URL (persistito): la "ultima chat attiva".
    private volatile String mLastThread = null;
    private boolean mGenServiceStarting = false;
    // Finestra di "generazione recente": se un run e' partito da meno di GEN_WINDOW_MS,
    // non ri-sincronizziamo (niente svuotamento IndexedDB / reload) per non distruggere
    // la chat in corso. Robusto anche con piu' cicli onResume/screen-on dentro la finestra.
    private static final long GEN_WINDOW_MS = 10 * 60 * 1000L;
    private volatile long mGenStartMs = 0;
    // Timestamp del ultimo onPause: serve a capire se la generazione e' finita
    // MENTRE eravamo via (gen_done_ts > mSuspendTs) o prima (in quel caso il
    // check /active lo decide).
    private volatile long mSuspendTs = 0;
    // Ultimo resync "gen-finita": evita raddoppi se onResume riparte piu' volte.
    private volatile long mGenResyncTs = 0;

    private boolean isRecentGen() {
        return mGenStartMs != 0 && (System.currentTimeMillis() - mGenStartMs) < GEN_WINDOW_MS;
    }
    // All'avvio: aprire da solo il thread più recente dal server (una volta per connessione).
    private volatile boolean mAutoOpenPending = false;
    // Il thread vivo e' stato aperto o confermato da openLiveThread: evita di
    // rilanciare healAndOpenLive a ogni pushState della SPA.
    private volatile boolean mLiveOpened = false;
    // Un heal e' gia' in corso (lettura token con retry): anti-doppio heal.
    private volatile boolean mHealRunning = false;
    // Rientro in primo piano dopo essere stati in secondo piano (da ri-sincronizzare).
    private volatile boolean mWasSuspended = false;
    // L'attività è visibile (in primo piano).
    private volatile boolean mInForeground = false;
    // Lo schermo si è spento mentre l'app era in primo piano (always-on off).
    private volatile boolean mScreenOffWhileForeground = false;
    private BroadcastReceiver mScreenReceiver;
    private float mScrollY = 0;
    private float mSwipeStartY = -1;
    // Thread target arrivato dal tap sulla notifica (extra "threadId"):
    // all'avvio openLiveThread lo apre al posto del thread piu' recente.
    private volatile String mTargetThreadId = "";

    // v1.5: rilevazione cross-client dei run. Mentre l'app e' su una pagina
    // chat fa polling di /chat-runs/active?threadId=X (account-level): se nel
    // thread corrente c'e' un run attivo - anche partito da un altro client -
    // mostra il banner "elaborazione in corso", blocca l'invio e a runs vuoto
    // ri-sincronizza la chat dal server.
    private static final long RUN_POLL_MS = 3000;
    private volatile boolean mPollRunning = false;
    private volatile boolean mRunActive = false;
    private volatile String mSawActiveThread = "";
    private volatile long mLastAuthRefreshMs = 0;
    // URL della pagina WebView, aggiornata solo sui callback main-thread
    // (onPageStarted/Finished/CommitVisible e reportUrl). I thread di
    // background (run-poll, checkGenStatusAndResync) la leggono invece di
    // chiamare webView.getUrl(), che fuori dal thread UI lancia un'eccezione
    // (violation StrictMode WebViewMethodCalledOnWrongThread).
    private volatile String mPageUrl = "";
    private Thread mRunPollThread;
    private Button mBtnRetry;

    // Download blob: un solo job alla volta, chunk base64 dal WebView
    private static final int BLOB_CHUNK = 2 * 1024 * 1024;
    private String mBlobName;
    private String mBlobPendingName;
    private long mBlobTotal;
    private long mBlobReceived;
    private int mBlobChunkIndex;
    // Numero di versione mostrato nel drawer: incrementato a ogni build,
    // cosi Mattia sa sempre in quale versione è.
    static final String APP_VERSION = "1.5";

    private int mBlobChunkCount;
    private OutputStream mBlobOut;
    private Uri mBlobUri;
    private final ArrayList<String> mRecentDownloads = new ArrayList<>();
    private BroadcastReceiver mDownloadReceiver;
    private final java.util.HashMap<Long, String> mDownloadTitles = new java.util.HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        App.log("MainActivity.onCreate: inizio");
        super.onCreate(savedInstanceState);
        App.log("MainActivity.onCreate: super.onCreate ok");
        // Se l'avvio viene dal tap sulla notifica "generazione/risposta",
        // l'intent porta l'extra "threadId": all'avvio si apre quella chat.
        try {
            Intent li = getIntent();
            if (li != null) {
                String tt = li.getStringExtra("threadId");
                if (tt != null && !tt.isEmpty()) {
                    mTargetThreadId = tt;
                    App.log("OPEN-THREAD: target dall'intent " + tt);
                }
            }
        } catch (Exception e) {
        }
        // La lingua (se non "auto") e' gia' applicata in attachBaseContext:
        // getResources() restituisce le Resources della lingua scelta, quindi
        // il layout gonfiato qui sotto usa le stringhe (@string) giuste.
        try {
            setContentView(R.layout.activity_main);
            App.log("MainActivity.onCreate: setContentView ok");
        } catch (Exception e) {
            App.log("MainActivity.onCreate: setContentView FAILED: " + e.getMessage());
            throw e;
        }

        // Channel notifiche download
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel("downloads", getString(R.string.downloads_channel), NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription(getString(R.string.downloads_channel_desc));
            nm.createNotificationChannel(ch);
        }

        // Channel notifiche generazione: gen_done (DEFAULT) per la notifica
        // "risposta pronta", gen_keepalive per la notifica ongoing.
        GenService.ensureChannels(this);

        // Android 13+: il permesso POST_NOTIFICATIONS va chiesto a runtime,
        // altrimenti le notifiche non appaiono affatto.
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
            App.log("Notifiche: permesso POST_NOTIFICATIONS richiesto");
        }

        // Notifica quando un download DownloadManager completa
        mDownloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == -1) return;
                String title = mDownloadTitles.remove(id);
                if (title == null || title.isEmpty()) title = downloadTitleFromPrefs(id);
                if (title == null || title.isEmpty()) title = downloadTitleFromManager(id);
                if (title == null || title.isEmpty()) title = "file";
                App.log("DOWNLOAD completato: " + title);
                // URI e MIME del file, per aprire il file col tap sulla notifica
                Uri fileUri = null;
                String fileMime = null;
                try {
                    DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    if (dm != null) {
                        android.database.Cursor c = dm.query(new DownloadManager.Query().setFilterById(id));
                        if (c != null) {
                            try {
                                if (c.moveToFirst()) {
                                    int iu = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
                                    if (iu >= 0) {
                                        String s = c.getString(iu);
                                        if (s != null && !s.isEmpty()) fileUri = Uri.parse(s);
                                    }
                                    int im = c.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE);
                                    if (im >= 0) fileMime = c.getString(im);
                                }
                            } finally {
                                c.close();
                            }
                        }
                    }
                } catch (Exception e) {
                    App.log("download file uri FAILED: " + e.getMessage());
                }
                App.log("DOWNLOAD tap -> uri=" + (fileUri == null ? "NULL" : fileUri.toString()) + " mime=" + (fileMime == null ? "?" : fileMime));
                // Toast sempre (richiesto da Mattia) + notifica se il permesso è concesso
                Toast.makeText(context, getString(R.string.download_done, title), Toast.LENGTH_LONG).show();
                boolean notifOk = Build.VERSION.SDK_INT < 33
                        || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
                notifyDownload((int) id, title, getString(R.string.download_done_notif), false, openFilePendingIntent(fileUri, fileMime));
            }
        };
        // RECEIVER_EXPORTED: serve per ricevere il broadcast di completamento
        // del DownloadManager (viene da un'altra app); NOT_EXPORTED non lo riceve mai.
        registerReceiver(mDownloadReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED);

        // Rileva schermo spento/acceso: serve a capire il trigger dei timeout e
        // a ri-sincronizzare quando lo schermo torna acceso in primo piano.
        mScreenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String a = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                    App.log("SCREEN OFF (schermo spento)");
                    if (mInForeground) mScreenOffWhileForeground = true;
                } else if (Intent.ACTION_SCREEN_ON.equals(a)) {
                    App.log("SCREEN ON (schermo acceso)");
                    if (mInForeground && mScreenOffWhileForeground) {
                        mScreenOffWhileForeground = false;
                        if (!isRecentGen()) {
                            autoResync("screen-on");
                        } else {
                            App.log("RESYNC screen-on: generazione recente, salto");
                        }
                    }
                }
            }
        };
        IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(mScreenReceiver, screenFilter, Context.RECEIVER_NOT_EXPORTED);

        try {
            drawerLayout = findViewById(R.id.drawer_layout);
            bar = findViewById(R.id.bar);
            webView = findViewById(R.id.webview);
            App.log("MainActivity.onCreate: webView trovato");

            // File picker per input[type=file] (tasto allega della chat):
            // senza WebChromeClient.onShowFileChooser la WebView lo ignora in silenzio.
            webView.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onShowFileChooser(WebView wv, ValueCallback<Uri[]> callback,
                        android.webkit.WebChromeClient.FileChooserParams params) {
                    mFileCallback = callback;
                    try {
                        Intent intent = params.createIntent();
                        startActivityForResult(intent, REQ_FILE_CHOOSER);
                        App.log("FILE_CHOOSER: picker aperto");
                        return true;
                    } catch (Exception e) {
                        App.log("FILE_CHOOSER errore: " + e);
                        mFileCallback = null;
                        return false;
                    }
                }
            });
            WebSettings ws = webView.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setLoadWithOverviewMode(true);
            ws.setUseWideViewPort(true);
            // Niente cache HTTP: la pagina e le sue risposte API devono essere sempre fresche
            ws.setCacheMode(WebSettings.LOAD_NO_CACHE);

            // Download: http/https -> DownloadManager; blob -> estrazione via JS
            webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
                App.log("DOWNLOAD requested: url=" + url
                        + " disp=" + contentDisposition
                        + " mime=" + mimeType
                        + " len=" + contentLength);
                if (url == null) return;
                // Chromium chiama il listener due volte per lo stesso download:
                // ignora i duplicati entro 5 secondi
                String key = url + "|" + contentLength;
                long now = System.currentTimeMillis();
                synchronized (mRecentDownloads) {
                    mRecentDownloads.removeIf(s -> now - Long.parseLong(s.substring(s.lastIndexOf('|') + 1)) > 5000);
                    for (String s : mRecentDownloads) {
                        if (s.startsWith(key + "|")) return;
                    }
                    mRecentDownloads.add(key + "|" + now);
                }
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    startHttpDownload(url, contentDisposition, mimeType);
                } else if (url.startsWith("blob:")) {
                    // Fallback: l'intercettazione JS dovrebbe averlo gestito prima;
                    // se siamo qui, proviamo a estrarre dal blob URL
                    String name = nameFromDisposition(contentDisposition);
                    startBlobFromUrl(url, name != null ? name : "download.bin", mimeType);
                } else {
                    App.log("DOWNLOAD ignorato (schema non gestito): " + url);
                }
            });

            // Ponte JS per i download blob: la pagina ci passa il Blob e il nome
            webView.addJavascriptInterface(new JsBridge(), "UnslothBridge");

            editHost = findViewById(R.id.edit_host);
            editPort = findViewById(R.id.edit_port);
            // Porta predefinita 8888: se il campo resta vuoto viene usata,
            // e nel campo compare in grigio chiaro come suggerimento.
            editPort.setHint("8888");
            editPort.setHintTextColor(android.graphics.Color.LTGRAY);
            optionsPanel = findViewById(R.id.options_panel);
            switchScreenOn = findViewById(R.id.switch_screen_on);
            errorPanel = findViewById(R.id.error_panel);
            errorText = findViewById(R.id.error_text);
            logPanel = findViewById(R.id.log_panel);
            creditsPanel = findViewById(R.id.credits_panel);
            logText = findViewById(R.id.log_text);
            gestureStrip = findViewById(R.id.gesture_strip);
            ((TextView) findViewById(R.id.tv_version)).setText(APP_VERSION);
            App.log("MainActivity.onCreate: view trovati (versione " + APP_VERSION + ")");


            // Opzione schermo sempre acceso (default: on, comportamento attuale)
            mScreenOnEnabled = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("screen_on", true);
            switchScreenOn.setChecked(mScreenOnEnabled);
            switchScreenOn.setOnCheckedChangeListener((v, checked) -> {
                mScreenOnEnabled = checked;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("screen_on", checked).apply();
                if (checked) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
                App.log("OPZIONI schermo sempre acceso: " + (checked ? "on" : "off"));
            });

            // Opzione log on/off (default: on). Off = niente scrittura di
            // crash.log (il crash handler scrive sempre) e il pulsante Log
            // nel drawer scompare.
            final SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            boolean logEnabled = prefs.getBoolean("log_enabled", true);
            App.logEnabled = logEnabled;
            switchLog = findViewById(R.id.switch_log);
            btnLogRow = findViewById(R.id.btn_log);
            btnLogRow.setVisibility(logEnabled ? View.VISIBLE : View.GONE);
            switchLog.setChecked(logEnabled);
            switchLog.setOnCheckedChangeListener((v, checked) -> {
                App.logEnabled = checked;
                prefs.edit().putBoolean("log_enabled", checked).apply();
                btnLogRow.setVisibility(checked ? View.VISIBLE : View.GONE);
                App.log("OPZIONI log: " + (checked ? "on" : "off"));
            });

            // Selettore lingua: Automatica / Italiano / Inglese. Al cambio
            // salvo il pref e faccio recreate() per riapplicare la lingua
            // a tutto il layout.
            spinnerLang = findViewById(R.id.spinner_lang);
            ArrayAdapter<CharSequence> langAdapter = ArrayAdapter.createFromResource(
                    this, R.array.lang_options, android.R.layout.simple_spinner_item);
            langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinnerLang.setAdapter(langAdapter);
            int langPos = "it".equals(mLang) ? 1 : ("en".equals(mLang) ? 2 : 0);
            spinnerLang.setSelection(langPos);
            spinnerLang.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    String l = position == 1 ? "it" : (position == 2 ? "en" : "auto");
                    if (l.equals(mLang)) return; // selezione iniziale: niente recreate
                    mLang = l;
                    prefs.edit().putString("lang", l).apply();
                    App.log("OPZIONI lingua: " + l);
                    recreate();
                }
                @Override
                public void onNothingSelected(android.widget.AdapterView<?> parent) {
                }
            });

            // Download del log: FileProvider (gia' nel manifest) + intent
            // ACTION_SEND con chooser, cosi il file si allega a email/issue.
            View btnDownloadLog = findViewById(R.id.btn_download_log);
            btnDownloadLog.setOnClickListener(v -> shareLog());
        } catch (Exception e) {
            App.log("MainActivity.onCreate: findViewById FAILED: " + e.getMessage());
            throw e;
        }

        ImageView btnMenu = findViewById(R.id.btn_menu);
        btnMenu.setOnClickListener(v -> drawerLayout.openDrawer(Gravity.START));
        View btnChat = findViewById(R.id.btn_chat);
        btnChat.setOnClickListener(v -> {
            drawerLayout.closeDrawer(Gravity.START);
            if (currentUrl == null) return;
            if (webView.canGoBack()) {
                App.log("CHAT: goBack");
                mErrorActive = false;
                errorPanel.setVisibility(View.GONE);
                logPanel.setVisibility(View.GONE);
                creditsPanel.setVisibility(View.GONE);
                optionsPanel.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                updateBar();
                webView.goBack();
            } else {
                // Niente cronologia: torna alla pagina iniziale di Unsloth
                App.log("CHAT: niente cronologia -> home " + currentUrl);
                mErrorActive = false;
                errorPanel.setVisibility(View.GONE);
                logPanel.setVisibility(View.GONE);
                creditsPanel.setVisibility(View.GONE);
                optionsPanel.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                updateBar();
                webView.loadUrl(currentUrl);
            }
        });
        View btnRefresh = findViewById(R.id.btn_refresh);
        btnRefresh.setOnClickListener(v -> {
            drawerLayout.closeDrawer(Gravity.START);
            if (currentUrl == null) return;
            // Home: torna sempre alla pagina iniziale di Unsloth (URL base),
            // non alla pagina corrente
            webView.clearCache(true);
            mErrorActive = false;
            errorPanel.setVisibility(View.GONE);
            logPanel.setVisibility(View.GONE);
            creditsPanel.setVisibility(View.GONE);
            optionsPanel.setVisibility(View.GONE);
            webView.setVisibility(View.VISIBLE);
            updateBar();
            webView.loadUrl(currentUrl);
        });
        View btnSettings = findViewById(R.id.btn_settings);
        btnSettings.setOnClickListener(v -> {
            drawerLayout.closeDrawer(Gravity.START);
            openOptions();
        });
        View btnLog = findViewById(R.id.btn_log);
        btnLog.setOnClickListener(v -> {
            drawerLayout.closeDrawer(Gravity.START);
            showLog();
        });
        View btnCredits = findViewById(R.id.btn_credits);
        btnCredits.setOnClickListener(v -> {
            drawerLayout.closeDrawer(Gravity.START);
            openCredits();
        });

        // Icona di ricarica a destra nella barra: ricarica la pagina corrente
        // senza cache (tipo Ctrl+F5). Visibile solo dentro la chat/Unsloth
        // (la gestisce updateBar in base ai pannelli attivi).
        btnReload = findViewById(R.id.btn_reload);
        btnReload.setOnClickListener(v -> {
            if (webView.getVisibility() != View.VISIBLE) return;
            String u = webView.getUrl();
            if (u == null) return;
            App.log("RELOAD: pagina corrente senza cache " + u);
            webView.clearCache(true);
            webView.loadUrl(u);
        });

        Button btnConnect = findViewById(R.id.btn_connect);
        btnConnect.setOnClickListener(v -> connectFromSettings());
        mBtnRetry = (Button) findViewById(R.id.btn_retry);
        mBtnRetry.setOnClickListener(v -> {
            // Riprova usa l'indirizzo scritto nel campo Opzioni, se c'è uno
            // valido: cosi un indirizzo appena modificato (ma non ancora con
            // "Salva e connetti") viene provato al posto dell'ultimo.
            String h = editHost.getText().toString().trim();
            String p = editPort.getText().toString().trim();
            if (p.isEmpty()) p = "8888";
            if (!h.isEmpty() && p.matches("[0-9]+")
                    && Integer.parseInt(p) >= 1 && Integer.parseInt(p) <= 65535) {
                saveAndConnect(h, p);
            } else if (currentUrl != null) {
                mErrorActive = false;
                errorPanel.setVisibility(View.GONE);
                logPanel.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                updateBar();
                webView.loadUrl(currentUrl);
            }
        });

        // Barra che si nasconde quando scorri la pagina verso il basso
        webView.setOnScrollListener((newScrollX, newScrollY, oldScrollY, scrollYRange) -> {
            mScrollY = newScrollY;
            updateBar();
        });

        // Striscia trasparente in alto: swipe verso il basso per far riapparire la barra
        float density = getResources().getDisplayMetrics().density;
        gestureStrip.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mSwipeStartY = ev.getY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (mSwipeStartY >= 0 && ev.getY() - mSwipeStartY > 30 * density) {
                        showBar();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mSwipeStartY = -1;
                    return true;
            }
            return true;
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    return false;
                }
                return true;
            }

            // Diagnostica: logga ogni richiesta di rete per identificare l'API dei messaggi
            // del thread e la richiesta della generazione. Ritorna null = comportamento normale.
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    Uri u = request.getUrl();
                    String sc = u.getScheme();
                    if ("http".equalsIgnoreCase(sc) || "https".equalsIgnoreCase(sc)) {
                        App.log("REQ " + request.getMethod() + " " + u.toString());
                        String uStr = u.toString();
                        // Cattura l'id del thread corrente dalle richieste API
                        // (/api/chat/threads/<tid>/...): serve a GenService per
                        // monitorare il run nel thread giusto.
                        int ti = uStr.indexOf("/api/chat/threads/");
                        if (ti >= 0) {
                            String rest = uStr.substring(ti + "/api/chat/threads/".length());
                            int tj = rest.indexOf('/');
                            if (tj > 0) {
                                String tid = rest.substring(0, tj);
                                if (!tid.isEmpty() && !tid.equals("undefined")) {
                                    getSharedPreferences(PREFS, MODE_PRIVATE)
                                            .edit().putString("current_thread", tid).apply();
                                }
                            }
                        }
                        // Studio avvia una generazione con POST /api/inference/chat-runs (crea il run).
                        // Il polling dei token e' /chat-runs/<id>/events?after=N; il cancel e' /chat-runs/<id>/cancel.
                        if ("POST".equalsIgnoreCase(request.getMethod())
                                && uStr.contains("/api/inference/chat-runs")
                                && !uStr.contains("/events")
                                && !uStr.contains("/cancel")) {
                            mGenStartMs = System.currentTimeMillis();
                            // La generazione parte ORA: avvio GenService subito
                            // (notifica persistente "generazione in corso" e
                            // keep-alive anche se l'app va in background).
                            startGenServiceNow();
                        }
                    }
                } catch (Exception e) {
                    // ignora
                }
                return null;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                App.log("onReceivedError: mainFrame=" + request.isForMainFrame()
                        + " desc=" + (error != null && error.getDescription() != null ? error.getDescription() : "null"));
                if (request.isForMainFrame()) {
                    showError(error);
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                mPageUrl = url;
                injectBlobInterceptor();
                injectUrlReporter();
                injectComposerGuard();
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                mPageUrl = url;
                App.log("onPageCommitVisible: " + url);
                mScrollY = 0;
                if (!mErrorActive) {
                    errorPanel.setVisibility(View.GONE);
                }
                updateBar();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                mPageUrl = url;
                syncTokensFromPage();
                if (mAutoOpenPending && url != null && !url.contains("/login")) {
                    mAutoOpenPending = false;
                    healAndOpenLive();
                }
            }
        });

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        mLastThread = prefs.getString("last_thread", null);
        String host = prefs.getString("host", "");
        String port = prefs.getString("port", "8888");
        App.log("MainActivity.onCreate: prefs host=" + host + " port=" + port);
        if (!host.isEmpty()) {
            editHost.setText(host);
            editPort.setText(port);
            connect(host, port);
        } else {
            openOptions();
        }
        App.log("MainActivity.onCreate: FINE - app avviata con successo");
    }

    private void showBar() {
        bar.setVisibility(View.VISIBLE);
        gestureStrip.setVisibility(View.GONE);
    }

    private void hideBar() {
        bar.setVisibility(View.GONE);
        if (webView.getVisibility() == View.VISIBLE) {
            gestureStrip.setVisibility(View.VISIBLE);
        }
    }

    private void updateBar() {
        boolean webActive = webView.getVisibility() == View.VISIBLE
                && optionsPanel.getVisibility() != View.VISIBLE
                && errorPanel.getVisibility() != View.VISIBLE
                && logPanel.getVisibility() != View.VISIBLE;
        // Icona di ricarica: solo dentro la chat/Unsloth, non nei pannelli.
        if (btnReload != null) btnReload.setVisibility(webActive ? View.VISIBLE : View.GONE);
        if (webActive && mScrollY > HIDE_SCROLL_Y) {
            hideBar();
        } else {
            showBar();
        }
    }

    private void openOptions() {
        creditsPanel.setVisibility(View.GONE);
        optionsPanel.setVisibility(View.VISIBLE);
        webView.setVisibility(View.GONE);
        errorPanel.setVisibility(View.GONE);
        logPanel.setVisibility(View.GONE);
        updateBar();
        editHost.requestFocus();
    }

    private void connectFromSettings() {
        String host = editHost.getText().toString().trim();
        String port = editPort.getText().toString().trim();
        if (port.isEmpty()) port = "8888";
        if (host.isEmpty()) return;
        if (!port.matches("[0-9]+")) return;
        int p = Integer.parseInt(port);
        if (p < 1 || p > 65535) return;
        saveAndConnect(host, port);
    }

    private void saveAndConnect(String host, String port) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("host", host)
                .putString("port", port)
                .apply();
        connect(host, port);
    }

    private void connect(String host, String port) {
        String url;
        if (host.startsWith("http://") || host.startsWith("https://")) {
            url = host + (port.isEmpty() ? "" : ":" + port);
        } else {
            url = "http://" + host + ":" + port;
        }
        currentUrl = url;
        mAutoOpenPending = true;
        mErrorActive = false;
        mScrollY = 0;
        creditsPanel.setVisibility(View.GONE);
        optionsPanel.setVisibility(View.GONE);
        errorPanel.setVisibility(View.GONE);
        logPanel.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        updateBar();
        webView.loadUrl(url);
    }

    // All'avvio: verifica che il token di auth sia vivo. Se scade lo rinnova
    // col refresh token; se anche quello e morto svuota lo stato locale della
    // web view e porta alla schermata di login di Studio. Poi apre da solo il
    // thread più recentemente aggiornato dal server, cosi l'app non resta su
    // un thread vecchio/morto scelto dallo stato locale.
    // Legge i token di auth dal localStorage senza usare nomi esatti: il nome
    // reale della chiave potrebbe contenere caratteri invisibili (la lettura
    // con la stringa letterale torna vuota pur con la chiave presente). L'access
    // token e un JWT (preffisso eyJ), il refresh token sta nella chiave con
    // "refresh".
    private static final String JS_READ_TOKENS =
            "(function(){try{var t='',r='';for(var k in localStorage){if(k.length<10||k.length>60)continue;" +
            "var v=localStorage.getItem(k)||'';if(!v)continue;" +
            "if(v.indexOf('eyJ')===0&&t.length<v.length)t=v;else if(k.indexOf('refresh')>=0&&r.length<v.length)r=v;}" +
            "return {t:t,r:r};}catch(e){return {t:'' ,r:''}}})()";

    private void healAndOpenLive() {
        final String base = currentUrl;
        if (base == null || base.isEmpty()) return;
        if (mHealRunning) {
            App.log("AUTH-HEAL: gia' in corso, salto");
            return;
        }
        mHealRunning = true;
        String js = JS_READ_TOKENS;
        final String curUrl = webView.getUrl();
        // La SPA idrata il localStorage poco dopo onPageFinished: un primo
        // lettura vuota non significa sessione morta. Si rilegge con retry.
        readTokensWithRetry(js, 6, 2000L, (at, rt) -> {
            mHealRunning = false;
            long exp = jwtExpMs(at);
            long now = System.currentTimeMillis();
            if (!at.isEmpty() && exp > now + 5 * 60 * 1000L) {
                openLiveThread(base, at, rt, curUrl);
                return;
            }
            if (!at.isEmpty()) {
                // Token presente ma scaduto: rinnovo subito senza attendere.
                App.log("AUTH-HEAL: token scaduto (exp=" + exp + "), provo il refresh");
                tryRefresh(base, rt, this::afterHealOk, this::healFailed);
                return;
            }
            // Nessun token in pagina. Se nei prefs c'e un refresh salvato dal
            // keepalive lo uso; altrimenti NON svuoto lo stato locale: Studio
            // mostrera da solo la schermata di login se serve.
            String rtPrefs = getSharedPreferences(PREFS, MODE_PRIVATE).getString("auth_refresh", "");
            if (!rtPrefs.isEmpty()) {
                App.log("AUTH-HEAL: nessun token in pagina, provo il refresh dai prefs");
                tryRefresh(base, rtPrefs, this::afterHealOk, this::healFailed);
            } else {
                App.log("AUTH-HEAL: nessun token e nessun refresh disponibile: non tocco lo stato locale");
            }
        });
    }

    // Legge i token dalla pagina con retry (maxTries letture, delayMs tra una
    // e l'altra). Chiama il callback appena trova un access token non vuoto,
    // oppure al termine del ciclo con la coppia vuota.
    private void readTokensWithRetry(final String js, final int maxTries, final long delayMs,
                                     TokenPairCallback cb) {
        final android.os.Handler h = new android.os.Handler(getMainLooper());
        final int[] n = {0};
        final Runnable[] holder = new Runnable[1];
        holder[0] = new Runnable() {
            @Override
            public void run() {
                n[0]++;
                webView.evaluateJavascript(js, value -> {
                    String at = "", rt = "";
                    try {
                        org.json.JSONObject j = new org.json.JSONObject(value == null ? "{}" : value);
                        at = j.optString("t");
                        rt = j.optString("r");
                    } catch (Exception ignored) {
                    }
                    if (!at.isEmpty()) {
                        cb.onTokens(at, rt);
                        return;
                    }
                    if (n[0] < maxTries) {
                        App.log("AUTH-HEAL: lettura " + n[0] + " senza token, riprovo tra " + delayMs + " ms");
                        h.postDelayed(holder[0], delayMs);
                    } else {
                        cb.onTokens("", "");
                    }
                });
            }
        };
        holder[0].run();
    }

    // Interroga il server e apre da solo il thread più recentemente aggiornato.
    private void openLiveThread(final String base, final String token, final String rt, final String curUrl) {
        new Thread(() -> {
            try {
                java.net.URL u = new java.net.URL(base + "/api/chat/threads");
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();
                c.setRequestProperty("Authorization", "Bearer " + token);
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                int code = c.getResponseCode();
                if (code != 200) {
                    App.log("LIVE-THREAD: HTTP " + code);
                    if (code == 401 && rt != null && !rt.isEmpty()) {
                        // Token ruotato dal server (vivo per exp ma non accettato):
                        // rinnovo col refresh token e riprovo dopo il reload.
                        tryRefresh(base, rt, this::afterHealOk, this::healFailed);
                    }
                    return;
                }
                java.io.InputStream in = c.getInputStream();
                StringBuilder sb = new StringBuilder();
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(in, "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                org.json.JSONObject root = new org.json.JSONObject(sb.toString());
                org.json.JSONArray arr = root.optJSONArray("threads");
                if (arr == null || arr.length() == 0) { App.log("LIVE-THREAD: lista thread vuota"); return; }
                String bestId = null;
                double bestTs = -1;
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject t = arr.optJSONObject(i);
                    if (t == null) continue;
                    String id = t.optString("id");
                    if (id.isEmpty()) continue;
                    double ts = threadTs(t, "updatedAt");
                    if (ts < 0) ts = threadTs(t, "createdAt");
                    if (ts > bestTs) { bestTs = ts; bestId = id; }
                }
                if (bestId == null) { App.log("LIVE-THREAD: nessun thread valido"); return; }
                // Arrivato dal tap sulla notifica: apre il thread target
                // (extra "threadId") al posto del piu' recente.
                String targetId = bestId;
                if (mTargetThreadId != null && !mTargetThreadId.isEmpty()) {
                    boolean found = false;
                    for (int i = 0; i < arr.length(); i++) {
                        org.json.JSONObject t2 = arr.optJSONObject(i);
                        if (t2 != null && mTargetThreadId.equals(t2.optString("id"))) { found = true; break; }
                    }
                    if (found) {
                        targetId = mTargetThreadId;
                        App.log("LIVE-THREAD: uso il target dall'intent " + targetId);
                    } else {
                        App.log("LIVE-THREAD: target non in lista, uso il piu' recente");
                    }
                }
                mTargetThreadId = "";
                String curId = null;
                if (curUrl != null && curUrl.contains("thread=")) {
                    int p = curUrl.indexOf("thread=") + 7;
                    int q = curUrl.indexOf('&', p);
                    String raw = (q >= 0) ? curUrl.substring(p, q) : curUrl.substring(p);
                    try { curId = java.net.URLDecoder.decode(raw, "UTF-8"); } catch (Exception e) { curId = raw; }
                }
                App.log("LIVE-THREAD: target id=" + targetId + " ts=" + bestTs + " | attuale=" + curId);
                if (targetId.equals(curId)) {
                    App.log("LIVE-THREAD: gia' sul thread target");
                    mLiveOpened = true;
                    return;
                }
                final String fid = targetId;
                runOnUiThread(() -> {
                    try {
                        String target = base + "/chat?thread=" + java.net.URLEncoder.encode(fid, "UTF-8");
                        App.log("LIVE-THREAD: apri " + target);
                        mLiveOpened = true;
                        webView.loadUrl(target);
                    } catch (Exception e) { App.log("LIVE-THREAD: errore nav " + e.getMessage()); }
                });
            } catch (Exception e) {
                App.log("LIVE-THREAD: errore " + e.getMessage());
            }
        }).start();
    }

    // Scadenza (ms epoch) del payload JWT di un token; -1 se non leggibile.
    private static long jwtExpMs(String token) {
        try {
            String[] p = token.split("\\.");
            if (p.length != 3) return -1;
            byte[] d = java.util.Base64.getUrlDecoder().decode(p[1]);
            org.json.JSONObject j = new org.json.JSONObject(new String(d, "UTF-8"));
            long s = j.optLong("exp", -1);
            return s > 0 ? s * 1000L : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    interface TokenPairCallback { void onTokens(String at, String rt); }

    // Rinnova il token: prova prima il refresh token della web view, poi quello
    // salvato nei prefs dal keepalive. Su successo scrive i nuovi token nel
    // localStorage e nei prefs e ricarica la pagina; su fallimento mostra il login.
    private void tryRefresh(final String base, final String rtLocal,
                           TokenPairCallback ok, Runnable fail) {
        java.util.List<String> rts = new java.util.ArrayList<>();
        if (rtLocal != null && !rtLocal.isEmpty()) rts.add(rtLocal);
        String rtPrefs = getSharedPreferences(PREFS, MODE_PRIVATE).getString("auth_refresh", "");
        if (rtPrefs != null && !rtPrefs.isEmpty() && !rts.contains(rtPrefs)) rts.add(rtPrefs);
        if (rts.isEmpty()) {
            App.log("AUTH-HEAL: nessun refresh token disponibile");
            runOnUiThread(fail::run);
            return;
        }
        new Thread(() -> {
            for (String rt : rts) {
                try {
                    java.net.URL u = new java.net.URL(base + "/api/auth/refresh");
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();
                    c.setRequestMethod("POST");
                    c.setRequestProperty("Content-Type", "application/json");
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(20000);
                    c.setDoOutput(true);
                    c.getOutputStream().write(("{\"refresh_token\":\"" + rt + "\"}").getBytes("UTF-8"));
                    int code = c.getResponseCode();
                    if (code != 200) { App.log("AUTH-HEAL: refresh HTTP " + code); continue; }
                    java.io.InputStream in = c.getInputStream();
                    StringBuilder sb = new StringBuilder();
                    java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(in, "UTF-8"));
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    org.json.JSONObject j = new org.json.JSONObject(sb.toString());
                    String at = j.optString("access_token");
                    String nrt = j.optString("refresh_token");
                    if (at.isEmpty() || nrt.isEmpty()) { App.log("AUTH-HEAL: 200 senza token"); continue; }
                    App.log("AUTH-HEAL: refresh ok, nuovo token emesso");
                    runOnUiThread(() -> ok.onTokens(at, nrt));
                    return;
                } catch (Exception e) {
                    App.log("AUTH-HEAL: refresh errore " + e.getMessage());
                }
            }
            runOnUiThread(fail::run);
        }).start();
    }

    // Refresh riuscito: nuovi token in prefs e in localStorage, poi ricarico la
    // pagina; al prossimo onPageFinished si apre da solo il thread più recente.
    private void afterHealOk(String at, String rt) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("auth_access", at).putString("auth_refresh", rt)
                .putLong("auth_synced_ts", System.currentTimeMillis()).apply();
        String js = "(function(){try{localStorage.setItem('unsloth_auth_token','" + at + "');" +
                "localStorage.setItem('unsloth_auth_refresh_token','" + rt + "');}catch(e){}})()";
        webView.evaluateJavascript(js, null);
        mAutoOpenPending = true;
        webView.clearCache(true);
        App.log("AUTH-HEAL: token scritto, ricarico la pagina");
        webView.loadUrl(currentUrl);
    }

    // Refresh fallito (sessione morta): svuoto localStorage e IndexedDB della
    // web view, cosi Studio non mostra piu la copia locale stantia, e vado
    // alla schermata di login. Dopo il login l'app apre da sola il thread vivo.
    private void healFailed() {
        App.log("AUTH-HEAL: refresh fallito, svuoto lo stato locale e vado al login");
        String js = "(function(){" +
                "try{localStorage.removeItem('unsloth_auth_token');}catch(e){}" +
                "try{localStorage.removeItem('unsloth_auth_refresh_token');}catch(e){}" +
                "function go(){try{location.href='/login';}catch(e){}}" +
                "if(!window.indexedDB||!indexedDB.deleteDatabase){go();return;}" +
                "if(!indexedDB.databases){var d2=indexedDB.deleteDatabase('unsloth-chat');" +
                "d2.onsuccess=function(){go();};d2.onerror=function(){go();};return;}" +
                "indexedDB.databases().then(function(list){" +
                "var names=list?list.map(function(d){return d.name;}):[];" +
                "var pending=0,done=function(){if(--pending===0)go();};" +
                "for(var i=0;i<names.length;i++){pending++;var del=indexedDB.deleteDatabase(names[i]);" +
                "del.onsuccess=done;del.onerror=function(){done();};del.onblocked=function(){done();};}" +
                "}).catch(function(e){go();});" +
                ")})();";
        webView.clearCache(true);
        mAutoOpenPending = true;
        webView.evaluateJavascript(js, null);
    }

    // A ogni pagina carica: salvo nei prefs i token correnti della web view,
    // cosi il keepalive ha sempre il refresh token più fresco.
    private void syncTokensFromPage() {
        // DEBUG temporaneo (v32b): elenca tutte le chiavi localStorage con la
        // lunghezza del valore, per capire cosa vede davvero la web view.
        String js = JS_READ_TOKENS;
        webView.evaluateJavascript(js, value -> {
            if (value == null || value.equals("null")) {
                App.log("SYNC-TOKENS: evaluateJavascript senza valore");
                return;
            }
            try {
                org.json.JSONObject j = new org.json.JSONObject(value);
                String at = j.optString("t");
                String rt = j.optString("r");
                if (at.isEmpty() || rt.isEmpty()) {
                    App.log("SYNC-TOKENS: nessun token in pagina");
                    return;
                }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("auth_access", at).putString("auth_refresh", rt)
                        .putLong("auth_synced_ts", System.currentTimeMillis()).apply();
                App.log("SYNC-TOKENS: salvati nei prefs");
            } catch (Exception ignored) {
            }
        });
    }

    // Programma l'allarme orario del keepalive (una volta ogni 50 minuti):
    // sveglia il processo per pochi secondi, un solo POST al server.
    private void scheduleKeepAlive() {
        try {
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(this, KeepAliveReceiver.class);
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(this, 1, i,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_CANCEL_CURRENT
                            | android.app.PendingIntent.FLAG_IMMUTABLE);
            long when = System.currentTimeMillis() + 50 * 60 * 1000L;
            try {
                if (Build.VERSION.SDK_INT >= 31) am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, when, pi);
                else am.setExact(android.app.AlarmManager.RTC_WAKEUP, when, pi);
            } catch (SecurityException e) {
                am.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        android.os.SystemClock.elapsedRealtime() + 50 * 60 * 1000L, pi);
                App.log("KEEPALIVE: allarme impreciso (permesso exact assente)");
            }
            App.log("KEEPALIVE: allarme programmato tra 50 min");
        } catch (Exception e) {
            App.log("KEEPALIVE: scheduling errore " + e.getMessage());
        }
    }

    private static double threadTs(org.json.JSONObject t, String key) {
        if (!t.has(key)) return -1;
        Object o = t.opt(key);
        if (o instanceof Number) return ((Number) o).doubleValue();
        String s = String.valueOf(o);
        if (s == null || s.isEmpty() || "null".equals(s)) return -1;
        try { return Double.parseDouble(s); } catch (Exception e) { /* non numerico */ }
        String[] pats = {"yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss"};
        for (String p : pats) {
            try {
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(p, java.util.Locale.US);
                return f.parse(s).getTime();
            } catch (Exception e) { /* prova il pattern successivo */ }
        }
        return -1;
    }

    private void showError(WebResourceError error) {
        String desc;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && error != null && error.getDescription() != null) {
            desc = error.getDescription().toString();
        } else {
            desc = "connessione rifiutata";
        }
        errorText.setText(getString(R.string.error_cannot_reach, currentUrl, desc));
        mErrorActive = true;
        webView.setVisibility(View.GONE);
        creditsPanel.setVisibility(View.GONE);
        optionsPanel.setVisibility(View.GONE);
        logPanel.setVisibility(View.GONE);
        errorPanel.setVisibility(View.VISIBLE);
        updateBar();
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout.isDrawerOpen(Gravity.START)) {
            drawerLayout.closeDrawer(Gravity.START);
            return;
        }
        if (creditsPanel.getVisibility() == View.VISIBLE) {
            hideCredits();
            return;
        }
        if (logPanel.getVisibility() == View.VISIBLE) {
            hideLog();
            return;
        }
        if (optionsPanel.getVisibility() == View.VISIBLE) {
            finish();
            return;
        }
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mInForeground = true;
        App.log("onResume (primo piano)");
        if (mScreenOnEnabled) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        // Rientro in primo piano dopo essere stati in secondo piano.
        // Se c'era una generazione in corso NON ri-sincronizziamo (niente reload
        // e niente svuotamento IndexedDB): altrimenti distruggiamo la chat in
        // corso e al rientro si torna alla home con "retry". La pagina WebView
        // conserva il suo stato; la generazione, se viva, e' gia' mostrata o
        // continua a streaming. Ricarico solo se non stavo generando.
        boolean wasGenerating = isRecentGen();
        if (mWasSuspended) {
            mWasSuspended = false;
            long doneTs = 0;
            try {
                doneTs = getSharedPreferences(PREFS, MODE_PRIVATE).getLong("gen_done_ts", 0);
            } catch (Exception e) {
            }
            if (!wasGenerating) {
                // Niente resync automatico al rientro: resto sulla chat senza
                // ricaricarla. Se serve a ricaricare c'e' il tasto refresh
                // nella barra in alto.
                App.log("onResume: niente resync, resto sulla chat");
            } else if (doneTs > mSuspendTs) {
                // Il service ha segnato il completamento MENTRE eravamo via:
                // ricarico la chat dal server cosi telefono e PC mostrano gli
                // stessi messaggi.
                genFinitaResync();
            } else {
                // Race coperta: il POST chat-runs puo' essere arrivato in
                // background dopo onPause, quando startGenServiceNow non poteva
                // partire il FGS (ForegroundServiceStartNotAllowedException).
                // Al rientro l'app e' in primo piano: riprovo l'avvio del service
                // e poi verifico lo stato della generazione.
                startGenServiceNow();
                checkGenStatusAndResync();
            }
        }
        startRunPolling();
        scheduleKeepAlive();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mInForeground = false;
        mWasSuspended = true;
        mSuspendTs = System.currentTimeMillis();
        App.log("onPause (secondo piano)");
        if (isRecentGen()) {
            Intent gi = new Intent(this, GenService.class);
            try {
                String url = webView.getUrl();
                if (url != null) {
                    Uri u = Uri.parse(url);
                    String t = u.getQueryParameter("thread");
                    if (t != null) gi.putExtra("threadId", t);
                }
            } catch (Exception e) {
                // ignora
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(gi); else startService(gi);
        }
    }

    // App gia' aperta (in primo piano): il tap sulla notifica "generazione"
    // o "risposta pronta" arriva qui. Naviga la WebView alla chat del thread.
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        try {
            if (intent != null && currentUrl != null) {
                String tt = intent.getStringExtra("threadId");
                if (tt != null && !tt.isEmpty()) {
                    final String fid = tt;
                    App.log("OPEN-THREAD: onNewIntent target " + fid);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                String target = currentUrl + "/chat?thread="
                                        + java.net.URLEncoder.encode(fid, "UTF-8");
                                mErrorActive = false;
                                errorPanel.setVisibility(View.GONE);
                                logPanel.setVisibility(View.GONE);
                                creditsPanel.setVisibility(View.GONE);
                                optionsPanel.setVisibility(View.GONE);
                                webView.setVisibility(View.VISIBLE);
                                updateBar();
                                webView.loadUrl(target);
                            } catch (Exception e) {
                                App.log("OPEN-THREAD: errore nav " + e.getMessage());
                            }
                        }
                    });
                }
            }
        } catch (Exception e) {
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        App.log("onStop (app in background)");
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // Ri-sincronizza la chat corrente dal server: svuota la cache HTTP e il
    // database IndexedDB "unsloth-chat-background" (copia locale della storia
    // che Studio tiene nella web view) e ricarica la pagina. Stesso flusso
    // della Ricarica, ma senza re-target del thread perché l'URL lo porta già.
    // Avvia il keep-alive (foreground service) quando Studio invia la richiesta
    // di completamento: mantiene la generazione attiva anche con l'app in
    // background. Il token si legge dal localStorage della web view.
    private void startGenKeepAlive(final String base) {
        if (base == null || base.isEmpty()) return;
        if (mGenServiceStarting) return;
        mGenServiceStarting = true;
        String js = "(function(){var t=null;try{t=localStorage.getItem('unsloth_auth_token')||localStorage.getItem('unsloth_token')||localStorage.getItem('token');}catch(e){}if(!t){try{var m=/unsloth_auth_token=([^;]+)/.exec(document.cookie);if(m)t=m[1];}catch(e){}return t||'';}return ''})()";
        webView.evaluateJavascript(js, value -> {
            mGenServiceStarting = false;
            final String token = (value != null && !value.equals("null")) ? value : "";
            if (token.isEmpty()) {
                App.log("GEN: token assente, keep-alive non avviato");
                return;
            }
            App.log("GEN: avvio keep-alive host=" + base);
            Intent i = new Intent(MainActivity.this, GenService.class);
            i.putExtra("host", base);
            i.putExtra("token", token);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        });
    }

    // Parte GenService nel momento esatto in cui Studio crea il run
    // (POST /api/inference/chat-runs intercettato qui sopra). La notifica
    // persistente "generazione in corso" appare subito e il service mantiene
    // la generazione viva anche con l'app in background e schermo spento.
    // Host e token li legge GenService dai prefs (aggiornati da
    // syncTokensFromPage e dal refresh di KeepAliveService); qui passiamo
    // solo il threadId dell'URL corrente.
    private void startGenServiceNow() {
        try {
            Intent gi = new Intent(this, GenService.class);
            gi.putExtra("threadId", currentThreadId());
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(gi); else startService(gi);
            App.log("GEN: avvio dal POST chat-runs (thread=" + currentThreadId() + ")");
        } catch (Exception e) {
            // Es. ForegroundServiceStartNotAllowedException se l'app e' gia'
            // in background: il fallback resta l'avvio in onPause.
            App.log("GEN: avvio dal POST fallito " + e.getMessage());
        }
    }

    private String currentThreadId() {
        // mPageUrl (cache main-thread): chiamare webView.getUrl() da un
        // thread di background lancia un'eccezione su alcuni Android.
        try {
            String url = mPageUrl;
            if (url != null && !url.isEmpty()) {
                Uri u = Uri.parse(url);
                String t = u.getQueryParameter("thread");
                if (t != null && !t.isEmpty()) return t;
            }
        } catch (Exception e) {}
        // Fallback: thread catturato dalle richieste API nel interceptor.
        try {
            return getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString("current_thread", "");
        } catch (Exception e) {
            return "";
        }
    }

    private void genFinitaResync() {
        if (System.currentTimeMillis() - mGenResyncTs < 30 * 1000L) return;
        mGenResyncTs = System.currentTimeMillis();
        App.log("SYNC: generazione finita mentre eravamo via, resync dal server");
        autoResync("gen-finita-bg");
    }

    // Lettura dal server sul PC: con il token nei prefs interrogo
    // /chat-runs/active per il thread corrente. Se la generazione recente e'
    // finita mentre eravamo via (runs vuoto), ricarico la chat cosi telefono e
    // PC mostrano gli stessi messaggi; se e' ancora attiva, Studio si
    // riconnette da solo allo stream e salto.
    private void checkGenStatusAndResync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
                    String host = p.getString("host", "");
                    String port = p.getString("port", "8888");
                    String tok = p.getString("auth_access", "");
                    if (host.isEmpty() || tok.isEmpty()) {
                        App.log("SYNC: host o token assenti, salto");
                        return;
                    }
                    String base = (host.startsWith("http://") || host.startsWith("https://"))
                            ? host + (port.isEmpty() ? "" : ":" + port)
                            : "http://" + host + ":" + port;
                    final String tid = currentThreadId();
                    java.net.URL u = new java.net.URL(base + "/api/inference/chat-runs/active?threadId="
                            + java.net.URLEncoder.encode(tid, "UTF-8"));
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();
                    c.setRequestProperty("Authorization", "Bearer " + tok);
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(20000);
                    int code = c.getResponseCode();
                    if (code != 200) {
                        App.log("SYNC: HTTP " + code + ", salto");
                        return;
                    }
                    java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    org.json.JSONObject j = new org.json.JSONObject(sb.toString());
                    org.json.JSONArray runs = j.optJSONArray("runs");
                    final boolean active = runs != null && runs.length() > 0;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (active) {
                                App.log("SYNC: generazione ancora attiva, Studio si riconnette");
                            } else {
                                // Generazione finita mentre eravamo via e il service
                                // non l'ha mai segnata (non era partito): mando la
                                // notifica "risposta pronta" e ri-sincronizzo.
                                App.log("SYNC: generazione finita senza notifica, postResponseReady");
                                GenService.postResponseReady(MainActivity.this, currentThreadId(), "");
                                genFinitaResync();
                            }
                        }
                    });
                } catch (Exception e) {
                    App.log("SYNC: errore " + e.getMessage());
                }
            }
        }).start();
    }

    private void autoResync(String why) {
        String pageUrl = webView.getUrl();
        if (pageUrl == null || !pageUrl.contains("chat")) {
            App.log("RESYNC auto (" + why + "): non su una chat, salto");
            return;
        }
        App.log("RESYNC auto (" + why + "): " + pageUrl);
        webView.clearCache(true);
        updateBar();
        String js = "(function(){" +
                "function finish(ok){try{location.reload();}catch(e){}}" +
                "if(!window.indexedDB||!indexedDB.deleteDatabase){finish(false);return;}" +
                "if(!indexedDB.databases){" +
                "var d2=indexedDB.deleteDatabase('unsloth-chat');" +
                "d2.onsuccess=function(){finish(true);};d2.onerror=function(){finish(false);};return;}" +
                "indexedDB.databases().then(function(list){" +
                "if(!list||list.length===0){finish(true);return;}" +
                "var names=list.map(function(d){return d.name;});" +
                "try{UnslothBridge.appLog('RESYNC db: '+names.join(', '));}catch(e){}" +
                "var pending=0,done=function(){if(--pending===0)finish(true);};for(var i=0;i<names.length;i++){" +
                "pending++;var del=indexedDB.deleteDatabase(names[i]);" +
                "del.onsuccess=done;del.onerror=function(e){done();};del.onblocked=function(){done();};" +
                "}}).catch(function(e){finish(false);});" +
                "})();";
        webView.evaluateJavascript(js, null);
    }

    // v1.5: polling nativo di /chat-runs/active?threadId=X (stesso endpoint e
    // stesso stile di checkGenStatusAndResync, ma continuo). Rileva i run del
    // thread corrente partiti da un ALTRO client: mostra il banner, blocca
    // l'invio e a runs vuoto ri-sincronizza la chat. I run avviati da questo
    // telefono (isRecentGen) non vengono bloccati: il UI di Studio li mostra
    // gia' in corso e l'input resta libero (coda messaggi come al solito).
    private void startRunPolling() {
        if (mPollRunning) return;
        mPollRunning = true;
        mRunPollThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (mPollRunning) {
                    try {
                        pollActiveRuns();
                    } catch (Exception e) {
                        App.log("RUN-POLL errore: " + e.getMessage());
                    }
                    try {
                        Thread.sleep(RUN_POLL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "run-poll");
        mRunPollThread.start();
    }

    private void stopRunPolling() {
        mPollRunning = false;
        if (mRunPollThread != null) mRunPollThread.interrupt();
    }

    private void pollActiveRuns() {
        // mPageUrl, non webView.getUrl(): questo gira su un thread di
        // background e gli accessi WebView fuori dal thread UI lancia.
        String url = mPageUrl;
        if (url == null || !url.contains("chat")) return;
        final String tid = currentThreadId();
        if (tid.isEmpty()) return;
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String host = p.getString("host", "");
        String port = p.getString("port", "8888");
        String tok = p.getString("auth_access", "");
        if (host.isEmpty() || tok.isEmpty()) return;
        String base = (host.startsWith("http://") || host.startsWith("https://"))
                ? host + (port.isEmpty() ? "" : ":" + port)
                : "http://" + host + ":" + port;
        java.net.HttpURLConnection c = null;
        try {
            java.net.URL u = new java.net.URL(base + "/api/inference/chat-runs/active?threadId="
                    + java.net.URLEncoder.encode(tid, "UTF-8"));
            c = (java.net.HttpURLConnection) u.openConnection();
            c.setRequestProperty("Authorization", "Bearer " + tok);
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            int code = c.getResponseCode();
            if (code == 401) {
                // Token scaduto: rinnovo (max una volta al minuto) e salto il giro.
                if (System.currentTimeMillis() - mLastAuthRefreshMs > 60 * 1000L) {
                    mLastAuthRefreshMs = System.currentTimeMillis();
                    refreshAuthTokens(base);
                }
                return;
            }
            if (code != 200) return;
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            org.json.JSONObject j = new org.json.JSONObject(sb.toString());
            org.json.JSONArray runs = j.optJSONArray("runs");
            final boolean active = runs != null && runs.length() > 0;
            if (active) {
                if (isRecentGen()) {
                    // Run avviato da questo telefono: il UI di Studio lo mostra
                    // gia' in corso, niente blocco: l'input resta libero e i
                    // messaggi si mettono in coda come al solito.
                    if (mRunActive) {
                        mRunActive = false;
                        mSawActiveThread = "";
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                setGenBanner(false);
                                lockComposer(false);
                            }
                        });
                    }
                } else {
                    // Run partito da un altro client: banner + blocco invio.
                    mSawActiveThread = tid;
                    if (!mRunActive) {
                        mRunActive = true;
                        App.log("RUN-POLL: run cross-client attivo nel thread " + tid);
                    }
                    // Banner e lock a ogni tick (idempotenti): se la prima
                    // iniezione fallisce (pagina ancora in caricamento, JS
                    // null) i tick successivi la ripetono; se Studio
                    // re-render elimina il div, il tick dopo lo reinietta.
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setGenBanner(true);
                            lockComposer(true);
                        }
                    });
                }
            } else if (mRunActive && !tid.equals(mSawActiveThread)) {
                // Cambio di thread con un run attivo in un altro: azzero banner
                // e lock, senza resync (il run non riguarda questa chat).
                mRunActive = false;
                mSawActiveThread = "";
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setGenBanner(false);
                        lockComposer(false);
                    }
                });
            } else if (mRunActive) {
                // Run finito nel thread corrente: sblocco e, se il run non era
                // partito da questa app (quindi Studio non lo ha mai "visto"),
                // ri-sincronizzo la chat dal server.
                mRunActive = false;
                mSawActiveThread = "";
                App.log("RUN-POLL: run finito, resync");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setGenBanner(false);
                        lockComposer(false);
                        String u2 = webView.getUrl();
                        if (u2 != null && u2.contains(tid) && !isRecentGen()) genFinitaResync();
                    }
                });
            }
        } catch (Exception e) {
            App.log("RUN-POLL errore: " + e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // Rinnova i token nei prefs via /api/auth/refresh (stesso flusso di
    // GenService): il refresh token e' single-use, quindi salvo quello nuovo.
    private void refreshAuthTokens(String base) {
        try {
            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            String rt = p.getString("auth_refresh", "");
            if (rt.isEmpty()) return;
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL(base + "/api/auth/refresh").openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setDoOutput(true);
            c.getOutputStream().write(("{\"refresh_token\":\"" + rt + "\"}").getBytes("UTF-8"));
            if (c.getResponseCode() != 200) {
                App.log("RUN-POLL: refresh token fallito");
                return;
            }
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            org.json.JSONObject j = new org.json.JSONObject(sb.toString());
            String at = j.optString("access_token");
            String nrt = j.optString("refresh_token");
            if (at.isEmpty()) return;
            p.edit().putString("auth_access", at).putString("auth_refresh", nrt)
                    .putLong("auth_synced_ts", System.currentTimeMillis()).apply();
            App.log("RUN-POLL: token rinnovato");
        } catch (Exception e) {
            App.log("RUN-POLL: refresh errore " + e.getMessage());
        }
    }

    // Banner "risposta in generazione": iniettato nel DOM di Studio come
    // figlio del box composer (.unsloth-composer-surface), stabile ai
    // re-render di Studio durante lo streaming (un antenato piu' alto viene
    // riconciliato da React e fa sparire il div). Larga uguale alla card
    // "2 runtime updates" di Studio: larghezza viewport meno 2rem, centrata
    // sulla viewport dei messaggi (.aui-thread-viewport); se assente, sul
    // documento. Offset left/right relativi al rect del composer: responsivo
    // per costruzione, ricalcolato a ogni tick. Copre tutta
    // l'area input (testo + tasto invio) con gradiente verde e spinner in
    // rotazione. pointer-events:none: non intercetta i tocchi, la chat resta
    // visibile e scorribile; il blocco dell'invio lo fa lockComposer via JS.
    // Essendo nel DOM resta sotto la sidebar di Studio e sotto i pannelli
    // nativi (drawer, Opzioni, Log, Credits). Re-iniettato a ogni tick del
    // poller in caso di re-render di Studio.
    private void setGenBanner(boolean show) {
        if (mBtnRetry != null) mBtnRetry.setEnabled(!show);
        try {
            String js;
            if (show) {
                String main = jsEsc(getString(R.string.gen_main));
                String sub = jsEsc(getString(R.string.gen_wait));
                js = "(function(){"
                        + "if(!window.__unslothGenStyle){"
                        + "var st=document.createElement('style');"
                        + "st.textContent='#unsloth-gen-banner{position:absolute;z-index:50;"
                        + "display:flex;align-items:center;justify-content:center;gap:12px;"
                        + "border-radius:var(--radius-3xl,18px);"
                        + "background:linear-gradient(180deg,#00C98F,#00A371);"
                        + "border:1px solid rgba(255,255,255,.28);opacity:0;"
                        + "transition:opacity .2s ease;pointer-events:none}"
                        + "#unsloth-gen-banner.on{opacity:1}"
                        + "@keyframes unslothGenSpin{to{transform:rotate(360deg)}}"
                        + ".unsloth-gen-spin svg{animation:unslothGenSpin .9s linear infinite}"
                        + ".unsloth-gen-txt{display:flex;flex-direction:column}"
                        + ".unsloth-gen-main{color:#fff;font-weight:700;font-size:18px;line-height:1.25}"
                        + ".unsloth-gen-sub{color:rgba(255,255,255,.85);font-size:13px;margin-top:2px}';"
                        + "document.head.appendChild(st);window.__unslothGenStyle=true;}"
                        + "var c=document.querySelector('.unsloth-composer-surface');"
                        + "if(!c)return 'no-surface';"
                        + "var cr=c.getBoundingClientRect();"
                        + "function rr(e){if(!e)return null;"
                        + "var q=e.getBoundingClientRect();"
                        + "return Math.round(q.left)+','+Math.round(q.top)+','+Math.round(q.right)+','+Math.round(q.bottom);}"
                        + "var vp=document.querySelector('.aui-thread-viewport');"
                        + "var vleft=0,vwidth=0;"
                        + "if(vp){var vqr=vp.getBoundingClientRect();"
                        + "vleft=vqr.left;vwidth=vqr.width;}"
                        + "var rem=parseFloat(getComputedStyle(document.documentElement).fontSize)||16;"
                        + "var vw=vwidth||document.documentElement.clientWidth||cr.right-cr.left;"
                        + "var bw=vw-2*rem;"
                        + "var bl=vwidth>0?vleft+(vwidth-bw)/2:(vw-bw)/2;"
                        + "var left=bl-cr.left,right=cr.right-(bl+bw);"
                        + "var old=document.getElementById('unsloth-gen-banner');"
                        + "if(old&&old.parentNode)old.parentNode.removeChild(old);"
                        + "var b=document.createElement('div');b.id='unsloth-gen-banner';"
                        + "b.innerHTML='<div class=\"unsloth-gen-spin\"><svg width=\"30\" height=\"30\" viewBox=\"0 0 24 24\" fill=\"none\">"
                        + "<path d=\"M21,12 A9,9 0 1 1 12,3\" stroke=\"#fff\" stroke-width=\"2.5\" stroke-linecap=\"round\"/></svg></div>"
                        + "<div class=\"unsloth-gen-txt\"><div class=\"unsloth-gen-main\">" + main + "</div>"
                        + "<div class=\"unsloth-gen-sub\">" + sub + "</div></div>';"
                        + "b.style.left=left+'px';"
                        + "b.style.right=right+'px';"
                        + "b.style.top='0px';"
                        + "b.style.height='100%';"
                        + "b.style.opacity='1';"
                        + "c.appendChild(b);"
                        + "window.__unslothGenBannerInfo=rr(b)+'|op='+getComputedStyle(b).opacity;"
                        + "return 'ok|info='+window.__unslothGenBannerInfo+'|c='+rr(c)+'|vp='+rr(vp)+'|rem='+Math.round(rem)+'|bw='+Math.round(bw)+'|lr='+Math.round(left)+','+Math.round(right);"
                        + "})();";
            } else {
                js = "(function(){"
                        + "var b=document.getElementById('unsloth-gen-banner');"
                        + "if(!b)return 'none';"
                        + "b.style.opacity='0';"
                        + "setTimeout(function(){if(b.parentNode)b.parentNode.removeChild(b);},260);"
                        + "return 'ok';"
                        + "})();";
            }
            webView.evaluateJavascript(js, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    App.log("BANNER js: " + value);
                }
            });
        } catch (Exception e) {
            App.log("BANNER errore: " + e.getMessage());
        }
    }

    // Escape per stringhe JS single-quoted (testi del banner).
    private static String jsEsc(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    // Blocca il composer dentro la web view: send disabled, editor readonly.
    // I listener capture installati da injectComposerGuard bloccano anche il
    // click sul send e l'Enter finche' window.__unslothLock e' vero.
    private void lockComposer(boolean lock) {
        try {
            String js = "(function(){" +
                    "window.__unslothLock=" + lock + ";" +
                    "var s=document.querySelector('.aui-composer-send');" +
                    "if(s){s.disabled=" + lock + ";}" +
                    "var e=document.querySelector('.unsloth-composer-editor textarea');" +
                    "if(e){e.readOnly=" + lock + ";" +
                    (lock ? "e.style.opacity='0.6';" : "e.style.opacity='';") + "}" +
                    "})();";
            webView.evaluateJavascript(js, null);
        } catch (Exception e) {
            App.log("LOCK errore: " + e.getMessage());
        }
    }

    // Listener capture su document (una volta per pagina): finche' il lock e'
    // attivo bloccano il click sul pulsante send e l'Enter nell'editor.
    // Stanno su document, quindi sopravvivono ai re-render di React/Studio.
    private void injectComposerGuard() {
        try {
            String js = "(function(){" +
                    "if(window.__unslothGuard)return;" +
                    "window.__unslothGuard=true;window.__unslothLock=false;" +
                    "document.addEventListener('click',function(ev){" +
                    "if(!window.__unslothLock)return;" +
                    "var t=ev.target;if(!t||!t.closest)return;" +
                    "if(t.closest('.aui-composer-send')){ev.preventDefault();ev.stopPropagation();}" +
                    "},true);" +
                    "document.addEventListener('keydown',function(ev){" +
                    "if(!window.__unslothLock)return;" +
                    "var t=ev.target;if(!t||!t.closest)return;" +
                    "if(t.closest('.unsloth-composer-editor')&&ev.key==='Enter'&&!ev.shiftKey){" +
                    "ev.preventDefault();ev.stopPropagation();}" +
                    "},true);" +
                    "})();";
            webView.evaluateJavascript(js, null);
        } catch (Exception e) {
            App.log("GUARD errore: " + e.getMessage());
        }
    }

    private void showLog() {
        try {
            File f = App.getLogFile();
            if (f == null || !f.exists()) {
                logText.setText(getString(R.string.no_log_yet));
            } else {
                FileReader fr = new FileReader(f);
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[4096];
                int n;
                while ((n = fr.read(buf)) != -1) sb.append(buf, 0, n);
                fr.close();
                logText.setText(sb.toString());
            }
            webView.setVisibility(View.GONE);
            creditsPanel.setVisibility(View.GONE);
            optionsPanel.setVisibility(View.GONE);
            errorPanel.setVisibility(View.GONE);
            logPanel.setVisibility(View.VISIBLE);
            updateBar();
        } catch (Exception e) {
            App.log("showLog failed: " + e.getMessage());
        }
    }

    // Condivide crash.log via FileProvider (gia' dichiarato nel manifest,
    // authorities it.mattia.unsloth.fileprovider): chooser ACTION_SEND per
    // allegare il log a email o issue GitHub.
    private void shareLog() {
        try {
            File f = App.getLogFile();
            if (f == null || !f.exists()) {
                Toast.makeText(this, getString(R.string.no_log_yet), Toast.LENGTH_LONG).show();
                return;
            }
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, "it.mattia.unsloth.fileprovider", f);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            App.log("SHARE LOG: " + uri);
            startActivity(Intent.createChooser(send, getString(R.string.download_log)));
        } catch (Exception e) {
            App.log("SHARE LOG FAILED: " + e.getMessage());
            Toast.makeText(this, getString(R.string.download_error, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Inietta un listener di click (fase capture) che intercetta gli anchor
     * con href blob: prima che Studio revochi l'objectURL: salva il Blob in
     * window.__unslothBlob e avvisa il bridge Java.
     */
    private void injectBlobInterceptor() {
        String js = "(function() {" +
                "if (window.__unslothHooked) return; window.__unslothHooked = true;" +
                "document.addEventListener('click', function(ev) {" +
                "var t = ev.target; while (t && t.tagName !== 'A') t = t.parentElement;" +
                "if (!t || !t.href || t.href.indexOf('blob:') !== 0) return;" +
                "ev.preventDefault(); ev.stopPropagation();" +
                "var name = t.download || ('download_' + Date.now() + '.bin');" +
                "fetch(t.href).then(function(r){ return r.blob(); }).then(function(b) {" +
                "b.arrayBuffer().then(function(ab) {" +
                "window.__unslothBlobBuf = new Uint8Array(ab);" +
                "UnslothBridge.startBlobDownload(name, ab.byteLength);" +
                "}).catch(function(e){ UnslothBridge.blobError(String(e)); });" +
                "}).catch(function(e){ UnslothBridge.blobError(String(e)); });" +
                "}, true);" +
                "})()";
        webView.evaluateJavascript(js, null);
    }

    class JsBridge {
        @JavascriptInterface
        public void startBlobDownload(String name, long size) {
            App.log("startBlobDownload: name=" + name + " size=" + size);
            runOnUiThread(() -> startBlobChunks(name, size));
        }

        @JavascriptInterface
        public void blobChunkResult(String b64) {
            runOnUiThread(() -> handleBlobChunk(b64));
        }

        @JavascriptInterface
        public void blobFetched(long size) {
            App.log("blobFetched: size=" + size);
            runOnUiThread(() -> {
                if (mBlobPendingName == null) return;
                String n = mBlobPendingName;
                mBlobPendingName = null;
                startBlobChunks(n, size);
            });
        }

        @JavascriptInterface
        public void blobError(String msg) {
            App.log("Blob download error: " + msg);
            runOnUiThread(() ->
                    Toast.makeText(MainActivity.this, getString(R.string.download_error, msg), Toast.LENGTH_LONG).show());
        }

        @JavascriptInterface
        public void appLog(String msg) {
            App.log(msg);
        }

        @JavascriptInterface
        public void reportUrl(String url) {
            App.log("URL: " + url);
            if (url != null && url.contains("thread=")) {
                int i = url.indexOf("thread=") + 7;
                int j = url.indexOf('&', i);
                String id = j > 0 ? url.substring(i, j) : url.substring(i);
                try {
                    id = java.net.URLDecoder.decode(id, "UTF-8");
                } catch (Exception ignored) {
                }
                mLastThread = id;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("last_thread", id).apply();
            }
            // Navigazione SPA (pushState/replaceState) senza onPageFinished:
            // e' il caso del post-login di Studio. Salvo i token freschi nei
            // prefs e, se non ho ancora aperto il thread vivo, lo apro
            // (una volta per connessione).
            // Il bridge JS chiama reportUrl da un thread di background;
            // evaluateJavascript e i suoi callback scattano solo sul thread
            // UI (stesso motivo del flusso blob: startBlobDownload ->
            // runOnUiThread). Da li i token non venivano mai letti.
            if (url != null) {
                final String u = url;
                runOnUiThread(() -> {
                    mPageUrl = u;
                    App.log("REPORT-URL: sync su thread UI");
                    syncTokensFromPage();
                    if (!mLiveOpened && u.contains("chat") && !u.contains("/login")) {
                        healAndOpenLive();
                    }
                    if (u.contains("/login")) {
                        // Re-login: la prossima chat va riaperta dal thread vivo.
                        mLiveOpened = false;
                    }
                });
            }
        }
    }

    private void injectUrlReporter() {
        String js = "(function(){" +
                "if (window.__unslothUrl) return;" +
                "window.__unslothUrl = true;" +
                "var rp = function(){ try { UnslothBridge.reportUrl(location.href); } catch(e){} };" +
                "var op = history.pushState, orp = history.replaceState;" +
                "history.pushState = function(){ var r = op.apply(this, arguments); rp(); return r; };" +
                "history.replaceState = function(){ var r = orp.apply(this, arguments); rp(); return r; };" +
                "window.addEventListener('popstate', rp);" +
                "})();";
        webView.evaluateJavascript(js, null);
    }

    private String nameFromDisposition(String disp) {
        if (disp == null) return null;
        Matcher m = Pattern.compile("filename\\*?=\"([^\"]+)\"").matcher(disp);
        if (m.find()) return m.group(1);
        m = Pattern.compile("filename=([^;]+)").matcher(disp);
        if (m.find()) {
            String n = m.group(1).trim();
            if (!n.isEmpty()) return n;
        }
        return null;
    }

    private void startHttpDownload(String url, String contentDisposition, String mimeType) {
        try {
            String title = nameFromDisposition(contentDisposition);
            if (title == null || title.isEmpty()) {
                Uri u = Uri.parse(url);
                String path = u.getLastPathSegment();
                if (path != null && !path.isEmpty()) {
                    title = URLDecoder.decode(path, "UTF-8");
                } else {
                    title = "download.bin";
                }
            }
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url))
                    .setTitle(title)
                    .setMimeType(mimeType != null ? mimeType : "application/octet-stream")
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, title)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
            long dlId = dm.enqueue(req);
            mDownloadTitles.put(dlId, title);
            // Persisto il titolo anche su disco: se l'app si riavvia o l'activity viene
            // ricreata, la mappa in memoria è persa ma il receiver lo rilegge da qui.
            getSharedPreferences("downloads", MODE_PRIVATE).edit().putString("dl_" + dlId, title).commit();
            App.log("DownloadManager enqueue: " + title + " (id=" + dlId + ")");
            // Toast sempre (richiesto da Mattia) + notifica se il permesso è concesso
            Toast.makeText(this, getString(R.string.download_started, title), Toast.LENGTH_LONG).show();
            notifyDownload((int) dlId, title, getString(R.string.download_in_progress_notif), true, null);
        } catch (Exception e) {
            App.log("DownloadManager FAILED: " + e.getMessage());
            Toast.makeText(this, getString(R.string.download_error, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    // Id fisso per le notifiche del download blob (un solo blob alla volta)
    private static final int BLOB_NOTIF_ID = 9001;

    /** Notifica di download con il nome file sia nel titolo che nel testo. */
    /** Titolo persistito in SharedPreferences all'enqueue: sopravvive a restart e ricreazione
     *  dell'activity (la mappa mDownloadTitles è solo in memoria). */
    private String downloadTitleFromPrefs(long id) {
        try {
            SharedPreferences prefs = getSharedPreferences("downloads", MODE_PRIVATE);
            String t = prefs.getString("dl_" + id, null);
            if (t != null && !t.isEmpty()) {
                prefs.edit().remove("dl_" + id).commit();
                return t;
            }
        } catch (Exception e) {
            App.log("downloadTitleFromPrefs FAILED: " + e.getMessage());
        }
        return null;
    }

    /** Recupera il titolo del download dal DownloadManager (ripiego di riserva).
     *  La mappa mDownloadTitles è solo in memoria. */
    private String downloadTitleFromManager(long id) {
        try {
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm == null) return null;
            DownloadManager.Query q = new DownloadManager.Query().setFilterById(id);
            android.database.Cursor c = dm.query(q);
            if (c == null) return null;
            try {
                if (c.moveToFirst()) {
                    int idx = c.getColumnIndex(DownloadManager.COLUMN_TITLE);
                    if (idx >= 0) {
                        String t = c.getString(idx);
                        if (t != null && !t.isEmpty()) return t;
                    }
                }
            } finally {
                c.close();
            }
        } catch (Exception e) {
            App.log("downloadTitleFromManager FAILED: " + e.getMessage());
        }
        return null;
    }

    private void notifyDownload(int id, String title, String status, boolean ongoing, PendingIntent contentIntent) {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification.Builder b = new Notification.Builder(this, "downloads")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(status + ": " + title)
                .setOngoing(ongoing);
        if (contentIntent != null) b.setContentIntent(contentIntent);
        nm.notify(id, b.build());
    }

    // Autorita' del FileProvider per esporre i file scaricati come content://.
    private static final String FILE_PROVIDER_AUTHORITY = "it.mattia.unsloth.fileprovider";

    /** PendingIntent che apre il file scaricato al tap sulla notifica (ACTION_VIEW). */
    private PendingIntent openFilePendingIntent(Uri uri, String mime) {
        if (uri == null) return null;
        try {
            Uri shareUri = uri;
            // Android 7+ blocca i file:// passati in un Intent tra app: serve un content://.
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                File f = new File(uri.getPath());
                Uri fp = androidx.core.content.FileProvider.getUriForFile(this, FILE_PROVIDER_AUTHORITY, f);
                if (fp != null) shareUri = fp;
                App.log("openFilePendingIntent: file:// -> " + shareUri.toString());
            }
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(shareUri, (mime != null && !mime.isEmpty()) ? mime : "*/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            return PendingIntent.getActivity(this, (int) System.currentTimeMillis(), i, flags);
        } catch (Exception e) {
            App.log("openFilePendingIntent FAILED: " + e.getMessage());
            return null;
        }
    }

    private void startBlobFromUrl(String url, String name, String mimeType) {
        if (mBlobOut != null) {
            Toast.makeText(this, getString(R.string.download_already), Toast.LENGTH_LONG).show();
            return;
        }
        mBlobPendingName = name;
        String js = "(async () => { try { const r = await fetch('" + url + "');"
                + " const b = await r.blob(); const ab = await b.arrayBuffer();"
                + " window.__unslothBlobBuf = new Uint8Array(ab);"
                + " UnslothBridge.blobFetched(ab.byteLength);"
                + " } catch (e) { UnslothBridge.blobError(String(e)); } })()";
        webView.evaluateJavascript(js, null);
    }

    private void startBlobChunks(String name, long size) {
        if (mBlobOut != null) {
            Toast.makeText(this, getString(R.string.download_already), Toast.LENGTH_LONG).show();
            return;
        }
        mBlobName = name;
        mBlobTotal = size;
        mBlobReceived = 0;
        mBlobChunkIndex = 0;
        mBlobChunkCount = (int) ((size + BLOB_CHUNK - 1) / BLOB_CHUNK);
        try {
            android.database.Cursor cur = getContentResolver().query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME},
                    null, null, null);
            if (cur != null) {
                while (cur.moveToNext()) {
                    App.log("DB row: " + cur.getString(2) + " @ " + cur.getString(1));
                }
                cur.close();
            }
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            mBlobUri = uri;
            App.log("insert ok: " + uri);
            mBlobOut = getContentResolver().openOutputStream(uri);
            App.log("openOutputStream ok");
            notifyDownload(BLOB_NOTIF_ID, name, getString(R.string.download_in_progress_notif), true, null);
        } catch (Exception e) {
            App.log("MediaStore FAILED: " + e.getMessage());
            Toast.makeText(this, getString(R.string.write_error, e.getMessage()), Toast.LENGTH_LONG).show();
            return;
        }
        nextBlobChunk();
    }

    private void nextBlobChunk() {
        if (mBlobOut == null) return;
        if (mBlobChunkIndex >= mBlobChunkCount) {
            finishBlob();
            return;
        }
        final int i = mBlobChunkIndex;
        // Chunk sincrono sul buffer di byte (window.__unslothBlobBuf):
        // il Blob fetched si svuota dopo pochi ms, i byte no.
        String js = "(function(i) { var d = window.__unslothBlobBuf; if (!d) return 'DEAD';" +
                " var off = i * " + BLOB_CHUNK + "; var end = Math.min(off + " + BLOB_CHUNK + ", d.length);" +
                " var buf = d.subarray(off, end); var s = '';" +
                " for (var j = 0; j < buf.length; j += 32768)" +
                "   s += String.fromCharCode.apply(null, buf.subarray(j, j + 32768));" +
                " return btoa(s); })(i)";
        webView.evaluateJavascript(js, value -> {
            if (mBlobOut == null) return;
            if (value == null || !value.startsWith("\"") || value.equals("\"DEAD\"")) {
                App.log("Blob chunk FAILED at " + i);
                failBlob(getString(R.string.download_interrupted));
                return;
            }
            String v = value.substring(1, value.length() - 1);
            try {
                byte[] data = Base64.getDecoder().decode(v);
                mBlobOut.write(data);
                mBlobReceived += data.length;
            } catch (Exception e) {
                App.log("Blob write FAILED: " + e.getMessage());
                failBlob(getString(R.string.write_error_bare));
                return;
            }
            mBlobChunkIndex++;
            if (mBlobReceived >= mBlobTotal) finishBlob();
            else nextBlobChunk();
        });
    }

    /** Risultato di un chunk blob arrivato dal bridge JS. */
    private void handleBlobChunk(String b64) {
        if (mBlobOut == null) return;
        if (b64 == null || b64.equals("DEAD") || b64.startsWith("ERR:")) {
            App.log("Blob chunk FAILED at " + mBlobChunkIndex + (b64 != null ? ": " + b64 : ""));
            failBlob("Download interrotto");
            return;
        }
        try {
            byte[] data = Base64.getDecoder().decode(b64);
            mBlobOut.write(data);
            mBlobReceived += data.length;
        } catch (Exception e) {
            App.log("Blob write FAILED: " + e.getMessage());
            failBlob("Errore di scrittura");
            return;
        }
        mBlobChunkIndex++;
        if (mBlobReceived >= mBlobTotal) finishBlob();
        else nextBlobChunk();
    }

    private void finishBlob() {
        try {
            if (mBlobOut != null) mBlobOut.close();
        } catch (Exception ignored) {
        }
        mBlobOut = null;
        webView.evaluateJavascript("window.__unslothBlobBuf = null", null);
        App.log("Blob download OK: " + mBlobName + " (" + mBlobReceived + " bytes)");
        Toast.makeText(this, getString(R.string.download_done, mBlobName), Toast.LENGTH_LONG).show();
        notifyDownload(BLOB_NOTIF_ID, mBlobName, getString(R.string.download_done_notif), false, openFilePendingIntent(mBlobUri, "application/octet-stream"));
    }

    private void failBlob(String msg) {
        try {
            if (mBlobOut != null) mBlobOut.close();
        } catch (Exception ignored) {
        }
        mBlobOut = null;
        webView.evaluateJavascript("window.__unslothBlobBuf = null", null);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private void hideLog() {
        logPanel.setVisibility(View.GONE);
        if (currentUrl != null) {
            webView.setVisibility(View.VISIBLE);
            updateBar();
        } else {
            openOptions();
        }
    }

    private void openCredits() {
        mErrorActive = false;
        errorPanel.setVisibility(View.GONE);
        logPanel.setVisibility(View.GONE);
        optionsPanel.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
        creditsPanel.setVisibility(View.VISIBLE);
        updateBar();
    }

    private void hideCredits() {
        creditsPanel.setVisibility(View.GONE);
        if (currentUrl != null) {
            webView.setVisibility(View.VISIBLE);
            updateBar();
        } else {
            openOptions();
        }
    }

    private static final int REQ_FILE_CHOOSER = 42;
    private ValueCallback<Uri[]> mFileCallback;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_FILE_CHOOSER || mFileCallback == null) return;
        Uri result = null;
        if (resultCode == RESULT_OK && data != null) {
            android.content.ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                result = clip.getItemAt(0).getUri();
            } else if (data.getData() != null) {
                result = data.getData();
            }
        }
        App.log("FILE_CHOOSER: risultato " + (result == null ? "annullato" : result.toString()));
        if (result == null) {
            mFileCallback.onReceiveValue(new Uri[0]);
        } else {
            mFileCallback.onReceiveValue(new Uri[]{result});
        }
        mFileCallback = null;
    }

    @Override
    protected void onDestroy() {
        stopRunPolling();
        if (mDownloadReceiver != null) {
            unregisterReceiver(mDownloadReceiver);
            mDownloadReceiver = null;
        }
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
