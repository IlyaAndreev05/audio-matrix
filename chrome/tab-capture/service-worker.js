const HOST_NAME = "io.github.ilyaandreev05.audiomatrix.chrome_tab_capture";
const VERSION = 1;
const IDENTITY_KEY = "audioMatrixBrowserIdentity";
const SESSION_TOKENS_KEY = "audioMatrixTabSessionTokens";
const CAPTURES_KEY = "audioMatrixTabCaptures";
const CAPTURE_KEYS_KEY = "audioMatrixTabCaptureKeys";
const JAVA_BRIDGE_ERROR_KEY = "audioMatrixJavaBridgeError";
const NATIVE_REQUEST_TIMEOUT_MS = 5000;
const PCM_REQUEST_TIMEOUT_MS = 1500;

let nativePort = null;
let nativeError = "";
let javaBridgeError = "";
let bridgeReady = false;
let bridgeReadyPromise = null;
let resolveBridgeReady = null;
let rejectBridgeReady = null;
let identityPromise = null;
let sessionTokensPromise = null;
let sessionTokens = null;
let sessionWriteQueue = Promise.resolve();
let capturesPromise = null;
let captureRecords = null;
let captureWriteQueue = Promise.resolve();
let captureKeysPromise = null;
let captureKeys = null;
let captureKeyWriteQueue = Promise.resolve();
let outgoingQueue = Promise.resolve();
let knownEntries = new Map();
const pendingNativeRequests = new Map();
const stoppingCaptures = new Map();
const tabGenerations = new Map();
let reconnectTimer = null;
let reconnectDelay = 500;

function uuid() {
  return crypto.randomUUID();
}

function validUuid(value) {
  return typeof value === "string" &&
    /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}
function validToken(value) {
  return typeof value === "string" && value.length > 0 && value.length <= 128;
}
function safeTitle(value, limit = 512) {
  if (typeof value !== "string") return "";
  return value
    .replace(/\b[a-z][a-z0-9+.-]*:\/\/[^\s]+|\bwww\.[^\s]+|\b(?:[a-z0-9-]+\.)+[a-z]{2,}(?::\d+)?(?:\/[^\s]*)?/gi, "")
    .replace(/[\u0000-\u001f\u007f]/g, " ")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, limit);
}



function sessionKey(instanceId, profileId, sessionToken) {
  return `${instanceId}\u0000${profileId}\u0000${sessionToken}`;
}

function serialOutgoing(task) {
  const next = outgoingQueue.then(task, task);
  outgoingQueue = next.catch((error) => {
    nativeError = error?.message || "Could not send a browser bridge message.";
  });
  return next;
}

// The Java browser bridge is optional for capture: the native host keeps the tab
// audio flowing and reports the Audio Matrix connection state separately.
function setJavaBridgeError(reason) {
  javaBridgeError = typeof reason === "string" ? reason : "";
  chrome.storage.session.set({ [JAVA_BRIDGE_ERROR_KEY]: javaBridgeError }).catch(() => {});
}

async function getIdentity() {
  if (!identityPromise) {
    identityPromise = (async () => {
      const stored = await chrome.storage.local.get(IDENTITY_KEY);
      const existing = stored[IDENTITY_KEY] || {};
      const identity = {
        instanceId: validUuid(existing.instanceId) ? existing.instanceId : uuid(),
        profileId: validUuid(existing.profileId) ? existing.profileId : uuid()
      };
      if (identity.instanceId !== existing.instanceId || identity.profileId !== existing.profileId)
        await chrome.storage.local.set({ [IDENTITY_KEY]: identity });
      return identity;
    })().catch((error) => {
      identityPromise = null;
      throw error;
    });
  }
  return identityPromise;
}

async function loadSessionTokens() {
  if (!sessionTokensPromise) {
    sessionTokensPromise = chrome.storage.session.get(SESSION_TOKENS_KEY).then((stored) => {
      const value = stored[SESSION_TOKENS_KEY];
      sessionTokens = value && typeof value === "object" && !Array.isArray(value) ? value : {};
      return sessionTokens;
    }).catch((error) => {
      sessionTokensPromise = null;
      throw error;
    });
  }
  return sessionTokensPromise;
}

function persistSessionTokens() {
  const snapshot = { ...sessionTokens };
  sessionWriteQueue = sessionWriteQueue.then(() =>
    chrome.storage.session.set({ [SESSION_TOKENS_KEY]: snapshot }));
  return sessionWriteQueue;
}

async function getSessionToken(tabId) {
  if (!Number.isInteger(tabId) || tabId < 0) throw new Error("The Chrome tab identifier is invalid.");
  const tokens = await loadSessionTokens();
  const key = String(tabId);
  if (!validToken(tokens[key])) {
    tokens[key] = uuid();
    await persistSessionTokens();
  }
  return tokens[key];
}

async function removeSessionToken(tabId) {
  const tokens = await loadSessionTokens();
  delete tokens[String(tabId)];
  await persistSessionTokens();
}
async function loadCaptureKeys() {
  if (!captureKeysPromise) {
    captureKeysPromise = chrome.storage.session.get(CAPTURE_KEYS_KEY).then((stored) => {
      const value = stored[CAPTURE_KEYS_KEY];
      captureKeys = value && typeof value === "object" && !Array.isArray(value) ? value : {};
      return captureKeys;
    }).catch((error) => {
      captureKeysPromise = null;
      throw error;
    });
  }
  return captureKeysPromise;
}

function persistCaptureKeys() {
  const snapshot = { ...captureKeys };
  captureKeyWriteQueue = captureKeyWriteQueue.then(() =>
    chrome.storage.session.set({ [CAPTURE_KEYS_KEY]: snapshot }));
  return captureKeyWriteQueue;
}

async function getCaptureStreamKey(tabId, sessionToken) {
  const keys = await loadCaptureKeys();
  const key = String(tabId);
  const stored = keys[key];
  if (stored?.sessionToken === sessionToken && validToken(stored.captureStreamKey))
    return stored.captureStreamKey;
  let captureStreamKey;
  do {
    captureStreamKey = uuid();
  } while (Object.values(keys).some((item) => item?.captureStreamKey === captureStreamKey));
  keys[key] = { sessionToken, captureStreamKey };
  await persistCaptureKeys();
  return captureStreamKey;
}

async function removeCaptureStreamKey(tabId, sessionToken = null) {
  const keys = await loadCaptureKeys();
  const key = String(tabId);
  if (!sessionToken || keys[key]?.sessionToken === sessionToken) {
    delete keys[key];
    await persistCaptureKeys();
  }
}

async function loadCaptureRecords() {
  if (!capturesPromise) {
    capturesPromise = chrome.storage.session.get(CAPTURES_KEY).then((stored) => {
      const value = stored[CAPTURES_KEY];
      captureRecords = value && typeof value === "object" && !Array.isArray(value) ? value : {};
      return captureRecords;
    }).catch((error) => {
      capturesPromise = null;
      throw error;
    });
  }
  return capturesPromise;
}

function persistCaptureRecords() {
  const snapshot = { ...captureRecords };
  captureWriteQueue = captureWriteQueue.then(() =>
    chrome.storage.session.set({ [CAPTURES_KEY]: snapshot }));
  return captureWriteQueue;
}

async function recordForTab(tabId) {
  const records = await loadCaptureRecords();
  return records[String(tabId)] || null;
}

async function saveCaptureRecord(record) {
  const records = await loadCaptureRecords();
  records[String(record.tabId)] = record;
  await persistCaptureRecords();
}

async function deleteCaptureRecord(record) {
  const records = await loadCaptureRecords();
  const key = String(record.tabId);
  if (records[key]?.sessionToken === record.sessionToken &&
      records[key]?.captureStreamKey === record.captureStreamKey) {
    delete records[key];
    await persistCaptureRecords();
  }
}

function safeTab(tab) {
  if (!tab || !Number.isInteger(tab.id) || tab.id < 0) return null;
  return {
    id: tab.id,
    title: safeTitle(tab.title),
    audible: Boolean(tab.audible),
    muted: Boolean(tab.muted ?? tab.mutedInfo?.muted),
    windowId: Number.isInteger(tab.windowId) ? tab.windowId : null
  };
}

async function tabEntry(tab) {
  const safe = safeTab(tab);
  if (!safe) return null;
  const identity = await getIdentity();
  const token = await getSessionToken(safe.id);
  const capture = await recordForTab(safe.id);
  const captured = capture?.status === "active" &&
    capture.instanceId === identity.instanceId && capture.profileId === identity.profileId &&
    capture.sessionToken === token && validToken(capture.captureStreamKey);
  const entry = {
    sessionToken: token,
    tabId: safe.id,
    title: safe.title,
    audible: safe.audible,
    muted: safe.muted,
    volumeCapability: captured ? "captured" : "none",
    captureStreamKey: captured ? capture.captureStreamKey : ""
  };
  knownEntries.set(safe.id, entry);
  return entry;
}

function newEnvelope(type, identity, tabs) {
  return {
    version: VERSION,
    type,
    requestId: uuid(),
    browser: "chrome",
    instanceId: identity.instanceId,
    profileId: identity.profileId,
    tabs
  };
}

function sendPortMessage(port, message) {
  if (!port || nativePort !== port) throw new Error("The local capture helper is disconnected.");
  port.postMessage(message);
}
function scheduleNativeReconnect() {
  if (reconnectTimer !== null) return;
  const delay = reconnectDelay;
  reconnectDelay = Math.min(reconnectDelay * 2, 30000);
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    try {
      connectNative();
    } catch (error) {
      nativeError = error?.message || "The local capture helper could not be restarted.";
      scheduleNativeReconnect();
    }
  }, delay);
}

function connectNative() {
  if (nativePort) return nativePort;
  if (reconnectTimer !== null) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
  nativeError = "";
  bridgeReady = false;
  bridgeReadyPromise = new Promise((resolve, reject) => {
    resolveBridgeReady = resolve;
    rejectBridgeReady = reject;
  });
  bridgeReadyPromise.catch(() => {});
  let port;
  try {
    port = chrome.runtime.connectNative(HOST_NAME);
  } catch (error) {
    rejectBridgeReady?.(error);
    scheduleNativeReconnect();
    throw error;
  }
  nativePort = port;
  port.onMessage.addListener((message) => handleNativeMessage(port, message));
  port.onDisconnect.addListener(() => {
    if (nativePort !== port) return;
    const reason = chrome.runtime.lastError?.message || "The local capture helper disconnected.";
    nativeError = reason;
    nativePort = null;
    bridgeReady = false;
    rejectBridgeReady?.(new Error(reason));
    resolveBridgeReady = null;
    rejectBridgeReady = null;
    for (const [requestId, request] of pendingNativeRequests) {
      clearTimeout(request.timer);
      request.reject(new Error(reason));
      pendingNativeRequests.delete(requestId);
    }
    chrome.storage.session.set({ tabCaptureError: reason }).catch(() => {});
    failAllCaptures(reason).catch(() => {});
    scheduleNativeReconnect();
  });
  return port;
}

function waitForBridgeReady() {
  const port = connectNative();
  if (bridgeReady) return Promise.resolve(port);
  const ready = bridgeReadyPromise;
  if (!ready) return Promise.reject(new Error("The local browser bridge is unavailable."));
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("The local browser bridge did not become ready.")), NATIVE_REQUEST_TIMEOUT_MS);
    ready.then(() => {
      clearTimeout(timer);
      resolve(port);
    }, (error) => {
      clearTimeout(timer);
      reject(error);
    });
  });
}

function nativeRequest(type, values = {}, timeout = NATIVE_REQUEST_TIMEOUT_MS) {
  if (!bridgeReady) return Promise.reject(new Error("The local browser bridge is not ready."));
  const port = nativePort || connectNative();
  const requestId = uuid();
  const message = { ...values, type, requestId };
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      pendingNativeRequests.delete(requestId);
      reject(new Error("The local capture helper did not acknowledge the request."));
    }, timeout);
    pendingNativeRequests.set(requestId, { resolve, reject, timer });
    try {
      sendPortMessage(port, message);
    } catch (error) {
      clearTimeout(timer);
      pendingNativeRequests.delete(requestId);
      reject(error);
    }
  });
}

function resolveNativeRequest(message) {
  if (typeof message.bridgeError === "string") setJavaBridgeError(message.bridgeError);
  const request = pendingNativeRequests.get(message.requestId);
  if (!request) return;
  pendingNativeRequests.delete(message.requestId);
  clearTimeout(request.timer);
  if (message.ok === true) request.resolve(message);
  else request.reject(new Error(message.error || "The local capture helper rejected the request."));
}

async function sendOffscreen(action, values = {}) {
  if (!await chrome.offscreen.hasDocument()) {
    if (action === "stop") return { ok: true, playbackRecovered: true, trackState: "ended", contextState: "closed" };
    throw new Error("The offscreen audio processor is unavailable.");
  }
  return chrome.runtime.sendMessage({ target: "offscreen", action, ...values });
}
async function ensureOffscreen() {
  if (await chrome.offscreen.hasDocument()) return;
  await chrome.offscreen.createDocument({
    url: "offscreen.html",
    reasons: ["USER_MEDIA"],
    justification: "Process audio from a tab that the user explicitly chose to capture."
  });
}


async function stopCaptureSides(record, { askNative = true } = {}) {
  const nativePromise = askNative && nativePort
    ? nativeRequest("capture-stop", {
      browser: "chrome",
      instanceId: record.instanceId,
      profileId: record.profileId,
      sessionToken: record.sessionToken,
      captureStreamKey: record.captureStreamKey,
      tabId: record.tabId
    }).catch((error) => ({ ok: false, error: error.message }))
    : Promise.resolve({ ok: true });
  const offscreenPromise = sendOffscreen("stop", {
    instanceId: record.instanceId,
    profileId: record.profileId,
    sessionToken: record.sessionToken,
    captureStreamKey: record.captureStreamKey,
    tabId: record.tabId
  }).catch((error) => ({ ok: false, error: error.message, playbackRecovered: false }));
  const [host, offscreen] = await Promise.all([nativePromise, offscreenPromise]);
  await deleteCaptureRecord(record);
  return {
    ok: host.ok !== false && offscreen.ok !== false,
    playbackRecovered: offscreen.playbackRecovered === true &&
      offscreen.trackState === "ended" && offscreen.contextState === "closed",
    trackState: offscreen.trackState || "unknown",
    contextState: offscreen.contextState || "unknown",
    error: host.error || offscreen.error || ""
  };
}

async function teardownCapture(record, { askNative = true, emit = true } = {}) {
  if (!record) return { ok: true, playbackRecovered: true, trackState: "ended", contextState: "closed" };
  const key = sessionKey(record.instanceId, record.profileId, record.sessionToken);
  if (stoppingCaptures.has(key)) return stoppingCaptures.get(key);
  const operation = (async () => {
    const wasActive = record.status === "active";
    const result = await stopCaptureSides(record, { askNative });
    if (emit && wasActive) await emitTabChange(record.tabId, "tab_updated");
    return result;
  })();
  stoppingCaptures.set(key, operation);
  try {
    return await operation;
  } finally {
    stoppingCaptures.delete(key);
  }
}

async function failAllCaptures(reason) {
  nativeError = reason || "The local capture helper disconnected.";
  const records = await loadCaptureRecords().catch(() => ({}));
  await Promise.all(Object.values(records).map((record) =>
    teardownCapture(record, { askNative: false, emit: false })));
  await chrome.storage.session.set({ tabCaptureError: nativeError });
}

function serializeNativeEnvelope(port, envelope) {
  sendPortMessage(port, envelope);
}

async function cleanupStaleCaptures() {
  const records = await loadCaptureRecords();
  const stale = Object.values(records);
  await Promise.all(stale.map(async (record) => {
    await stopCaptureSides(record, { askNative: false });
  }));
}

function handleBridgeReady(port) {
  serialOutgoing(async () => {
    if (nativePort !== port) return;
    bridgeReady = false;
    await cleanupStaleCaptures();
    const identity = await getIdentity();
    const tabs = await chrome.tabs.query({});
    const entries = (await Promise.all(tabs.map(tabEntry))).filter(Boolean);
    serializeNativeEnvelope(port, newEnvelope("snapshot", identity, entries));
    reconnectDelay = 500;
    if (reconnectTimer !== null) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    bridgeReady = true;
    resolveBridgeReady?.(port);
    resolveBridgeReady = null;
    nativeError = "";
    await chrome.storage.session.set({ tabCaptureError: "" });
  }).catch((error) => {
    bridgeReady = false;
    nativeError = error?.message || "Could not send the browser snapshot.";
  });
}

function receiveEvent(port, message) {
  if (message.type === "bridge-ready") {
    handleBridgeReady(port);
    return;
  }
  if (message.type === "bridge-error") {
    if (message.connected === true) {
      setJavaBridgeError("");
      return;
    }
    if (message.connected === false) {
      // Audio Matrix is not connected: capture keeps running, only the Java-side
      // tab controls are unavailable until the socket comes back.
      setJavaBridgeError(message.message || "Audio Matrix is not connected; Chrome tab controls are unavailable.");
      return;
    }
    nativeError = message.message || "The local capture helper failed.";
    failAllCaptures(nativeError).catch(() => {});
    return;
  }
  if (message.type === "capture-response") {
    resolveNativeRequest(message);
    return;
  }
  if (message.type === "capture-error") {
    handleCaptureFailure(message).catch(() => {});
    return;
  }
  if (message.type === "command") {
    handleJavaCommand(message).catch(() => {});
  }
}

function handleNativeMessage(port, message) {
  if (nativePort !== port || !message || typeof message !== "object") return;
  receiveEvent(port, message);
}

function enqueueEvent(type, makeTabs) {
  return serialOutgoing(async () => {
    const identity = await getIdentity();
    const tabs = await makeTabs();
    const envelope = newEnvelope(type, identity, tabs.filter(Boolean));
    if (!bridgeReady || !nativePort) return;
    serializeNativeEnvelope(nativePort, envelope);
  });
}

function emitTabChange(tabId, type) {
  return enqueueEvent(type, async () => {
    const tab = await chrome.tabs.get(tabId).catch(() => null);
    if (!tab) return [];
    const entry = await tabEntry(tab);
    return entry ? [entry] : [];
  });
}

const startingTabs = new Set();

async function startCapture(message, sender) {
  const tabId = Number(message.tabId);
  if (startingTabs.has(tabId)) throw new Error("This tab already has a capture request in progress.");
  startingTabs.add(tabId);
  const generation = tabGenerations.get(tabId) || 0;
  try {
    return await startCaptureOnce(message, sender, generation);
  } finally {
    startingTabs.delete(tabId);
    tabGenerations.delete(tabId);
  }
}
function noteTabLifecycle(tabId) {
  if (startingTabs.has(tabId))
    tabGenerations.set(tabId, (tabGenerations.get(tabId) || 0) + 1);
}

function assertTabGeneration(tabId, expectedGeneration) {
  if ((tabGenerations.get(tabId) || 0) !== expectedGeneration)
    throw new Error("The selected tab closed during capture startup.");
}


async function startCaptureOnce(message, sender, generation) {
  const tabId = Number(message.tabId);
  if (!Number.isInteger(tabId) || tabId < 0 || typeof message.streamId !== "string" || !message.streamId)
    throw new Error("The selected tab capture request is invalid.");
  const popupUrl = chrome.runtime.getURL("popup.html");
  if (sender?.id !== chrome.runtime.id || sender.url !== popupUrl)
    throw new Error("Capture can only start from the extension popup.");
  const [tab, activeTabs] = await Promise.all([
    chrome.tabs.get(tabId),
    chrome.tabs.query({ active: true, lastFocusedWindow: true })
  ]);
  if (!tab.active || activeTabs[0]?.id !== tabId)
    throw new Error("Select the active tab before starting capture.");
  assertTabGeneration(tabId, generation);
  const identity = await getIdentity();
  const token = await getSessionToken(tabId);
  assertTabGeneration(tabId, generation);
  const old = await recordForTab(tabId);
  if (old) throw new Error("This tab already has an active or starting capture.");
  await waitForBridgeReady();
  const captureStreamKey = await getCaptureStreamKey(tabId, token);
  assertTabGeneration(tabId, generation);
  const record = {
    browser: "chrome",
    instanceId: identity.instanceId,
    profileId: identity.profileId,
    sessionToken: token,
    captureStreamKey,
    tabId,
    title: safeTitle(tab.title, 128),
    status: "starting",
    nextSequence: 0
  };
  await saveCaptureRecord(record);
  try {
    assertTabGeneration(tabId, generation);
    await nativeRequest("capture-start", {
      browser: "chrome",
      instanceId: record.instanceId,
      profileId: record.profileId,
      sessionToken: record.sessionToken,
      captureStreamKey: record.captureStreamKey,
      tabId,
      title: record.title
    });
    assertTabGeneration(tabId, generation);
    await ensureOffscreen();
    assertTabGeneration(tabId, generation);
    const prepared = await sendOffscreen("prepare", {
      browser: "chrome",
      instanceId: record.instanceId,
      profileId: record.profileId,
      sessionToken: record.sessionToken,
      captureStreamKey: record.captureStreamKey,
      tabId,
      streamId: message.streamId
    });
    if (!prepared?.ok) throw new Error(prepared?.error || "The offscreen capture could not become ready.");
    assertTabGeneration(tabId, generation);
    record.status = "ready";
    await saveCaptureRecord(record);
    assertTabGeneration(tabId, generation);
    const committed = await sendOffscreen("commit", {
      browser: "chrome",
      instanceId: record.instanceId,
      profileId: record.profileId,
      sessionToken: record.sessionToken,
      captureStreamKey: record.captureStreamKey,
      tabId
    });
    if (!committed?.ok) throw new Error(committed?.error || "The offscreen capture could not start.");
    assertTabGeneration(tabId, generation);
    record.status = "active";
    await saveCaptureRecord(record);
    assertTabGeneration(tabId, generation);
    await chrome.storage.session.set({ tabCaptureError: "" });
    nativeError = "";
    await emitTabChange(tabId, "tab_updated");
    assertTabGeneration(tabId, generation);
    return { ok: true, captureStreamKey: record.captureStreamKey };
  } catch (error) {
    await teardownCapture(record, { askNative: true, emit: false });
    throw error;
  }
}

function safeWindowTabs(windowId) {
  if (!Number.isInteger(windowId) || windowId < 0) return Promise.resolve([]);
  return chrome.tabs.query({ windowId }).then(async (tabs) =>
    (await Promise.all(tabs.map(tabEntry))).filter(Boolean));
}


async function stopCapture(tabId, sessionToken = null, emit = true) {
  const record = await recordForTab(tabId);
  if (!record || (sessionToken && record.sessionToken !== sessionToken))
    return { ok: true, playbackRecovered: true, trackState: "ended", contextState: "closed" };
  return teardownCapture(record, { askNative: true, emit });
}

async function handleCaptureFailure(message) {
  const tabId = Number(message.tabId);
  const record = await recordForTab(tabId);
  if (!record || record.sessionToken !== message.sessionToken ||
      record.instanceId !== message.instanceId || record.profileId !== message.profileId ||
      (message.captureStreamKey && record.captureStreamKey !== message.captureStreamKey)) return;
  const reason = typeof message.error === "string" ? message.error : "Tab audio capture failed.";
  nativeError = reason;
  await chrome.storage.session.set({ tabCaptureError: reason });
  await teardownCapture(record, { askNative: true, emit: true });
}

async function handlePcm(message) {
  const tabId = Number(message.tabId);
  const record = await recordForTab(tabId);
  if (!record || !["ready", "active"].includes(record.status) ||
      record.instanceId !== message.instanceId || record.profileId !== message.profileId ||
      record.sessionToken !== message.sessionToken || record.captureStreamKey !== message.captureStreamKey ||
      message.sequence !== record.nextSequence) {
    throw new Error("PCM does not match an active Chrome tab capture.");
  }
  const sequence = record.nextSequence++;
  await nativeRequest("capture-pcm", {
    browser: "chrome",
    instanceId: record.instanceId,
    profileId: record.profileId,
    sessionToken: record.sessionToken,
    captureStreamKey: record.captureStreamKey,
    tabId,
    sequence,
    data: message.data
  }, PCM_REQUEST_TIMEOUT_MS);
  return { ok: true, sequence };
}

async function handleJavaCommand(message) {
  let status = "ok";
  let error = "";
  try {
    if (message.version !== VERSION || message.type !== "command" || message.browser !== "chrome" ||
        typeof message.requestId !== "string" || !message.requestId ||
        !Array.isArray(message.tabs) || message.tabs.length !== 0) {
      throw new Error("The Java browser command is malformed.");
    }
    const identity = await getIdentity();
    if (message.instanceId !== identity.instanceId || message.profileId !== identity.profileId)
      throw new Error("The browser command targets a different Chrome profile.");
    if (!validToken(message.sessionToken)) throw new Error("The browser command has an invalid tab session.");
    const records = await loadSessionTokens();
    const tabId = Number(Object.keys(records).find((key) => records[key] === message.sessionToken));
    if (!Number.isInteger(tabId) || tabId < 0) throw new Error("The browser tab session is no longer active.");
    if (message.command === "mute") {
      if (typeof message.muted !== "boolean") throw new Error("The mute command is missing its state.");
      await chrome.tabs.update(tabId, { muted: message.muted });
    } else if (message.command === "focus") {
      const tab = await chrome.tabs.get(tabId);
      await chrome.windows.update(tab.windowId, { focused: true });
      await chrome.tabs.update(tabId, { active: true });
    } else if (message.command === "stopCapture") {
      const record = await recordForTab(tabId);
      if (record?.sessionToken !== message.sessionToken ||
          record.instanceId !== identity.instanceId || record.profileId !== identity.profileId)
        throw new Error("This tab has no capture to stop.");
      const result = await stopCapture(tabId, message.sessionToken);
      if (!result.ok) throw new Error(result.error || "Capture shutdown was incomplete.");
    } else {
      throw new Error("The browser command is unsupported.");
    }
  } catch (cause) {
    status = "error";
    error = cause?.message || "The browser command failed.";
  }
  const identity = await getIdentity();
  await serialOutgoing(async () => {
    if (!bridgeReady || !nativePort) return;
    serializeNativeEnvelope(nativePort, {
      version: VERSION,
      type: "ack",
      requestId: message.requestId,
      browser: "chrome",
      instanceId: identity.instanceId,
      profileId: identity.profileId,
      tabs: [],
      status,
      error
    });
  });
}

async function status() {
  const records = await loadCaptureRecords();
  const captures = Object.values(records)
    .filter((record) => record.status === "active")
    .map((record) => ({ tabId: record.tabId, status: "active" }));
  const stored = await chrome.storage.session.get("tabCaptureError");
  const bridge = await chrome.storage.session.get(JAVA_BRIDGE_ERROR_KEY);
  return {
    captures,
    error: nativeError || stored.tabCaptureError || "",
    javaBridgeError: javaBridgeError || bridge[JAVA_BRIDGE_ERROR_KEY] || ""
  };
}

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message?.target === "service-worker") {
    if (message.type === "pcm") {
      handlePcm(message).then(sendResponse).catch(async (error) => {
        await handleCaptureFailure({
          tabId: message.tabId,
          instanceId: message.instanceId,
          profileId: message.profileId,
          sessionToken: message.sessionToken,
          captureStreamKey: message.captureStreamKey,
          error: error.message
        });
        sendResponse({ ok: false, error: error.message });
      });
      return true;
    }
    if (message.type === "capture-error") {
      handleCaptureFailure(message).then(() => sendResponse({ ok: true }))
        .catch((error) => sendResponse({ ok: false, error: error.message }));
      return true;
    }
    return false;
  }
  if (message?.target === "offscreen") return false;
  if (message?.action === "start") {
    startCapture(message, sender).then(sendResponse)
      .catch((error) => sendResponse({ ok: false, error: error.message }));
    return true;
  }
  if (message?.action === "stop") {
    stopCapture(Number(message.tabId)).then((result) => sendResponse(result))
      .catch((error) => sendResponse({ ok: false, error: error.message }));
    return true;
  }
  if (message?.action === "mute") {
    chrome.tabs.update(Number(message.tabId), { muted: Boolean(message.muted) })
      .then(() => sendResponse({ ok: true }))
      .catch((error) => sendResponse({ ok: false, error: error.message }));
    return true;
  }
  if (message?.action === "status") {
    status().then(sendResponse)
      .catch((error) => sendResponse({ captures: [], error: error.message }));
    return true;
  }
  return false;
});

chrome.tabs.onCreated.addListener((tab) => {
  const safe = safeTab(tab);
  if (!safe) return;
  noteTabLifecycle(safe.id);
  enqueueEvent("tab_created", async () => {
    const entry = await tabEntry(safe);
    return entry ? [entry] : [];
  }).catch(() => {});
});

chrome.tabs.onUpdated.addListener((tabId, changeInfo, tab) => {
  const safe = safeTab(tab);
  if (!safe) return;
  const type = Object.hasOwn(changeInfo, "audible") || Object.hasOwn(changeInfo, "mutedInfo")
    ? "tab_audio" : "tab_updated";
  enqueueEvent(type, async () => {
    const entry = await tabEntry(safe);
    return entry ? [entry] : [];
  }).catch(() => {});
});

chrome.tabs.onRemoved.addListener((tabId) => {
  noteTabLifecycle(tabId);
  serialOutgoing(async () => {
    const records = await loadCaptureRecords();
    const record = records[String(tabId)] || null;
    const oldEntry = knownEntries.get(tabId) || (record ? {
      sessionToken: record.sessionToken,
      tabId,
      title: record.title || "",
      audible: false,
      muted: false,
      volumeCapability: record.status === "active" ? "captured" : "none",
      captureStreamKey: record.status === "active" ? record.captureStreamKey : ""
    } : null);
    if (record) await teardownCapture(record, { askNative: true, emit: false });
    const identity = await getIdentity();
    if (oldEntry) {
      const envelope = newEnvelope("tab_removed", identity, [oldEntry]);
      if (bridgeReady && nativePort) serializeNativeEnvelope(nativePort, envelope);
    }
    knownEntries.delete(tabId);
    await removeCaptureStreamKey(tabId, record?.sessionToken || oldEntry?.sessionToken);
    await removeSessionToken(tabId);
  }).catch(() => {});
});

chrome.tabs.onActivated.addListener((activeInfo) => {
  enqueueEvent("window_updated", () => safeWindowTabs(activeInfo.windowId)).catch(() => {});
});

chrome.windows.onFocusChanged.addListener((windowId) => {
  enqueueEvent("window_updated", () => safeWindowTabs(windowId)).catch(() => {});
});

try {
  connectNative();
} catch (error) {
  nativeError = error?.message || "The local capture helper is unavailable.";
}
