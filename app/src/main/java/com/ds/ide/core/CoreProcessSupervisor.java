package com.ds.ide.core;

import android.content.Context;
import android.util.Log;

import com.ds.ide.runtime.RuntimeManager;
import com.ds.ide.util.CoreUrl;
import com.ds.ide.util.ErrorText;
import com.ds.ide.util.ProcessUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * Supervises the native DeepSeek Harness server for its whole life. Installs/verifies,
 * launches, captures the startup token, exchanges it for a 30-day signed session cookie,
 * and auto-restarts on unexpected exits with exponential backoff.
 */
public final class CoreProcessSupervisor {

    private static final String TAG = "CoreProcessSupervisor";

    private final Context appContext;
    private final RuntimeManager runtime;
    private final OpenUrlWatcher openUrlWatcher;

    private volatile Process process;
    private volatile boolean stopping = false;
    private Thread worker;

    public CoreProcessSupervisor(Context context, RuntimeManager runtime,
                                 OpenUrlWatcher openUrlWatcher) {
        this.appContext = context.getApplicationContext();
        this.runtime = runtime;
        this.openUrlWatcher = openUrlWatcher;
    }

    public void start() {
        stopping = false;
        worker = new Thread(this::runCore, "CoreServerThread");
        worker.start();
    }

    public void stop() {
        stopping = true;
        destroy(process);
        if (worker != null) {
            worker.interrupt();
        }
    }

    /** Triggers a clean supervised restart of the DeepSeek Harness process. */
    public void restartProcess() {
        Process p = process;
        if (p != null) {
            destroy(p);
        }
    }

    private void runCore() {
        int attempt = 0;
        while (!stopping) {
            Writer log = CoreLogWriter.open(appContext.getFilesDir());
            try {
                CoreRuntimeState.lastError = null;
                CoreRuntimeState.restarting = false;
                CoreRuntimeState.launchUrl = null;
                CoreRuntimeState.sessionSetCookieRaw = null;
                CoreRuntimeState.sessionCookieHeader = null;
                CoreRuntimeState.setCachedInitialHtml(null);
                Process proc = CoreLauncher.launch(runtime, appContext.getFilesDir());
                process = proc;

                final Writer streamWriter = log;
                Thread streamer = new Thread(() -> streamLog(proc, streamWriter), "CoreStreamThread");
                streamer.setDaemon(true);
                streamer.start();

                if (!waitForReady(proc, log)) {
                    if (stopping) {
                        break;
                    }
                    destroy(proc);
                    attempt++;
                    if (attempt > CoreRestartPolicy.MAX_RESTARTS) {
                        CoreRuntimeState.restarting = false;
                        if (CoreRuntimeState.lastError == null) {
                            CoreRuntimeState.lastError =
                                    "DeepSeek server did not become ready within " + CoreReadyProbe.READY_TIMEOUT_MS + "ms";
                        }
                        CoreRuntimeState.notifyStateChanged();
                        CoreLogWriter.writeLine(log, CoreRuntimeState.lastError);
                        break;
                    }
                    long delay = CoreRestartPolicy.delayFor(attempt);
                    CoreRuntimeState.restarting = true;
                    CoreRuntimeState.lastError = "DeepSeek startup timed out; retrying in " + (delay / 1000)
                            + "s (" + attempt + "/" + CoreRestartPolicy.MAX_RESTARTS + ")";
                    CoreRuntimeState.notifyStateChanged();
                    Log.w(TAG, CoreRuntimeState.lastError);
                    CoreLogWriter.writeLine(log, CoreRuntimeState.lastError);
                    CoreLogWriter.closeQuietly(log);
                    log = null;
                    sleepInterruptible(delay);
                    continue;
                }

                long readyAt = System.currentTimeMillis();
                try {
                    proc.waitFor();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                if (stopping) {
                    break;
                }

                int code = ProcessUtils.exitCode(proc);
                if (System.currentTimeMillis() - readyAt >= CoreRestartPolicy.SUSTAINED_READY_MS) {
                    attempt = 0;
                }
                CoreRuntimeState.serverUrl = null;
                attempt++;
                String tail = getLastLinesFromLog(appContext.getFilesDir(), 15);
                if (attempt > CoreRestartPolicy.MAX_RESTARTS) {
                    CoreRuntimeState.restarting = false;
                    CoreRuntimeState.lastError = "DeepSeek exited " + CoreRestartPolicy.MAX_RESTARTS
                            + " times; giving up (last exit code " + code + ")"
                            + (tail.isEmpty() ? "" : "\n--- core.log tail ---\n" + tail);
                    CoreRuntimeState.notifyStateChanged();
                    CoreLogWriter.writeLine(log, CoreRuntimeState.lastError);
                    break;
                }
                long delay = CoreRestartPolicy.delayFor(attempt);
                CoreRuntimeState.restarting = true;
                CoreRuntimeState.lastError = "DeepSeek restarting in " + (delay / 1000) + "s (attempt "
                        + attempt + "/" + CoreRestartPolicy.MAX_RESTARTS + ", code " + code + ")";
                CoreRuntimeState.notifyStateChanged();
                Log.w(TAG, CoreRuntimeState.lastError);
                CoreLogWriter.writeLine(log, CoreRuntimeState.lastError);
                CoreLogWriter.closeQuietly(log);
                log = null;
                sleepInterruptible(delay);
            } catch (Exception e) {
                if (stopping) {
                    break;
                }
                Log.e(TAG, "DeepSeek start failed", e);
                CoreRuntimeState.lastError = ErrorText.describe(e);
                CoreRuntimeState.notifyStateChanged();
                CoreLogWriter.writeLine(log, "DeepSeek start failed: " + CoreRuntimeState.lastError);
                break;
            } finally {
                CoreLogWriter.closeQuietly(log);
            }
        }
    }

    private boolean waitForReady(Process proc, Writer log) {
        long start = System.currentTimeMillis();
        String csrf = runtime.getCsrfToken();
        int port = CoreRuntimeState.httpsPort;
        while (System.currentTimeMillis() - start < CoreReadyProbe.READY_TIMEOUT_MS) {
            if (stopping) {
                return false;
            }
            if (!ProcessUtils.isAlive(proc)) {
                int code = ProcessUtils.exitCode(proc);
                ProcessUtils.sleep(200);
                String lastLogs = getLastLinesFromLog(appContext.getFilesDir(), 12);
                CoreRuntimeState.lastError = "DeepSeek exited before becoming ready (exit code " + code + ")"
                        + (lastLogs.isEmpty() ? "" : "\n--- core.log tail ---\n" + lastLogs);
                CoreLogWriter.writeLine(log, CoreRuntimeState.lastError);
                return false;
            }
            if (CoreReadyProbe.probeOnce(port, csrf)) {
                CoreReadyProbe.warmDefaultWorkspace(port, csrf, runtime.getDefaultWorkspaceDir());
                String sessionToken = CoreReadyProbe.mintSessionToken(port, csrf);
                CoreRuntimeState.sessionToken = sessionToken;
                CoreRuntimeState.serverUrl = CoreUrl.baseUrl(port) + "/";
                CoreRuntimeState.lastError = null;
                CoreRuntimeState.restarting = false;
                CoreRuntimeState.readyGeneration++;
                CoreRuntimeState.notifyStateChanged();
                Log.i(TAG, "DeepSeek ready: " + CoreRuntimeState.serverUrl
                        + " (sessionCookie=" + (CoreRuntimeState.sessionCookieHeader != null) + ")");
                CoreLogWriter.writeLine(log, "DeepSeek ready: " + CoreRuntimeState.serverUrl);
                return true;
            }
        }
        return false;
    }

    private void streamLog(Process proc, Writer log) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int dshIdx = line.indexOf("dsh web: http");
                if (dshIdx >= 0) {
                    String url = line.substring(dshIdx + "dsh web: ".length()).trim();
                    synchronized (CoreRuntimeState.LAUNCH_URL_LOCK) {
                        CoreRuntimeState.launchUrl = url;
                        CoreRuntimeState.LAUNCH_URL_LOCK.notifyAll();
                    }
                    Log.i(TAG, "Captured DeepSeek launch readiness signal on loopback");
                    CoreLogWriter.writeLine(log, "dsh web: [launch-token captured]");
                    continue;
                }
                String safeLine = LogRedaction.redact(line);
                Log.d(TAG, safeLine);
                CoreLogWriter.writeLine(log, safeLine);
                if (line.contains("[deepseek-account] android-oauth-complete")) {
                    Log.i(TAG, "OAuth completed in browser; bringing DeepSeek task to front");
                    com.ds.ide.oauth.OAuthCallbackActivity.bringAppToFront(appContext);
                }
                openUrlWatcher.handleStreamLine(line);
            }
        } catch (Exception e) {
            Log.w(TAG, "log stream ended", e);
        }
    }

    private void sleepInterruptible(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (!stopping) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) {
                return;
            }
            ProcessUtils.sleep(Math.min(left, 250));
        }
    }

    private static void destroy(Process proc) {
        if (proc != null) {
            proc.destroy();
        }
    }

    private static String getLastLinesFromLog(File filesDir, int maxLines) {
        File logFile = new File(filesDir, "logs/core.log");
        if (!logFile.isFile()) {
            return "";
        }
        java.util.ArrayDeque<String> lines = new java.util.ArrayDeque<>(maxLines + 1);
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(logFile, "r")) {
            long len = raf.length();
            long start = Math.max(0L, len - 16384L);
            raf.seek(start);
            byte[] tail = new byte[(int) (len - start)];
            raf.readFully(tail);
            String chunk = new String(tail, java.nio.charset.StandardCharsets.UTF_8);
            int idx = start > 0 ? chunk.indexOf('\n') + 1 : 0;
            for (String line : chunk.substring(Math.max(0, idx)).split("\r?\n")) {
                if (lines.size() >= maxLines) {
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
    /**
     * [PARCHE redaccion] Enmascara credenciales en las lineas del log del core.
     * El log se puede leer desde la interfaz, y los proveedores de inferencia a
     * veces devuelven la clave en el mensaje de error. Werdio traia un
     * LogRedactor que este fork elimino; esto restaura esa proteccion.
     */
    static final class LogRedaction {
        private static final java.util.regex.Pattern[] RULES = {
            // Claves conocidas por prefijo
            java.util.regex.Pattern.compile("\\b(sk-[A-Za-z0-9_-]{8,})"),
            java.util.regex.Pattern.compile("\\b(sk-or-v1-[A-Za-z0-9]{8,})"),
            java.util.regex.Pattern.compile("\\b(gsk_[A-Za-z0-9]{8,})"),
            java.util.regex.Pattern.compile("\\b(hf_[A-Za-z0-9]{8,})"),
            java.util.regex.Pattern.compile("\\b(xai-[A-Za-z0-9]{8,})"),
            java.util.regex.Pattern.compile("\\b(nvapi-[A-Za-z0-9_-]{8,})"),
            java.util.regex.Pattern.compile("\\b(thk_[a-z]+_[A-Za-z0-9_-]{8,})"),
            java.util.regex.Pattern.compile("\\b(abl_[A-Za-z0-9]{8,})"),
            java.util.regex.Pattern.compile("\\b(coa_[A-Za-z0-9]{8,})"),
            java.util.regex.Pattern.compile("\\b(oc_sk_[A-Za-z0-9]{8,})"),
            // Authorization: Bearer <token>
            java.util.regex.Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]{12,}"),
            // Parametros de URL con token
            java.util.regex.Pattern.compile("(?i)([?&](?:token|api_?key|access_token)=)[^&\\s\"]+"),
            // Cabeceras de clave
            java.util.regex.Pattern.compile("(?i)(x-api-key\\s*[:=]\\s*)[^\\s\",}]+"),
        };

        static String redact(String line) {
            if (line == null || line.isEmpty()) {
                return line;
            }
            String out = line;
            try {
                out = RULES[1].matcher(out).replaceAll("sk-or-v1-[REDACTED]");
                out = RULES[0].matcher(out).replaceAll("sk-[REDACTED]");
                out = RULES[2].matcher(out).replaceAll("gsk_[REDACTED]");
                out = RULES[3].matcher(out).replaceAll("hf_[REDACTED]");
                out = RULES[4].matcher(out).replaceAll("xai-[REDACTED]");
                out = RULES[5].matcher(out).replaceAll("nvapi-[REDACTED]");
                out = RULES[6].matcher(out).replaceAll("thk_[REDACTED]");
                out = RULES[7].matcher(out).replaceAll("abl_[REDACTED]");
                out = RULES[8].matcher(out).replaceAll("coa_[REDACTED]");
                out = RULES[9].matcher(out).replaceAll("oc_sk_[REDACTED]");
                out = RULES[10].matcher(out).replaceAll("$1[REDACTED]");
                out = RULES[11].matcher(out).replaceAll("$1[REDACTED]");
                out = RULES[12].matcher(out).replaceAll("$1[REDACTED]");
            } catch (Throwable ignored) {
                return out;
            }
            return out;
        }

        private LogRedaction() {
        }
    }

}
