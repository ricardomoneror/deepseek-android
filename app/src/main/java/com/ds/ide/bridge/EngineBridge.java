package com.ds.ide.bridge;

import android.util.Log;

import com.ds.ide.core.CoreRuntimeState;
import com.ds.ide.core.CoreServerService;
import com.ds.ide.runtime.RuntimeManager;
import com.ds.ide.util.AtomicFileWriter;
import com.ds.ide.util.CoreUrl;
import com.ds.ide.util.FileModeUtil;
import com.ds.ide.util.IoUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Exposes DeepSeek server state, restart controls, live core.log reader,
 * and the 1-Tap "Built for Android" MCP Plugin Hub (downloading native ARM64 Rust/Bionic
 * plugins from Yhoanes/antigravity-android-plugins, rewriting sandbox paths to com.ds.ide,
 * running install-plugin.sh, syncing SKILL.md + rules/*.md into ~/.claude/{skills,rules},
 * and registering mcpServers in ~/.claude.json).
 */
public final class EngineBridge {

    private static final String TAG = "EngineBridge";
    private static final String ANDROID_PLUGINS_REPO_BASE =
            "https://raw.githubusercontent.com/Yhoanes/antigravity-android-plugins/main/plugins/";
    private static final long MAX_ZIP_DOWNLOAD_BYTES = 256L * 1024L * 1024L;
    private static final long MAX_EXTRACTED_TOTAL_BYTES = 600L * 1024L * 1024L;
    private static final int MAX_ZIP_ENTRIES = 5000;
    private static final int MAX_HOOK_OUTPUT_BYTES = 32768;

    private final RuntimeManager runtime;

    public EngineBridge(RuntimeManager runtime) {
        this.runtime = runtime;
    }

    public boolean isReady() {
        return CoreRuntimeState.serverUrl != null;
    }

    public int getPort() {
        int live = CoreRuntimeState.httpsPort;
        return live > 0 ? live : runtime.getHttpsPort();
    }

    public String getBaseUrl() {
        return CoreUrl.baseUrl(getPort());
    }

    public String getCsrfToken() {
        String live = CoreRuntimeState.csrfToken;
        return live != null ? live : runtime.getCsrfToken();
    }

    private File getPluginHubDir() {
        File d = new File(runtime.getDshDir(), "plugins-hub");
        if (!d.isDirectory()) {
            d.mkdirs();
        }
        return d;
    }

    private File getHubStateFile() {
        return new File(runtime.getDshDir(), "android-plugins.json");
    }

    private File getClaudeJsonFile() {
        return new File(runtime.getDshDir(), "android-mcp.json");
    }

    private File getDshHomePatchFile() {
        return new File(runtime.getDshDir(), "cordis.patch.yml");
    }

    /**
     * Renders ~/.dsh/android-mcp.json (mcpServers) into ~/.dsh/cordis.patch.yml so
     * @deepseek-ai/dsh-hmr + @deepseek-ai/dsh-mcp-manager hot-mounts native ARM64 MCP servers live.
     */
    private void syncDshHomePatch() {
        try {
            JSONObject mcpDoc = readJsonObject(getClaudeJsonFile());
            JSONObject servers = mcpDoc.optJSONObject("mcpServers");
            File patchFile = getDshHomePatchFile();
            if (servers == null || servers.length() == 0) {
                AtomicFileWriter.writeText(patchFile, "[]\n");
                return;
            }
            JSONArray patchArr = new JSONArray();
            JSONObject row = new JSONObject();
            row.put("id", "mcp-manager");
            row.put("name", "@deepseek-ai/dsh-mcp-manager");
            JSONArray injectArr = new JSONArray();
            injectArr.put("credentials");
            row.put("inject", injectArr);

            JSONObject dshServers = new JSONObject();
            Iterator<String> keys = servers.keys();
            while (keys.hasNext()) {
                String rawName = keys.next();
                JSONObject src = servers.optJSONObject(rawName);
                if (src == null) continue;
                String safeSrv = rawName.replaceAll("[^A-Za-z0-9_-]", "-");
                if (safeSrv.length() > 32) safeSrv = safeSrv.substring(0, 32);
                if (safeSrv.isEmpty()) continue;

                JSONObject dst = new JSONObject();
                String url = src.optString("url", "");
                if (!url.isEmpty()) {
                    dst.put("transport", "streamable-http");
                    dst.put("url", url);
                    JSONObject hdrs = src.optJSONObject("headers");
                    if (hdrs != null) dst.put("headers", hdrs);
                } else {
                    String cmd = src.optString("command", "");
                    if (cmd.isEmpty()) continue;
                    dst.put("transport", "stdio");
                    dst.put("command", cmd);
                    JSONArray args = src.optJSONArray("args");
                    dst.put("args", args != null ? args : new JSONArray());
                    JSONObject env = src.optJSONObject("env");
                    if (env != null) dst.put("env", env);
                }
                dst.put("enabled", true);
                dshServers.put(safeSrv, dst);
            }
            if (dshServers.length() == 0) {
                AtomicFileWriter.writeText(patchFile, "[]\n");
                return;
            }
            JSONObject cfg = new JSONObject();
            cfg.put("servers", dshServers);
            row.put("config", cfg);
            patchArr.put(row);
            // JSON is a strict subset of YAML 1.2 and parsed natively by js-yaml in dsh-app-boot
            AtomicFileWriter.writeText(patchFile, patchArr.toString(2) + "\n");
        } catch (Exception e) {
            Log.w(TAG, "syncDshHomePatch warning: " + e.getMessage());
        }
    }

    private static String resolvePluginArchiveUrl(String safeName, String archiveUrl) {
        if (archiveUrl != null && !archiveUrl.trim().isEmpty()) {
            String trimmed = archiveUrl.trim();
            try {
                URL candidate = new URL(trimmed);
                if (!isAllowedPluginArchiveUrl(candidate)) {
                    throw new SecurityException("Untrusted plugin archive URL: " + trimmed);
                }
                return trimmed;
            } catch (SecurityException se) {
                throw se;
            } catch (Exception e) {
                throw new SecurityException("Invalid plugin archive URL: " + trimmed);
            }
        }
        return ANDROID_PLUGINS_REPO_BASE + safeName + "/" + safeName + ".zip";
    }

    private static boolean isAllowedPluginArchiveUrl(URL url) {
        if (url == null || !"https".equalsIgnoreCase(url.getProtocol())) {
            return false;
        }
        String host = url.getHost();
        if (host == null) {
            return false;
        }
        String lowerHost = host.trim().toLowerCase(java.util.Locale.ROOT);
        String path = url.getPath() != null ? url.getPath() : "";
        if ("raw.githubusercontent.com".equals(lowerHost)
                || "github.com".equals(lowerHost)
                || "codeload.githubusercontent.com".equals(lowerHost)) {
            return path.startsWith("/Yhoanes/");
        }
        return "objects.githubusercontent.com".equals(lowerHost)
                || "release-assets.githubusercontent.com".equals(lowerHost);
    }

    private static void downloadPluginZipVerified(String initialUrl, File destZip) throws Exception {
        String current = initialUrl;
        for (int hop = 0; hop < 6; hop++) {
            URL u = new URL(current);
            if (!isAllowedPluginArchiveUrl(u)) {
                throw new SecurityException("Blocked redirect or untrusted host: " + current);
            }
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("User-Agent", "DeepSeek-Android/" + RuntimeManager.CORE_VERSION);
            try {
                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = conn.getHeaderField("Location");
                    if (loc == null || loc.isEmpty()) {
                        throw new IllegalStateException("Redirect missing Location header");
                    }
                    URL nextUrl = new URL(u, loc);
                    if (!isAllowedPluginArchiveUrl(nextUrl)) {
                        throw new SecurityException("Blocked redirect to untrusted host: " + nextUrl.getHost());
                    }
                    current = nextUrl.toString();
                    continue;
                }
                if (code < 200 || code >= 300) {
                    throw new IllegalStateException("HTTP " + code + " downloading plugin archive");
                }
                long declaredLen = conn.getContentLengthLong();
                if (declaredLen > MAX_ZIP_DOWNLOAD_BYTES) {
                    throw new IllegalStateException("Plugin archive exceeds 256 MiB safety limit");
                }
                long total = 0L;
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(destZip)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_ZIP_DOWNLOAD_BYTES) {
                            throw new IllegalStateException("Plugin archive exceeded 256 MiB limit during download");
                        }
                        out.write(buf, 0, n);
                    }
                }
                return;
            } finally {
                conn.disconnect();
            }
        }
        throw new IllegalStateException("Too many redirects downloading plugin archive");
    }

    /** Returns bundled __ds_plugins_catalog.json directly from filesDir/web for 0-ms UI rendering. */
    public String getPluginsCatalogJson() {
        // [PARCHE hub-off] Catalogo desactivado: el hub descargaba zips de
        // terceros y ejecutaba install-plugin.sh con DSH_HOME en el entorno.
        return "{\"plugins\":[]}";
    }

    /** Returns JSON map of installed Android MCP plugins and their enabled/MCP state. */
    public String listAndroidPlugins() {
        try {
            JSONObject state = readJsonObject(getHubStateFile());
            JSONObject installed = state.optJSONObject("plugins");
            if (installed == null) {
                installed = new JSONObject();
            }
            JSONObject claudeJson = readJsonObject(getClaudeJsonFile());
            JSONObject mcpServers = claudeJson.optJSONObject("mcpServers");

            JSONObject result = new JSONObject();
            File[] dirs = getPluginHubDir().listFiles();
            if (dirs != null) {
                for (File d : dirs) {
                    if (!d.isDirectory() || d.getName().startsWith(".")) continue;
                    String name = d.getName();
                    JSONObject meta = installed.optJSONObject(name);
                    JSONObject item = new JSONObject();
                    boolean enabled = meta == null || meta.optBoolean("enabled", true);
                    JSONArray serverNames = meta != null ? meta.optJSONArray("servers") : null;
                    if (serverNames != null && serverNames.length() > 0 && mcpServers != null) {
                        String firstSrv = serverNames.optString(0, "");
                        if (!firstSrv.isEmpty()) {
                            enabled = mcpServers.has(firstSrv);
                        }
                    }
                    item.put("installed", true);
                    item.put("enabled", enabled);
                    item.put("version", meta != null ? meta.optString("version", "1.0.0") : "1.0.0");
                    result.put(name, item);
                }
            }
            return result.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    public String installMarketplacePlugin(String pluginName, String archiveUrl,
                                           String catalogId, String marketplace, String version) {
        JSONObject res = new JSONObject();
        File hubDir = getPluginHubDir();
        String safeName = sanitizeName(pluginName);
        if (safeName.isEmpty() || ".".equals(safeName) || "..".equals(safeName)) {
            try {
                res.put("ok", false);
                res.put("error", "Invalid plugin name");
            } catch (Exception ignored) {
            }
            return res.toString();
        }
        File targetDir = new File(hubDir, safeName);
        File stagingDir = new File(hubDir, ".staging-" + safeName + "-" + System.currentTimeMillis());
        File backupDir = new File(hubDir, ".backup-" + safeName);
        File tmpZip = new File(runtime.appContext().getCacheDir(),
                safeName + "-" + System.currentTimeMillis() + ".ccplugin.zip");
        boolean swappedTarget = false;
        boolean hadPreviousInstall = targetDir.exists();

        try {
            String url = resolvePluginArchiveUrl(safeName, archiveUrl);
            downloadPluginZipVerified(url, tmpZip);

            deleteRecursive(stagingDir);
            if (!stagingDir.mkdirs() && !stagingDir.isDirectory()) {
                throw new IllegalStateException("Cannot create staging directory");
            }

            String canonicalStaging = stagingDir.getCanonicalPath();
            int entryCount = 0;
            long totalExtracted = 0L;
            try (ZipInputStream zis = new ZipInputStream(new FileInputStream(tmpZip))) {
                ZipEntry entry;
                byte[] buf = new byte[16384];
                while ((entry = zis.getNextEntry()) != null) {
                    entryCount++;
                    if (entryCount > MAX_ZIP_ENTRIES) {
                        throw new IllegalStateException("Plugin archive exceeds maximum entry limit (" + MAX_ZIP_ENTRIES + ")");
                    }
                    String entryName = entry.getName().replace('\\', '/');
                    while (entryName.startsWith("/")) {
                        entryName = entryName.substring(1);
                    }
                    if (entryName.startsWith(safeName + "/")) {
                        entryName = entryName.substring(safeName.length() + 1);
                    }
                    if (entryName.isEmpty() || entryName.contains("..")) {
                        zis.closeEntry();
                        continue;
                    }
                    File outFile = new File(stagingDir, entryName);
                    if (!outFile.getCanonicalPath().startsWith(canonicalStaging + File.separator)) {
                        throw new SecurityException("Blocked Zip-Slip entry: " + entry.getName());
                    }
                    if (outFile.exists() && isSymlink(outFile)) {
                        throw new SecurityException("Blocked symlink in staging extraction: " + entry.getName());
                    }
                    if (entry.isDirectory()) {
                        outFile.mkdirs();
                    } else {
                        File parent = outFile.getParentFile();
                        if (parent != null && !parent.isDirectory()) {
                            parent.mkdirs();
                        }
                        try (FileOutputStream fos = new FileOutputStream(outFile)) {
                            int n;
                            while ((n = zis.read(buf)) > 0) {
                                totalExtracted += n;
                                if (totalExtracted > MAX_EXTRACTED_TOTAL_BYTES) {
                                    throw new IllegalStateException("Extracted plugin exceeds 600 MiB quota");
                                }
                                fos.write(buf, 0, n);
                            }
                        }
                    }
                    zis.closeEntry();
                }
            } finally {
                tmpZip.delete();
            }

            deleteRecursive(backupDir);
            if (hadPreviousInstall) {
                if (!targetDir.renameTo(backupDir)) {
                    copyRecursive(targetDir, backupDir);
                    deleteRecursive(targetDir);
                }
            }
            if (!stagingDir.renameTo(targetDir)) {
                copyRecursive(stagingDir, targetDir);
                deleteRecursive(stagingDir);
            }
            swappedTarget = true;

            // Mirror/symlink under ~/.gemini/config/plugins/<safeName> for script compatibility
            mirrorCompatPluginPath(safeName, targetDir);

            JSONObject hookRes = runPostInstallAndWireDeepSeek(
                    safeName, targetDir, version != null && !version.isEmpty() ? version : "1.0.0");

            deleteRecursive(backupDir);

            res.put("ok", true);
            res.put("path", targetDir.getAbsolutePath());
            res.put("hookExit", hookRes.optInt("hookExit", 0));
            res.put("servers", hookRes.optJSONArray("servers"));
            res.put("skills", hookRes.optJSONArray("skills"));
            res.put("reloaded", reloadEngineConfigBool());
            return res.toString();
        } catch (Exception e) {
            tmpZip.delete();
            deleteRecursive(stagingDir);
            if (swappedTarget) {
                deleteRecursive(targetDir);
                if (hadPreviousInstall && backupDir.exists()) {
                    if (!backupDir.renameTo(targetDir)) {
                        try {
                            copyRecursive(backupDir, targetDir);
                        } catch (Exception ignored) {
                        }
                        deleteRecursive(backupDir);
                    }
                    mirrorCompatPluginPath(safeName, targetDir);
                } else {
                    deleteRecursive(new File(new File(runtime.getGeminiDir(), "config/plugins"), safeName));
                }
            }
            try {
                res.put("ok", false);
                res.put("error", String.valueOf(e.getMessage()));
            } catch (Exception ignored) {
            }
            return res.toString();
        }
    }

    public String postProcessInstalledPlugin(String pluginName) {
        try {
            String safeName = sanitizeName(pluginName);
            File targetDir = new File(getPluginHubDir(), safeName);
            if (!targetDir.isDirectory()) {
                return "{\"ok\":false,\"error\":\"Plugin directory not found\"}";
            }
            JSONObject r = runPostInstallAndWireDeepSeek(safeName, targetDir, "1.0.0");
            r.put("ok", true);
            r.put("reloaded", reloadEngineConfigBool());
            return r.toString();
        } catch (Exception e) {
            try {
                JSONObject err = new JSONObject();
                err.put("ok", false);
                err.put("error", String.valueOf(e.getMessage()));
                return err.toString();
            } catch (Exception ignored) {
                return "{\"ok\":false,\"error\":\"Plugin post-process error\"}";
            }
        }
    }

    public String toggleAndroidPlugin(String pluginName, boolean enabled) {
        JSONObject res = new JSONObject();
        try {
            String safeName = sanitizeName(pluginName);
            File stateFile = getHubStateFile();
            JSONObject state = readJsonObject(stateFile);
            JSONObject plugins = state.optJSONObject("plugins");
            if (plugins == null || !plugins.has(safeName)) {
                res.put("ok", false);
                res.put("error", "Plugin not installed");
                return res.toString();
            }
            JSONObject pMeta = plugins.getJSONObject(safeName);
            pMeta.put("enabled", enabled);

            File targetDir = new File(getPluginHubDir(), safeName);
            File claudeJsonFile = getClaudeJsonFile();
            JSONObject claudeJson = readJsonObject(claudeJsonFile);
            JSONObject mcpServers = claudeJson.optJSONObject("mcpServers");
            if (mcpServers == null) {
                mcpServers = new JSONObject();
                claudeJson.put("mcpServers", mcpServers);
            }

            if (enabled) {
                JSONObject savedConfigs = pMeta.optJSONObject("serverConfigs");
                if (savedConfigs != null && savedConfigs.length() > 0) {
                    Iterator<String> keys = savedConfigs.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        JSONObject cfg = savedConfigs.optJSONObject(k);
                        if (cfg != null) {
                            mcpServers.put(k, cfg);
                        }
                    }
                    AtomicFileWriter.writeText(claudeJsonFile, claudeJson.toString(2) + "\n");
                } else if (targetDir.isDirectory()) {
                    JSONObject wired = wireMcpConfigIntoDeepSeek(
                            safeName, targetDir, runtime.appContext().getFilesDir().getAbsolutePath());
                    pMeta.put("servers", wired.optJSONArray("names"));
                    pMeta.put("serverConfigs", wired.optJSONObject("configs"));
                }
                syncSkillsAndRules(safeName, targetDir);
            } else {
                JSONArray srvNames = pMeta.optJSONArray("servers");
                if (srvNames != null) {
                    for (int i = 0; i < srvNames.length(); i++) {
                        String sName = srvNames.optString(i, "");
                        if (!sName.isEmpty()) {
                            mcpServers.remove(sName);
                        }
                    }
                } else {
                    mcpServers.remove(safeName);
                }
                AtomicFileWriter.writeText(claudeJsonFile, claudeJson.toString(2) + "\n");
                removeSkillsAndRules(safeName, pMeta);
            }

            AtomicFileWriter.writeText(stateFile, state.toString(2) + "\n");

            res.put("ok", true);
            res.put("enabled", enabled);
            res.put("reloaded", reloadEngineConfigBool());
            return res.toString();
        } catch (Exception e) {
            try {
                res.put("ok", false);
                res.put("error", String.valueOf(e.getMessage()));
            } catch (Exception ignored) {
            }
            return res.toString();
        }
    }

    public boolean uninstallMarketplacePlugin(String pluginName) {
        try {
            String safeName = sanitizeName(pluginName);
            if (safeName.isEmpty()) return false;

            File stateFile = getHubStateFile();
            JSONObject state = readJsonObject(stateFile);
            JSONObject plugins = state.optJSONObject("plugins");
            JSONObject pMeta = plugins != null ? plugins.optJSONObject(safeName) : null;

            // Remove MCP servers from ~/.claude.json
            File claudeJsonFile = getClaudeJsonFile();
            JSONObject claudeJson = readJsonObject(claudeJsonFile);
            JSONObject mcpServers = claudeJson.optJSONObject("mcpServers");
            if (mcpServers != null) {
                if (pMeta != null && pMeta.optJSONArray("servers") != null) {
                    JSONArray arr = pMeta.optJSONArray("servers");
                    for (int i = 0; i < arr.length(); i++) {
                        mcpServers.remove(arr.optString(i, ""));
                    }
                } else {
                    mcpServers.remove(safeName);
                }
                AtomicFileWriter.writeText(claudeJsonFile, claudeJson.toString(2) + "\n");
            }

            removeSkillsAndRules(safeName, pMeta);
            deleteRecursive(new File(getPluginHubDir(), safeName));
            deleteRecursive(new File(new File(runtime.getGeminiDir(), "config/plugins"), safeName));

            if (plugins != null && plugins.has(safeName)) {
                plugins.remove(safeName);
                AtomicFileWriter.writeText(stateFile, state.toString(2) + "\n");
            }

            reloadEngineConfigBool();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String reloadEngineConfig() {
        boolean ok = reloadEngineConfigBool();
        return "{\"ok\":" + ok + "}";
    }

    private boolean reloadEngineConfigBool() {
        syncDshHomePatch();
        int port = getPort();
        if (port <= 0) return true;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/").openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(3000);
            String cookie = CoreRuntimeState.sessionCookieHeader;
            if (cookie != null && !cookie.isEmpty()) {
                conn.setRequestProperty("Cookie", cookie);
            }
            int status = conn.getResponseCode();
            return status >= 200 && status < 400;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public boolean restartEngine() {
        return CoreServerService.requestRestart();
    }

    public String getEngineLogs(int maxLines) {
        int limit = (maxLines <= 0 || maxLines > 500) ? 120 : maxLines;
        File logFile = new File(runtime.appContext().getFilesDir(), "logs/core.log");
        if (!logFile.isFile()) {
            return "(core.log not created yet)";
        }
        java.util.ArrayDeque<String> lines = new java.util.ArrayDeque<>(limit + 1);
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(logFile, "r")) {
            long len = raf.length();
            long start = Math.max(0L, len - 65536L);
            raf.seek(start);
            byte[] tail = new byte[(int) (len - start)];
            raf.readFully(tail);
            String chunk = new String(tail, StandardCharsets.UTF_8);
            int idx = start > 0 ? chunk.indexOf('\n') + 1 : 0;
            String[] split = chunk.substring(Math.max(0, idx)).split("\r?\n");
            for (String line : split) {
                if (lines.size() >= limit) {
                    lines.pollFirst();
                }
                lines.addLast(line);
            }
        } catch (Exception ignored) {
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String l : lines) {
            if (!first) sb.append('\n');
            first = false;
            sb.append(l);
        }
        return sb.toString();
    }

    private JSONObject runPostInstallAndWireDeepSeek(String safeName, File targetDir, String version)
            throws Exception {
        File filesDir = runtime.appContext().getFilesDir();
        String homePath = filesDir.getAbsolutePath();
        normalizeExtractedTree(targetDir, false, homePath);

        int hookExit = 0;
        File installHook = new File(new File(targetDir, "scripts"), "install-plugin.sh");
        if (!installHook.isFile()) {
            installHook = new File(targetDir, "install-plugin.sh");
        }
        if (installHook.isFile()) {
            File prefixDir = new File(filesDir, "usr");
            File prefixBin = new File(prefixDir, "bin");
            File runtimeBin = runtime.getBinDir();
            File bashBin = new File(prefixBin, "bash");
            String shell = bashBin.isFile() ? bashBin.getAbsolutePath() : "/system/bin/sh";

            ProcessBuilder pb = new ProcessBuilder(shell, installHook.getAbsolutePath());
            pb.directory(targetDir);
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            env.put("HOME", homePath);
            env.put("PREFIX", prefixDir.getAbsolutePath());
            env.put("TMPDIR", new File(prefixDir, "tmp").getAbsolutePath());
            env.put("DSH_HOME", runtime.getDshDir().getAbsolutePath());
            env.put("PLUGIN_ROOT", targetDir.getAbsolutePath());
            env.put("CLAUDE_PLUGIN_ROOT", targetDir.getAbsolutePath());
            env.put("PATH", prefixBin.getAbsolutePath() + ":" + runtimeBin.getAbsolutePath()
                    + ":" + homePath + "/.local/bin:/system/bin");
            env.put("LD_LIBRARY_PATH", new File(prefixDir, "lib").getAbsolutePath());
            File certFile = new File(prefixDir, "etc/tls/cert.pem");
            if (!certFile.isFile()) {
                certFile = runtime.getCertFile();
            }
            if (certFile.isFile()) {
                env.put("SSL_CERT_FILE", certFile.getAbsolutePath());
                env.put("CURL_CA_BUNDLE", certFile.getAbsolutePath());
            }
            Process p = pb.start();
            try {
                p.getOutputStream().close();
            } catch (Exception ignored) {
            }
            final InputStream pis = p.getInputStream();
            final java.io.ByteArrayOutputStream tailBuf = new java.io.ByteArrayOutputStream(4096);
            Thread drainer = new Thread(() -> {
                byte[] chunk = new byte[4096];
                try {
                    int n;
                    while ((n = pis.read(chunk)) != -1) {
                        synchronized (tailBuf) {
                            tailBuf.write(chunk, 0, n);
                            if (tailBuf.size() > MAX_HOOK_OUTPUT_BYTES * 2) {
                                byte[] cur = tailBuf.toByteArray();
                                tailBuf.reset();
                                tailBuf.write(cur, cur.length - MAX_HOOK_OUTPUT_BYTES, MAX_HOOK_OUTPUT_BYTES);
                            }
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    try {
                        pis.close();
                    } catch (Exception ignored) {
                    }
                }
            }, "CcPluginHookDrain");
            drainer.setDaemon(true);
            drainer.start();

            boolean finished = p.waitFor(20, TimeUnit.SECONDS);
            if (finished) {
                hookExit = p.exitValue();
                drainer.join(2000);
            } else {
                p.destroyForcibly();
                try {
                    pis.close();
                } catch (Exception ignored) {
                }
                drainer.join(1500);
                throw new IllegalStateException("Plugin post-install timed out after 20s");
            }
            if (hookExit != 0) {
                String tail;
                synchronized (tailBuf) {
                    byte[] rawTail = tailBuf.toByteArray();
                    int start = Math.max(0, rawTail.length - 512);
                    tail = new String(rawTail, start, rawTail.length - start, StandardCharsets.UTF_8).trim();
                }
                throw new IllegalStateException("Plugin post-install failed (exit " + hookExit + ")"
                        + (tail.isEmpty() ? "" : ": " + tail));
            }
            normalizeExtractedTree(targetDir, false, homePath);
        }

        // Sync skills + rules into ~/.claude/{skills,rules}
        JSONArray syncedSkills = syncSkillsAndRules(safeName, targetDir);

        // Convert mcp_config.json / .mcp.json -> ~/.claude.json (mcpServers)
        JSONObject wired = wireMcpConfigIntoDeepSeek(safeName, targetDir, homePath);
        JSONArray registeredServers = wired.optJSONArray("names");
        if (registeredServers == null) registeredServers = new JSONArray();
        JSONObject serverConfigs = wired.optJSONObject("configs");
        if (serverConfigs == null) serverConfigs = new JSONObject();

        // Persist plugin metadata in ~/.claude/android-plugins.json
        File stateFile = getHubStateFile();
        JSONObject state = readJsonObject(stateFile);
        JSONObject plugins = state.optJSONObject("plugins");
        if (plugins == null) {
            plugins = new JSONObject();
            state.put("plugins", plugins);
        }
        JSONObject pMeta = new JSONObject();
        pMeta.put("enabled", true);
        pMeta.put("version", version);
        pMeta.put("servers", registeredServers);
        pMeta.put("serverConfigs", serverConfigs);
        pMeta.put("skills", syncedSkills);
        plugins.put(safeName, pMeta);
        AtomicFileWriter.writeText(stateFile, state.toString(2) + "\n");

        JSONObject out = new JSONObject();
        out.put("hookExit", hookExit);
        out.put("servers", registeredServers);
        out.put("skills", syncedSkills);
        return out;
    }

    private JSONArray syncSkillsAndRules(String safeName, File targetDir) {
        JSONArray syncedSkills = new JSONArray();
        try {
            File skillsRoot = new File(runtime.getDshDir(), "skills");
            skillsRoot.mkdirs();
            File pluginSkills = new File(targetDir, "skills");
            if (pluginSkills.isDirectory()) {
                File[] children = pluginSkills.listFiles();
                if (children != null) {
                    for (File skillDir : children) {
                        if (skillDir.isDirectory() && new File(skillDir, "SKILL.md").isFile()) {
                            File dst = new File(skillsRoot, skillDir.getName());
                            deleteRecursive(dst);
                            copyRecursive(skillDir, dst);
                            syncedSkills.put(skillDir.getName());
                        }
                    }
                }
            }
            File rulesRoot = new File(runtime.getDshDir(), "rules");
            rulesRoot.mkdirs();
            File pluginRules = new File(targetDir, "rules");
            if (pluginRules.isDirectory()) {
                File[] ruleFiles = pluginRules.listFiles();
                if (ruleFiles != null) {
                    for (File rf : ruleFiles) {
                        if (rf.isFile() && rf.getName().endsWith(".md")) {
                            File dst = new File(rulesRoot, safeName + "-" + rf.getName());
                            copyFile(rf, dst);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "syncSkillsAndRules warning: " + e.getMessage());
        }
        return syncedSkills;
    }

    private void removeSkillsAndRules(String safeName, JSONObject pMeta) {
        try {
            File skillsRoot = new File(runtime.getDshDir(), "skills");
            if (pMeta != null && pMeta.optJSONArray("skills") != null) {
                JSONArray skills = pMeta.optJSONArray("skills");
                for (int i = 0; i < skills.length(); i++) {
                    String sk = skills.optString(i, "");
                    if (!sk.isEmpty()) {
                        deleteRecursive(new File(skillsRoot, sk));
                    }
                }
            }
            File rulesRoot = new File(runtime.getDshDir(), "rules");
            File[] rfs = rulesRoot.listFiles();
            if (rfs != null) {
                for (File rf : rfs) {
                    if (rf.getName().startsWith(safeName + "-")) {
                        rf.delete();
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private JSONObject wireMcpConfigIntoDeepSeek(String safeName, File targetDir, String homePath) {
        JSONObject result = new JSONObject();
        JSONArray names = new JSONArray();
        JSONObject configs = new JSONObject();
        try {
            result.put("names", names);
            result.put("configs", configs);

            File claudeJsonFile = getClaudeJsonFile();
            JSONObject claudeJson = readJsonObject(claudeJsonFile);
            JSONObject dstServers = claudeJson.optJSONObject("mcpServers");
            if (dstServers == null) {
                dstServers = new JSONObject();
                claudeJson.put("mcpServers", dstServers);
            }

            File mcpFile = new File(targetDir, "mcp_config.json");
            if (!mcpFile.isFile()) {
                mcpFile = new File(targetDir, ".mcp.json");
            }
            if (!mcpFile.isFile()) {
                mcpFile = new File(targetDir, "mcp.json");
            }
            if (!mcpFile.isFile()) {
                return result;
            }
            JSONObject src = readJsonObject(mcpFile);
            JSONObject srcServers = src.optJSONObject("mcpServers");
            if (srcServers == null) {
                srcServers = src.optJSONObject("servers");
            }
            if (srcServers == null) {
                return result;
            }

            String pluginRoot = targetDir.getAbsolutePath();
            String prefixPath = new File(homePath, "usr").getAbsolutePath();
            Iterator<String> keys = srcServers.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject entry = srcServers.optJSONObject(key);
                if (entry == null) continue;

                String serverName = key.equals(safeName) ? safeName : key;
                JSONObject ccEntry = new JSONObject();

                String remoteUrl = entry.optString("url", entry.optString("serverUrl", ""));
                if (!remoteUrl.isEmpty()) {
                    String explicitType = entry.optString("type", "");
                    ccEntry.put("type", "sse".equalsIgnoreCase(explicitType) ? "sse" : "http");
                    ccEntry.put("url", expandVars(remoteUrl, pluginRoot, homePath, prefixPath));
                    JSONObject hdrs = entry.optJSONObject("headers");
                    if (hdrs != null) {
                        ccEntry.put("headers", hdrs);
                    }
                } else {
                    ccEntry.put("type", "stdio");
                    String cmd = entry.optString("command", "");
                    JSONArray argsOut = new JSONArray();
                    if (cmd.isEmpty()) {
                        JSONArray cmdArr = entry.optJSONArray("command");
                        if (cmdArr != null && cmdArr.length() > 0) {
                            cmd = cmdArr.optString(0, "");
                            for (int i = 1; i < cmdArr.length(); i++) {
                                argsOut.put(expandVars(cmdArr.optString(i, ""), pluginRoot, homePath, prefixPath));
                            }
                        }
                    }
                    if (cmd.isEmpty()) continue;
                    ccEntry.put("command", expandVars(cmd, pluginRoot, homePath, prefixPath));

                    JSONArray argsIn = entry.optJSONArray("args");
                    if (argsIn != null) {
                        for (int i = 0; i < argsIn.length(); i++) {
                            argsOut.put(expandVars(argsIn.optString(i, ""), pluginRoot, homePath, prefixPath));
                        }
                    }
                    ccEntry.put("args", argsOut);

                    JSONObject envIn = entry.optJSONObject("env");
                    if (envIn == null) {
                        envIn = entry.optJSONObject("environment");
                    }
                    JSONObject envOut = new JSONObject();
                    envOut.put("HOME", homePath);
                    envOut.put("PREFIX", prefixPath);
                    envOut.put("TMPDIR", prefixPath + "/tmp");
                    envOut.put("LD_LIBRARY_PATH", prefixPath + "/lib");
                    envOut.put("PATH", prefixPath + "/bin:" + homePath + "/runtime/bin:" + homePath + "/.local/bin:/system/bin");
                    if (envIn != null) {
                        Iterator<String> ek = envIn.keys();
                        while (ek.hasNext()) {
                            String k = ek.next();
                            envOut.put(k, expandVars(envIn.optString(k, ""), pluginRoot, homePath, prefixPath));
                        }
                    }
                    ccEntry.put("env", envOut);
                }

                dstServers.put(serverName, ccEntry);
                configs.put(serverName, new JSONObject(ccEntry.toString()));
                names.put(serverName);
            }
            AtomicFileWriter.writeText(claudeJsonFile, claudeJson.toString(2) + "\n");
        } catch (Exception e) {
            Log.w(TAG, "wireMcpConfigIntoDeepSeek warning: " + e.getMessage());
        }
        return result;
    }

    private static String expandVars(String val, String pluginRoot, String homePath, String prefixPath) {
        if (val == null) return "";
        return val.replace("${CLAUDE_PLUGIN_ROOT}", pluginRoot)
                .replace("$CLAUDE_PLUGIN_ROOT", pluginRoot)
                .replace("${PLUGIN_ROOT}", pluginRoot)
                .replace("$PLUGIN_ROOT", pluginRoot)
                .replace("/data/user/0/com.agy/files/.gemini/config/plugins/", homePath + "/.dsh/plugins-hub/")
                .replace("/data/user/0/com.agy/files", homePath)
                .replace("/data/data/com.agy/files", homePath)
                .replace("/data/user/0/com.oc.ide/files", homePath)
                .replace("/data/data/com.oc.ide/files", homePath)
                .replace("${PREFIX}", prefixPath)
                .replace("${HOME}", homePath);
    }

    private void mirrorCompatPluginPath(String safeName, File targetDir) {
        try {
            File compatParent = new File(runtime.getGeminiDir(), "config/plugins");
            compatParent.mkdirs();
            File compatLink = new File(compatParent, safeName);
            deleteRecursive(compatLink);
            try {
                android.system.Os.symlink(targetDir.getAbsolutePath(), compatLink.getAbsolutePath());
            } catch (Throwable t) {
                copyRecursive(targetDir, compatLink);
            }
        } catch (Exception ignored) {
        }
    }

    private static void normalizeExtractedTree(File dir, boolean inExecDir, String homePath) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            String name = f.getName();
            if (f.isDirectory()) {
                boolean childExec = inExecDir || "bin".equals(name) || "scripts".equals(name);
                normalizeExtractedTree(f, childExec, homePath);
                continue;
            }
            if (f.length() > 0 && f.length() < 1024 * 1024) {
                try {
                    byte[] raw = new byte[(int) f.length()];
                    try (FileInputStream fis = new FileInputStream(f)) {
                        int off = 0;
                        while (off < raw.length) {
                            int r = fis.read(raw, off, raw.length - off);
                            if (r <= 0) break;
                            off += r;
                        }
                    }
                    if (!(raw.length >= 4 && raw[0] == 0x7F && raw[1] == 'E' && raw[2] == 'L' && raw[3] == 'F')) {
                        String txt = new String(raw, StandardCharsets.UTF_8);
                        if (txt.indexOf('\r') >= 0 || txt.contains("com.agy") || txt.contains("com.oc.ide")) {
                            String clean = txt.replace("\r\n", "\n")
                                    .replace("/data/user/0/com.agy/files/.gemini/config/plugins/",
                                            homePath + "/.dsh/plugins-hub/")
                                    .replace("/data/user/0/com.agy/files", homePath)
                                    .replace("/data/data/com.agy/files", homePath)
                                    .replace("/data/user/0/com.oc.ide/files", homePath)
                                    .replace("/data/data/com.oc.ide/files", homePath);
                            AtomicFileWriter.writeText(f, clean);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            if (inExecDir || name.endsWith(".sh")) {
                FileModeUtil.setExec(f);
            }
        }
    }

    private static final java.util.regex.Pattern SAFE_PLUGIN_NAME =
            java.util.regex.Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$");

    private static String sanitizeName(String s) {
        if (s == null) return "";
        String trimmed = s.trim();
        if (".".equals(trimmed) || "..".equals(trimmed)) {
            return "";
        }
        if (SAFE_PLUGIN_NAME.matcher(trimmed).matches()) {
            return trimmed;
        }
        return "";
    }

    private static boolean isSymlink(File f) {
        if (f == null) return false;
        try {
            android.system.StructStat stat = android.system.Os.lstat(f.getAbsolutePath());
            return android.system.OsConstants.S_ISLNK(stat.st_mode);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void copyRecursive(File src, File dst) throws Exception {
        if (isSymlink(src)) {
            Log.w(TAG, "Skipping symlink during copy: " + src);
            return;
        }
        if (src.isDirectory()) {
            dst.mkdirs();
            File[] children = src.listFiles();
            if (children != null) {
                for (File c : children) {
                    copyRecursive(c, new File(dst, c.getName()));
                }
            }
        } else if (src.isFile()) {
            copyFile(src, dst);
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        if (isSymlink(src) || isSymlink(dst)) {
            Log.w(TAG, "Refusing copy with symlink involved: src=" + src + " dst=" + dst);
            return;
        }
        File parent = dst.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            parent.mkdirs();
        }
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        try {
            if (android.system.Os.lstat(f.getAbsolutePath()).st_mode != 0
                    && android.system.OsConstants.S_ISLNK(android.system.Os.lstat(f.getAbsolutePath()).st_mode)) {
                f.delete();
                return;
            }
        } catch (Throwable ignored) {
        }
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursive(c);
            }
        }
        f.delete();
    }

    private static JSONObject readJsonObject(File f) {
        if (f != null && f.isFile()) {
            try (FileInputStream fis = new FileInputStream(f)) {
                String txt = IoUtil.readAll(fis, "UTF-8").trim();
                if (!txt.isEmpty()) {
                    return new JSONObject(txt);
                }
            } catch (Exception ignored) {
            }
        }
        return new JSONObject();
    }
}
