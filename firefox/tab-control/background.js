'use strict';

const HOST_NAME = 'io.github.ilyaandreev05.audiomatrix.firefox_tab_control';
const IDENTITY_KEY = 'audioMatrixBrowserIdentity';
const TOKENS_KEY = 'audioMatrixTabSessionTokens';
let port = null;
let ready = false;
let javaConnected = false;
let bridgeError = '';
let reconnectTimer = null;
let reconnectDelay = 500;
let identityPromise = null;
let tokensPromise = null;
let known = new Map();
let queue = Promise.resolve();

function serial(operation) {
  const result = queue.then(operation, operation);
  queue = result.catch(error => { bridgeError = error.message || 'Не удалось обновить вкладки Firefox.'; });
  return result;
}

function validUuid(value) {
  return typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}

function identity() {
  if (!identityPromise) identityPromise = browser.storage.local.get(IDENTITY_KEY).then(async stored => {
    const previous = stored[IDENTITY_KEY] || {};
    const value = {
      instanceId: validUuid(previous.instanceId) ? previous.instanceId : crypto.randomUUID(),
      profileId: validUuid(previous.profileId) ? previous.profileId : crypto.randomUUID()
    };
    await browser.storage.local.set({[IDENTITY_KEY]: value});
    return value;
  }).catch(error => { identityPromise = null; throw error; });
  return identityPromise;
}

function tokens() {
  if (!tokensPromise) tokensPromise = browser.storage.session.get(TOKENS_KEY).then(stored => {
    const value = stored[TOKENS_KEY];
    return value && typeof value === 'object' && !Array.isArray(value) ? value : {};
  }).catch(error => { tokensPromise = null; throw error; });
  return tokensPromise;
}

function safeTitle(value) {
  return (typeof value === 'string' ? value : '')
    .replace(/\b[a-z][a-z0-9+.-]*:\/\/[^\s]+|\bwww\.[^\s]+|\b(?:[a-z0-9-]+\.)+[a-z]{2,}(?::\d+)?(?:\/[^\s]*)?/gi, '')
    .replace(/[\u0000-\u001f\u007f]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 512) || 'Вкладка Firefox';
}

async function envelope(type, tabs, extra = {}) {
  return {version: 1, type, requestId: crypto.randomUUID(), browser: 'firefox',
    ...await identity(), tabs, ...extra};
}

async function send(type, entries, extra) {
  if (!port || !ready) return false;
  const message = await envelope(type, entries, extra);
  if (!port || !ready) return false;
  port.postMessage(message);
  return true;
}

async function publish(initial = false) {
  if (!port || !ready) return;
  const tabs = await browser.tabs.query({});
  const sessions = await tokens();
  const entries = new Map();
  let changedTokens = false;
  for (const tab of tabs) {
    if (!Number.isInteger(tab.id) || tab.id < 0) continue;
    if (!validUuid(sessions[tab.id])) {
      sessions[tab.id] = crypto.randomUUID();
      changedTokens = true;
    }
    entries.set(sessions[tab.id], {sessionToken: sessions[tab.id], tabId: tab.id,
      title: safeTitle(tab.title), audible: Boolean(tab.audible), muted: Boolean(tab.mutedInfo?.muted),
      volumeCapability: 'none', captureStreamKey: ''});
  }
  for (const tabId of Object.keys(sessions)) {
    if (!tabs.some(tab => String(tab.id) === tabId)) { delete sessions[tabId]; changedTokens = true; }
  }
  if (changedTokens) await browser.storage.session.set({[TOKENS_KEY]: sessions});
  if (initial) {
    if (await send('snapshot', [...entries.values()])) known = entries;
    return;
  }
  const removed = [...known.values()].filter(entry => !entries.has(entry.sessionToken));
  const created = [...entries.values()].filter(entry => !known.has(entry.sessionToken));
  const updated = [...entries.values()].filter(entry => known.has(entry.sessionToken)
    && JSON.stringify(entry) !== JSON.stringify(known.get(entry.sessionToken)));
  if (removed.length) await send('tab_removed', removed);
  if (created.length) await send('tab_created', created);
  if (updated.length) await send('tab_updated', updated);
  known = entries;
}

async function command(message) {
  const current = await identity();
  if (message?.type !== 'command' || message.version !== 1 || message.browser !== 'firefox'
      || message.instanceId !== current.instanceId || message.profileId !== current.profileId
      || typeof message.requestId !== 'string' || !/^[A-Za-z0-9._:-]{1,256}$/.test(message.requestId)) return;
  let error = '';
  try {
    const entry = known.get(message.sessionToken);
    if (!entry) throw new Error('Вкладка уже закрыта или её сеанс изменился.');
    const sessions = await tokens();
    if (sessions[entry.tabId] !== message.sessionToken) throw new Error('Сеанс вкладки изменился.');
    if (message.command === 'mute' && typeof message.muted === 'boolean') {
      const tab = await browser.tabs.update(entry.tabId, {muted: message.muted});
      if (Boolean(tab.mutedInfo?.muted) !== message.muted) throw new Error('Firefox не подтвердил состояние звука вкладки.');
    } else if (message.command === 'focus') {
      const tab = await browser.tabs.update(entry.tabId, {active: true});
      if (Number.isInteger(tab.windowId)) await browser.windows.update(tab.windowId, {focused: true});
    } else throw new Error('Firefox не поддерживает эту команду вкладки.');
    // The confirmed tab update reaches Java before its command acknowledgement.
    await publish();
    if (message.command === 'mute') {
      const confirmed = known.get(message.sessionToken);
      if (!confirmed || confirmed.muted !== message.muted) throw new Error('Состояние звука вкладки изменилось до подтверждения.');
      await send('tab_updated', [confirmed]);
    }
  } catch (failure) { error = failure.message || 'Не удалось изменить вкладку Firefox.'; }
  await send('ack', [], {requestId: message.requestId, status: error ? 'error' : 'ok', error});
}

function scheduleReconnect() {
  if (reconnectTimer !== null) return;
  reconnectTimer = setTimeout(() => { reconnectTimer = null; connect(); }, reconnectDelay);
  reconnectDelay = Math.min(30000, reconnectDelay * 2);
}

function connect() {
  if (port) return;
  try {
    const connection = browser.runtime.connectNative(HOST_NAME);
    port = connection;
    connection.onMessage.addListener(message => {
      if (port !== connection) return;
      if (message?.type === 'bridge-ready') {
        ready = true;
        reconnectDelay = 500;
        serial(() => publish(true));
      } else if (message?.type === 'bridge-error') {
        javaConnected = Boolean(message.connected);
        bridgeError = javaConnected ? '' : message.message || 'Audio Matrix не подключён.';
      } else if (message?.type === 'command') serial(() => command(message));
    });
    connection.onDisconnect.addListener(() => {
      if (port !== connection) return;
      port = null;
      ready = false;
      javaConnected = false;
      known = new Map();
      bridgeError = connection.error?.message || 'Локальный мост Firefox недоступен. Установите его из пакета Audio Matrix.';
      scheduleReconnect();
    });
  } catch (error) {
    port = null;
    bridgeError = error.message || 'Не удалось подключить локальный мост Firefox.';
    scheduleReconnect();
  }
}

browser.tabs.onCreated.addListener(tab => serial(async () => {
  const sessions = await tokens();
  delete sessions[tab.id];
  await browser.storage.session.set({[TOKENS_KEY]: sessions});
  await publish();
}));
browser.tabs.onUpdated.addListener(() => serial(() => publish()));
browser.tabs.onRemoved.addListener(() => serial(() => publish()));
browser.tabs.onActivated.addListener(() => serial(() => publish()));
browser.runtime.onStartup.addListener(connect);
browser.runtime.onInstalled.addListener(connect);
browser.runtime.onMessage.addListener(message => message?.action === 'status'
  ? Promise.resolve({connected: ready && javaConnected, error: bridgeError, independentTabVolume: false}) : undefined);
connect();
