const HOST_NAME = "io.github.ilyaandreev05.audiomatrix.chrome_tab_capture";
let nativePort = null;
let nativeError = "";

function connectNative() {
  if (nativePort) return nativePort;
  nativeError = "";
  nativePort = chrome.runtime.connectNative(HOST_NAME);
  nativePort.onMessage.addListener((message) => {
    if (message.type === "error") nativeError = message.message || "Local capture helper reported an error.";
    else if (message.type === "ready") nativeError = "";
    chrome.storage.session.set({ tabCaptureError: nativeError });
  });
  nativePort.onDisconnect.addListener(() => {
    const reason = chrome.runtime.lastError?.message || "The local capture helper disconnected.";
    nativeError = reason;
    nativePort = null;
    chrome.storage.session.set({ tabCaptureError: reason });
    chrome.runtime.sendMessage({ target: "offscreen", action: "stop-all" }).catch(() => {});
  });
  return nativePort;
}

async function ensureOffscreen() {
  if (await chrome.offscreen.hasDocument()) return;
  await chrome.offscreen.createDocument({
    url: "offscreen.html",
    reasons: ["USER_MEDIA"],
    justification: "Process audio from a tab that the user explicitly chose to capture."
  });
}

async function startCapture(message) {
  const tabId = Number(message.tabId);
  if (!Number.isInteger(tabId) || tabId < 0 || typeof message.streamId !== "string" || !message.streamId)
    throw new Error("The selected tab capture request is invalid.");
  const port = connectNative();
  const title = typeof message.title === "string" ? message.title.slice(0, 128) : "";
  port.postMessage({ type: "start", tabId, title });
  try {
    await ensureOffscreen();
    const result = await chrome.runtime.sendMessage({ target: "offscreen", action: "start", tabId, streamId: message.streamId });
    if (!result?.ok) throw new Error(result?.error || "The offscreen capture could not start.");
    await chrome.storage.session.set({ tabCaptureError: "" });
    return { ok: true };
  } catch (error) {
    port.postMessage({ type: "stop", tabId });
    throw error;
  }
}

async function stopCapture(tabId) {
  const id = Number(tabId);
  if (!Number.isInteger(id) || id < 0) return { ok: false };
  if (nativePort) nativePort.postMessage({ type: "stop", tabId: id });
  await chrome.runtime.sendMessage({ target: "offscreen", action: "stop", tabId: id }).catch(() => {});
  return { ok: true };
}

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (message.target === "offscreen") {
    if (message.type === "pcm" && nativePort) nativePort.postMessage({ type: "pcm", tabId: message.tabId, data: message.data });
    if (message.type === "capture-error") {
      nativeError = message.message || "Chrome could not capture this tab.";
      chrome.storage.session.set({ tabCaptureError: nativeError });
      if (nativePort) nativePort.postMessage({ type: "stop", tabId: message.tabId });
    }
    return false;
  }
  if (message.action === "start") {
    startCapture(message).then(sendResponse).catch((error) => sendResponse({ ok: false, error: error.message }));
    return true;
  }
  if (message.action === "stop") {
    stopCapture(message.tabId).then(sendResponse).catch((error) => sendResponse({ ok: false, error: error.message }));
    return true;
  }
  if (message.action === "mute") {
    chrome.tabs.update(Number(message.tabId), { muted: Boolean(message.muted) })
      .then(() => sendResponse({ ok: true })).catch((error) => sendResponse({ ok: false, error: error.message }));
    return true;
  }
  if (message.action === "status") {
    Promise.all([chrome.tabCapture.getCapturedTabs(), chrome.storage.session.get("tabCaptureError")])
      .then(([captures, state]) => sendResponse({ captures, error: state.tabCaptureError || nativeError }))
      .catch((error) => sendResponse({ captures: [], error: error.message }));
    return true;
  }
  return false;
});

chrome.tabs.onRemoved.addListener((tabId) => { stopCapture(tabId).catch(() => {}); });
