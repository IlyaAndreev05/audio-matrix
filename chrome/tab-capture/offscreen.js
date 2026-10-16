const active = new Map();

function toBase64(buffer) {
  const bytes = new Uint8Array(buffer);
  let binary = "";
  for (let offset = 0; offset < bytes.length; offset += 0x8000)
    binary += String.fromCharCode(...bytes.subarray(offset, Math.min(offset + 0x8000, bytes.length)));
  return btoa(binary);
}

async function startCapture(tabId, streamId) {
  await stopCapture(tabId);
  const stream = await navigator.mediaDevices.getUserMedia({
    audio: { mandatory: { chromeMediaSource: "tab", chromeMediaSourceId: streamId } },
    video: false
  });
  let context = null;
  try {
    context = new AudioContext({ sampleRate: 48000 });
    if (context.sampleRate !== 48000) throw new Error("Chrome did not provide 48 kHz tab audio.");
    await context.audioWorklet.addModule("pcm-worklet.js");
    const source = context.createMediaStreamSource(stream);
    const processor = new AudioWorkletNode(context, "audio-matrix-pcm", { numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [2] });
    processor.port.onmessage = (event) => {
      chrome.runtime.sendMessage({ target: "offscreen", type: "pcm", tabId, data: toBase64(event.data) }).catch(() => {});
    };
    source.connect(processor);
    processor.connect(context.destination); // The worklet outputs silence; the captured tab is not played twice.
    await context.resume();
    active.set(tabId, { stream, context, source, processor });
  } catch (error) {
    stream.getTracks().forEach((track) => track.stop());
    if (context) await context.close().catch(() => {});
    throw error;
  }
}

async function stopCapture(tabId) {
  const capture = active.get(tabId);
  if (!capture) return;
  active.delete(tabId);
  capture.processor.port.onmessage = null;
  capture.source.disconnect();
  capture.processor.disconnect();
  capture.stream.getTracks().forEach((track) => track.stop());
  await capture.context.close().catch(() => {});
}

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (message.target !== "offscreen") return false;
  if (message.action === "start") {
    startCapture(Number(message.tabId), message.streamId).then(() => sendResponse({ ok: true }))
      .catch((error) => {
        chrome.runtime.sendMessage({ target: "offscreen", type: "capture-error", tabId: message.tabId, message: error.message }).catch(() => {});
        sendResponse({ ok: false, error: error.message });
      });
    return true;
  }
  if (message.action === "stop") {
    stopCapture(Number(message.tabId)).then(() => sendResponse({ ok: true }));
    return true;
  }
  if (message.action === "stop-all") {
    Promise.all([...active.keys()].map(stopCapture)).then(() => sendResponse({ ok: true }));
    return true;
  }
  return false;
});
