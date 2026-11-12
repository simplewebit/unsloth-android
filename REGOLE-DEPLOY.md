# Regole di deploy — app Android Unsloth

Regole fisse che valgono **ogni volta che arriva una versione nuova** (date da Mattia il 11/11).
Ordine dei passi: 1 → 2 → (attendo l'ok di Mattia) → 3. Non salto nessun passo.

## 1. Numero di versione dappertutto nel codice
Quando cambio versione, la metto in questi tre punti (nessuno escluso):
- `app/src/it/mattia/unsloth/MainActivity.java`: `APP_VERSION = "X.Y"` (la mostra in basso a destra).
- `app/src/AndroidManifest.xml`: `android:versionName="X.Y"` e `android:versionCode` incrementato di 1.
- Pagina Credits in `app/src/res/layout/activity_main.xml`: testo della versione hardcoded sotto il logo.

Dopo la build verifico sempre: `aapt2 dump xmltree <apk> --file AndroidManifest.xml` (deve mostrare versionName/versionCode giusti) e SHA-256 dell'APK.

## 2. Faccio testare a Mattia prima di pubblicare
- Compilo con `build.sh` (marker di successo `== done ==`).
- Collaudo sull'emulatore i comportamenti che la versione cambia (es. cold start / warm resume).
- Metto l'APK sul server :8000 e lo faccio testare a Mattia sul suo telefono.
- **Non pubblico su GitHub finché non mi dà l'ok.** Il test sul telefono è di lui, non mio.

## 3. Con l'ok: carico tutto su GitHub
- Push del sorgente su `main` (ramo locale `publish`, token da `secret_tmp\.credenziali.json` chiave `github_token`, mai stampato; PowerShell può dare exit 1 per stderr, verifico sempre via API il sha di main).
- Creo la **release GitHub** con tag `vX.Y`: **changelog completo** (in inglese, stesso formato delle release precedenti) + **APK pronto** come asset (`Unsloth.apk`).
- Verifico la release via API (id, asset presente, URL download, dimensione/hash).
- Aggiorno `STATO-LAVORO.md` e la `NOTE.md` della cartella task.

## Note operative
- Stesso keystore: l'APK nuovo aggiorna in posto quello installato.
- Server APK: task `unsloth_apk_server` (pythonw http.server 8000 in `out/`); la build svuota e ricostruisce `out/`, quindi serve sempre l'ultimo APK.
- Upload asset via API: `POST /repos/simplewebit/unsloth-android/releases/{id}/assets?name=Unsloth.apk` con il binario nel body (curl `--data-binary`).
