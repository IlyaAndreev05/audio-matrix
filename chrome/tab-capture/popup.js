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
      activeLabel.textContent = active.title || "Текущая вкладка";
      captureButton.disabled = false;
      captureButton.textContent = captureIds.has(active.id) ? "Остановить захват звука" : "Захватить звук вкладки";
      captureButton.setAttribute("aria-pressed", captureIds.has(active.id) ? "true" : "false");
    } else {
      activeLabel.textContent = "Нет активной вкладки";
      captureButton.disabled = true;
    }
    const problems = [state.error, state.javaBridgeError].filter(Boolean);
    status.textContent = problems.length
      ? problems.join(" · ")
      : `Вкладок: ${tabs.length} · с захватом звука: ${captureIds.size}`;
    list.replaceChildren();
    for (const tab of tabs) {
      const row = document.createElement("li");
      const title = document.createElement("span");
      title.className = "title";
      title.textContent = tab.title || "Без названия";
      title.title = title.textContent;
      const focus = document.createElement("button");
      focus.type = "button";
      focus.className = "tab-action";
      focus.textContent = tab.active ? "Активна" : "Открыть";
      focus.disabled = Boolean(tab.active);
      focus.setAttribute("aria-label", tab.active ? "Текущая вкладка" : `Открыть ${title.textContent}`);
      focus.addEventListener("click", async () => {
        await chrome.tabs.update(tab.id, { active: true });
        window.close();
      });
      const mute = document.createElement("button");
      mute.type = "button";
      mute.className = "tab-action";
      mute.textContent = tab.mutedInfo?.muted ? "Включить звук" : "Выключить звук";
      mute.setAttribute("aria-pressed", tab.mutedInfo?.muted ? "true" : "false");
      mute.setAttribute("aria-label", `${tab.mutedInfo?.muted ? "Включить звук" : "Выключить звук"}: ${title.textContent}`);
      mute.addEventListener("click", async () => {
        const result = await send({ action: "mute", tabId: tab.id, muted: !tab.mutedInfo?.muted });
        if (!result?.ok) status.textContent = result?.error || "Не удалось изменить состояние звука вкладки.";
        await refresh();
      });
      row.append(title, focus, mute);
      list.append(row);
    }
  } catch (error) {
    status.textContent = error.message || "Audio Matrix не удалось прочитать список вкладок.";
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
      if (!result?.ok) throw new Error(result?.error || "Не удалось остановить захват звука.");
      status.textContent = result.playbackRecovered
        ? "Захват звука остановлен; воспроизведение вкладки восстановлено."
        : "Захват звука остановлен, но Chrome не подтвердил восстановление воспроизведения.";
    } else {
      // This call stays inside the user's popup-button gesture, as required by tabCapture.
      const streamId = await chrome.tabCapture.getMediaStreamId({ targetTabId: currentTab.id });
      const result = await send({ action: "start", tabId: currentTab.id, streamId });
      if (!result?.ok) throw new Error(result?.error || "Не удалось начать захват звука.");
      status.textContent = "Звук вкладки передаётся в Audio Matrix.";
    }
  } catch (error) {
    status.textContent = error.message || "Chrome не удалось начать захват звука вкладки.";
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
