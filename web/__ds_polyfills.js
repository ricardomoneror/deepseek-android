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
  if (typeof structuredClone !== "function") {
    window.structuredClone = function (valor) {
      if (valor === undefined) return undefined;
      return JSON.parse(JSON.stringify(valor));
    };
  }

  // Object.hasOwn() — Chrome 93
  if (typeof Object.hasOwn !== "function") {
    Object.hasOwn = function (obj, prop) {
      return Object.prototype.hasOwnProperty.call(obj, prop);
    };
  }
})();

