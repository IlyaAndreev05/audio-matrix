class AudioMatrixPcmProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    if (sampleRate !== 48000) throw new Error("Audio Matrix PCM capture requires 48 kHz.");
    this.chunkFrames = 512;
    this.frameCount = 0;
    this.buffer = new Float32Array(this.chunkFrames * 2);
    this.nextSequence = 0;
    this.outstanding = new Set();
    this.enabled = false;
    this.stopped = false;
    this.failed = false;
    this.port.onmessage = (event) => this.handleControl(event.data);
    this.port.postMessage({ type: "ready" });
  }

  handleControl(message) {
    if (!message || typeof message !== "object") return;
    if (message.type === "start" && !this.stopped && !this.failed) {
      this.enabled = true;
      this.port.postMessage({ type: "started" });
      return;
    }
    if (message.type === "ack") {
      if (!Number.isInteger(message.sequence) || !this.outstanding.delete(message.sequence)) {
        this.fail("The PCM acknowledgement did not match an outstanding chunk.");
      }
      return;
    }
    if (message.type === "stop") {
      this.enabled = false;
      this.stopped = true;
      this.outstanding.clear();
      this.frameCount = 0;
      this.port.postMessage({ type: "stopped" });
    }
  }

  fail(error) {
    if (this.failed || this.stopped) return;
    this.failed = true;
    this.enabled = false;
    this.port.postMessage({ type: "failure", error });
  }

  process(inputs, outputs) {
    const output = outputs[0] || [];
    for (const channel of output) channel.fill(0);
    if (this.stopped || this.failed) return false;
    if (!this.enabled) return true;
    const input = inputs[0] || [];
    if (input.length === 0 || input[0].length === 0) return true;
    const left = input[0];
    const right = input[1] || left;
    for (let frame = 0; frame < left.length; frame++) {
      const offset = this.frameCount * 2;
      this.buffer[offset] = left[frame];
      this.buffer[offset + 1] = right[frame];
      this.frameCount++;
      if (this.frameCount !== this.chunkFrames) continue;
      if (this.outstanding.size >= 4) {
        this.fail("The PCM queue exceeded four unacknowledged 512-frame chunks.");
        return false;
      }
      const sequence = this.nextSequence++;
      this.outstanding.add(sequence);
      const chunk = this.buffer.buffer;
      this.port.postMessage({ type: "pcm", sequence, data: chunk }, [chunk]);
      this.buffer = new Float32Array(this.chunkFrames * 2);
      this.frameCount = 0;
    }
    return true;
  }
}

registerProcessor("audio-matrix-pcm", AudioMatrixPcmProcessor);
