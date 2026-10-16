const list = document.querySelector("#tabs");
const status = document.querySelector("#status");
const activeLabel = document.querySelector("#active-tab");
const captureButton = document.querySelector("#capture");
let currentTab = null;
let refreshTimer = null;

function send(message) {
  return chrome.runtime.sendMessage(message);
}

async function refresh() {
  try {
    const [tabs, activeTabs, state] = await Promise.all([
      chrome.tabs.query({}),
      chrome.tabs.query({ active: true, lastFocusedWindow: true }),
      send({ action: "status" })
    ]);
    const active = activeTabs[0] || null;
    currentTab = active || null;
    const captureIds = new Set((state.captures || []).filter((item) => item.status === "active").map((item) => item.tabId));
    if (active) {
      activeLabel.textContent = active.title || active.url || "Current tab";
      captureButton.disabled = false;
      captureButton.textContent = captureIds.has(active.id) ? "Stop capture" : "Capture this tab";
      captureButton.setAttribute("aria-pressed", captureIds.has(active.id) ? "true" : "false");
    } else {
      activeLabel.textContent = "No active tab";
      captureButton.disabled = true;
    }
    status.textContent = state.error || `${tabs.length} tabs · ${captureIds.size} captured`;
    list.replaceChildren();
    for (const tab of tabs) {
      const row = document.createElement("li");
      const title = document.createElement("span");
      title.className = "title";
      title.textContent = tab.title || tab.url || "Untitled tab";
      title.title = title.textContent;
      const focus = document.createElement("button");
      focus.type = "button";
      focus.className = "tab-action";
      focus.textContent = tab.active ? "Active" : "Focus";
      focus.disabled = Boolean(tab.active);
      focus.setAttribute("aria-label", tab.active ? "Current tab" : `Focus ${title.textContent}`);
      focus.addEventListener("click", async () => {
        await chrome.tabs.update(tab.id, { active: true });
        window.close();
      });
      const mute = document.createElement("button");
      mute.type = "button";
      mute.className = "tab-action";
      mute.textContent = tab.mutedInfo?.muted ? "Unmute" : "Mute";
      mute.setAttribute("aria-pressed", tab.mutedInfo?.muted ? "true" : "false");
      mute.setAttribute("aria-label", `${tab.mutedInfo?.muted ? "Unmute" : "Mute"} ${title.textContent}`);
      mute.addEventListener("click", async () => {
        const result = await send({ action: "mute", tabId: tab.id, muted: !tab.mutedInfo?.muted });
        if (!result?.ok) status.textContent = result?.error || "Could not change tab mute.";
        await refresh();
      });
      row.append(title, focus, mute);
      list.append(row);
    }
  } catch (error) {
    status.textContent = error.message || "Audio Matrix could not read the tab list.";
    captureButton.disabled = true;
  }
}

captureButton.addEventListener("click", async () => {
  if (!currentTab) return;
  captureButton.disabled = true;
  try {
    const state = await send({ action: "status" });
    const captured = (state.captures || []).some((item) => item.tabId === currentTab.id && item.status === "active");
    if (captured) {
      const result = await send({ action: "stop", tabId: currentTab.id });
      if (!result?.ok) throw new Error(result?.error || "Could not stop capture.");
      status.textContent = "Capture stopped; Chrome tab playback restored.";
    } else {
      // This call is inside the user's popup-button gesture, as required by tabCapture.
      const streamId = await chrome.tabCapture.getMediaStreamId({ targetTabId: currentTab.id });
      const result = await send({ action: "start", tabId: currentTab.id, streamId });
      if (!result?.ok) throw new Error(result?.error || "Could not start capture.");
      status.textContent = "Tab audio is routed into Audio Matrix.";
    }
  } catch (error) {
    status.textContent = error.message || "Chrome could not start tab capture.";
  }
  await refresh();
});

refresh();

function scheduleRefresh() {
  clearTimeout(refreshTimer);
  refreshTimer = setTimeout(refresh, 120);
}

chrome.tabs.onCreated.addListener(scheduleRefresh);
chrome.tabs.onUpdated.addListener(scheduleRefresh);
chrome.tabs.onActivated.addListener(scheduleRefresh);
chrome.tabs.onRemoved.addListener(scheduleRefresh);
chrome.tabCapture.onStatusChanged.addListener(scheduleRefresh);
