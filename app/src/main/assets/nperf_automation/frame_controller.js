(() => {
  "use strict";
  if (window.__speedtestNlNperfFrameController) return;
  window.__speedtestNlNperfFrameController = true;

  const APP = "speedtestnl";
  const BRIDGE = "__speedtestNlNperfBridgeV2";
  const isTop = window.top === window;
  const beganAt = Date.now();
  let lastState = "";
  let lastStateAt = 0;
  let lastTap = "";
  let lastTapAt = 0;
  let attempts = 0;
  let consentAttempts = 0;
  let done = false;
  let lastMetrics = "";
  let childActiveAt = 0;

  const norm = value => String(value || "")
    .normalize("NFD").replace(/[\u0300-\u036f]/g, "")
    .replace(/\s+/g, " ").trim().toLowerCase();

  const rootList = () => {
    const result = [document];
    const queue = [document];
    const seen = new Set(queue);
    while (queue.length) {
      const root = queue.shift();
      let nodes = [];
      try { nodes = root.querySelectorAll("*"); } catch (_) {}
      for (const node of nodes) {
        if (node.shadowRoot && !seen.has(node.shadowRoot)) {
          seen.add(node.shadowRoot);
          result.push(node.shadowRoot);
          queue.push(node.shadowRoot);
        }
      }
    }
    return result;
  };

  const visible = node => {
    if (!node || !node.getBoundingClientRect) return false;
    const r = node.getBoundingClientRect();
    let s;
    try { s = getComputedStyle(node); } catch (_) { return false; }
    return r.width > 8 && r.height > 8 && r.bottom > 0 && r.right > 0 &&
      r.top < innerHeight && r.left < innerWidth && s.display !== "none" &&
      s.visibility !== "hidden" && Number(s.opacity || 1) > 0.01 &&
      s.pointerEvents !== "none";
  };

  const textOf = node => norm(node && (
    node.innerText || node.textContent || node.value ||
    node.getAttribute?.("aria-label") || node.getAttribute?.("title") || ""
  ));

  const nativeSend = payload => {
    try { browser.runtime.sendNativeMessage(APP, payload).catch(() => {}); }
    catch (_) {}
  };

  const payload = message => Object.assign({
    href: location.href,
    frameHref: location.href,
    title: document.title,
    viewportWidth: innerWidth || 1,
    viewportHeight: innerHeight || 1,
    frameDepth: 0,
    timestamp: Date.now()
  }, message);

  const send = message => {
    const data = payload(message);
    if (isTop) return nativeSend(data);
    try { parent.postMessage({ [BRIDGE]: true, data }, "*"); }
    catch (_) { nativeSend(data); }
  };

  const sourceFrame = source => {
    for (const root of rootList()) {
      let frames = [];
      try { frames = root.querySelectorAll("iframe,frame"); } catch (_) {}
      for (const frame of frames) {
        try { if (frame.contentWindow === source) return frame; } catch (_) {}
      }
    }
    return null;
  };

  addEventListener("message", event => {
    const bridge = event?.data;
    if (!bridge || bridge[BRIDGE] !== true || !bridge.data) return;
    const data = Object.assign({}, bridge.data);
    const frame = sourceFrame(event.source);
    if (frame && data.type === "tap") {
      const r = frame.getBoundingClientRect();
      const vw = Number(data.viewportWidth) || r.width || 1;
      const vh = Number(data.viewportHeight) || r.height || 1;
      data.x = r.left + Number(data.x || 0) * r.width / vw;
      data.y = r.top + Number(data.y || 0) * r.height / vh;
      data.width = Number(data.width || 0) * r.width / vw;
      data.height = Number(data.height || 0) * r.height / vh;
    }
    data.frameDepth = Number(data.frameDepth || 0) + 1;
    data.viewportWidth = innerWidth || 1;
    data.viewportHeight = innerHeight || 1;
    if (["state", "tap", "metrics", "complete", "error"].includes(data.type)) {
      childActiveAt = Date.now();
    }
    if (isTop) {
      data.frameHref = data.frameHref || data.href;
      data.href = location.href;
      nativeSend(data);
    } else {
      try { parent.postMessage({ [BRIDGE]: true, data }, "*"); }
      catch (_) { nativeSend(data); }
    }
  });

  const state = (name, detail, force = false) => {
    const now = Date.now();
    if (!force && name === lastState && now - lastStateAt < 3000) return;
    lastState = name;
    lastStateAt = now;
    send({ type: "state", state: name, detail });
  };

  const all = selector => {
    const result = [];
    for (const root of rootList()) {
      try { result.push(...root.querySelectorAll(selector)); } catch (_) {}
    }
    return result;
  };

  const firstVisible = selectors => {
    for (const selector of selectors) {
      for (const node of all(selector)) if (visible(node)) return node;
    }
    return null;
  };

  const clickable = node => {
    for (let current = node, depth = 0; current && depth < 6;
         current = current.parentElement, depth += 1) {
      const tag = String(current.tagName || "").toLowerCase();
      const role = norm(current.getAttribute?.("role"));
      let cursor = "";
      try { cursor = getComputedStyle(current).cursor; } catch (_) {}
      if (["button", "a", "input"].includes(tag) || role === "button" ||
          typeof current.onclick === "function" || cursor === "pointer") return current;
    }
    return node;
  };

  const byWords = words => {
    let best = null;
    let bestArea = Infinity;
    const wanted = words.map(norm);
    const selector = "button,a,[role=button],input,div,span,p,text,tspan,g,svg";
    for (const node of all(selector)) {
      if (!visible(node)) continue;
      const label = textOf(node);
      if (!label || label.length > 150 || !wanted.some(w => label === w || label.includes(w))) continue;
      const target = clickable(node);
      if (!visible(target)) continue;
      const r = target.getBoundingClientRect();
      const area = r.width * r.height;
      if (area > 0 && area < 220000 && area < bestArea) {
        best = target;
        bestArea = area;
      }
    }
    return best;
  };

  const findConsent = body => {
    if (!["politica de uso de cookies", "uso de cookies y privacidad",
          "cookie policy", "cookies and privacy"].some(v => body.includes(v))) return null;
    return firstVisible([
      "#didomi-notice-agree-button", "#onetrust-accept-btn-handler",
      "button[id*=accept]", "button[class*=accept]", "button[class*=agree]",
      "[class*=cookie] button"
    ]) || byWords(["ok", "aceptar", "accept", "agree", "continuar"]);
  };

  const findStart = () => firstVisible([
    "#start-test", "#start-button", ".start-test", ".start-button",
    "button[data-testid*=start]", "[data-testid*=start][role=button]",
    "button[class*=start]", "a[class*=start]", "[aria-label*=Iniciar]",
    "[title*=Iniciar]"
  ]) || byWords(["iniciar test", "iniciar prueba", "comenzar test",
                 "start test", "lancer le test"]);

  const findGauge = () => {
    let best = null;
    let score = 0;
    for (const node of all("canvas,svg,[class*=gauge],[id*=gauge],[class*=meter],[id*=meter],[class*=speedtest],[id*=speedtest]")) {
      if (!visible(node)) continue;
      const r = node.getBoundingClientRect();
      if (r.width < 70 || r.height < 70 || r.width > innerWidth * 0.9 || r.height > innerHeight * 0.9) continue;
      const ratio = r.width / Math.max(1, r.height);
      if (ratio < 0.55 || ratio > 1.8) continue;
      const bonus = textOf(node).includes("iniciar test") ? 1000000 : 0;
      const value = bonus + r.width * r.height;
      if (value > score) { best = node; score = value; }
    }
    return best;
  };

  const activate = (candidate, role) => {
    const node = clickable(candidate);
    if (!visible(node)) return false;
    try { node.scrollIntoView({ block: "center", inline: "center" }); } catch (_) {}
    const r = node.getBoundingClientRect();
    const x = r.left + r.width / 2;
    const y = r.top + r.height / 2;
    const key = `${role}:${Math.round(x)}:${Math.round(y)}`;
    if (key === lastTap && Date.now() - lastTapAt < 2800) return false;
    lastTap = key;
    lastTapAt = Date.now();
    try { node.focus({ preventScroll: true }); } catch (_) {}
    const init = { bubbles: true, cancelable: true, composed: true,
      clientX: x, clientY: y, button: 0, buttons: 1 };
    for (const name of ["pointerdown", "mousedown", "pointerup", "mouseup", "click"]) {
      try {
        const Type = name.startsWith("pointer") && typeof PointerEvent !== "undefined" ? PointerEvent : MouseEvent;
        node.dispatchEvent(new Type(name, init));
      } catch (_) {}
    }
    try { node.click(); } catch (_) {}
    send({ type: "tap", role, x, y, width: r.width, height: r.height,
      target: String(node.tagName || "").toLowerCase(), label: textOf(node).slice(0, 80) });
    return true;
  };

  const number = value => {
    const match = String(value || "").replace(/,/g, ".").match(/\b(\d+(?:\.\d+)?)\b/);
    return match ? match[1] : "";
  };

  const metric = (selectors, labels) => {
    for (const node of all(selectors.join(","))) {
      if (visible(node)) {
        const value = number(node.textContent || node.value || "");
        if (value) return value;
      }
    }
    const lines = (document.body?.innerText || "").split(/\n+/).map(v => v.trim()).filter(Boolean);
    for (let i = 0; i < lines.length; i += 1) {
      const line = norm(lines[i]);
      if (!labels.some(label => line === label || line.startsWith(label + " "))) continue;
      for (let j = i; j < Math.min(lines.length, i + 6); j += 1) {
        const value = number(lines[j]);
        if (value) return value;
      }
    }
    return "";
  };

  const metrics = () => ({
    download: metric([".download-value", "#download-value", "[data-download]", "[class*=download][class*=value]"], ["download", "descarga", "velocidad de descarga"]),
    upload: metric([".upload-value", "#upload-value", "[data-upload]", "[class*=upload][class*=value]"], ["upload", "subida", "velocidad de subida"]),
    latency: metric([".latency-value", "#latency-value", "#ping-value", "[data-latency]", "[class*=ping][class*=value]"], ["latency", "latencia", "ping"]),
    jitter: metric([".jitter-value", "#jitter-value", "[data-jitter]", "[class*=jitter][class*=value]"], ["jitter"]),
    resultUrl: location.href,
    resultId: (location.href.match(/\/r\/(\d+)/i) || location.href.match(/\/result\/?([^/?#]+)/i) || [])[1] || ""
  });

  const tick = () => {
    if (done || !document.body) return;
    const body = norm(document.body.innerText || "");
    const fatal = ["no fue posible inicializar", "no se pudo inicializar", "error al inicializar",
      "unable to initialize", "could not initialize", "initialization failed"].find(v => body.includes(v));
    if (fatal) return send({ type: "error", code: "ENGINE_INITIALIZATION", detail: fatal });

    const consent = findConsent(body);
    if (consent && consentAttempts < 6) {
      consentAttempts += 1;
      state("consent", `Aceptando cookies nPerf (${consentAttempts}/6)`, true);
      activate(consent, "consent");
      return;
    }

    const values = metrics();
    const hasMetric = Boolean(values.download || values.upload || values.latency || values.jitter);
    if (hasMetric) {
      const signature = JSON.stringify(values);
      if (signature !== lastMetrics) {
        lastMetrics = signature;
        send(Object.assign({ type: "metrics" }, values));
      }
    }

    const completeText = ["haz click aqui para probar de nuevo", "haz clic aqui para probar de nuevo",
      "restart test", "reiniciar test", "reinitier le test"].some(v => body.includes(v));
    if ((completeText || /\/r\/|\/result/i.test(location.href)) && values.download && values.upload) {
      done = true;
      state("complete", "Resultado nPerf detectado", true);
      send(Object.assign({ type: "complete" }, values));
      return;
    }

    const start = findStart();
    const gauge = start ? null : findGauge();
    if ((start || gauge) && !hasMetric) {
      const target = start || gauge;
      const role = start ? "start" : "gauge";
      if (attempts >= 12) return send({ type: "error", code: "START_NOT_RESPONDING",
        detail: "El control Iniciar test siguió visible después de 12 activaciones" });
      if (activate(target, role)) {
        attempts += 1;
        state("ready", `Activando Iniciar test dentro del medidor (${attempts}/12, ${role})`, true);
      }
      return;
    }

    if (hasMetric) return state("running", "nPerf midiendo conexión");

    if ((body.includes("inicializando") || body.includes("initializing")) &&
        (!isTop || Date.now() - childActiveAt > 5000)) {
      state("initializing", "Inicializando motor y servidor nPerf");
      return;
    }

    if (Date.now() - beganAt > 90000) {
      return send({ type: "error", code: "NO_OPERATIONAL_CONTROL",
        detail: "nPerf no presentó un control operativo dentro de GeckoView",
        frameCount: document.querySelectorAll("iframe,frame").length,
        excerpt: body.slice(0, 400) });
    }

    if (!isTop || Date.now() - childActiveAt > 5000) {
      const count = document.querySelectorAll("iframe,frame").length;
      state("waiting", count
        ? `Buscando Iniciar test dentro de ${count} marco(s) nPerf`
        : "Esperando que nPerf prepare el medidor");
    }
  };

  send({ type: "extension_ready", detail: isTop
    ? "Automatización nPerf v2 activa en página principal"
    : "Automatización nPerf v2 activa dentro del medidor" });
  tick();
  setInterval(tick, 1000);
})();
