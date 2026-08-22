/**
 * Exponentially weighted throughput estimate.
 *
 * A raw bytes/elapsed number jitters far too much to render — it swings with
 * every TCP window adjustment. Smoothing over a 250 ms window gives a figure
 * that still reacts within a second but does not strobe.
 */
export class SpeedMeter {
  #last = Date.now();
  #accumulated = 0;
  #rate = 0;

  add(bytes) {
    this.#accumulated += bytes;
    const now = Date.now();
    const elapsed = now - this.#last;
    if (elapsed >= 250) {
      const instant = (this.#accumulated * 1000) / elapsed;
      this.#rate = this.#rate === 0 ? instant : this.#rate * 0.7 + instant * 0.3;
      this.#accumulated = 0;
      this.#last = now;
    }
  }

  /** Bytes per second, or 0 once the stream has clearly stalled. */
  get bytesPerSecond() {
    if (Date.now() - this.#last > 2000) return 0;
    return Math.round(this.#rate);
  }
}
