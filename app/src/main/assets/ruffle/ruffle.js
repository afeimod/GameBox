/**
 * Ruffle loader — loads the Ruffle Flash emulator from CDN.
 *
 * If you want to use a local (offline) build of Ruffle instead, download
 * the self-hosted web build from https://github.com/ruffle-rs/ruffle/releases
 * and replace this file with the bundled ruffle.js (and the .wasm file).
 */
(function () {
    "use strict";

    var CDN_URL = "https://unpkg.com/@ruffle-rs/ruffle";

    function loadRuffle() {
        var script = document.createElement("script");
        script.src = CDN_URL;
        script.async = true;
        script.onerror = function () {
            var el = document.getElementById("loading");
            if (el) {
                el.textContent = "无法加载 Ruffle 引擎，请检查网络连接";
                el.style.color = "#ff6b6b";
            }
            console.error("Failed to load Ruffle from CDN:", CDN_URL);
        };
        document.head.appendChild(script);
    }

    // Auto-load when this script is included
    loadRuffle();
})();
