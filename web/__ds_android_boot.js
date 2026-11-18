/* __DS_POLYFILLS__ */
/**
 * Polyfills para WebView antiguos.
 *
 * El WebView de HarmonyOS 4.x (basado en una version anterior a Chrome 116)
 * no trae varias APIs que los bundles del cliente usan. Sin ellas, acciones
 * como elegir la carpeta de trabajo fallan con "AbortSignal.any is not a
 * function".
 *
 * Se instala una sola vez y no toca nada si la API ya existe.
 */
(function () {
  "use strict";

  // AbortSignal.any(signals) — Chrome 116
  if (typeof AbortSignal !== "undefined" && typeof AbortSignal.any !== "function") {
    AbortSignal.any = function (signals) {
      var controller = new AbortController();
      var lista = Array.prototype.slice.call(signals || []);
      var activos = 0;
      var pendientes = lista.length;

      if (pendientes === 0) return controller.signal;

      for (var i = 0; i < lista.length; i++) {
        (function (sig) {
          if (sig == null) return;
          if (sig.aborted) {
            if (!controller.signal.aborted) {
              controller.abort(sig.reason);
            }
            return;
          }
          activos++;
          sig.addEventListener(
            "abort",
            function () {
              if (!controller.signal.aborted) {
                controller.abort(sig.reason);
              }
            },
            { once: true }
          );
        })(lista[i]);
      }
      return controller.signal;
    };
  }

  // AbortSignal.timeout(ms) — Chrome 103
  if (typeof AbortSignal !== "undefined" && typeof AbortSignal.timeout !== "function") {
    AbortSignal.timeout = function (ms) {
      var controller = new AbortController();
      setTimeout(function () {
        try {
          controller.abort(new DOMException("TimeoutError", "TimeoutError"));
        } catch (e) {
          controller.abort();
        }
      }, ms);
      return controller.signal;
    };
  }

  // Promise.withResolvers() — Chrome 119
  if (typeof Promise !== "undefined" && typeof Promise.withResolvers !== "function") {
    Promise.withResolvers = function () {
      var resolve, reject;
      var promise = new Promise(function (res, rej) {
        resolve = res;
        reject = rej;
      });
      return { promise: promise, resolve: resolve, reject: reject };
    };
  }

  // structuredClone() — Chrome 98
  // Se evita JSON.stringify: corrompe Date, Map, Set, ArrayBuffer y undefined,
  // y eso puede dejar sin dibujar respuestas que si llegaron.
  if (typeof structuredClone !== "function") {
    var clonar = function (v, vistos) {
      if (v === null || typeof v !== "object") return v;
      if (v instanceof Date) return new Date(v.getTime());
      if (v instanceof RegExp) return new RegExp(v.source, v.flags);
      if (typeof ArrayBuffer !== "undefined" && v instanceof ArrayBuffer) return v.slice(0);
      if (typeof ArrayBuffer !== "undefined" && ArrayBuffer.isView && ArrayBuffer.isView(v)) {
        try { return new v.constructor(v); } catch (e) { return v; }
      }
      if (vistos.has(v)) return vistos.get(v);
      if (Array.isArray(v)) {
        var arr = [];
        vistos.set(v, arr);
        for (var i = 0; i < v.length; i++) arr[i] = clonar(v[i], vistos);
        return arr;
      }
      if (typeof Map !== "undefined" && v instanceof Map) {
        var m = new Map();
        vistos.set(v, m);
        v.forEach(function (val, k) { m.set(clonar(k, vistos), clonar(val, vistos)); });
        return m;
      }
      if (typeof Set !== "undefined" && v instanceof Set) {
        var st = new Set();
        vistos.set(v, st);
        v.forEach(function (val) { st.add(clonar(val, vistos)); });
        return st;
      }
      var out = {};
      vistos.set(v, out);
      var claves = Object.keys(v);
      for (var k2 = 0; k2 < claves.length; k2++) {
        var c = claves[k2];
        out[c] = clonar(v[c], vistos);
      }
      return out;
    };
    window.structuredClone = function (valor) {
      return clonar(valor, new Map());
    };
  }

  // Object.hasOwn() — Chrome 93
  if (typeof Object.hasOwn !== "function") {
    Object.hasOwn = function (obj, prop) {
      return Object.prototype.hasOwnProperty.call(obj, prop);
    };
  }
})();
(function(){
  if (document.getElementById('__ds_oled')) return;
  var s = document.createElement('style');
  s.id = '__ds_oled';
  s.textContent = "\n/* [OLED] Rampa oscura a negros reales. El tema de escritorio usa #151517,\n   que en un panel OLED se ve gris y gasta bateria. Se remapea solo en modo\n   oscuro; el modo claro no cambia. */\n[data-ds-dark-theme] {\n  --dsw-static-neutral-bluish-1000: #000000 !important;\n  --dsw-static-neutral-bluish-950: #000000 !important;\n  --dsw-static-neutral-bluish-900: #050506 !important;\n  --dsw-static-neutral-bluish-875: #0a0a0b !important;\n  --dsw-static-neutral-bluish-850: #101012 !important;\n  --dsw-static-neutral-bluish-800: #17171a !important;\n  --dsw-static-neutral-bluish-750: #202024 !important;\n  --dsw-static-neutral-bluish-700: #4a4d52 !important;\n}\nhtml[data-ds-dark-theme] body,\nbody[data-ds-dark-theme] {\n  background-color: #000000 !important;\n}\n";
  (document.head || document.documentElement).appendChild(s);
})();


(function () {
  "use strict";

  function getHost() {
    var h = window.Android || window.AndroidBridge || null;
    if (h && !window.AndroidBridge) {
      try { window.AndroidBridge = h; } catch (_) {}
    }
    return h;
  }

  if (window.__DS_ANDROID_BOOTED__ && getHost()) return;

  var BRIDGE_TOKEN = typeof window.__DS_BRIDGE_TOKEN__ === "string" ? window.__DS_BRIDGE_TOKEN__ : "";
  try {
    delete window.__DS_BRIDGE_TOKEN__;
  } catch (e) {
    window.__DS_BRIDGE_TOKEN__ = undefined;
  }

  if (getHost()) {
    window.__DS_ANDROID_BOOTED__ = true;
  }

  // Unregister any stale ServiceWorker so fresh local assets always load
  try {
    if (navigator.serviceWorker && typeof navigator.serviceWorker.getRegistrations === "function") {
      navigator.serviceWorker.getRegistrations().then(function (regs) {
        regs.forEach(function (r) { r.unregister(); });
      }).catch(function () {});
    }
  } catch (_) {}

  var pendingCallbacks = Object.create(null);
  var pendingTimers = Object.create(null);
  var cbSeq = 0;
  function registerCb(prefix, timeoutMs, fn) {
    var id = (prefix || "ds_cb_") + (++cbSeq) + "_" + Date.now();
    pendingCallbacks[id] = fn;
    pendingTimers[id] = setTimeout(function () {
      var expired = pendingCallbacks[id];
      delete pendingCallbacks[id];
      delete pendingTimers[id];
      if (expired) {
        try { expired({ ok: false, error: "timeout" }); } catch (_) {}
      }
    }, timeoutMs || 90000);
    return id;
  }

  function settleCb(cbId, payload) {
    var fn = pendingCallbacks[cbId];
    if (pendingTimers[cbId]) {
      clearTimeout(pendingTimers[cbId]);
      delete pendingTimers[cbId];
    }
    if (fn) {
      delete pendingCallbacks[cbId];
      fn(payload);
    }
  }

  window.__dsOnPluginOpDone = function (cbId, resJson) {
    var parsed = {};
    try {
      parsed = typeof resJson === "string" ? JSON.parse(resJson) : (resJson || {});
    } catch (_) {}
    settleCb(cbId, parsed);
  };

  window.__dsOnDirectoryPicked = function (cbId, pickedPath) {
    settleCb(cbId, typeof pickedPath === "string" ? pickedPath : "");
  };

  var latestUpdateState = { type: "idle" };
  var lastRenderedUpdateType = "idle";
  window.__dsOnUpdaterStateChanged = function (st) {
    var next = st || { type: "idle" };
    var prevType = lastRenderedUpdateType;
    latestUpdateState = next;
    if (next.type !== prevType || next.type === "downloading") {
      lastRenderedUpdateType = next.type;
      if (next.type === "ready" && prevType !== "ready") {
        showToast(isSpanish()
          ? "Actualización v" + (next.version || "") + " lista en Complementos"
          : "Update v" + (next.version || "") + " ready in Plugins");
      }
      renderPluginsHubIfMounted();
    }
  };

  function isSpanish() {
    var lang = ((document.documentElement && document.documentElement.lang) || navigator.language || "").toLowerCase();
    return lang.indexOf("es") === 0;
  }

  // Toast notification
  var toastEl = null;
  var toastTimer = null;
  function showToast(msg) {
    if (!msg || !document.body) return;
    if (!toastEl || !toastEl.isConnected) {
      toastEl = document.createElement("div");
      toastEl.id = "ds-android-toast";
      document.body.appendChild(toastEl);
    }
    toastEl.textContent = String(msg);
    toastEl.classList.add("visible");
    if (toastTimer) clearTimeout(toastTimer);
    toastTimer = setTimeout(function () {
      if (toastEl) toastEl.classList.remove("visible");
    }, 3200);
  }

  // 1. Mobile CSS & Virtual Viewport Keyboard Adaptation + Native Complementos Styling
  function injectMobileCss() {
    if (document.getElementById("ds-android-mobile-css")) return;
    var st = document.createElement("style");
    st.id = "ds-android-mobile-css";
    st.textContent = [
      "button, a, [role='button'], [role='tab'], input, select, textarea { touch-action: manipulation; -webkit-tap-highlight-color: transparent; }",
      "html, body, #root { height: var(--ds-vvh, 100dvh) !important; max-height: var(--ds-vvh, 100dvh) !important; }",
      "@media (max-width: 767px) {",
      "  .ZuhsRW_dialog.ZuhsRW_dialog { max-width: calc(100vw - 16px) !important; max-height: min(560px, calc(var(--ds-vvh, 100dvh) - 24px)) !important; }",
      "  .ZuhsRW_header { padding: 14px 14px 8px 16px !important; }",
      "  .ZuhsRW_content { padding: 12px 12px 12px 16px !important; }",
      "  .ZuhsRW_footerBar { padding: 12px 16px !important; flex-wrap: wrap !important; gap: 8px !important; }",
      "}",
      /* Authentic PC Desktop OnboardingSurface backdrop (no custom icon, solid base surface when unauthenticated) */
      "body:has([data-shortcut-modal] ._76YFJG_dialog:not(:has(._76YFJG_close)), .jLrgrW_dialog) > div[role='presentation'] > div[aria-hidden='true'],",
      "body > div[role='presentation']:has(> ._76YFJG_dialog:not(:has(._76YFJG_close)), > .jLrgrW_dialog) > div[aria-hidden='true'] {",
      "  background: var(--dsw-alias-bg-base, #15161a) !important;",
      "  opacity: 1 !important;",
      "  backdrop-filter: none !important;",
      "  -webkit-backdrop-filter: none !important;",
      "}",
      "@media (max-width: 960px) {",
      "  section[data-desktop-onboarding] { overflow-y: auto !important; padding: 24px 20px !important; box-sizing: border-box !important; }",
      "  section[data-desktop-onboarding='welcome'] > div { position: relative !important; top: auto !important; left: auto !important; transform: none !important; display: flex !important; flex-direction: column !important; gap: 28px !important; padding-top: 32px !important; height: auto !important; }",
      "  section[data-desktop-onboarding='welcome'] > div > div:first-child { position: relative !important; top: auto !important; left: auto !important; transform: none !important; width: 100% !important; max-width: 520px !important; }",
      "  section[data-desktop-onboarding='welcome'] > div > div:last-child { position: relative !important; top: auto !important; left: auto !important; width: 100% !important; height: auto !important; }",
      "  section[data-desktop-onboarding='welcome'] img { position: relative !important; left: 0 !important; top: 0 !important; width: 100% !important; max-width: 720px !important; height: auto !important; }",
      "  section[data-desktop-onboarding='credit'] > div { grid-template-columns: 1fr !important; gap: 24px !important; position: relative !important; top: auto !important; left: auto !important; transform: none !important; padding-top: 24px !important; }",
      "}",
      "#ds-agy-plugins-hub { display: flex; flex-direction: column; gap: 10px; width: 100%; }",
      ".ds-pm-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 0 4px; }",
      ".ds-pm-title { margin: 0; font-size: 13.5px; font-weight: 500; color: var(--dsw-alias-label-primary, #f5f6fa); }",
      ".ds-pm-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 2px; }",
      ".ds-pm-row { display: flex; align-items: center; gap: 14px; padding: 8px; border-radius: 12px; transition: background 0.15s ease; }",
      "@media (hover: hover) { .ds-pm-row:hover { background: var(--dsw-alias-bg-fill-hover, rgba(255,255,255,0.04)); } }",
      ".ds-pm-icon {",
      "  width: 48px; height: 48px; flex: none; border-radius: 12px;",
      "  background: var(--dsw-alias-bg-layer-2, rgba(255,255,255,0.04));",
      "  border: 0.5px solid var(--dsw-alias-border-l3, rgba(255,255,255,0.07));",
      "  display: flex; align-items: center; justify-content: center; color: #4D6BFE;",
      "}",
      ".ds-pm-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px; }",
      ".ds-pm-title-row { display: flex; align-items: center; gap: 7px; flex-wrap: wrap; }",
      ".ds-pm-name { font-size: 14px; font-weight: 500; color: var(--dsw-alias-label-primary, #f5f6fa); line-height: 1.3; }",
      ".ds-pm-tag {",
      "  font-size: 11px; font-weight: 500; line-height: 1.4; padding: 1px 6px; border-radius: 5px;",
      "  background: rgba(77, 107, 254, 0.16); color: #6e88ff;",
      "}",
      ".ds-pm-desc {",
      "  font-size: 12.5px; line-height: 1.42; color: var(--dsw-alias-label-secondary, rgba(160,160,175,0.85));",
      "  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; white-space: normal;",
      "}",
      ".ds-pm-end { flex: none; display: flex; align-items: center; gap: 8px; }",
      "@media (max-width: 520px) {",
      "  .ds-pm-row { align-items: flex-start; flex-wrap: wrap; gap: 10px; padding: 10px 8px; }",
      "  .ds-pm-icon { width: 40px; height: 40px; border-radius: 10px; }",
      "  .ds-pm-main { min-width: calc(100% - 54px); }",
      "  .ds-pm-end { width: 100%; justify-content: flex-end; padding-left: 50px; }",
      "}",
      ".ds-agy-btn {",
      "  height: 28px; padding: 0 12px; border-radius: 8px; font-size: 12.5px; font-weight: 500; cursor: pointer;",
      "  border: 0.5px solid var(--dsw-alias-border-l2, rgba(160,160,180,0.25));",
      "  background: var(--dsw-alias-bg-layer-2, rgba(255,255,255,0.04));",
      "  color: var(--dsw-alias-label-primary, #f5f6fa); display: inline-flex; align-items: center; gap: 5px;",
      "}",
      ".ds-agy-btn.primary { background: #4D6BFE; border-color: #4D6BFE; color: #ffffff; }",
      ".ds-agy-btn.ghost-danger { padding: 0 8px; border-color: rgba(239,68,68,0.32); color: #ef4444; background: transparent; }",
      ".ds-agy-btn:disabled { opacity: 0.5; cursor: default; }",
      ".ds-pm-switch {",
      "  position: relative; width: 36px; height: 20px; border-radius: 999px; border: none; padding: 0; cursor: pointer;",
      "  background: rgba(140,140,155,0.35); transition: background 0.18s ease; flex: none;",
      "}",
      ".ds-pm-switch[data-checked='1'] { background: #4D6BFE; }",
      ".ds-pm-switch::after {",
      "  content: ''; position: absolute; top: 2px; left: 2px; width: 16px; height: 16px; border-radius: 50%;",
      "  background: #ffffff; transition: transform 0.18s ease; box-shadow: 0 1px 2px rgba(0,0,0,0.28);",
      "}",
      ".ds-pm-switch[data-checked='1']::after { transform: translateX(16px); }",
      ".ds-pm-switch:disabled { opacity: 0.45; cursor: default; }",
      "#ds-android-toast {",
      "  position: fixed; bottom: 68px; left: 50%; transform: translateX(-50%) translateY(14px);",
      "  background: #1e1f24; color: #f5f6fa; border: 1px solid rgba(77,107,254,0.55);",
      "  padding: 8px 14px; border-radius: 999px; font-size: 12.5px; font-weight: 600;",
      "  opacity: 0; pointer-events: none; transition: all 0.2s ease; z-index: 99999;",
      "  max-width: 88vw; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;",
      "}",
      "#ds-android-toast.visible { opacity: 1; transform: translateX(-50%) translateY(0); }"
    ].join("\n");
    (document.head || document.documentElement).appendChild(st);
  }

  function updateVisualViewport() {
    if (!window.visualViewport) return;
    var h = Math.round(window.visualViewport.height);
    if (h > 120) {
      document.documentElement.style.setProperty("--ds-vvh", h + "px");
    }
  }
  if (window.visualViewport) {
    window.visualViewport.addEventListener("resize", updateVisualViewport);
    updateVisualViewport();
  }

  // 2. Dismiss native splash as soon as DeepSeek #root mounts
  var uiSignaled = false;
  function signalReady() {
    if (uiSignaled) return;
    var host = getHost();
    if (!host) return;
    uiSignaled = true;
    window.__DS_ANDROID_BOOTED__ = true;
    try {
      if (typeof host.onUiReady === "function") {
        host.onUiReady();
      }
    } catch (_) {}
  }

  function checkRootMounted() {
    var root = document.getElementById("root");
    if (root && root.childNodes && root.childNodes.length > 0) {
      requestAnimationFrame(function () {
        requestAnimationFrame(signalReady);
      });
      return true;
    }
    return false;
  }

  // 3. Bi-directional theme sync
  var lastVisual = "";
  var lastSource = "";
  function syncTheme() {
    var host = getHost();
    if (!host || !document.body) return;
    try {
      var isDark = document.body.hasAttribute("data-ds-dark-theme");
      var visual = isDark ? "dark" : "light";
      if (visual !== lastVisual && typeof host.onVisualThemeChanged === "function") {
        lastVisual = visual;
        host.onVisualThemeChanged(visual);
      }
      var src = (document.documentElement && document.documentElement.getAttribute("data-ds-theme-source")) || "system";
      if (src !== lastSource && typeof host.setTheme === "function") {
        lastSource = src;
        host.setTheme(src);
      }
    } catch (_) {}
  }

  // 4. Native Android Folder Picker button inside DeepSeek's DirectoryBrowser modal
  function findDirectoryBrowserFooter() {
    var footer = document.querySelector(".ZuhsRW_footerBar");
    if (footer) return footer;
    var dialogs = document.querySelectorAll("[role='dialog']");
    for (var i = 0; i < dialogs.length; i++) {
      var dlg = dialogs[i];
      if (dlg.querySelector(".ZuhsRW_content, [data-directory-browser]")) {
        var f = dlg.querySelector("footer") || dlg.lastElementChild;
        if (f) return f;
      }
    }
    return null;
  }

  function enhanceDirectoryBrowserModal() {
    var host = getHost();
    if (!host || !BRIDGE_TOKEN || typeof host.pickProjectDirectoryAuth !== "function") return;
    var footer = findDirectoryBrowserFooter();
    if (!footer || footer.querySelector("[data-ds-android-picker='1']")) return;

    var btn = document.createElement("button");
    btn.type = "button";
    btn.setAttribute("data-ds-android-picker", "1");
    btn.className = "ds-agy-btn primary";
    btn.textContent = isSpanish() ? "Selector Android" : "Android Picker";
    btn.addEventListener("click", function () {
      var cbId = registerCb("ds_dir_", 120000, function (pickedPath) {
        if (typeof pickedPath !== "string" || !pickedPath) return;
        applyPickedPathToModal(pickedPath, 0);
      });
      try {
        host.pickProjectDirectoryAuth(BRIDGE_TOKEN, cbId);
      } catch (_) {
        settleCb(cbId, "");
      }
    });
    footer.insertBefore(btn, footer.firstChild);
  }

  function applyPickedPathToModal(pickedPath, attempt) {
    try {
      var input = document.querySelector(".ZuhsRW_pathInput")
        || document.querySelector("[role='dialog'] input[type='text']");
      if (!input && attempt === 0) {
        var editBtn = document.querySelector(".ZuhsRW_crumbEditZone");
        if (editBtn) editBtn.click();
      }
      if (!input && attempt < 8) {
        setTimeout(function () {
          applyPickedPathToModal(pickedPath, attempt + 1);
        }, 45);
        return;
      }
      if (!input) {
        showToast(pickedPath);
        return;
      }
      var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value");
      if (setter && setter.set) {
        setter.set.call(input, pickedPath);
      } else {
        input.value = pickedPath;
      }
      input.dispatchEvent(new Event("input", { bubbles: true }));
      input.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", code: "Enter", keyCode: 13, which: 13, bubbles: true }));
      showToast((isSpanish() ? "Ruta cargada: " : "Path loaded: ") + pickedPath);
    } catch (_) {}
  }

  // 5. Native Android ARM64 Plugins inside the dedicated "Complementos" page ([data-plugin-panel])
  var busyPluginMap = Object.create(null);
  var cachedCatalog = null;

  var PLUGIN_SVG = {
    "craft-anything": '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#6e88ff" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg>',
    "agy-video-gen": '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#a855f7" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><polygon points="23 7 16 12 23 17 23 7"/><rect x="1" y="5" width="15" height="14" rx="2.5"/></svg>',
    "agy-video-studio": '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#ec4899" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="3" width="20" height="18" rx="2.5"/><line x1="7" y1="3" x2="7" y2="21"/><line x1="17" y1="3" x2="17" y2="21"/><line x1="2" y1="12" x2="22" y2="12"/></svg>',
    "game-studio": '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#22c55e" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><line x1="6" y1="12" x2="10" y2="12"/><line x1="8" y1="10" x2="8" y2="14"/><line x1="15" y1="13" x2="15.01" y2="13"/><line x1="18" y1="11" x2="18.01" y2="11"/><rect x="2" y="6" width="20" height="12" rx="4"/></svg>',
    "unity-android": '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#38bdf8" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/><polyline points="3.27 6.96 12 12.01 20.73 6.96"/><line x1="12" y1="22.08" x2="12" y2="12"/></svg>',
    "github": '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#e2e8f0" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="18" cy="18" r="3"/><circle cx="6" cy="6" r="3"/><path d="M13 6h3a2 2 0 0 1 2 2v7"/><line x1="6" y1="9" x2="6" y2="21"/></svg>',
    "agy-adb-bridge": '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#10b981" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/></svg>',
    "chrome-devtools-plugin": '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#f59e0b" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><circle cx="12" cy="12" r="4"/><line x1="21.17" y1="8" x2="12" y2="8"/></svg>',
    "android-cli-plugin": '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#34d399" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="6" y="4" width="12" height="16" rx="2"/><line x1="10" y1="17" x2="14" y2="17"/></svg>',
    "science": '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#60a5fa" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M9 3h6"/><path d="M10 3v6l-5 8a2 2 0 0 0 1.7 3h10.6a2 2 0 0 0 1.7-3l-5-8V3"/></svg>'
  };

  function loadCatalog() {
    if (cachedCatalog) return cachedCatalog;
    var host = getHost();
    try {
      if (host && typeof host.getPluginsCatalogJson === "function") {
        var parsed = JSON.parse(host.getPluginsCatalogJson() || "{}");
        if (Array.isArray(parsed.plugins)) {
          cachedCatalog = parsed.plugins;
          return cachedCatalog;
        }
      }
    } catch (_) {}
    return [];
  }

  function loadInstalledMap() {
    var host = getHost();
    try {
      if (host && BRIDGE_TOKEN && typeof host.listAndroidPluginsAuth === "function") {
        return JSON.parse(host.listAndroidPluginsAuth(BRIDGE_TOKEN) || "{}") || {};
      }
    } catch (_) {}
    return {};
  }

  function renderPluginsHubIfMounted() {
    var host = getHost();
    if (!host) return;

    var anchorGroup = document.querySelector("[data-plugin-panel] [data-plugin-group='extensions']")
      || document.querySelector("[data-plugin-panel] [data-plugin-group='basic']");
    if (!anchorGroup || !anchorGroup.parentElement) return;

    var hostList = anchorGroup.parentElement;
    var container = hostList.querySelector("#ds-agy-plugins-hub");
    if (!container) {
      container = document.createElement("section");
      container.id = "ds-agy-plugins-hub";
      if (anchorGroup.nextSibling) {
        hostList.insertBefore(container, anchorGroup.nextSibling);
      } else {
        hostList.appendChild(container);
      }
    }

    var es = isSpanish();
    var catalog = loadCatalog();
    var installed = loadInstalledMap();

    container.innerHTML = "";

    var head = document.createElement("div");
    head.className = "ds-pm-head";

    var heading = document.createElement("h3");
    heading.className = "ds-pm-title";
    heading.textContent = es ? "Complementos nativos Android" : "Android Native Plugins";
    head.appendChild(heading);

    if (latestUpdateState && latestUpdateState.type === "ready") {
      var otaBtn = document.createElement("button");
      otaBtn.type = "button";
      otaBtn.className = "ds-agy-btn primary";
      otaBtn.textContent = es
        ? "Actualizar" + (latestUpdateState.version ? " v" + latestUpdateState.version : " app")
        : "Update" + (latestUpdateState.version ? " v" + latestUpdateState.version : " app");
      otaBtn.addEventListener("click", function () {
        try { getHost().applyAppUpdateAuth(BRIDGE_TOKEN); } catch (_) {}
      });
      head.appendChild(otaBtn);
    } else if (latestUpdateState && latestUpdateState.type === "downloading") {
      var dlBtn = document.createElement("button");
      dlBtn.type = "button";
      dlBtn.className = "ds-agy-btn";
      dlBtn.disabled = true;
      var pct = typeof latestUpdateState.progress === "number" && latestUpdateState.progress >= 0
        ? " " + latestUpdateState.progress + "%"
        : "…";
      dlBtn.textContent = (es ? "Descargando" : "Downloading") + pct;
      head.appendChild(dlBtn);
    } else {
      var chkBtn = document.createElement("button");
      chkBtn.type = "button";
      chkBtn.className = "ds-agy-btn";
      chkBtn.disabled = !!(latestUpdateState && latestUpdateState.type === "checking for updates");
      chkBtn.textContent = es ? "Buscar actualización" : "Check updates";
      chkBtn.addEventListener("click", function () {
        try { getHost().checkForAppUpdates(true); } catch (_) {}
      });
      head.appendChild(chkBtn);
    }

    container.appendChild(head);

    var ul = document.createElement("ul");
    ul.className = "ds-pm-list";

    catalog.forEach(function (item) {
      var st = installed[item.name] || null;
      var isInst = !!(st && st.installed);
      var isEn = !!(st && st.enabled);
      var isBusy = !!busyPluginMap[item.name];

      var li = document.createElement("li");
      li.className = "ds-pm-row";

      var iconWrap = document.createElement("span");
      iconWrap.className = "ds-pm-icon";
      iconWrap.innerHTML = PLUGIN_SVG[item.name] || PLUGIN_SVG["craft-anything"];
      li.appendChild(iconWrap);

      var main = document.createElement("div");
      main.className = "ds-pm-main";

      var titleRow = document.createElement("div");
      titleRow.className = "ds-pm-title-row";

      var nameSpan = document.createElement("span");
      nameSpan.className = "ds-pm-name";
      nameSpan.textContent = item.displayName || item.name;
      titleRow.appendChild(nameSpan);

      var tag = document.createElement("span");
      tag.className = "ds-pm-tag";
      tag.textContent = "ARM64 · v" + (item.version || "1.0.0");
      titleRow.appendChild(tag);

      main.appendChild(titleRow);

      var desc = document.createElement("span");
      desc.className = "ds-pm-desc";
      var descText = (es && item.descriptionEs) ? item.descriptionEs : (item.description || "");
      desc.textContent = descText;
      desc.title = descText;
      main.appendChild(desc);

      li.appendChild(main);

      var end = document.createElement("div");
      end.className = "ds-pm-end";

      if (!isInst) {
        var instBtn = document.createElement("button");
        instBtn.type = "button";
        instBtn.className = "ds-agy-btn";
        instBtn.disabled = isBusy;
        instBtn.textContent = isBusy ? (es ? "Instalando…" : "Installing…") : (es ? "Instalar" : "Install");
        instBtn.addEventListener("click", function () {
          busyPluginMap[item.name] = true;
          renderPluginsHubIfMounted();
          var cbId = registerCb("ds_plg_", 180000, function (res) {
            delete busyPluginMap[item.name];
            if (res && res.ok) {
              showToast((item.displayName || item.name) + (es ? " instalado" : " installed"));
            } else {
              showToast((es ? "Error: " : "Failed: ") + ((res && res.error) || "unknown"));
            }
            renderPluginsHubIfMounted();
          });
          try {
            getHost().installMarketplacePluginAsync(
              BRIDGE_TOKEN, cbId, item.name, item.archiveUrl || "", item.name, "android-arm64", item.version || "1.0.0"
            );
          } catch (err) {
            settleCb(cbId, { ok: false, error: String(err) });
          }
        });
        end.appendChild(instBtn);
      } else {
        var delBtn = document.createElement("button");
        delBtn.type = "button";
        delBtn.className = "ds-agy-btn ghost-danger";
        delBtn.disabled = isBusy;
        delBtn.title = es ? "Desinstalar" : "Uninstall";
        delBtn.textContent = es ? "Quitar" : "Remove";
        delBtn.addEventListener("click", function () {
          busyPluginMap[item.name] = true;
          renderPluginsHubIfMounted();
          var cbId = registerCb("ds_del_", 60000, function (res) {
            delete busyPluginMap[item.name];
            if (!res || res.error !== "timeout") {
              showToast((item.displayName || item.name) + (es ? " quitado" : " removed"));
            }
            renderPluginsHubIfMounted();
          });
          try {
            getHost().uninstallMarketplacePluginAsync(BRIDGE_TOKEN, cbId, item.name);
          } catch (err) {
            settleCb(cbId, { ok: false, error: String(err) });
          }
        });
        end.appendChild(delBtn);

        var sw = document.createElement("button");
        sw.type = "button";
        sw.setAttribute("role", "switch");
        sw.setAttribute("aria-checked", isEn ? "true" : "false");
        sw.setAttribute("data-checked", isEn ? "1" : "0");
        sw.className = "ds-pm-switch";
        sw.disabled = isBusy;
        sw.addEventListener("click", function () {
          var currentEn = sw.getAttribute("data-checked") === "1";
          busyPluginMap[item.name] = true;
          renderPluginsHubIfMounted();
          var cbId = registerCb("ds_tog_", 45000, function () {
            delete busyPluginMap[item.name];
            renderPluginsHubIfMounted();
          });
          try {
            getHost().toggleAndroidPluginAsync(BRIDGE_TOKEN, cbId, item.name, !currentEn);
          } catch (err) {
            settleCb(cbId, { ok: false, error: String(err) });
          }
        });
        end.appendChild(sw);
      }

      li.appendChild(end);
      ul.appendChild(li);
    });

    container.appendChild(ul);
  }

  // 6. Lifecycle & zero-churn DOM observation (disconnects subtree observer after #root mounts)
  var domSyncTimer = null;
  var bootMountObs = null;

  function scheduleDomSync() {
    if (domSyncTimer || document.visibilityState === "hidden") return;
    domSyncTimer = setTimeout(function () {
      domSyncTimer = null;
      if (!uiSignaled && checkRootMounted() && bootMountObs) {
        try { bootMountObs.disconnect(); } catch (_) {}
        bootMountObs = null;
      }
      syncTheme();
      enhanceDirectoryBrowserModal();
      if (document.querySelector("[data-plugin-panel]") && !document.getElementById("ds-agy-plugins-hub")) {
        renderPluginsHubIfMounted();
      }
    }, 160);
  }

  function boot() {
    injectMobileCss();
    updateVisualViewport();
    if (!checkRootMounted()) {
      try {
        bootMountObs = new MutationObserver(function () {
          if (checkRootMounted() && bootMountObs) {
            try { bootMountObs.disconnect(); } catch (_) {}
            bootMountObs = null;
          }
        });
        bootMountObs.observe(document.documentElement, { childList: true, subtree: true });
      } catch (_) {}
    }
    syncTheme();

    try {
      var themeObs = new MutationObserver(syncTheme);
      themeObs.observe(document.documentElement, {
        attributes: true,
        attributeFilter: ["data-ds-theme-source", "data-ds-dark-theme"]
      });
      if (document.body) {
        themeObs.observe(document.body, {
          attributes: true,
          attributeFilter: ["data-ds-dark-theme"]
        });
        var portalObs = new MutationObserver(scheduleDomSync);
        portalObs.observe(document.body, { childList: true, subtree: false });
      }
    } catch (_) {}

    document.addEventListener("click", scheduleDomSync, true);
    window.addEventListener("popstate", scheduleDomSync);
    window.addEventListener("hashchange", scheduleDomSync);

    setTimeout(function () {
      if (bootMountObs) {
        try { bootMountObs.disconnect(); } catch (_) {}
        bootMountObs = null;
      }
      signalReady();
    }, 4500);
  }

  var lastResumeTs = 0;
  function onHostResume() {
    var now = Date.now();
    if (now - lastResumeTs < 250) return;
    lastResumeTs = now;
    try {
      if (navigator.onLine !== false) {
        window.dispatchEvent(new Event("online"));
      }
      window.dispatchEvent(new Event("focus"));
      syncTheme();
      scheduleDomSync();
    } catch (_) {}
  }

  window.addEventListener("ds:host-resume", onHostResume);
  window.addEventListener("ds:host-theme", syncTheme);
  document.addEventListener("visibilitychange", function () {
    if (document.visibilityState === "visible") onHostResume();
  });




  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
})();
