(() => {
  "use strict";

  if (window.__speedtestNlNperfAutomationInstalled) return;
  window.__speedtestNlNperfAutomationInstalled = true;

  const NATIVE_APP = "speedtestnl";
  const startedAt = Date.now();
  let lastState = "";
  let lastStateAt = 0;
  let lastTapKey = "";
  let lastTapAt = 0;
  let startAttempts = 0;
  let consentAttempts = 0;
  let completed = false;
  let lastMetricSignature = "";
  let consecutiveNoControl = 0;

  const normalize = value => String(value || "")
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/\s+/g, " ")
    .trim()
    .toLowerCase();

  const visible = element => {
    if (!element || !element.getBoundingClientRect) return false;
    const rect = element.getBoundingClientRect();
    const style = getComputedStyle(element);
    return rect.width > 10 && rect.height > 10 && rect.bottom > 0 && rect.right > 0 &&
      rect.top < window.innerHeight && rect.left < window.innerWidth &&
      style.display !== "none" && style.visibility !== "hidden" &&
      Number.parseFloat(style.opacity || "1") > 0;
  };

  const label = element => normalize(
    element && (
      element.innerText || element.textContent || element.value ||
      element.getAttribute("aria-label") || element.getAttribute("title") || ""
    )
  );

  const send = message => {
    const payload = Object.assign({
      href: location.href,
      title: document.title,
      timestamp: Date.now(),
      viewportWidth: window.innerWidth || 1,
      viewportHeight: window.innerHeight || 1
    }, message);
    try {
      browser.runtime.sendNativeMessage(NATIVE_APP, payload).catch(() => {});
    } catch (_) {}
  };

  const setState = (state, detail, force = false) => {
    const now = Date.now();
    if (!force && state === lastState && now - lastStateAt < 3000) return;
    lastState = state;
    lastStateAt = now;
    send({ type: "state", state, detail: detail || "" });
  };

  const center = element => {
    const rect = element.getBoundingClientRect();
    return {
      x: rect.left + rect.width / 2,
      y: rect.top + rect.height / 2,
      width: rect.width,
      height: rect.height
    };
  };

  const activate = (element, role) => {
    if (!element || !visible(element)) return false;
    const point = center(element);
    const key = `${role}:${Math.round(point.x)}:${Math.round(point.y)}`;
    const now = Date.now();
    if (key === lastTapKey && now - lastTapAt < 4500) return false;

    lastTapKey = key;
    lastTapAt = now;
    try {
      element.scrollIntoView({ block: "center", inline: "center", behavior: "instant" });
      element.setAttribute("tabindex", element.getAttribute("tabindex") || "0");
      element.focus({ preventScroll: true });
      element.click();
    } catch (_) {}

    send({
      type: "tap",
      role,
      x: point.x,
      y: point.y,
      width: point.width,
      height: point.height
    });
    return true;
  };

  const queryVisible = selectors => {
    for (const selector of selectors) {
      let nodes = [];
      try { nodes = document.querySelectorAll(selector); } catch (_) {}
      for (const node of nodes) {
        if (visible(node)) return node;
      }
    }
    return null;
  };

  const findByWords = (words, requireSmall = true) => {
    const nodes = document.querySelectorAll(
      "button,a,[role='button'],input[type='button'],input[type='submit'],div,span"
    );
    let best = null;
    let bestArea = Number.POSITIVE_INFINITY;
    for (const node of nodes) {
      if (!visible(node)) continue;
      const text = label(node);
      if (!text || text.length > 100) continue;
      if (!words.some(word => text === word || text.includes(word))) continue;
      const rect = node.getBoundingClientRect();
      const area = rect.width * rect.height;
      if (requireSmall && area > 180000) continue;
      if (area > 0 && area < bestArea) {
        best = node;
        bestArea = area;
      }
    }
    return best;
  };

  const findConsent = body => {
    const bannerPresent = [
      "politica de uso de cookies",
      "uso de cookies y privacidad",
      "cookie policy",
      "cookies and privacy"
    ].some(value => body.includes(value));
    if (!bannerPresent) return null;

    return queryVisible([
      "#didomi-notice-agree-button",
      "#onetrust-accept-btn-handler",
      "button[id*='accept']",
      "button[class*='accept']",
      "button[class*='agree']",
      "[class*='cookie'] button"
    ]) || findByWords([
      "ok", "aceptar", "accept", "agree", "continuar", "got it"
    ]);
  };

  const findStart = () => queryVisible([
    "#start-test",
    "#start-button",
    ".start-test",
    ".start-button",
    "button[data-testid*='start']",
    "[data-testid*='start'][role='button']",
    "button[class*='start']",
    "a[class*='start']"
  ]) || findByWords([
    "iniciar test",
    "iniciar prueba",
    "comenzar test",
    "start test",
    "lancer le test"
  ]);

  const findCanvas = () => {
    let best = null;
    let bestArea = 0;
    for (const canvas of document.querySelectorAll("canvas")) {
      if (!visible(canvas)) continue;
      const rect = canvas.getBoundingClientRect();
      const area = rect.width * rect.height;
      if (rect.width >= 140 && rect.height >= 140 && area > bestArea) {
        best = canvas;
        bestArea = area;
      }
    }
    return best;
  };

  const extractNumber = value => {
    const match = String(value || "").replace(/,/g, ".")
      .match(/(?:^|\s)(\d+(?:\.\d+)?)(?:\s|$|mb|ms)/i);
    return match ? match[1] : "";
  };

  const textLines = () => (document.body ? document.body.innerText : "")
    .split(/\n+/)
    .map(line => line.trim())
    .filter(Boolean);

  const metricBySelectors = selectors => {
    for (const selector of selectors) {
      let nodes = [];
      try { nodes = document.querySelectorAll(selector); } catch (_) {}
      for (const node of nodes) {
        if (!visible(node)) continue;
        const value = extractNumber(node.textContent || node.value || "");
        if (value) return value;
      }
    }
    return "";
  };

  const metricByLabels = labels => {
    const lines = textLines();
    const normalizedLabels = labels.map(normalize);
    for (let index = 0; index < lines.length; index++) {
      const current = normalize(lines[index]);
      if (!normalizedLabels.some(item => current === item || current.startsWith(item + " "))) {
        continue;
      }
      const inline = extractNumber(lines[index]);
      if (inline) return inline;
      for (let next = index + 1; next < Math.min(lines.length, index + 7); next++) {
        const value = extractNumber(lines[next]);
        if (value) return value;
      }
    }
    return "";
  };

  const nearbyText = labels => {
    const lines = textLines();
    const normalizedLabels = labels.map(normalize);
    for (let index = 0; index < lines.length; index++) {
      const current = normalize(lines[index]);
      if (!normalizedLabels.includes(current)) continue;
      for (let next = index + 1; next < Math.min(lines.length, index + 5); next++) {
        const candidate = lines[next].trim();
        if (candidate && !/^(mb\/s|mbps|ms)$/i.test(candidate)) return candidate;
      }
    }
    return "";
  };

  const extractMetrics = () => {
    const download = metricBySelectors([
      ".download-value", "#download-value", "[data-download]",
      "[class*='download'][class*='value']", ".result-download"
    ]) || metricByLabels(["download", "descarga", "velocidad de descarga"]);

    const upload = metricBySelectors([
      ".upload-value", "#upload-value", "[data-upload]",
      "[class*='upload'][class*='value']", ".result-upload"
    ]) || metricByLabels(["upload", "subida", "velocidad de subida"]);

    const latency = metricBySelectors([
      ".latency-value", "#latency-value", "#ping-value", "[data-latency]",
      "[class*='latency'][class*='value']", "[class*='ping'][class*='value']"
    ]) || metricByLabels(["latency", "latencia", "ping"]);

    const jitter = metricBySelectors([
      ".jitter-value", "#jitter-value", "[data-jitter]",
      "[class*='jitter'][class*='value']"
    ]) || metricByLabels(["jitter"]);

    const serverNode = queryVisible([
      "[class*='server-name']", "[data-server]", "[class*='server']"
    ]);
    const operatorNode = queryVisible([
      "[class*='operator']", "[class*='provider']", "[class*='isp']"
    ]);

    return {
      download,
      upload,
      latency,
      jitter,
      server: serverNode ? (serverNode.textContent || "").trim() :
        nearbyText(["server", "servidor"]),
      operator: operatorNode ? (operatorNode.textContent || "").trim() :
        nearbyText(["connection", "conexion", "conexión", "operator", "operador"]),
      resultUrl: location.href,
      resultId: (() => {
        const match = location.href.match(/\/r\/(\d+)(?:-|\/|$)/i) ||
          location.href.match(/\/result\/?([^/?#]+)/i);
        return match ? match[1] : "";
      })()
    };
  };

  const emitMetrics = metrics => {
    const signature = JSON.stringify(metrics);
    if (signature === lastMetricSignature) return;
    lastMetricSignature = signature;
    send(Object.assign({ type: "metrics" }, metrics));
  };

  const run = () => {
    if (completed || !document.body) return;

    const body = normalize(document.body.innerText || "");
    const elapsed = Date.now() - startedAt;

    const fatalMessages = [
      "no fue posible inicializar",
      "no se pudo inicializar",
      "error al inicializar",
      "unable to initialize",
      "could not initialize",
      "initialization failed"
    ];
    const fatal = fatalMessages.find(item => body.includes(item));
    if (fatal) {
      send({ type: "error", code: "ENGINE_INITIALIZATION", detail: fatal });
      return;
    }

    const consent = findConsent(body);
    if (consent && consentAttempts < 5) {
      consentAttempts += 1;
      setState("consent", "Aceptando cookies nPerf", true);
      activate(consent, "consent");
      return;
    }

    const initializing = body.includes("inicializando") || body.includes("initializing");
    const start = findStart();
    const metrics = extractMetrics();
    const hasAnyMetric = Boolean(
      metrics.download || metrics.upload || metrics.latency || metrics.jitter
    );

    if (hasAnyMetric) emitMetrics(metrics);

    const doneText = [
      "haz click aqui para probar de nuevo",
      "haz clic aqui para probar de nuevo",
      "restart test",
      "reiniciar test",
      "reinitier le test"
    ].some(item => body.includes(item));
    const resultUrl = /\/r\/|\/result/i.test(location.href);
    if ((doneText || resultUrl) && metrics.download && metrics.upload) {
      completed = true;
      setState("complete", "Resultado nPerf detectado", true);
      send(Object.assign({ type: "complete" }, metrics));
      return;
    }

    if (initializing && !start && !hasAnyMetric) {
      consecutiveNoControl = 0;
      setState("initializing", "Inicializando motor y servidor nPerf");
      return;
    }

    if (start && !hasAnyMetric) {
      consecutiveNoControl = 0;
      if (startAttempts < 8) {
        startAttempts += 1;
        setState("ready", `Activando Iniciar test (${startAttempts}/8)`, true);
        activate(start, "start");
      } else {
        send({
          type: "error",
          code: "START_NOT_RESPONDING",
          detail: "El botón Iniciar test permaneció visible después de 8 activaciones"
        });
      }
      return;
    }

    if (hasAnyMetric) {
      consecutiveNoControl = 0;
      setState("running", "nPerf midiendo conexión");
      return;
    }

    const canvas = findCanvas();
    if (canvas && startAttempts < 8 && elapsed > 4000) {
      startAttempts += 1;
      setState("canvas", `Activando medidor nPerf (${startAttempts}/8)`, true);
      activate(canvas, "canvas");
      return;
    }

    consecutiveNoControl += 1;
    if (elapsed > 75000 && consecutiveNoControl > 20) {
      send({
        type: "error",
        code: "NO_OPERATIONAL_CONTROL",
        detail: "nPerf no presentó un control operativo dentro de GeckoView",
        excerpt: body.slice(0, 500)
      });
      return;
    }

    setState("waiting", "Esperando que nPerf prepare el medidor");
  };

  send({ type: "extension_ready", detail: "Automatización nPerf cargada" });
  run();
  setInterval(run, 1000);
})();
