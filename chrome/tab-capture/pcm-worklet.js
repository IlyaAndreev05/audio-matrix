class AudioMatrixPcmProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.frameCount = 0;
    this.chunkFrames = 4096;
    this.buffer = new Float32Array(this.chunkFrames * 2);
  }

  process(inputs, outputs) {
    const input = inputs[0] || [];
    const output = outputs[0] || [];
    for (const channel of output) channel.fill(0);
    if (input.length === 0 || input[0].length === 0) return true;
    const left = input[0];
    const right = input[1] || left;
    for (let frame = 0; frame < left.length; frame++) {
      const offset = this.frameCount * 2;
      this.buffer[offset] = left[frame];
      this.buffer[offset + 1] = right[frame];
      this.frameCount++;
      if (this.frameCount === this.chunkFrames) {
        this.port.postMessage(this.buffer.buffer, [this.buffer.buffer]);
        this.buffer = new Float32Array(this.chunkFrames * 2);
        this.frameCount = 0;
      }
    }
    return true;
  }
}

registerProcessor("audio-matrix-pcm", AudioMatrixPcmProcessor);
