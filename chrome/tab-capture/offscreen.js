const MAX_OUTSTANDING_CHUNKS = 4;
const CHUNK_BYTES = 512 * 2 * Float32Array.BYTES_PER_ELEMENT;
const active = new Map();

function captureKey(message) {
  return `${message.instanceId}\u0000${message.profileId}\u0000${message.sessionToken}\u0000${message.captureStreamKey}`;
}

function validIdentity(message) {
  return message?.browser === "chrome" &&
    typeof message.instanceId === "string" && message.instanceId.length > 0 &&
    typeof message.profileId === "string" && message.profileId.length > 0 &&
    typeof message.sessionToken === "string" && message.sessionToken.length > 0 &&
    typeof message.captureStreamKey === "string" && message.captureStreamKey.length > 0 &&
    Number.isInteger(message.tabId) && message.tabId >= 0;
}

function toBase64(buffer) {
  const bytes = new Uint8Array(buffer);
  let binary = "";
  for (let offset = 0; offset < bytes.length; offset += 0x8000)
    binary += String.fromCharCode(...bytes.subarray(offset, Math.min(offset + 0x8000, bytes.length)));
  return btoa(binary);
}

function waitForPortMessage(capture, type, timeout = 2000) {
  if (capture.received.has(type)) {
    const message = capture.received.get(type);
    capture.received.delete(type);
    return Promise.resolve(message);
  }
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      capture.waiters.delete(type);
      reject(new Error(`The audio processor did not report ${type}.`));
    }, timeout);
    capture.waiters.set(type, {
      resolve: (message) => {
        clearTimeout(timer);
        capture.waiters.delete(type);
        resolve(message);
      },
      reject: (error) => {
        clearTimeout(timer);
        capture.waiters.delete(type);
        reject(error);
      }
    });
  });
}

function settleWaiter(capture, type, message) {
  const waiter = capture.waiters.get(type);
  if (waiter) waiter.resolve(message);
  else capture.received.set(type, message);
}

function notifyFailure(capture, error) {
  if (capture.failureReported || capture.stopping) return;
  capture.failureReported = true;
  const message = error?.message || "Chrome could not capture this tab.";
  stopCapture(capture).finally(() => chrome.runtime.sendMessage({
    target: "service-worker",
    type: "capture-error",
    instanceId: capture.instanceId,
    profileId: capture.profileId,
    sessionToken: capture.sessionToken,
    captureStreamKey: capture.captureStreamKey,
    tabId: capture.tabId,
    error: message
  }).catch(() => {}));
}

function handleWorkletMessage(capture, message) {
  if (!message || typeof message !== "object") {
    notifyFailure(capture, new Error("The audio processor sent an invalid message."));
    return;
  }
  if (message.type === "ready" || message.type === "started" || message.type === "stopped") {
    settleWaiter(capture, message.type, message);
    return;
  }
  if (message.type === "failure") {
    notifyFailure(capture, new Error(message.error || "The PCM queue exceeded its limit."));
    return;
  }
  if (message.type !== "pcm" || capture.state !== "active") {
    notifyFailure(capture, new Error("The audio processor produced PCM outside an active capture."));
    return;
  }
  if (!(message.data instanceof ArrayBuffer) || message.data.byteLength !== CHUNK_BYTES ||
      !Number.isInteger(message.sequence) || message.sequence !== capture.nextSequence ||
      capture.outstanding.size >= MAX_OUTSTANDING_CHUNKS) {
    notifyFailure(capture, new Error("The PCM chunk is malformed or the four-chunk queue is full."));
    return;
  }
  const sequence = message.sequence;
  capture.nextSequence++;
  capture.outstanding.add(sequence);
  chrome.runtime.sendMessage({
    target: "service-worker",
    type: "pcm",
    instanceId: capture.instanceId,
    profileId: capture.profileId,
    sessionToken: capture.sessionToken,
    captureStreamKey: capture.captureStreamKey,
    tabId: capture.tabId,
    sequence,
    data: toBase64(message.data)
  }).then((result) => {
    if (capture.stopping || !capture.outstanding.has(sequence)) return;
    capture.outstanding.delete(sequence);
    if (!result?.ok || result.sequence !== sequence) {
      notifyFailure(capture, new Error(result?.error || "The PCM chunk was not acknowledged."));
      return;
    }
    capture.processor.port.postMessage({ type: "ack", sequence });
  }).catch((error) => notifyFailure(capture, error));
}

async function prepareCapture(message) {
  if (!validIdentity(message) || typeof message.streamId !== "string" || !message.streamId)
    throw new Error("The selected tab capture request is invalid.");
  const key = captureKey(message);
  if (active.has(key) || [...active.values()].some((capture) =>
      capture.instanceId === message.instanceId && capture.profileId === message.profileId &&
      capture.sessionToken === message.sessionToken))
    throw new Error("This tab capture is already prepared.");
  let stream = null;
  let context = null;
  let source = null;
  let processor = null;
  try {
    stream = await navigator.mediaDevices.getUserMedia({
      audio: { mandatory: { chromeMediaSource: "tab", chromeMediaSourceId: message.streamId } },
      video: false
    });
    context = new AudioContext({ sampleRate: 48000 });
    if (context.sampleRate !== 48000) throw new Error("Chrome did not provide 48 kHz tab audio.");
    await context.audioWorklet.addModule("pcm-worklet.js");
    source = context.createMediaStreamSource(stream);
    processor = new AudioWorkletNode(context, "audio-matrix-pcm", {
      numberOfInputs: 1,
      numberOfOutputs: 1,
      outputChannelCount: [2]
    });
    const capture = {
      key,
      instanceId: message.instanceId,
      profileId: message.profileId,
      sessionToken: message.sessionToken,
      captureStreamKey: message.captureStreamKey,
      tabId: message.tabId,
      stream,
      context,
      source,
      processor,
      state: "preparing",
      nextSequence: 0,
      outstanding: new Set(),
      waiters: new Map(),
      received: new Map(),
      stopping: false,
      failureReported: false,
      trackEndedListener: null
    };
    active.set(key, capture);
    processor.port.onmessage = (event) => handleWorkletMessage(capture, event.data);
    processor.onprocessorerror = () => notifyFailure(capture, new Error("Chrome's PCM audio worklet failed."));
    source.connect(processor);
    processor.connect(context.destination);
    const track = stream.getAudioTracks()[0];
    if (!track) throw new Error("Chrome did not return a tab audio track.");
    capture.trackEndedListener = () => {
      if (!capture.stopping) notifyFailure(capture, new Error("The captured tab audio track ended unexpectedly."));
    };
    track.addEventListener("ended", capture.trackEndedListener);
    context.onstatechange = () => {
      if (capture.state === "active" && context.state !== "running" && !capture.stopping)
        notifyFailure(capture, new Error("The tab audio processing context stopped unexpectedly."));
    };
    const ready = waitForPortMessage(capture, "ready");
    await context.resume();
    if (context.state !== "running") throw new Error("Chrome did not resume the tab audio context.");
    await ready;
    if (capture.stopping || track.readyState !== "live")
      throw new Error("The captured tab audio track ended before capture was ready.");
    capture.state = "prepared";
    return { ok: true, trackState: track.readyState, contextState: context.state };
  } catch (error) {
    const capture = active.get(key);
    if (capture) {
      await stopCapture(capture);
    } else {
      if (stream) stream.getTracks().forEach((track) => track.stop());
      if (context) await context.close().catch(() => {});
      if (source) {
        try { source.disconnect(); } catch (_) {}
      }
      if (processor) {
        try { processor.disconnect(); } catch (_) {}
      }
    }
    throw error;
  }
}

async function commitCapture(message) {
  if (!validIdentity(message)) throw new Error("The selected tab capture identity is invalid.");
  const capture = active.get(captureKey(message));
  if (!capture || capture.state !== "prepared") throw new Error("The offscreen capture is not ready.");
  const started = waitForPortMessage(capture, "started");
  capture.processor.port.postMessage({ type: "start" });
  await started;
  capture.state = "active";
  return { ok: true, trackState: capture.stream.getAudioTracks()[0]?.readyState || "unknown",
    contextState: capture.context.state };
}

async function stopCapture(captureOrMessage) {
  const capture = captureOrMessage instanceof Object && captureOrMessage.key
    ? captureOrMessage
    : active.get(captureKey(captureOrMessage));
  if (!capture) return { ok: true, playbackRecovered: true, trackState: "ended", contextState: "closed" };
  if (capture.stopPromise) return capture.stopPromise;
  capture.stopPromise = (async () => {
    capture.stopping = true;
    capture.state = "stopping";
    const errors = [];
    if (capture.processor) {
      if (!capture.failureReported) {
        try {
          const stopped = waitForPortMessage(capture, "stopped", 500);
          capture.processor.port.postMessage({ type: "stop" });
          await stopped;
        } catch (error) {
          errors.push(error);
        }
      }
      capture.processor.port.onmessage = null;
      capture.processor.onprocessorerror = null;
    }
    if (capture.source) {
      try { capture.source.disconnect(); } catch (error) { errors.push(error); }
    }
    if (capture.processor) {
      try { capture.processor.disconnect(); } catch (error) { errors.push(error); }
    }
    const track = capture.stream?.getAudioTracks()[0];
    if (track && capture.trackEndedListener)
      track.removeEventListener("ended", capture.trackEndedListener);
    capture.stream?.getTracks().forEach((item) => {
      try { item.stop(); } catch (error) { errors.push(error); }
    });
    if (capture.context && capture.context.state !== "closed") {
      try { await capture.context.close(); } catch (error) { errors.push(error); }
    }
    capture.outstanding.clear();
    active.delete(capture.key);
    const trackState = track?.readyState || "ended";
    const contextState = capture.context?.state || "closed";
    return {
      ok: errors.length === 0,
      playbackRecovered: trackState === "ended" && contextState === "closed",
      trackState,
      contextState,
      error: errors[0]?.message || ""
    };
  })();
  return capture.stopPromise;
}

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (message?.target !== "offscreen") return false;
  if (message.action === "prepare") {
    prepareCapture(message).then(sendResponse)
      .catch((error) => sendResponse({ ok: false, error: error.message }));
    return true;
  }
  if (message.action === "commit") {
    commitCapture(message).then(sendResponse)
      .catch((error) => sendResponse({ ok: false, error: error.message }));
    return true;
  }
  if (message.action === "stop") {
    stopCapture(message).then(sendResponse)
      .catch((error) => sendResponse({ ok: false, error: error.message }));
    return true;
  }
  if (message.action === "stop-all") {
    Promise.all([...active.values()].map(stopCapture)).then(() => sendResponse({ ok: true }));
    return true;
  }
  if (message.action === "status") {
    sendResponse({
      ok: true,
      captures: [...active.values()].map((capture) => ({
        tabId: capture.tabId,
        sessionToken: capture.sessionToken,
        state: capture.state,
        trackState: capture.stream.getAudioTracks()[0]?.readyState || "unknown",
        contextState: capture.context.state
      }))
    });
    return false;
  }
  return false;
});
