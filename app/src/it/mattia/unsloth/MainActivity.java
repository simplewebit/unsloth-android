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
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// v3.0: AppCompatActivity (non Activity): serve per il tema dark/sistema
// via AppCompatDelegate + risorse values-night (tema DayNight).
public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "unsloth";
    private static final int HIDE_SCROLL_Y = 100;
    private static final int SHOW_SCROLL_Y = 20;

    private DrawerLayout drawerLayout;
    private LinearLayout bar;
    private ScrollTrackingWebView webView;
    // v2.8: riferimento statico alla WebView, cosi i service (KeepAlive,
    // GenService) possono leggere/iniettare i token nella pagina senza
    // passare per l'Activity.
    private static volatile java.lang.ref.WeakReference<ScrollTrackingWebView> sWebView = null;
    private EditText editHost;
    private EditText editDdns;
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
    // v3.0: tema scelto nelle opzioni: "light", "dark", "system".
    private Spinner spinnerTheme;
    private String mTheme = "system";
    // v3.0: palette della pagina Studio (id della SPA, key localStorage "palette").
    private Spinner spinnerPalette;
    private String mPalette = "standard";
    // Id delle palette Studio, nello STESSO ordine di @array/palette_options
    // nelle strings.xml: la posizione nello spinner vale come index qui.
    private static final String[] PALETTE_IDS = {
            "standard", "matcha", "espresso", "butterfly-pea", "cherry", "earl-grey",
            "neon-cyberpunk", "macaron", "trick-or-treat", "mint", "taro", "licorice",
            "blueberry", "tangerine", "honey", "plum", "wasabi", "oat-milk",
            "cinnamon", "dragon-fruit", "peach", "yuzu", "cotton-candy", "pina-paraiso"
    };
    private static int palettePos(String id) {
        for (int i = 0; i < PALETTE_IDS.length; i++) {
            if (PALETTE_IDS[i].equals(id)) return i;
        }
        return 0;
    }
    // v3.0: crash guard: true mentre l'activity e' viva (onResume..onStop).
    // CrashTickReceiver lo usa per distinguere "l'app e' viva" dal
    // "processo ripreso adesso dall'allarme" (allora l'activity non esiste).
    public static boolean sActivityAlive = false;
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
            // v3.0: il context di lingua NON si crea qui: in
            // attachBaseContext la configuration non ha ancora la flag
            // night applicata da AppCompatDelegate, e con lingua it/en il
            // dark risolverebbe i colori day. Si crea in onCreate dopo
            // super.onCreate() (vedi li).
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
    // Id del run avviato da questo telefono (catturato dal polling
    // /chat-runs/<id>/events): cosi un run "mio" resta riconosciuto come mio
    // anche oltre la finestra di 10 min di isRecentGen() (run lunghi > 10 min).
    private volatile String mMyRunId = null;
    // Thread per cui ripristinare lo scroll all'avvio (ultimo thread ripreso).
    private volatile String mScrollRestoreThread = null;
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
    // URL della pagina in cui e' partito l'ultimo heal: dopo il refresh
    // ricarico questa pagina (la chat corrente), non la pagina base.
    private volatile String mHealReturnUrl = null;

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

    // v2.7: mirroring di un run partito da un altro client. Il run-poll lo
    // rileva su /chat-runs/active e qui l'app apre un SSE nativo
    // (POST /chat-runs/<id>/events?after=N, stesso pattern di GenService):
    // 1) tiene il run vivo: finche' l'app e' iscritta Studio lo conta
    //    "attento" e non ferma un run stopWhenUnattended;
    // 2) streamma i chunk nel banner: la riga sotto del banner mostra la
    //    preview del testo generato sul PC (stesso stream che Studio
    //    mostrerebbe nella web view).
    // A fine run il run-poll chiude lo stream e fa il resync esistente.
    // Finche' il run esterno e' attivo, il box errore "interrupted" della SPA
    // (con il Retry inutile, che darebbe 409) resta nascosto: la SPA lo
    // mostra perche' il run adottato dalla web view non viene registrato nel
    // set activeRuns dell'adapter (K_/Vu non chiamati nel percorso
    // wake/merge). hideRetryBox lo nasconde con uno stile nel DOM.
    private volatile String mMirrorRunId = null;
    private volatile boolean mMirrorStop = true;
    private volatile long mMirrorAfter = 0;
    private Thread mMirrorThread;
    private final StringBuilder mMirrorText = new StringBuilder();
    private volatile long mLastMirrorUiMs = 0;

    // Download blob: un solo job alla volta, chunk base64 dal WebView
    private static final int BLOB_CHUNK = 2 * 1024 * 1024;
    private String mBlobName;
    private String mBlobPendingName;
    private long mBlobTotal;
    private long mBlobReceived;
    private int mBlobChunkIndex;
    // Versione dell'app: incrementata a ogni build, usata nel check update
    // GitHub e nel User-Agent (non più mostrata a schermo).
    static final String APP_VERSION = "3.0";

    // v2: banner aggiornamento (check GitHub all'avvio, download nativo + install).
    private View updateBanner;
    private TextView updateText;
    private Button btnUpdateDownload;
    private TextView updateStatus;
    private Button btnCheckUpdates;
    private boolean mUpdateAvailable = false;
    private String mUpdateApkUrl;
    private boolean mUpdateChecking;
    private boolean mUpdateDownloading;

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
        // v3.0: qui AppCompatDelegate ha gia' applicato il night mode
        // (scelto in App.onCreate dai prefs), quindi la configuration
        // corrente include la flag night: il context di lingua (se non
        // auto) si crea ADESSO, cosi in dark i colori values-night si
        // risolvono anche con lingua it/en.
        try {
            if (!"auto".equals(mLang)) {
                Locale loc = "it".equals(mLang) ? Locale.ITALIAN : Locale.ENGLISH;
                Configuration c = getResources().getConfiguration();
                c.setLocale(loc);
                c.setLocales(new LocaleList(loc));
                mLocaleContext = getApplicationContext().createConfigurationContext(c);
            }
        } catch (Exception e) {
        }
        // v3.0: tema (light/dark/system) e palette Studio, applicati al
        // chrome nativo dal night mode e alla pagina Studio da
        // injectAppearanceJs (localStorage.theme / localStorage.palette).
        mTheme = getSharedPreferences(PREFS, MODE_PRIVATE).getString("theme", "system");
        if (mTheme == null) mTheme = "system";
        // v3.0: palette Studio (key "palette", default "standard").
        mPalette = getSharedPreferences(PREFS, MODE_PRIVATE).getString("palette", "standard");
        if (mPalette == null) mPalette = "standard";
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
                            wakeRefreshThenResync();
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
            sWebView = new java.lang.ref.WeakReference<>(webView);
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
        editDdns = findViewById(R.id.edit_ddns);
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
            // v2: banner nuova versione (visibile solo se GitHub ha una release
            // più recente di APP_VERSION).
            updateBanner = findViewById(R.id.update_banner);
            updateText = findViewById(R.id.update_text);
            btnUpdateDownload = findViewById(R.id.btn_update_download);
            btnUpdateDownload.setOnClickListener(v -> startUpdate());
            updateStatus = findViewById(R.id.update_status);
            btnCheckUpdates = findViewById(R.id.btn_check_updates);
            btnCheckUpdates.setOnClickListener(v -> manualUpdateCheck());
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
            // v2.9: svuota crash.log anche dal pannello Options (stesso handler
            // del tasto del pannello Log). Lo si vede solo se il Log e' attivo.
            final View btnClearLogOptions = findViewById(R.id.btn_clear_log_options);
            btnClearLogOptions.setVisibility(logEnabled ? View.VISIBLE : View.GONE);
            btnClearLogOptions.setOnClickListener(v -> clearLog());
            switchLog.setOnCheckedChangeListener((v, checked) -> {
                App.logEnabled = checked;
                prefs.edit().putBoolean("log_enabled", checked).apply();
                btnLogRow.setVisibility(checked ? View.VISIBLE : View.GONE);
                btnClearLogOptions.setVisibility(checked ? View.VISIBLE : View.GONE);
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

            // v3.0: tema (Chiaro/Scuro/Sistema). Al cambio:
            // 1) prefs,
            // 2) injectAppearanceJs: scrive localStorage.theme (e
            //    localStorage.palette) nella pagina Studio (theme-boot.js
            //    li applica al primo paint, anche dopo il reload che
            //    segue il recreate),
            // 3) setDefaultNightMode: ricrea l'activity (comportamento
            //    di AppCompat) e il chrome nativo riparte in dark/light.
            spinnerTheme = findViewById(R.id.spinner_theme);
            ArrayAdapter<CharSequence> themeAdapter = ArrayAdapter.createFromResource(
                    this, R.array.theme_options, android.R.layout.simple_spinner_item);
            themeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinnerTheme.setAdapter(themeAdapter);
            int themePos = "light".equals(mTheme) ? 0 : ("dark".equals(mTheme) ? 1 : 2);
            spinnerTheme.setSelection(themePos);
            spinnerTheme.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    String t = position == 0 ? "light" : (position == 1 ? "dark" : "system");
                    if (t.equals(mTheme)) return; // selezione iniziale: niente cambio
                    mTheme = t;
                    prefs.edit().putString("theme", t).apply();
                    App.log("OPZIONI tema: " + t);
                    injectAppearanceJs();
                    AppCompatDelegate.setDefaultNightMode(App.nightModeFor(t));
                }
                @Override
                public void onNothingSelected(android.widget.AdapterView<?> parent) {
                }
            });

            // v3.0: palette della pagina Studio. Al cambio: prefs +
            // localStorage.palette (theme-boot.js applica data-palette su
            // <html> al load; l'iniezione qui sotto applica anche subito
            // l'attributo CSS alla pagina gia' aperta).
            spinnerPalette = findViewById(R.id.spinner_palette);
            ArrayAdapter<CharSequence> paletteAdapter = ArrayAdapter.createFromResource(
                    this, R.array.palette_options, android.R.layout.simple_spinner_item);
            paletteAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinnerPalette.setAdapter(paletteAdapter);
            spinnerPalette.setSelection(palettePos(mPalette));
            spinnerPalette.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    String p = (position >= 0 && position < PALETTE_IDS.length)
                            ? PALETTE_IDS[position] : "standard";
                    if (p.equals(mPalette)) return; // selezione iniziale: niente cambio
                    mPalette = p;
                    prefs.edit().putString("palette", p).apply();
                    App.log("OPZIONI palette: " + p);
                    injectAppearanceJs();
                }
                @Override
                public void onNothingSelected(android.widget.AdapterView<?> parent) {
                }
            });

            // Download del log: FileProvider (gia' nel manifest) + intent
            // ACTION_SEND con chooser, cosi il file si allega a email/issue.
            View btnDownloadLog = findViewById(R.id.btn_download_log);
            btnDownloadLog.setOnClickListener(v -> shareLog());
            // v2.9: svuota crash.log dal pannello Log.
            View btnClearLog = findViewById(R.id.btn_clear_log);
            btnClearLog.setOnClickListener(v -> clearLog());
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
        // v2.6: "Progetti e sessioni": chiude il drawer dell'app e apre la barra
        // laterale sinistra dell'SPA (lista di tutte le chat e i progetti),
        // cliccando il bottone "Open sidebar" dell'SPA stesso.
        View btnProjectsSessions = findViewById(R.id.btn_projects_sessions);
        btnProjectsSessions.setOnClickListener(v -> {
            drawerLayout.closeDrawer(Gravity.START);
            openSpaSidebar();
        });
        // v2.5: voci del drawer sulle pagine SPA di Studio (route verificate nel
        // frontend: /hub = hub dei modelli, /images = immagini, /settings =
        // impostazioni; la chat nuova e' /chat?new=<nonce>, come il bottone
        // "New chat" dell'SPA).
        View btnNewChat = findViewById(R.id.btn_newchat);
        btnNewChat.setOnClickListener(v -> {
            drawerLayout.closeDrawer(Gravity.START);
            openSpaPage("/chat?new=" + java.util.UUID.randomUUID());
        });
        View btnModelHub = findViewById(R.id.btn_modelhub);
        btnModelHub.setOnClickListener(v -> {
            drawerLayout.closeDrawer(Gravity.START);
            openSpaPage("/hub");
        });
        View btnImages = findViewById(R.id.btn_images);
        btnImages.setOnClickListener(v -> {
            drawerLayout.closeDrawer(Gravity.START);
            openSpaPage("/images");
        });
        View btnStudioSettings = findViewById(R.id.btn_studio_settings);
        btnStudioSettings.setOnClickListener(v -> {
            drawerLayout.closeDrawer(Gravity.START);
            openSpaPage("/settings");
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
            String d = editDdns.getText().toString().trim();
            String p = editPort.getText().toString().trim();
            if (p.isEmpty()) p = "8888";
            if ((!h.isEmpty() || !d.isEmpty()) && p.matches("[0-9]+")
                    && Integer.parseInt(p) >= 1 && Integer.parseInt(p) <= 65535) {
                saveAndConnect(h, d, p);
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
                        // Studio, dopo il POST chat-runs, fa polling
                        // /chat-runs/<runId>/events: quell'id e' il "run mio",
                        // salvato (anche in prefs) per il poll /active: cosi un
                        // run lungo partito da qui non diventa "cross-client".
                        int evi = uStr.indexOf("/api/inference/chat-runs/");
                        if (evi >= 0 && uStr.contains("/events")) {
                            String rest2 = uStr.substring(evi + "/api/inference/chat-runs/".length());
                            int ej = rest2.indexOf('/');
                            if (ej > 0) {
                                String rid = rest2.substring(0, ej);
                                if (!rid.isEmpty() && !rid.equals("undefined")) {
                                    mMyRunId = rid;
                                    getSharedPreferences(PREFS, MODE_PRIVATE)
                                            .edit().putString("my_run_id", rid).apply();
                                }
                            }
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
                injectWarningHider();
                // v3.0: applica tema e palette alla pagina (best effort:
                // anche se l'iniezione atterra nel documento precedente,
                // localStorage e' per-origin e resta).
                injectAppearanceJs();
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
                // Doppo il commit il documento in memoria e' quello nuovo
                // e completo: l'iniezione ritardata tocca sempre la pagina
                // vera (le iniezioni di onPageStarted finiscono spesso nel
                // documento precedente, che muore col cambio). La guardia
                // window.__unslothToastGuard evita i doppi.
                new android.os.Handler(getMainLooper())
                        .postDelayed(MainActivity.this::injectWarningHider, 500);
                // v3.0: backstop di tema e palette (doppia sicurezza sul
                // localStorage dopo eventuali clearCache(true)).
                new android.os.Handler(getMainLooper())
                        .postDelayed(MainActivity.this::injectAppearanceJs, 500);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                mPageUrl = url;
                syncTokensFromPage();
                injectWarningHider();
                if (mAutoOpenPending && url != null && !url.contains("/login")) {
                    mAutoOpenPending = false;
                    healAndOpenLive();
                }
                restoreScrollIfResuming(url);
            }
        });

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        mLastThread = prefs.getString("last_thread", null);
        // Run avviato dalla sessione precedente, ancora attivo: riapendolo
        // l'app lo riconosce come "mio" (niente banner cross-client).
        mMyRunId = prefs.getString("my_run_id", null);
        String ip = prefs.getString("host", "");
        String ddns = prefs.getString("ddns", "");
        String port = prefs.getString("port", "8888");
        App.log("MainActivity.onCreate: prefs ip=" + ip + " ddns=" + ddns + " port=" + port);
        if (!ip.isEmpty() || !ddns.isEmpty()) {
            editHost.setText(ip);
            editDdns.setText(ddns);
            editPort.setText(port);
            connect(ip, ddns, port);
        } else {
            openOptions();
        }
        App.log("MainActivity.onCreate: FINE - app avviata con successo");
    }

    // Se sto riaprendo l'ultimo thread all'avvio (mScrollRestoreThread),
    // ripristino la posizione di scroll salvata in onPause. Studio puo'
    // renderizzare i messaggi dopo onPageFinished: ripeto il scrollTo a
    // intervalli (1.5-10 s); l'ultima chiamata vince. Una sola volta per
    // connessione.
    private void restoreScrollIfResuming(String url) {
        if (mScrollRestoreThread == null || url == null) return;
        int i = url.indexOf("thread=");
        if (i < 0) return;
        i += 7;
        int j = url.indexOf('&', i);
        String id = j > 0 ? url.substring(i, j) : url.substring(i);
        try {
            id = java.net.URLDecoder.decode(id, "UTF-8");
        } catch (Exception ignored) {
        }
        if (!id.equals(mScrollRestoreThread)) return;
        final String tid = id;
        mScrollRestoreThread = null; // consumato: una volta per connessione
        final long y = getSharedPreferences(PREFS, MODE_PRIVATE).getLong("scroll_" + tid, 0);
        if (y <= 0) return;
        final int yv = (int) y;
        final android.os.Handler h = new android.os.Handler(getMainLooper());
        long[] delays = {1500L, 3000L, 5000L, 8000L, 10000L};
        for (long d : delays) {
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        webView.evaluateJavascript("window.scrollTo(0," + yv + ")", null);
                    } catch (Exception e) {
                        // ignora
                    }
                }
            }, d);
        }
        App.log("SCROLL-RESTORE: thread " + tid + " a y=" + yv);
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
        // v2: banner solo sulla schermata principale: nascosto quando un pannello
        // è aperto (altrimenti coprirebbe il contenuto del pannello, es. Credits).
        if (updateBanner != null) {
            updateBanner.setVisibility(mUpdateAvailable && webActive ? View.VISIBLE : View.GONE);
        }
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

    // v2.5: apre una pagina SPA di Studio (path relativo alla base salvata).
    // Le route sono verificate nel frontend dist e sul server vivo:
    // /hub = hub dei modelli, /images = immagini, /settings = impostazioni,
    // /chat?new=<nonce> = nuova chat (come il bottone "New chat" dell'SPA).
    private void openSpaPage(String path) {
        if (currentUrl == null) return;
        try {
            String url = new java.net.URL(new java.net.URL(currentUrl), path).toString();
            App.log("SPA: apri " + url);
            mErrorActive = false;
            errorPanel.setVisibility(View.GONE);
            logPanel.setVisibility(View.GONE);
            creditsPanel.setVisibility(View.GONE);
            optionsPanel.setVisibility(View.GONE);
            webView.setVisibility(View.VISIBLE);
            updateBar();
            webView.loadUrl(url);
        } catch (Exception e) {
            App.log("SPA: openSpaPage FAILED: " + e.getMessage());
        }
    }

    // v2.6: apre la barra laterale sinistra dell'SPA (chat e progetti)
    // cliccando il suo bottone toggle. L'aria-label dipende dalla lingua
    // dell'SPA: "Open sidebar" (EN) / "Apri la barra laterale" (IT).
    // Se la barra è già aperta esiste il bottone "Close sidebar": in quel
    // caso non facciamo niente. Se non c'è nessuno dei due (pagina senza
    // sidebar, SPA non caricata) lo segnala nel log.
    private void openSpaSidebar() {
        // Il toggle della sidebar dell'SPA, verificato nel bundle dist:
        // [data-slot="sidebar-edge-trigger"] esiste solo su desktop;
        // su mobile (viewport < 767px, il caso dell'app) il toggle e'
        // il bottone [data-slot="sidebar-trigger"] della topbar
        // (funzione di() nel chunk download-approval: al click chiama
        // toggleSidebar che apre o chiude lo sheet mobile). Il pannello
        // vero e' [data-slot="sidebar"] (assente dal DOM quando e'
        // chiuso): se c'e' con larghezza > 10 non clicco, per non
        // chiuderlo. Ultimo tentativo: aria-label in EN/IT.
        String js = "(function(){"
                + "var e=document.querySelector('[data-slot=\"sidebar-edge-trigger\"]');"
                + "if(e){e.click();return 'edge';}"
                + "var p=document.querySelector('[data-slot=\"sidebar\"]');"
                + "if(p&&p.getBoundingClientRect().width>10)return 'already-open';"
                + "var s=document.querySelector('[data-slot=\"sidebar-trigger\"]');"
                + "if(s){s.click();return 'trigger';}"
                + "var bs=document.querySelectorAll('button[aria-label]');"
                + "var labels=[];"
                + "for(var i=0;i<bs.length;i++)labels.push((bs[i].getAttribute('aria-label')||'').toLowerCase());"
                + "for(var i=0;i<bs.length;i++){"
                + "var l=labels[i];"
                + "if(l==='open sidebar'||l==='apri la barra laterale'){bs[i].click();return 'opened';}"
                + "if(l==='close sidebar'||l==='chiudi la barra laterale')return 'already-open';}"
                + "return 'not-found: '+labels.slice(0,40).join('|');})()";
        webView.evaluateJavascript(js, value -> App.log("SIDEBAR-OPEN: " + value));
        // Dopo 1.5 s leggo la larghezza del pannello sidebar per
        // verificare che si e' davvero aperto (0/circa 0 = chiuso).
        new android.os.Handler(getMainLooper()).postDelayed(() ->
                webView.evaluateJavascript(
                        "(function(){var p=document.querySelector('[data-slot=\"sidebar\"]');"
                                + "if(p)return 'panel-w=' + Math.round(p.getBoundingClientRect().width);"
                                + "var ds=document.querySelectorAll('[data-sidebar]');"
                                + "var w=0;"
                                + "for(var i=0;i<ds.length;i++){var r=ds[i].getBoundingClientRect();if(r.width>w)w=r.width;}"
                                + "return 'sidebar-w=' + Math.round(w);})()",
                        value -> App.log("SIDEBAR-STATE: " + value)),
                1500);
    }

    private void connectFromSettings() {
        String ip = editHost.getText().toString().trim();
        String ddns = editDdns.getText().toString().trim();
        String port = editPort.getText().toString().trim();
        if (port.isEmpty()) port = "8888";
        if (ip.isEmpty() && ddns.isEmpty()) return;
        if (!port.matches("[0-9]+")) return;
        int p = Integer.parseInt(port);
        if (p < 1 || p > 65535) return;
        saveAndConnect(ip, ddns, port);
    }

    private void saveAndConnect(String ip, String ddns, String port) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("host", ip)
                .putString("ddns", ddns)
                .putString("port", port)
                .apply();
        connect(ip, ddns, port);
    }

    // v2.9: prova l'IP per primo e, se non risponde entro 10 s, il DDNS.
    // La risoluzione gira in background (fino a 20 s di probe) e carica
    // l'host risolto nel thread UI.
    private void connect(final String ip, final String ddns, final String port) {
        new Thread(() -> {
            String host = App.resolveHost(ip, ddns, port);
            if (host.isEmpty()) {
                runOnUiThread(this::openOptions);
                return;
            }
            String url = App.baseUrl(host, port);
            App.log("CONNECT: host risolto " + host + " (ip=" + ip + " ddns=" + ddns + ")");
            runOnUiThread(() -> connectUrl(url));
        }).start();
    }

    private void connectUrl(String url) {
        currentUrl = url;
        mAutoOpenPending = true;
        // Avvio (processo fresco, app killata) o ricollegamento: riprendo
        // l'ultimo thread visitato (mLastThread, salvato nei prefs da
        // reportUrl). openLiveThread lo verifica sul server e, se non esiste
        // piu', l'app resta sulla pagina base. Se mTargetThreadId e' gia' un
        // tap su notifica, non lo sovrascrivo.
        if ((mTargetThreadId == null || mTargetThreadId.isEmpty())
                && mLastThread != null && !mLastThread.isEmpty()) {
            mTargetThreadId = mLastThread;
            mScrollRestoreThread = mLastThread;
            App.log("RESUME: ultimo thread " + mLastThread);
        }
        mErrorActive = false;
        mScrollY = 0;
        creditsPanel.setVisibility(View.GONE);
        optionsPanel.setVisibility(View.GONE);
        errorPanel.setVisibility(View.GONE);
        logPanel.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        updateBar();
        // Se l'app era solo in secondo piano il processo e' ancora vivo e
        // onResume la mantiene sulla chat corrente senza riaprire nulla.
        webView.loadUrl(currentUrl);
        // v2: all'avvio verifica se su GitHub c'è una release più recente.
        checkForNewVersion();
    }

    // URL di avvio: la pagina base; se nei prefs c'e' l'ultimo thread visitato
    // (mLastThread) l'avvio lo riprende via mTargetThreadId (vedi connect()).
    private String startupUrl() {
        return currentUrl;
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
        mHealReturnUrl = curUrl;
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

    // Apre un thread solo se c'e' un target esplicito (tap sulla notifica,
    // extra "threadId"). Altrimenti l'app resta dove si trova (pagina base o
    // chat corrente): niente auto-apertura dell'ultimo visitato ne' del piu'
    // recentemente aggiornato.
    private void openLiveThread(final String base, final String token, final String rt, final String curUrl) {
        if (mTargetThreadId == null || mTargetThreadId.isEmpty()) {
            App.log("LIVE-THREAD: nessun target, resto dove sono");
            return;
        }
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
                boolean found = false;
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject t2 = arr.optJSONObject(i);
                    if (t2 != null && mTargetThreadId.equals(t2.optString("id"))) { found = true; break; }
                }
                if (!found) {
                    App.log("LIVE-THREAD: target non in lista, resto dove sono");
                    return;
                }
                final String fid = mTargetThreadId;
                mTargetThreadId = "";
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

    // v2.8: la pagina WebView e la fonte unica dei token di auth. Il refresh
    // token e single-use (rotazione monouso): se un actor nativo ruota il
    // token senza riscriverlo nella pagina, la SPA resta con la copia vecchia
    // e al suo prossimo refresh ottiene 401 e cade su /login. Questi helper
    // permettono ai service di leggere/iniettare i token direttamente nella
    // pagina, tenendo le due copie sempre in fase.
    public static ScrollTrackingWebView peekWebView() {
        java.lang.ref.WeakReference<ScrollTrackingWebView> r = sWebView;
        return r == null ? null : r.get();
    }

    public static boolean isPageAlive() {
        return peekWebView() != null;
    }

    // Scrive i token freschi nel localStorage della pagina. Se la pagina non
    // e viva lo salto: la SPA tiene la sua copia in localStorage e al prossimo
    // avvio riparte da li senza re-login.
    public static void injectAuthTokensToPage(final String at, final String rt) {
        if (at == null || rt == null || at.isEmpty() || rt.isEmpty()) return;
        final String js = "(function(){try{localStorage.setItem('unsloth_auth_token','" + at + "');" +
                "localStorage.setItem('unsloth_auth_refresh_token','" + rt + "');}catch(e){}})()";
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                ScrollTrackingWebView w = peekWebView();
                if (w == null) { App.log("AUTH-INJECT: pagina non viva, salto"); return; }
                w.evaluateJavascript(js, null);
            }
        });
    }

    // Legge i token dal localStorage della pagina in modo bloccante
    // (timeout ms). Ritorna "t|r" oppure "" se la pagina non e viva o non ha
    // token. Va chiamato da un thread di background.
    public static String readPageTokensBlocking(long timeoutMs) {
        ScrollTrackingWebView w = peekWebView();
        if (w == null) return "";
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final String[] out = new String[2];
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                final ScrollTrackingWebView w2 = peekWebView();
                if (w2 == null) { latch.countDown(); return; }
                w2.evaluateJavascript(JS_READ_TOKENS, value -> {
                    try {
                        org.json.JSONObject j = new org.json.JSONObject(value == null ? "{}" : value);
                        out[0] = j.optString("t");
                        out[1] = j.optString("r");
                    } catch (Exception ignored) {
                    }
                    latch.countDown();
                });
            }
        });
        try {
            latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        if (out[0] == null || out[1] == null) return "";
        return out[0] + "|" + out[1];
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
        // Dopo il refresh torno alla chat che stavo usando (URL catturato al
        // momento del heal), non alla pagina base: cosi schermo spento/acceso
        // dentro una chat la mantiene, anche se il token era scaduto.
        String ret = mHealReturnUrl;
        if (ret == null || ret.isEmpty()) ret = startupUrl();
        App.log("AUTH-HEAL: token scritto, ricarico " + ret);
        webView.loadUrl(ret);
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

    // Schermo acceso in una chat: prima del resync rinnovo il token dai
    // prefs (il keepalive lo tiene fresco), cosi la pagina riparte con un
    // access token valido e resta nella chat invece di cadere sul login.
    // Il refresh della SPA da solo non basta: dopo 50 min il keepalive ha
    // gia' ruotato il refresh token nei prefs e quello della pagina e'
    // morto (rotazione monouso), quindi la SPA finirebbe su /login.
    // Se il refresh e' rifiutato (logout o sessione morta) svuoto lo stato
    // locale e vado al login, come fa la SPA stessa; un errore di rete non
    // tocca lo stato locale e faccio il resync normale.
    private void wakeRefreshThenResync() {
        String pageUrl = webView.getUrl();
        if (pageUrl == null || !pageUrl.contains("chat")) {
            autoResync("screen-on");
            return;
        }
        String base = currentUrl;
        if (base == null || base.isEmpty()) {
            autoResync("screen-on");
            return;
        }
        String rtPrefs = getSharedPreferences(PREFS, MODE_PRIVATE).getString("auth_refresh", "");
        if (rtPrefs.isEmpty()) {
            App.log("AUTH-WAKE: nessun refresh nei prefs, resync normale");
            autoResync("screen-on");
            return;
        }
        new Thread(() -> {
            try {
                java.net.URL u = new java.net.URL(base + "/api/auth/refresh");
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                c.setDoOutput(true);
                c.getOutputStream().write(("{\"refresh_token\":\"" + rtPrefs + "\"}").getBytes("UTF-8"));
                int code = c.getResponseCode();
                if (code == 401) {
                    App.log("AUTH-WAKE: refresh rifiutato (401), sessione morta");
                    runOnUiThread(() -> healFailed());
                    return;
                }
                if (code != 200) {
                    App.log("AUTH-WAKE: refresh HTTP " + code + ", resync normale");
                    runOnUiThread(() -> autoResync("screen-on"));
                    return;
                }
                java.io.InputStream in = c.getInputStream();
                StringBuilder sb = new StringBuilder();
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(in, "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                org.json.JSONObject j = new org.json.JSONObject(sb.toString());
                final String fat = j.optString("access_token");
                final String fnrt = j.optString("refresh_token");
                if (fat.isEmpty() || fnrt.isEmpty()) {
                    App.log("AUTH-WAKE: 200 senza token, resync normale");
                    runOnUiThread(() -> autoResync("screen-on"));
                    return;
                }
                App.log("AUTH-WAKE: refresh ok, inietto il token fresco");
                runOnUiThread(() -> {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString("auth_access", fat).putString("auth_refresh", fnrt)
                            .putLong("auth_synced_ts", System.currentTimeMillis()).apply();
                    String js = "(function(){try{localStorage.setItem('unsloth_auth_token','" + fat + "');" +
                            "localStorage.setItem('unsloth_auth_refresh_token','" + fnrt + "');}catch(e){}})()";
                    webView.evaluateJavascript(js, null);
                    autoResync("screen-on");
                });
            } catch (Exception e) {
                App.log("AUTH-WAKE: refresh errore " + e.getMessage() + ", resync normale");
                runOnUiThread(() -> autoResync("screen-on"));
            }
        }).start();
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
                // Il keepalive ruota il refresh token nei prefs senza aggiornare
                // la pagina: dopo la rotazione i token della pagina sono vecchi.
                // Se li salvassi nei prefs cancellerei l'unico refresh ancora
                // vivo e al prossimo heal la sessione morrebbe. Salvo i token
                // della pagina solo se sono almeno freschi quanto quelli dei
                // prefs (stesso server: il confronto di exp e' affidabile).
                SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
                String atPrefs = p.getString("auth_access", "");
                long expPage = jwtExpMs(at);
                long expPrefs = atPrefs.isEmpty() ? -1 : jwtExpMs(atPrefs);
                if (expPage < 0 || expPrefs > expPage) {
                    App.log("SYNC-TOKENS: token pagina non piu' fresco dei prefs, salto");
                    return;
                }
                p.edit()
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
        sActivityAlive = true;
        // v3.0: marker del crash guard (file in getFilesDir): ora dell'ultimo
        // primo piano; e allarme ripetuto (~30 s) attivo finche l'app e' in
        // uso: se il processo muore, l'allarme riparte il processo e
        // CrashTickReceiver riavvia l'app.
        writeCrashGuardMarker(String.valueOf(System.currentTimeMillis()));
        scheduleCrashGuardTick();
        // v3.0: se l'app e' tornata in primo piano (anche dopo il riavvio del
        // crash guard via FSI), la notifica di riavvio non serve piu'.
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(CrashTickReceiver.RESTART_NOTIF_ID);
        } catch (Exception ignored) {}
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
        // Salvo la posizione di scroll del thread corrente: serve a
        // "riprendere da dove eri" all'avvio successivo (processo killato).
        try {
            String tid = currentThreadId();
            if (tid != null && !tid.isEmpty() && mScrollY > 0) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putLong("scroll_" + tid, (long) mScrollY).apply();
            }
        } catch (Exception e) {
            // ignora
        }
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
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(gi); else startService(gi);
            } catch (Exception e) {
                // Schermo spento: l'app e' gia' in background e Android 12+
                // vieta startForegroundService da qui -> senza catch il
                // processo crasha e al rientro si parte dalla pagina base.
                // Il service, se gia' partito col POST chat-runs, continua;
                // al rientro onResume riprova l'avvio (startGenServiceNow).
                App.log("GEN: avvio FGS in onPause fallito " + e.getMessage());
            }
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
        sActivityAlive = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // v3.0: in background: se il processo muore qui, il crash guard non
        // deve riavviare l'app (marker "closed"); tolgo anche l'allarme.
        writeCrashGuardMarker("closed");
        cancelCrashGuardTick();
    }

    // v3.0: marker del crash guard. File in getFilesDir (cartella
    // /data/data/<pkg>/files): timestamp dell'ultimo primo piano o
    // "closed" se l'app e' in background/chiusa. Lo legge
    // CrashTickReceiver per decidere se riavviare.
    private void writeCrashGuardMarker(String content) {
        try {
            java.io.File f = new java.io.File(getFilesDir(), "crashguard_marker");
            java.io.FileWriter fw = new java.io.FileWriter(f);
            fw.write(content);
            fw.close();
        } catch (Exception e) {
            // non critico: senza marker il crash guard non fa niente
        }
    }

    // v3.0: allarme impreciso ripetuto (~30 s, request code 2), attivo solo
    // mentre l'app e' in uso (onResume lo programma, onStop lo cancella).
    // Se il processo principale muore, l'allarme persiste nel sistema: alla
    // scadenza Android riparte il processo principale per consegnare il
    // broadcast a CrashTickReceiver, che (solo se l'app era morta) posta una
    // notifica FSI: schermo off -> l'activity si apre da sola, schermo on ->
    // heads-up e un tap la apre (BAL: su Android 14 ogni via di
    // startActivity dal background e' chiusa, verificate).
    private void scheduleCrashGuardTick() {
        try {
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(this, CrashTickReceiver.class);
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(this, 2, i,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            am.setRepeating(android.app.AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 30000, 30000, pi);
            App.log("CRASHGUARD: allarme tick programmato (~30 s)");
        } catch (Exception e) {
            App.log("CRASHGUARD: scheduling errore " + e.getMessage());
        }
    }

    private void cancelCrashGuardTick() {
        try {
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(this, CrashTickReceiver.class);
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(this, 2, i,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            am.cancel(pi);
        } catch (Exception e) {
            // ignora
        }
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
                    String ip = p.getString("host", "");
                    String ddns = p.getString("ddns", "");
                    String port = p.getString("port", "8888");
                    String tok = p.getString("auth_access", "");
                    if ((ip.isEmpty() && ddns.isEmpty()) || tok.isEmpty()) {
                        App.log("SYNC: host o token assenti, salto");
                        return;
                    }
                    String base = App.baseUrl(App.resolveHost(ip, ddns, port), port);
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

    // Il run nell'array e' "mio" se contiene l'id catturato dal polling
    // eventi di questa app (mMyRunId): vale anche oltre la finestra di 10 min
    // di isRecentGen(), cosi un run lungo partito da qui non diventa
    // "cross-client" con banner e blocco invio.
    private boolean isMyRun(org.json.JSONArray runs) {
        String id = mMyRunId;
        if (id == null || id.isEmpty()) return false;
        for (int i = 0; i < runs.length(); i++) {
            org.json.JSONObject o = runs.optJSONObject(i);
            if (o != null && id.equals(o.optString("id"))) return true;
        }
        return false;
    }

    private void pollActiveRuns() {
        // mPageUrl, non webView.getUrl(): questo gira su un thread di
        // background e gli accessi WebView fuori dal thread UI lancia.
        String url = mPageUrl;
        if (url == null || !url.contains("chat")) return;
        final String tid = currentThreadId();
        if (tid.isEmpty()) return;
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String ip = p.getString("host", "");
        String ddns = p.getString("ddns", "");
        String port = p.getString("port", "8888");
        String tok = p.getString("auth_access", "");
        if ((ip.isEmpty() && ddns.isEmpty()) || tok.isEmpty()) return;
        String base = App.baseUrl(App.resolveHost(ip, ddns, port), port);
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
                if (isMyRun(runs) || isRecentGen()) {
                    // Run avviato da questo telefono (id == mMyRunId oppure
                    // partenza recente): il UI di Studio lo mostra gia' in
                    // corso, niente blocco: l'input resta libero e i messaggi
                    // si mettono in coda come al solito.
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
                    // v2.7: mirroring. Apri l'SSE nativo del run esterno:
                    // streamma i chunk nel banner e tiene il run vivo
                    // (follower "attento", stopWhenUnattended). Se e' un run
                    // diverso da quello gia' aperto, riparte da zero:
                    // cursore azzerato e testo azzerato.
                    final String extId = runs.getJSONObject(0).optString("id", "");
                    if (!extId.isEmpty() && !extId.equals(mMirrorRunId)) {
                        mMirrorAfter = 0;
                        synchronized (mMirrorText) { mMirrorText.setLength(0); }
                        mMirrorRunId = extId;
                        App.log("MIRROR: SSE del run esterno " + extId);
                        startMirrorSse(base, extId);
                    }
                    // Banner e lock a ogni tick (idempotenti): se la prima
                    // iniezione fallisce (pagina ancora in caricamento, JS
                    // null) i tick successivi la ripetono; se Studio
                    // re-render elimina il div, il tick dopo lo reinietta.
                    // hideRetryBox: finche' il run esterno e' attivo, nasconde
                    // il box errore "interrupted" della SPA (Retry inutile,
                    // darebbe 409 sul run in corso).
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setGenBanner(true);
                            lockComposer(true);
                            hideRetryBox(true);
                        }
                    });
                }
            } else {
                // Nessun run attivo. Se l'id del "run mio" era salvato, il run
                // e' appena finito: lo azzero (conta solo mentre e' ancora
                // attivo, per riconoscerlo come mio nel poll /active).
                final boolean wasMyRun = mMyRunId != null && !mMyRunId.isEmpty();
                if (wasMyRun) {
                    mMyRunId = null;
                    getSharedPreferences(PREFS, MODE_PRIVATE)
                            .edit().remove("my_run_id").apply();
                }
                if (mRunActive && !tid.equals(mSawActiveThread)) {
                    // Cambio di thread con un run attivo in un altro: azzero
                    // banner, lock e mirroring, senza resync (il run non
                    // riguarda questa chat).
                    mRunActive = false;
                    mSawActiveThread = "";
                    stopMirror();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setGenBanner(false);
                            lockComposer(false);
                            hideRetryBox(false);
                        }
                    });
                } else if (mRunActive) {
                    // Run finito nel thread corrente: sblocco. Se era un run
                    // MIO (partito da questa app) Studio ha gia' streamato la
                    // risposta in pagina: niente resync. Se era partito da un
                    // altro client (Studio non l'ha mai "visto"),
                    // ri-sincronizzo la chat dal server.
                    mRunActive = false;
                    mSawActiveThread = "";
                    stopMirror();
                    if (wasMyRun) {
                        App.log("RUN-POLL: mio run finito, niente resync");
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                setGenBanner(false);
                                lockComposer(false);
                                hideRetryBox(false);
                            }
                        });
                    } else {
                        App.log("RUN-POLL: run finito, resync");
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                setGenBanner(false);
                                lockComposer(false);
                                hideRetryBox(false);
                                String u2 = webView.getUrl();
                                if (u2 != null && u2.contains(tid) && !isRecentGen()) genFinitaResync();
                            }
                        });
                    }
                }
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
            // v2.8: la pagina potrebbe avere un refresh token piu' fresco
            // (ruotato dall'SPA dopo l'ultimo sync nei prefs): se c'e', uso
            // quello, altrimenti il refresh qui consumerebbe il token sbagliato.
            String pageTok = readPageTokensBlocking(5000L);
            if (!pageTok.isEmpty()) {
                String prt = pageTok.substring(pageTok.indexOf('|') + 1);
                if (!prt.equals(rt)) rt = prt;
            }
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
            // v2.8: riscrivo i token freschi nella pagina, altrimenti l'SPA
            // resterebbe con il refresh token appena consumato (401 al suo
            // prossimo refresh e caduta su /login).
            injectAuthTokensToPage(at, nrt);
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
                // v2.7: se il banner mostra il mirroring di un run esterno,
                // la riga sotto e' la preview del testo generato (ultimi 220
                // caratteri, ridotta a una riga), non "Attendi".
                String sub = jsEsc(getString(R.string.gen_wait));
                if (mMirrorRunId != null) {
                    synchronized (mMirrorText) {
                        int len = mMirrorText.length();
                        if (len > 0) {
                            String t = mMirrorText.substring(len > 220 ? len - 220 : 0, len);
                            t = t.replace("\n", " ").replace("\r", " ");
                            sub = jsEsc(t);
                        }
                    }
                }
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

    // v2.7: chiude il mirroring: ferma il thread SSE, azzera l'id del run
    // e svuota il testo. Idempotente: viene chiamato anche quando non c'e'
    // niente da chiudere (run "mio", cambio di thread), lascia stato pulito.
    private void stopMirror() {
        mMirrorStop = true;
        mMirrorRunId = null;
        synchronized (mMirrorText) { mMirrorText.setLength(0); }
        if (mMirrorThread != null) {
            mMirrorThread.interrupt();
            mMirrorThread = null;
        }
    }

    // v2.7: SSE nativo del run esterno (POST /chat-runs/<id>/events?after=N,
    // stesso pattern di GenService.openSse). Finche' e' aperto, Studio conta
    // l'app come follower "attento" e non ferma un run stopWhenUnattended.
    // I chunk vengono appesi a mMirrorText (solo preview: si tiene la coda,
    // tetto 200k caratteri) e updateMirrorPreview li sposta nella riga sotto
    // del banner ogni 400 ms.
    // Riaperture: 401 -> rinnovo token e riprovo; stream caduto -> riapero
    // dall'ultimo cursore (linee id: delle frame), niente replay dei chunk
    // gia' ricevuti; stream chiuso pulito -> il backend lo chiude solo
    // quando il run e' terminale (o l'id e' sparito): segno la fine qui, il
    // poller fa il resync e le notifiche come d'abitudine.
    private void startMirrorSse(final String base, final String runId) {
        if (mMirrorThread != null) {
            mMirrorStop = true;
            mMirrorThread.interrupt();
            mMirrorThread = null;
        }
        mMirrorStop = false;
        mMirrorAfter = 0;
        synchronized (mMirrorText) { mMirrorText.setLength(0); }
        mLastMirrorUiMs = 0;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!mMirrorStop && runId.equals(mMirrorRunId)) {
                    long after = mMirrorAfter;
                    java.net.HttpURLConnection conn = null;
                    boolean failed = false;
                    try {
                        String tok = getSharedPreferences(PREFS, MODE_PRIVATE).getString("auth_access", "");
                        if (tok.isEmpty()) {
                            App.log("MIRROR: token assente, attendo");
                            Thread.sleep(3000);
                            continue;
                        }
                        java.net.URL u = new java.net.URL(base
                                + "/api/inference/chat-runs/" + runId + "/events?after=" + after);
                        conn = (java.net.HttpURLConnection) u.openConnection();
                        conn.setRequestMethod("POST");
                        conn.setRequestProperty("Authorization", "Bearer " + tok);
                        conn.setRequestProperty("Accept", "text/event-stream");
                        conn.setRequestProperty("Content-Type", "application/json");
                        conn.setConnectTimeout(15000);
                        // Larga: i keep-alive del server arrivano ogni 15s anche
                        // a vuoto, ma un run lungo non deve farla morire.
                        conn.setReadTimeout(300000);
                        conn.setDoOutput(true);
                        conn.getOutputStream().write("{}".getBytes("UTF-8"));
                        int code = conn.getResponseCode();
                        if (code == 401) {
                            if (System.currentTimeMillis() - mLastAuthRefreshMs > 60 * 1000L) {
                                mLastAuthRefreshMs = System.currentTimeMillis();
                                refreshAuthTokens(base);
                            }
                            App.log("MIRROR: 401, rinnovo token e riprovo");
                            Thread.sleep(3000);
                            continue;
                        }
                        if (code != 200) {
                            App.log("MIRROR: SSE HTTP " + code + ", attendo");
                            Thread.sleep(3000);
                            continue;
                        }
                        App.log("MIRROR: SSE aperto per " + runId + " (after=" + after + ")");
                        java.io.BufferedReader r = new java.io.BufferedReader(
                                new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
                        String line;
                        while (!mMirrorStop && (line = r.readLine()) != null) {
                            if (line.startsWith("id:")) {
                                // Cursore della frame: su riapertura riparto da qui.
                                try {
                                    long seq = Long.parseLong(line.substring(3).trim());
                                    if (seq > mMirrorAfter) mMirrorAfter = seq;
                                } catch (Exception e) {
                                }
                            } else if (line.startsWith("data:")) {
                                try {
                                    org.json.JSONObject j = new org.json.JSONObject(line.substring(5).trim());
                                    String type = j.optString("type", "");
                                    if ("chunk".equals(type)) {
                                        org.json.JSONObject payload = j.optJSONObject("payload");
                                        if (payload != null) {
                                            org.json.JSONArray choices = payload.optJSONArray("choices");
                                            if (choices != null && choices.length() > 0) {
                                                org.json.JSONObject choice = choices.optJSONObject(0);
                                                if (choice != null) {
                                                    org.json.JSONObject delta = choice.optJSONObject("delta");
                                                    if (delta != null) {
                                                        String content = delta.optString("content", "");
                                                        if (!content.isEmpty()) {
                                                            synchronized (mMirrorText) {
                                                                mMirrorText.append(content);
                                                                if (mMirrorText.length() > 200000) {
                                                                    mMirrorText.delete(0, mMirrorText.length() - 200000);
                                                                }
                                                            }
                                                            updateMirrorPreview();
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        // Evento non chunk (done, error, ...): porta il run.
                                        org.json.JSONObject run = j.optJSONObject("run");
                                        if (run != null) {
                                            String status = run.optString("status", "");
                                            if (status.equals("completed") || status.equals("failed")
                                                    || status.equals("cancelled") || status.equals("cancelling")) {
                                                App.log("MIRROR: run " + runId + " terminale (" + status + ")");
                                                mMirrorStop = true;
                                                mMirrorRunId = null;
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    // Frame malformata: ignoro
                                }
                            }
                            // Righe keep-alive (" : keep-alive ...") e vuote: niente.
                        }
                    } catch (Exception e) {
                        failed = true;
                        if (!mMirrorStop) App.log("MIRROR: SSE chiuso (" + e.getMessage() + ")");
                    } finally {
                        if (conn != null) {
                            try { conn.disconnect(); } catch (Exception e) {
                            }
                        }
                    }
                    if (mMirrorStop || !runId.equals(mMirrorRunId)) return;
                    if (!failed) {
                        // Chiusura pulita: il backend chiude lo stream solo
                        // quando il run e' terminale (o l'id non esiste piu'):
                        // segno la fine; il poller fa resync e notifica.
                        App.log("MIRROR: stream terminato, run chiuso");
                        mMirrorStop = true;
                        mMirrorRunId = null;
                        return;
                    }
                    // Caduta della connessione: riprovo dall'ultimo cursore.
                    try { Thread.sleep(1500); } catch (InterruptedException e) { return; }
                }
                App.log("MIRROR: thread SSE chiuso (" + runId + ")");
            }
        }, "mirror-sse");
        t.start();
        mMirrorThread = t;
    }

    // v2.7: aggiorna la riga sotto del banner con la preview del testo
    // generato dal run esterno (ultimi ~200 caratteri, una sola riga).
    // Chiamata dal thread SSE, throttata a 400 ms; il banner viene
    // reiniettato a ogni tick da setGenBanner, che legge mMirrorText
    // direttamente, quindi la preview non si perde al re-render.
    private void updateMirrorPreview() {
        long now = System.currentTimeMillis();
        if (now - mLastMirrorUiMs < 400) return;
        mLastMirrorUiMs = now;
        final String preview;
        synchronized (mMirrorText) {
            int len = mMirrorText.length();
            if (len == 0) return;
            preview = mMirrorText.substring(len > 200 ? len - 200 : 0, len)
                    .replace("\n", " ").replace("\r", " ");
        }
        final String js = "(function(){"
                + "var b=document.getElementById('unsloth-gen-banner');"
                + "if(!b)return;"
                + "var s=b.querySelector('.unsloth-gen-sub');"
                + "if(s)s.textContent=" + jsEsc("'" + preview + "'") + ";"
                + "})();";
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    webView.evaluateJavascript(js, null);
                } catch (Exception e) {
                    App.log("MIRROR: preview errore " + e.getMessage());
                }
            }
        });
    }

    // v2.7: finche' il run esterno e' attivo, nasconde il box errore
    // "interrupted" della SPA (con il Retry inutile, che darebbe 409).
    // Stessa tecnica di iniezione del banner: uno stile iniettato una
    // volta, una classe su body che il poller toglie/mette a ogni tick
    // (idempotente). A run finito la classe viene rimossa e il resync
    // ricarica la pagina, quindi la pagina torna pulita.
    private void hideRetryBox(final boolean hide) {
        try {
            String js = "(function(){"
                    + "if(!window.__unslothRetryStyle){"
                    + "var st=document.createElement('style');"
                    + "st.id='unsloth-retry-hide-style';"
                    + "st.textContent='body.unsloth-mirror .aui-message-error-root{display:none !important}';"
                    + "document.head.appendChild(st);window.__unslothRetryStyle=true;}"
                    + "if(document.body)"
                    + (hide ? "document.body.classList.add('unsloth-mirror');"
                             : "document.body.classList.remove('unsloth-mirror');")
                    + "})();";
            webView.evaluateJavascript(js, null);
        } catch (Exception e) {
            App.log("MIRROR: hideRetryBox errore " + e.getMessage());
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
                logText.setText(sb.length() == 0 ? getString(R.string.no_log_yet) : sb.toString());
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

    // v2.9: svuota crash.log. Truncatura (non delete): il crash handler
    // riapre il file in append a ogni riga, cosi l'inode resta valido e il
    // successivo App.log/crash ricrea le righe da zero. La conferma dello
    // svuotamento e' un toast (nessuna riga di marca nel file).
    private void clearLog() {
        try {
            File f = App.getLogFile();
            if (f == null || !f.exists()) {
                Toast.makeText(this, getString(R.string.no_log_yet), Toast.LENGTH_LONG).show();
                return;
            }
            java.io.FileWriter fw = new java.io.FileWriter(f, false);
            fw.close();
            Toast.makeText(this, getString(R.string.log_cleared), Toast.LENGTH_LONG).show();
            // Ricarica il pannello solo se e' aperto: da Options resto dove sono.
            if (logPanel.getVisibility() == View.VISIBLE) showLog();
        } catch (Exception e) {
            App.log("CLEAR LOG FAILED: " + e.getMessage());
        }
    }

    // v2: interroga l'API pubblica GitHub (releases/latest) e restituisce la
    // release più recente di APP_VERSION con asset Unsloth.apk (o error=true).
    // Senza token (repo pubblico); senza rete o su errore torna error=true. Il
    // pref update_api_base permette di puntare il check a un altro endpoint
    // (usato nel collaudo emulatore).
    private static class UpdateCheckResult {
        String version = "";   // nuova versione, se c'è
        String apkUrl = null;  // URL asset, se c'è
        boolean error = false;
    }

    private UpdateCheckResult fetchLatestRelease() {
        UpdateCheckResult r = new UpdateCheckResult();
        try {
            String base = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString("update_api_base",
                            "https://api.github.com/repos/simplewebit/unsloth-android");
            HttpURLConnection conn = (HttpURLConnection) new URL(base + "/releases/latest").openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "unsloth-android-" + APP_VERSION);
            int code = conn.getResponseCode();
            if (code != 200) {
                App.log("UPDATE-CHECK: HTTP " + code + ", salto");
                r.error = true;
                return r;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            InputStream in = conn.getInputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            String json = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            JSONObject rel = new JSONObject(json);
            String tag = rel.optString("tag_name", "");
            String ver = tag.startsWith("v") ? tag.substring(1) : tag;
            if (ver.isEmpty() || !isVersionNewer(ver, APP_VERSION)) {
                App.log("UPDATE-CHECK: ultima release " + tag + ", niente da fare");
                return r;
            }
            String apkUrl = null;
            JSONArray assets = rel.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if ("Unsloth.apk".equals(a.optString("name"))) {
                        apkUrl = a.optString("browser_download_url");
                        break;
                    }
                }
            }
            if (apkUrl == null || apkUrl.isEmpty()) {
                App.log("UPDATE-CHECK: release " + tag + " senza asset Unsloth.apk, salto");
                r.error = true;
                return r;
            }
            r.version = ver;
            r.apkUrl = apkUrl;
        } catch (Exception e) {
            App.log("UPDATE-CHECK FAILED: " + e.getMessage());
            r.error = true;
        }
        return r;
    }

    // v2: all'avvio (cold start): se c'è una release più recente mostra il
    // banner in-app.
    private void checkForNewVersion() {
        if (mUpdateChecking) return;
        mUpdateChecking = true;
        new Thread(() -> {
            try {
                UpdateCheckResult r = fetchLatestRelease();
                if (!r.error && r.apkUrl != null) {
                    mUpdateApkUrl = r.apkUrl;
                    mUpdateAvailable = true;
                    runOnUiThread(() -> {
                        updateText.setText(getString(R.string.update_available, r.version));
                        updateBar();
                    });
                    App.log("UPDATE-CHECK: nuova versione " + r.version + ", banner mostrato");
                }
            } finally {
                mUpdateChecking = false;
            }
        }).start();
    }

    // v2: pulsante "Controlla aggiornamenti" nella pagina Credits: check
    // manuale e, se c'è una release più recente, avvia subito download +
    // installazione. Il risultato si vede in update_status.
    private void manualUpdateCheck() {
        if (mUpdateChecking) return;
        mUpdateChecking = true;
        runOnUiThread(() -> {
            updateStatus.setTextColor(getResources().getColor(R.color.text_muted));
            updateStatus.setText(getString(R.string.update_checking));
            btnCheckUpdates.setEnabled(false);
        });
        new Thread(() -> {
            try {
                UpdateCheckResult r = fetchLatestRelease();
                runOnUiThread(() -> {
                    if (r.error) {
                        updateStatus.setTextColor(getResources().getColor(R.color.status_error));
                        updateStatus.setText(getString(R.string.update_check_failed));
                    } else if (r.apkUrl != null) {
                        mUpdateApkUrl = r.apkUrl;
                        updateStatus.setTextColor(getResources().getColor(R.color.status_ok));
                        updateStatus.setText(getString(R.string.update_available, r.version));
                        App.log("UPDATE-MANUAL: nuova versione " + r.version + ", avvio aggiornamento");
                        downloadAndInstall(r.apkUrl);
                    } else {
                        updateStatus.setTextColor(getResources().getColor(R.color.status_ok));
                        updateStatus.setText(getString(R.string.update_up_to_date));
                        App.log("UPDATE-MANUAL: sei aggiornato");
                    }
                });
            } finally {
                mUpdateChecking = false;
                runOnUiThread(() -> btnCheckUpdates.setEnabled(true));
            }
        }).start();
    }

    // Confronto numerico di versioni dotate ("2.1" > "2.0" > "1.8").
    private static boolean isVersionNewer(String a, String b) {
        int[] va = parseVersion(a);
        int[] vb = parseVersion(b);
        int len = Math.max(va.length, vb.length);
        for (int i = 0; i < len; i++) {
            int x = i < va.length ? va[i] : 0;
            int y = i < vb.length ? vb[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] parseVersion(String s) {
        String[] p = s.split("\\.");
        int[] out = new int[p.length];
        for (int i = 0; i < p.length; i++) {
            try {
                out[i] = Integer.parseInt(p[i].trim());
            } catch (Exception e) {
                out[i] = 0;
            }
        }
        return out;
    }

    // v2: tap sul banner -> (se serve) permesso "installa app sconosciute",
    // poi download nativo dell'APK in getFilesDir()/update/ e intento di
    // installazione.
    private void startUpdate() {
        if (mUpdateApkUrl == null || mUpdateApkUrl.isEmpty()) return;
        // REQUEST_INSTALL_PACKAGES e' un tipo protetto: requestPermissions non
        // mostra alcun dialogo e torna DENIED subito (Android 11+). Il permesso
        // si concede tramite PackageInstaller quando parte l'intento di
        // installazione, quindi qui si va dritti al download.
        downloadAndInstall(mUpdateApkUrl);
    }

    private void downloadAndInstall(final String url) {
        if (mUpdateDownloading) return;
        mUpdateDownloading = true;
        runOnUiThread(() -> Toast.makeText(this, getString(R.string.update_downloading), Toast.LENGTH_LONG).show());
        new Thread(() -> {
            File out = null;
            try {
                File dir = new File(getFilesDir(), "update");
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("crea cartella update");
                out = new File(dir, "Unsloth.apk");
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                conn.setRequestProperty("User-Agent", "unsloth-android-" + APP_VERSION);
                int code = conn.getResponseCode();
                if (code != 200) throw new IOException("HTTP " + code);
                InputStream in = conn.getInputStream();
                FileOutputStream fos = new FileOutputStream(out);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                fos.close();
                in.close();
                App.log("UPDATE: APK scaricato, " + out.length() + " byte");
                final File downloaded = out;
                runOnUiThread(() -> installApk(downloaded));
            } catch (Exception e) {
                App.log("UPDATE DOWNLOAD FAILED: " + e.getMessage());
                if (out != null) out.delete();
                final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                runOnUiThread(() -> Toast.makeText(this, getString(R.string.update_error, msg), Toast.LENGTH_LONG).show());
            } finally {
                mUpdateDownloading = false;
            }
        }).start();
    }

    // Intent di installazione: FileProvider (gia' nel manifest) + ACTION_VIEW
    // con tipo application/vnd.android.package-archive.
    private void installApk(File f) {
        try {
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, "it.mattia.unsloth.fileprovider", f);
            Intent inst = new Intent(Intent.ACTION_VIEW);
            inst.setDataAndType(uri, "application/vnd.android.package-archive");
            inst.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            App.log("UPDATE: intento installazione " + uri);
            startActivity(inst);
        } catch (Exception e) {
            App.log("UPDATE INSTALL FAILED: " + e.getMessage());
            Toast.makeText(this, getString(R.string.update_error, e.getMessage()), Toast.LENGTH_LONG).show();
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

        // v2.5: l'SPA non mette l'id della chat nuova nell'URL (resta
        // ?new=<nonce>), quindi il hook su fetch nel bridge manda qui l'id
        // letto dalla risposta di POST /api/chat/threads: cosi "Torna alla
        // chat" riprende la chat nuova come ultima chat attiva.
        @JavascriptInterface
        public void reportNewThread(String id) {
            if (id == null || id.isEmpty()) return;
            App.log("NEW-THREAD: " + id);
            mLastThread = id;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("last_thread", id).apply();
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
                // v2.5: l'SPA crea la chat nuova con POST /api/chat/threads e
                // l'URL resta ?new=<nonce> (l'id del thread non finisce mai
                // nell'URL). Hook su fetch: se la POST va a buon fine, leggo
                // l'id dalla risposta e lo passo al bridge, cosi mLastThread
                // segue la chat nuova anche senza ?thread= nell'URL.
                "var of = window.fetch;" +
                "if (typeof of === 'function') {" +
                "  window.fetch = function(u, o) {" +
                "    try {" +
                "      var s = typeof u === 'string' ? u : (u && u.url) || '';" +
                "      var q = s.indexOf('?');" +
                "      if (q >= 0) s = s.substring(0, q);" +
                "      var m = o && typeof o.method === 'string' ? o.method.toUpperCase() : 'GET';" +
                "      if (m === 'POST' && s.length >= 17 && s.substring(s.length - 17) === '/api/chat/threads') {" +
                "        var p = of.apply(this, arguments);" +
                "        p.then(function(r) {" +
                "          if (r && r.ok) {" +
                "            r.clone().json().then(function(d) {" +
                "              if (d && typeof d.id === 'string') {" +
                "                try { UnslothBridge.reportNewThread(d.id); } catch(e) {}" +
                "              }" +
                "            }).catch(function() {});" +
                "          }" +
                "        }).catch(function() {});" +
                "        return p;" +
                "      }" +
                "    } catch(e) {}" +
                "    return of.apply(this, arguments);" +
                "  };" +
                "}" +
                "})();";
        webView.evaluateJavascript(js, null);
    }

    // v3.0: applica l'aspetto scelto nelle opzioni alla pagina Studio:
    // tema (localStorage.theme) e palette (localStorage.palette). L'SPA li
    // legge in theme-boot.js al primo paint (classi dark/light e
    // data-palette su <html>); "system" fa seguire il telefono
    // (prefers-color-scheme del WebView). Qui sotto applico anche subito il
    // data-palette all'html aperto: il CSS usa selettori html[data-palette]
    // e la pagina si ricolora a caldo senza reload. Si reinietta a ogni
    // navigazione: clearCache(true) (refresh/Home) puo' azzerare il
    // localStorage del WebView.
    private void injectAppearanceJs() {
        if (webView == null) return;
        String t = (mTheme == null) ? "system" : mTheme;
        String p = (mPalette == null) ? "standard" : mPalette;
        // Le variabili vanno dichiarate come locali nel JS iniettato: un
        // identificatore nudo (p) fallisce con ReferenceError nel realm
        // post-commit, dove non esiste alcun globale p (errore inghiottito
        // dal catch, pagina mai ricolorata a caldo).
        String js = "(function(){" +
                "var t = '" + t + "';" +
                "var p = '" + p + "';" +
                "try {" +
                "  localStorage.setItem('theme', t);" +
                "  localStorage.setItem('palette', p);" +
                "  var r = document.documentElement;" +
                "  if (r) {" +
                "    if (p === 'standard') {" +
                "      r.removeAttribute('data-palette');" +
                "    } else {" +
                "      r.setAttribute('data-palette', p);" +
                "    }" +
                "  }" +
                "  UnslothBridge.appLog('THEME: ' + t + ' PALETTE: ' + p);" +
                "} catch(e) {" +
                "  try { UnslothBridge.appLog('INJECT ERRORE: ' + (e && e.message)); } catch(e2) {}" +
                "}" +
                "})()";
        webView.evaluateJavascript(js, null);
    }

    // Nasconde il toast "Model loaded with a warning" (toast sonner con
    // id logico model-load-warning) che l'SPA mostra a ogni load di modello
    // con memory_warning. L'elemento <li data-sonner-toast> nel DOM non
    // porta l'id (verificato nel bundle dist), quindi non basta un
    // selettore CSS puro: inietto un observer che riconosce il toast dal
    // testo del titolo e lo tiene display:none (inline !important,
    // riapplicato a ogni re-render di React che tocca style/class).
    private void injectWarningHider() {
        // L'observer va su document.documentElement, che esiste in
        // qualsiasi momento: niente attesa di readyState (in pratica
        // non si raggiungeva mai, il self-test tornava sempre
        // no-result). Se il documento viene sostituito l'observer
        // muore, ma reinietto su onPageStarted/onPageCommitVisible/
        // onPageFinished e la guardia window.__unslothToastGuard evita
        // i doppi nel stesso documento.
        // Attenzione: a onPageStarted/onPageCommitVisible il documento
        // esiste ma non ha ancora la radice <html>
        // (document.documentElement e' null): observe() getta
        // "parameter 1 is not of type 'Node'". La guardia
        // __unslothToastGuard si mette solo quando l'observer viene
        // installato davvero; se la radice non c'e' la reiniezione a
        // onPageFinished (documento completo) installa l'observer.
        String js = "(function(){" +
                "if (window.__unslothToastGuard) return 'guard';" +
                "try {" +
                "  UnslothBridge.appLog('TOAST-HIDER: probe ' + document.readyState + ' | ' + location.href.slice(0, 80));" +
                "var T = 'loaded with a warning';" +
                "function isTarget(el){" +
                "  if (!el || el.nodeType !== 1 || !el.querySelector) return false;" +
                "  var t = el.querySelector('[data-title]');" +
                "  return !!(t && t.textContent.toLowerCase().indexOf(T) !== -1);" +
                "}" +
                "function hide(el){" +
                "  el.style.setProperty('display', 'none', 'important');" +
                "  try { UnslothBridge.appLog('TOAST-HIDER: toast nascosto (display:none)'); } catch(e) {}" +
                "}" +
                "function scan(){" +
                "  var its = document.querySelectorAll('li[data-sonner-toast]');" +
                "  for (var i = 0; i < its.length; i++) if (isTarget(its[i])) hide(its[i]);" +
                "}" +
                "if (!document.documentElement) return;" +
                "new MutationObserver(function(ms){" +
                "  for (var i = 0; i < ms.length; i++) {" +
                "    var m = ms[i];" +
                "    if (m.type === 'childList') {" +
                "      var ns = m.addedNodes;" +
                "      for (var j = 0; j < ns.length; j++) {" +
                "        var el = ns[j];" +
                "        if (!el || el.nodeType !== 1) continue;" +
                "        if (el.matches && el.matches('li[data-sonner-toast]')) {" +
                "          if (isTarget(el)) hide(el);" +
                "        } else if (el.querySelectorAll) {" +
                "          var its = el.querySelectorAll('li[data-sonner-toast]');" +
                "          for (var k = 0; k < its.length; k++) if (isTarget(its[k])) hide(its[k]);" +
                "        }" +
                "      }" +
                "    } else if (m.type === 'attributes' && m.target && m.target.nodeType === 1" +
                "      && m.target.matches && m.target.matches('li[data-sonner-toast]')) {" +
                "      if (isTarget(m.target)) hide(m.target);" +
                "    }" +
                "  }" +
                "}).observe(document.documentElement, {childList: true, subtree: true," +
                "  attributes: true, attributeFilter: ['style', 'class']});" +
                "window.__unslothToastGuard = true;" +
                "scan();" +
                // Self-test sincrone: creo un toast finto con la stessa
                // struttura che genera sonner, lo passo a scan(), leggo
                // display e lo tolgo. Il risultato ('mo-ok | display=...')
                // resta in window.__hiderSelfTestResult: lo leggo in Java
                // dopo 3 s. Se il blocco getta un'eccezione, lo loggo
                // con il messaggio e metto 'errore: ...' nel result.
                // timer-ok verifica che i setTimeout JS scattino.
                "var fake = document.createElement('li');" +
                "fake.setAttribute('data-sonner-toast', '');" +
                "var dt = document.createElement('div');" +
                "dt.setAttribute('data-title', '');" +
                "dt.textContent = 'Model loaded with a warning';" +
                "fake.appendChild(dt);" +
                "document.documentElement.appendChild(fake);" +
                "scan();" +
                "var d = '';" +
                "try { d = getComputedStyle(fake).display; } catch(e) { d = 'err'; }" +
                "if (fake.parentNode) fake.parentNode.removeChild(fake);" +
                "try { window.__hiderSelfTestResult = 'mo-ok | display=' + d; } catch(e) {}" +
                "try { UnslothBridge.appLog('TOAST-HIDER: self-test ok display=' + d); } catch(e) {}" +
                "setTimeout(function(){" +
                "  try { UnslothBridge.appLog('TOAST-HIDER: timer-ok'); } catch(e) {}" +
                "}, 200);" +
                "} catch(e) {" +
                "  try { window.__hiderSelfTestResult = 'errore: ' + (e && e.message); } catch(e2) {}" +
                "  try { UnslothBridge.appLog('TOAST-HIDER: ERRORE ' + (e && e.message)); } catch(e2) {}" +
                "}" +
                "})();";
        webView.evaluateJavascript(js, value -> {
            if (value != null) App.log("TOAST-HIDER: iniettato (" + value + ")");
        });
        // Poll dopo 3 s: se la pagina e' ancora viva leggo l'esito del
        // self-test dalla window var e lo loggo (doppia sicurezza rispetto
        // al log da JS, che potrebbe andare perso con un ricaricamento).
        new android.os.Handler(getMainLooper()).postDelayed(() ->
                webView.evaluateJavascript(
                        "window.__hiderSelfTestResult || 'no-result (pagina ricaricata?)'",
                        value -> App.log("TOAST-HIDER: self-test result=" + value)),
                3000);
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
        // v3.0: chiusura: stesso marker di onStop, cosi un eventuale
        // processo morente non viene riavviato dal crash guard.
        sActivityAlive = false;
        writeCrashGuardMarker("closed");
        stopRunPolling();
        stopMirror();
        if (mDownloadReceiver != null) {
            unregisterReceiver(mDownloadReceiver);
            mDownloadReceiver = null;
        }
        sWebView = null;
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
