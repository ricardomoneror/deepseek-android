package com.ds.ide.update;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.webkit.WebView;
import android.widget.Toast;

import com.ds.ide.runtime.RuntimeConfig;
import com.ds.ide.shell.RishLocator;
import com.ds.ide.shell.ShizukuBridge;
import com.ds.ide.ui.MainActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Native in-app auto-updater for DeepSeek for Android.
 * Checks GitHub Releases (ricardomoneror/deepseek-android), downloads newer APKs
 * in the background with live progress, and installs silently via
 * rish (ADB Bridge / Shizuku) or via Android's standard PackageInstaller.
 */
public final class AppUpdateManager {

    private static final String TAG = "AppUpdateManager";

    public static final String STATE_IDLE = "idle";
    public static final String STATE_CHECKING = "checking for updates";
    public static final String STATE_AVAILABLE = "available for download";
    public static final String STATE_DOWNLOADING = "downloading";
    public static final String STATE_READY = "ready";

    private static final String RELEASES_LATEST_URL =
            "https://api.github.com/repos/ricardomoneror/deepseek-android/releases/latest";
    private static final String USER_AGENT = "DeepSeek-Android-Updater/" + RuntimeConfig.CORE_VERSION;
    private static final long MIN_AUTO_CHECK_GAP_MS = 45_000L;
    private static final String UPDATES_CHANNEL_ID = "ds_updates_channel";
    private static final String UPDATE_NOTIFICATION_TAG = "ds_ota_ready";
    private static final int UPDATE_NOTIFICATION_ID = 2003;

    private static volatile AppUpdateManager instance;

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile String stateType = STATE_IDLE;
    private volatile String targetVersion = null;
    private volatile int downloadProgress = -1;
    private volatile String lastCheckResult = "";
    private volatile String lastError = null;
    private volatile String lastNotifiedVersion = null;
    private volatile boolean checkInFlight = false;
    private volatile long lastCheckStartedMs = 0L;
    private volatile boolean initialAutoCheckScheduled = false;
    private volatile boolean pendingInstallAfterPermission = false;
    private volatile WeakReference<WebView> activeWebViewRef = new WeakReference<>(null);

    private static boolean isSpanishLocale() {
        try {
            String lang = Locale.getDefault().getLanguage();
            return lang != null && lang.toLowerCase(Locale.ROOT).startsWith("es");
        } catch (Throwable t) {
            return true;
        }
    }

    private static String tr(String es, String en) {
        return isSpanishLocale() ? es : en;
    }

    private AppUpdateManager(Context context) {
        this.appContext = context.getApplicationContext();
        cleanupStaleStagedApk();
    }

    public static AppUpdateManager getInstance(Context context) {
        if (instance == null) {
            synchronized (AppUpdateManager.class) {
                if (instance == null) {
                    instance = new AppUpdateManager(context);
                }
            }
        }
        return instance;
    }

    public static File getOtaDir(Context context) {
        File dir = new File(context.getCacheDir(), "ota_updates");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static File getStagedApkFile(Context context) {
        return new File(getOtaDir(context), "update.apk");
    }

    private static File getStagedMetaFile(Context context) {
        return new File(getOtaDir(context), "update.json");
    }

    public void attachWebView(WebView webView) {
        this.activeWebViewRef = new WeakReference<>(webView);
        if (!initialAutoCheckScheduled) {
            initialAutoCheckScheduled = true;
            mainHandler.postDelayed(() -> checkForUpdates(false), 10_000L);
        }
    }

    public void detachWebView(WebView webView) {
        WebView cur = activeWebViewRef.get();
        if (webView == null || cur == webView) {
            activeWebViewRef = new WeakReference<>(null);
        }
    }

    public void onWebViewPageFinished(WebView webView) {
        if (webView != null) {
            attachWebView(webView);
        }
        if (!STATE_IDLE.equals(stateType)) {
            updateState(stateType, targetVersion);
        }
    }

    public void onActivityResumed(Activity activity, WebView webView) {
        if (webView != null) {
            attachWebView(webView);
        }
        if (!STATE_IDLE.equals(stateType)) {
            updateState(stateType, targetVersion);
            mainHandler.postDelayed(() -> {
                if (!STATE_IDLE.equals(stateType)) {
                    updateState(stateType, targetVersion);
                }
            }, 900L);
        }
        if (pendingInstallAfterPermission) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                    || appContext.getPackageManager().canRequestPackageInstalls()) {
                pendingInstallAfterPermission = false;
                mainHandler.postDelayed(() -> launchSystemPackageInstaller(activity), 350L);
            }
        }
    }

    public String getStateJson() {
        try {
            JSONObject obj = new JSONObject();
            String st = this.stateType;
            String ver = this.targetVersion;
            int prog = this.downloadProgress;
            String res = this.lastCheckResult;
            String err = this.lastError;
            obj.put("type", st);
            obj.put("installedVersion", getInstalledVersion());
            if (ver != null && !ver.isEmpty()) {
                obj.put("version", ver);
                if (STATE_AVAILABLE.equals(st) || STATE_DOWNLOADING.equals(st) || STATE_READY.equals(st)) {
                    JSONObject upd = new JSONObject();
                    upd.put("version", ver);
                    obj.put("update", upd);
                }
            }
            if (STATE_DOWNLOADING.equals(st) && prog >= 0) {
                obj.put("progress", prog);
            }
            if (res != null && !res.isEmpty()) {
                obj.put("result", res);
            }
            if (err != null && !err.isEmpty()) {
                obj.put("error", err);
            }
            return obj.toString();
        } catch (Throwable t) {
            return "{\"type\":\"idle\"}";
        }
    }

    private void updateState(String newType, String version) {
        this.stateType = newType;
        this.targetVersion = version;
        if (!STATE_DOWNLOADING.equals(newType)) {
            this.downloadProgress = -1;
        }
        emitStateToWebView();
        if (STATE_READY.equals(newType) && version != null && !version.isEmpty()) {
            postUpdateReadyNotification(version);
        }
    }

    private void emitStateToWebView() {
        final String json = getStateJson();
        mainHandler.post(() -> {
            WebView wv = activeWebViewRef.get();
            if (wv == null && MainActivity.activeActivity != null) {
                wv = MainActivity.activeActivity.getWebView();
                if (wv != null) {
                    activeWebViewRef = new WeakReference<>(wv);
                }
            }
            if (wv != null) {
                try {
                    wv.evaluateJavascript(
                            "(function(s){"
                                    + "if(window.__dsOnUpdaterStateChanged)window.__dsOnUpdaterStateChanged(s);"
                                    + "if(window.__agOnUpdaterStateChanged)window.__agOnUpdaterStateChanged(s);"
                                    + "})(" + json + ");",
                            null);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private void showToast(final String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        mainHandler.post(() -> {
            try {
                Context ctx = MainActivity.activeActivity != null ? MainActivity.activeActivity : appContext;
                Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        });
    }

    public String getInstalledVersion() {
        try {
            PackageInfo pi = appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), 0);
            if (pi != null && pi.versionName != null && !pi.versionName.trim().isEmpty()) {
                return normalizeVersion(pi.versionName);
            }
        } catch (Throwable ignored) {
        }
        return normalizeVersion(RuntimeConfig.CORE_VERSION);
    }

    public static String normalizeVersion(String raw) {
        if (raw == null) {
            return "0.0.0";
        }
        String s = raw.trim();
        while (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1).trim();
        }
        return s.isEmpty() ? "0.0.0" : s;
    }

    private static int[] parseVersionParts(String raw) {
        String v = normalizeVersion(raw);
        int plusIdx = v.indexOf('+');
        if (plusIdx >= 0) {
            v = v.substring(0, plusIdx);
        }
        int revNum = 0;
        int dashIdx = v.indexOf('-');
        if (dashIdx >= 0) {
            String suffix = v.substring(dashIdx + 1).trim().toLowerCase();
            v = v.substring(0, dashIdx);
            if (suffix.startsWith("r")) {
                suffix = suffix.substring(1);
            }
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < suffix.length(); i++) {
                char c = suffix.charAt(i);
                if (c >= '0' && c <= '9') {
                    digits.append(c);
                } else if (digits.length() > 0) {
                    break;
                }
            }
            if (digits.length() > 0) {
                try {
                    revNum = Integer.parseInt(digits.toString());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        String[] segs = v.split("\\.");
        int[] out = new int[5];
        for (int i = 0; i < Math.min(segs.length, 4); i++) {
            try {
                out[i] = Integer.parseInt(segs[i].replaceAll("[^0-9]", ""));
            } catch (NumberFormatException ignored) {
                out[i] = 0;
            }
        }
        out[4] = revNum;
        return out;
    }

    public static boolean isRemoteNewer(String installed, String remote) {
        int[] a = parseVersionParts(installed);
        int[] b = parseVersionParts(remote);
        for (int i = 0; i < a.length; i++) {
            if (b[i] != a[i]) {
                return b[i] > a[i];
            }
        }
        return false;
    }

    private void cleanupStaleStagedApk() {
        try {
            File apk = getStagedApkFile(appContext);
            File meta = getStagedMetaFile(appContext);
            if (!apk.exists() && !meta.exists()) {
                return;
            }
            String installed = getInstalledVersion();
            if (meta.isFile()) {
                String raw = readSmallFile(meta);
                JSONObject obj = new JSONObject(raw);
                String stagedVer = obj.optString("version", "");
                long expectedSize = obj.optLong("size", -1L);
                if (!stagedVer.isEmpty() && isRemoteNewer(installed, stagedVer)
                        && apk.isFile() && (expectedSize <= 0L || apk.length() == expectedSize)
                        && isValidApkArchive(apk)) {
                    this.stateType = STATE_READY;
                    this.targetVersion = stagedVer;
                    Log.i(TAG, "Restored verified staged update v" + stagedVer);
                    return;
                }
            }
            if (apk.exists()) apk.delete();
            if (meta.exists()) meta.delete();
            cancelUpdateReadyNotification();
        } catch (Throwable t) {
            Log.w(TAG, "cleanupStaleStagedApk: " + t.getMessage());
        }
    }

    public void checkForUpdates(final boolean manual) {
        long now = System.currentTimeMillis();
        if (checkInFlight) {
            if (manual) {
                showToast(tr("Buscando actualizaciones…", "Checking for updates…"));
            }
            return;
        }
        if (STATE_READY.equals(stateType) && targetVersion != null && getStagedApkFile(appContext).isFile()) {
            if (manual) {
                showToast(tr("Actualización v" + targetVersion + " lista para instalar",
                        "Update v" + targetVersion + " is ready to install"));
            }
            updateState(STATE_READY, targetVersion);
            return;
        }
        if (!manual && (now - lastCheckStartedMs) < MIN_AUTO_CHECK_GAP_MS) {
            return;
        }
        checkInFlight = true;
        lastCheckStartedMs = now;

        Thread worker = new Thread(() -> {
            try {
                runUpdateCheck(manual);
            } finally {
                checkInFlight = false;
            }
        }, "OcAppUpdater");
        worker.setDaemon(true);
        worker.start();
    }

    private void runUpdateCheck(boolean manual) {
        this.lastCheckResult = "";
        this.lastError = null;
        updateState(STATE_CHECKING, null);
        try {
            String installedVer = getInstalledVersion();
            JSONObject release = fetchJsonFollowRedirects(RELEASES_LATEST_URL);
            if (release == null) {
                // HTTP 404 on /releases/latest means no newer release has been published yet
                this.lastCheckResult = "up-to-date";
                updateState(STATE_IDLE, null);
                if (manual) {
                    showToast(tr("DeepSeek está actualizado (v" + installedVer + ")",
                            "DeepSeek is up to date (v" + installedVer + ")"));
                }
                return;
            }
            String tagName = release.optString("tag_name", "").trim();
            if (tagName.isEmpty()) {
                this.lastCheckResult = "up-to-date";
                updateState(STATE_IDLE, null);
                if (manual) {
                    showToast(tr("DeepSeek está actualizado (v" + installedVer + ")",
                            "DeepSeek is up to date (v" + installedVer + ")"));
                }
                return;
            }
            String remoteVer = normalizeVersion(tagName);
            if (!isRemoteNewer(installedVer, remoteVer)) {
                this.lastCheckResult = "up-to-date";
                updateState(STATE_IDLE, null);
                if (manual) {
                    showToast(tr("DeepSeek está actualizado (v" + installedVer + ")",
                            "DeepSeek is up to date (v" + installedVer + ")"));
                }
                return;
            }

            JSONArray assets = release.optJSONArray("assets");
            JSONObject chosenAsset = selectApkAsset(assets);
            if (chosenAsset == null) {
                this.lastCheckResult = "up-to-date";
                updateState(STATE_IDLE, null);
                if (manual) {
                    showToast(tr("La versión v" + remoteVer + " aún no incluye APK",
                            "Release v" + remoteVer + " has no APK asset yet"));
                }
                return;
            }

            String downloadUrl = chosenAsset.optString("browser_download_url", "");
            long expectedSize = chosenAsset.optLong("size", -1L);
            if (downloadUrl.isEmpty()) {
                updateState(STATE_IDLE, null);
                return;
            }

            File stagedApk = getStagedApkFile(appContext);
            File stagedMeta = getStagedMetaFile(appContext);
            if (stagedApk.isFile() && (expectedSize <= 0L || stagedApk.length() == expectedSize)
                    && isValidApkArchive(stagedApk)) {
                PackageInfo archiveInfo = appContext.getPackageManager()
                        .getPackageArchiveInfo(stagedApk.getAbsolutePath(), 0);
                if (archiveInfo != null && remoteVer.equals(normalizeVersion(archiveInfo.versionName))) {
                    writeStagedMeta(stagedMeta, remoteVer, stagedApk.length());
                    this.lastCheckResult = "ready";
                    updateState(STATE_READY, remoteVer);
                    if (manual) {
                        showToast(tr("Actualización v" + remoteVer + " lista para instalar",
                                "Update v" + remoteVer + " ready to install"));
                    }
                    return;
                }
            }

            updateState(STATE_AVAILABLE, remoteVer);
            this.downloadProgress = 0;
            updateState(STATE_DOWNLOADING, remoteVer);

            File partFile = new File(getOtaDir(appContext), "update.apk.part");
            if (partFile.exists()) {
                partFile.delete();
            }

            downloadBinaryFollowRedirects(downloadUrl, partFile, expectedSize, remoteVer);

            if (!isValidApkArchive(partFile)) {
                partFile.delete();
                throw new IllegalStateException("Downloaded APK failed archive verification");
            }

            if (stagedApk.exists() && !stagedApk.delete()) {
                throw new IllegalStateException("Cannot replace previous staged APK");
            }
            if (!partFile.renameTo(stagedApk)) {
                copyFile(partFile, stagedApk);
                partFile.delete();
            }

            writeStagedMeta(stagedMeta, remoteVer, stagedApk.length());
            Log.i(TAG, "Update v" + remoteVer + " downloaded (" + stagedApk.length() + " bytes)");
            this.lastCheckResult = "ready";
            updateState(STATE_READY, remoteVer);
            if (manual) {
                showToast(tr("Actualización v" + remoteVer + " descargada — toca para instalar",
                        "Update v" + remoteVer + " downloaded — tap to install"));
            }
        } catch (Throwable t) {
            Log.w(TAG, "Update check failed: " + t.getMessage());
            this.lastCheckResult = "error";
            this.lastError = t.getMessage();
            updateState(STATE_IDLE, null);
            if (manual) {
                showToast(tr("Error al buscar actualizaciones: " + t.getMessage(),
                        "Update check failed: " + t.getMessage()));
            }
        }
    }

    private static JSONObject selectApkAsset(JSONArray assets) {
        if (assets == null || assets.length() == 0) {
            return null;
        }
        JSONObject fallbackApk = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null) continue;
            String name = a.optString("name", "");
            String lower = name.toLowerCase();
            if (lower.endsWith(".apk")) {
                if (lower.startsWith("deepseek")) {
                    return a;
                }
                if (fallbackApk == null) {
                    fallbackApk = a;
                }
            }
        }
        return fallbackApk;
    }

    private static boolean isAllowedOtaUrl(URL url) {
        if (url == null || !"https".equalsIgnoreCase(url.getProtocol())) {
            return false;
        }
        String host = url.getHost();
        if (host == null) {
            return false;
        }
        String lower = host.trim().toLowerCase(Locale.ROOT);
        String path = url.getPath() != null ? url.getPath() : "";
        if ("api.github.com".equals(lower)) {
            return path.startsWith("/repos/ricardomoneror/deepseek-android/");
        }
        if ("github.com".equals(lower)) {
            return path.startsWith("/ricardomoneror/deepseek-android/");
        }
        return "objects.githubusercontent.com".equals(lower)
                || "release-assets.githubusercontent.com".equals(lower);
    }

    private static JSONObject fetchJsonFollowRedirects(String urlStr) throws Exception {
        String currentUrl = urlStr;
        for (int hop = 0; hop < 6; hop++) {
            URL u = new URL(currentUrl);
            if (!isAllowedOtaUrl(u)) {
                throw new SecurityException("Blocked OTA metadata URL: " + currentUrl);
            }
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(20_000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            try {
                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = conn.getHeaderField("Location");
                    if (loc == null || loc.isEmpty()) {
                        throw new IllegalStateException("Redirect missing Location");
                    }
                    URL nextUrl = new URL(u, loc);
                    if (!isAllowedOtaUrl(nextUrl)) {
                        throw new SecurityException("Blocked OTA redirect host: " + nextUrl.getHost());
                    }
                    currentUrl = nextUrl.toString();
                    continue;
                }
                if (code == 404) {
                    return null;
                }
                if (code != 200) {
                    throw new IllegalStateException("GitHub API HTTP " + code);
                }
                try (InputStream in = new BufferedInputStream(conn.getInputStream());
                     ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        bos.write(buf, 0, n);
                    }
                    return new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
                }
            } finally {
                conn.disconnect();
            }
        }
        throw new IllegalStateException("Too many redirects");
    }

    private void downloadBinaryFollowRedirects(String urlStr, File destFile,
                                               long expectedSize, String remoteVer) throws Exception {
        String currentUrl = urlStr;
        for (int hop = 0; hop < 6; hop++) {
            URL u = new URL(currentUrl);
            if (!isAllowedOtaUrl(u)) {
                throw new SecurityException("Blocked OTA download URL: " + currentUrl);
            }
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(20_000);
            conn.setReadTimeout(60_000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/octet-stream, */*");
            try {
                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = conn.getHeaderField("Location");
                    if (loc == null || loc.isEmpty()) {
                        throw new IllegalStateException("Asset redirect missing Location");
                    }
                    URL nextUrl = new URL(u, loc);
                    if (!isAllowedOtaUrl(nextUrl)) {
                        throw new SecurityException("Blocked OTA asset redirect host: " + nextUrl.getHost());
                    }
                    currentUrl = nextUrl.toString();
                    continue;
                }
                if (code != 200) {
                    throw new IllegalStateException("Download HTTP " + code);
                }
                long contentLen = conn.getContentLengthLong();
                long denom = expectedSize > 0L ? expectedSize : contentLen;
                long total = 0L;
                long lastEmitMs = 0L;
                try (InputStream in = new BufferedInputStream(conn.getInputStream(), 65536);
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(destFile), 65536)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        total += n;
                        if (denom > 0L) {
                            int pct = (int) Math.min(100L, (total * 100L) / denom);
                            long now = System.currentTimeMillis();
                            if (pct != this.downloadProgress && (now - lastEmitMs >= 250L || pct == 100)) {
                                this.downloadProgress = pct;
                                lastEmitMs = now;
                                emitStateToWebView();
                            }
                        }
                    }
                    out.flush();
                }
                if (expectedSize > 0L && total != expectedSize) {
                    destFile.delete();
                    throw new IllegalStateException("Incomplete APK download (" + total + "/" + expectedSize + " bytes)");
                }
                return;
            } finally {
                conn.disconnect();
            }
        }
        throw new IllegalStateException("Too many download redirects");
    }

    private static Set<String> extractSignerSha256Digests(PackageInfo pi) {
        Set<String> digests = new HashSet<>();
        if (pi == null) {
            return digests;
        }
        try {
            Signature[] sigs = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                SigningInfo si = pi.signingInfo;
                if (si != null) {
                    sigs = si.hasMultipleSigners()
                            ? si.getApkContentsSigners()
                            : si.getSigningCertificateHistory();
                }
            }
            if ((sigs == null || sigs.length == 0) && pi.signatures != null) {
                sigs = pi.signatures;
            }
            if (sigs != null) {
                for (Signature s : sigs) {
                    if (s == null) continue;
                    byte[] hash = MessageDigest.getInstance("SHA-256").digest(s.toByteArray());
                    StringBuilder hex = new StringBuilder(hash.length * 2);
                    for (byte b : hash) {
                        hex.append(String.format("%02x", b & 0xff));
                    }
                    digests.add(hex.toString());
                }
            }
        } catch (Throwable ignored) {
        }
        return digests;
    }

    private boolean isValidApkArchive(File apkFile) {
        if (apkFile == null || !apkFile.isFile() || apkFile.length() < 1024L) {
            return false;
        }
        try (FileInputStream fis = new FileInputStream(apkFile)) {
            byte[] magic = new byte[4];
            if (fis.read(magic) != 4
                    || magic[0] != 0x50 || magic[1] != 0x4B || magic[2] != 0x03 || magic[3] != 0x04) {
                return false;
            }
        } catch (Throwable t) {
            return false;
        }
        try {
            PackageManager pm = appContext.getPackageManager();
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? PackageManager.GET_SIGNING_CERTIFICATES
                    : PackageManager.GET_SIGNATURES;
            PackageInfo archiveInfo = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(), flags);
            if (archiveInfo == null || !appContext.getPackageName().equals(archiveInfo.packageName)) {
                return false;
            }
            PackageInfo installedInfo = pm.getPackageInfo(appContext.getPackageName(), flags);
            Set<String> installedCerts = extractSignerSha256Digests(installedInfo);
            Set<String> archiveCerts = extractSignerSha256Digests(archiveInfo);
            if (installedCerts.isEmpty() || archiveCerts.isEmpty()) {
                Log.w(TAG, "OTA APK rejected: missing signing certificate metadata");
                return false;
            }
            for (String d : archiveCerts) {
                if (installedCerts.contains(d)) {
                    return true;
                }
            }
            Log.e(TAG, "OTA APK rejected: signing certificate SHA-256 mismatch against installed app");
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "OTA APK verification failed: " + t.getMessage());
            return false;
        }
    }

    public void applyUpdate() {
        cancelUpdateReadyNotification();
        final File apk = getStagedApkFile(appContext);
        if (!apk.isFile() || !isValidApkArchive(apk)) {
            checkForUpdates(true);
            return;
        }
        Thread installerThread = new Thread(() -> {
            if (trySilentInstallViaShizuku(apk)) {
                return;
            }
            mainHandler.post(() -> launchSystemPackageInstaller(MainActivity.activeActivity));
        }, "OcApkInstaller");
        installerThread.setDaemon(true);
        installerThread.start();
    }

    private boolean trySilentInstallViaShizuku(File apkFile) {
        try {
            ShizukuBridge shizuku = new ShizukuBridge(appContext);
            if (!shizuku.isAvailable()) {
                return false;
            }
            String idCheck = shizuku.exec("id");
            if (idCheck == null || (!idCheck.contains("uid=2000") && !idCheck.contains("uid=0"))) {
                return false;
            }

            File rish = new RishLocator(appContext).rishBinary();
            if (!rish.isFile() || !rish.canExecute()) {
                return false;
            }

            String pkg = appContext.getPackageName();
            byte[] nonceBytes = new byte[8];
            new java.security.SecureRandom().nextBytes(nonceBytes);
            StringBuilder nonceHex = new StringBuilder(16);
            for (byte b : nonceBytes) {
                nonceHex.append(String.format("%02x", b & 0xff));
            }
            String remoteTmp = "/data/local/tmp/ds_ota_" + pkg.replace('.', '_') + "_" + nonceHex + ".apk";

            showToast(tr("Instalando actualización…", "Installing update…"));

            ProcessBuilder pb = new ProcessBuilder(
                    rish.getAbsolutePath(),
                    "-c",
                    "env -u LD_LIBRARY_PATH sh -c 'umask 077 && set -C && cat > " + remoteTmp
                            + " && chmod 644 " + remoteTmp
                            + " && wc -c < " + remoteTmp + "'");
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            env.remove("LD_LIBRARY_PATH");
            env.put("RISH_APPLICATION_ID", pkg);
            pb.directory(appContext.getFilesDir());

            Process p = pb.start();
            try (InputStream fis = new BufferedInputStream(new FileInputStream(apkFile), 65536);
                 OutputStream pos = new BufferedOutputStream(p.getOutputStream(), 65536)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = fis.read(buf)) != -1) {
                    pos.write(buf, 0, n);
                }
                pos.flush();
            }

            final StringBuilder out = new StringBuilder();
            final InputStream pis = p.getInputStream();
            Thread drainer = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(pis))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        synchronized (out) {
                            out.append(line.trim());
                        }
                    }
                } catch (Exception ignored) {
                }
            }, "OtaStreamDrain");
            drainer.setDaemon(true);
            drainer.start();

            if (!p.waitFor(25, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                shizuku.exec("rm -f " + remoteTmp);
                Log.w(TAG, "OTA stream via rish timed out");
                return false;
            }
            drainer.join(500);
            int code = p.exitValue();
            if (code != 0) {
                shizuku.exec("rm -f " + remoteTmp);
                Log.w(TAG, "OTA stream APK exited " + code + ": " + out);
                return false;
            }

            long remoteBytes = -1L;
            try {
                String outStr;
                synchronized (out) {
                    outStr = out.toString();
                }
                remoteBytes = Long.parseLong(outStr.replaceAll("[^0-9]", ""));
            } catch (NumberFormatException ignored) {
            }
            if (remoteBytes != apkFile.length()) {
                shizuku.exec("rm -f " + remoteTmp);
                Log.w(TAG, "OTA size mismatch: " + remoteBytes + " != " + apkFile.length());
                return false;
            }

            String installCmd = "nohup sh -c 'sleep 0.6; pm install -r " + remoteTmp
                    + " >/dev/null 2>&1; rm -f " + remoteTmp
                    + "; monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1'"
                    + " </dev/null >/dev/null 2>&1 &";
            String res = shizuku.exec(installCmd, 5000L);
            if (res != null && res.startsWith("error:")) {
                shizuku.exec("rm -f " + remoteTmp);
                return false;
            }
            Log.i(TAG, "Detached pm install scheduled for " + remoteTmp);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Shizuku install fallback: " + t.getMessage());
            return false;
        }
    }

    private void launchSystemPackageInstaller(Activity activity) {
        try {
            Context host = activity != null ? activity : appContext;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && !appContext.getPackageManager().canRequestPackageInstalls()) {
                pendingInstallAfterPermission = true;
                showToast(tr("Permite 'Instalar apps desconocidas' para actualizar",
                        "Allow 'Install unknown apps' to apply update"));
                Intent permIntent = new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + appContext.getPackageName()));
                if (!(host instanceof Activity)) {
                    permIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                host.startActivity(permIntent);
                return;
            }

            pendingInstallAfterPermission = false;
            Uri apkUri = UpdateApkProvider.buildUpdateUri(appContext);
            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            installIntent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
            host.startActivity(installIntent);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to launch system package installer: " + t.getMessage());
            showToast(tr("No se pudo abrir el instalador: " + t.getMessage(),
                    "Cannot launch installer: " + t.getMessage()));
        }
    }

    private static void writeStagedMeta(File metaFile, String version, long size) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("version", version);
            obj.put("size", size);
            obj.put("savedAt", System.currentTimeMillis());
            try (FileOutputStream fos = new FileOutputStream(metaFile)) {
                fos.write(obj.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    private static String readSmallFile(File f) throws Exception {
        try (FileInputStream fis = new FileInputStream(f);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void postUpdateReadyNotification(String version) {
        if (version == null || version.isEmpty() || version.equals(lastNotifiedVersion)) {
            return;
        }
        lastNotifiedVersion = version;
        try {
            NotificationManager nm = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                        UPDATES_CHANNEL_ID,
                        "App Updates",
                        NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
            }
            int iconId = appContext.getResources().getIdentifier(
                    "ic_notification", "drawable", appContext.getPackageName());
            if (iconId == 0) {
                iconId = android.R.drawable.stat_sys_download_done;
            }
            Intent tapIntent = new Intent(appContext, MainActivity.class);
            tapIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            tapIntent.putExtra(MainActivity.EXTRA_APPLY_OTA_UPDATE, true);
            int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? (PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
                    : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pi = PendingIntent.getActivity(appContext, UPDATE_NOTIFICATION_ID, tapIntent, piFlags);

            String title = tr("DeepSeek v" + version + " listo para instalar",
                    "DeepSeek v" + version + " ready to install");
            String body = tr("Actualización descargada y verificada. Toca para instalar sin perder tus datos.",
                    "Update downloaded and verified. Tap to install without losing data.");
            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(appContext, UPDATES_CHANNEL_ID)
                    : new Notification.Builder(appContext);
            builder.setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(body))
                    .setSmallIcon(iconId)
                    .setContentIntent(pi)
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(true);
            nm.notify(UPDATE_NOTIFICATION_TAG, UPDATE_NOTIFICATION_ID, builder.build());
        } catch (Throwable ignored) {
        }
    }

    private void cancelUpdateReadyNotification() {
        try {
            NotificationManager nm = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(UPDATE_NOTIFICATION_TAG, UPDATE_NOTIFICATION_ID);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        try (InputStream in = new BufferedInputStream(new FileInputStream(src), 65536);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dst), 65536)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
        }
    }
}
