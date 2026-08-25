/**
 * Draw the Windows icon from the same geometry everything else uses.
 *
 * The mark is one shape defined in three places already — a clip-path in the
 * desktop stylesheet, a vector drawable on Android, and here. Rather than a
 * fourth hand-drawn copy, this reads the same four points and rasterises them,
 * so the .ico in the repository is a build artifact anyone can reproduce
 * instead of a binary that arrived from somewhere and cannot be checked.
 *
 * Run: node scripts/make-icon.js
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const OUTPUT = path.join(ROOT, 'assets', 'flyshare.ico');

// polygon(50% 6%, 96% 92%, 50% 68%, 4% 92%) — ui/app.css, .bar__mark
const MARK = [[0.50, 0.06], [0.96, 0.92], [0.50, 0.68], [0.04, 0.92]];

/**
 * How much of the square the mark fills. On Android the visible area of the
 * adaptive icon is 72 of 108 units and the mark occupies 50 of them; keeping
 * the same ratio here is what makes the two icons look like the same app
 * rather than two drawings of the same idea.
 */
const MARK_SCALE = 50 / 72;
const CORNER = 0.22;

const BACKGROUND = [0x1f, 0x4f, 0xe0];
const INK = [0xff, 0xff, 0xff];

// Windows 10 and 11 are the supported versions and both read PNG-compressed
// icon entries, which keeps a 256px frame at a few kilobytes instead of the
// 256 KB an uncompressed one would take.
const SIZES = [16, 24, 32, 48, 64, 128, 256];

// Four samples per axis: enough that a diagonal edge reads as smooth at 16px,
// cheap enough that the largest frame still renders instantly.
const SUPERSAMPLE = 4;

function insideRoundedSquare(x, y, size) {
  const r = size * CORNER;
  if (x < 0 || y < 0 || x > size || y > size) return false;
  const cx = Math.min(Math.max(x, r), size - r);
  const cy = Math.min(Math.max(y, r), size - r);
  const dx = x - cx;
  const dy = y - cy;
  return dx * dx + dy * dy <= r * r;
}

function insidePolygon(x, y, points) {
  let inside = false;
  for (let i = 0, j = points.length - 1; i < points.length; j = i, i += 1) {
    const [xi, yi] = points[i];
    const [xj, yj] = points[j];
    const straddles = (yi > y) !== (yj > y);
    if (straddles && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) inside = !inside;
  }
  return inside;
}

/** The mark's four points placed in a box of `side`, centred in `size`. */
function markPoints(size) {
  const side = size * MARK_SCALE;
  const left = (size - side) / 2;
  // 0.49 is the midpoint of the shape's own vertical extent (0.06 to 0.92),
  // so centring on that rather than on the box puts the mark optically centre.
  const top = size / 2 - 0.49 * side;
  return MARK.map(([px, py]) => [left + px * side, top + py * side]);
}

function render(size) {
  const points = markPoints(size);
  const pixels = Buffer.alloc(size * size * 4);
  const step = 1 / SUPERSAMPLE;
  const samples = SUPERSAMPLE * SUPERSAMPLE;

  for (let y = 0; y < size; y += 1) {
    for (let x = 0; x < size; x += 1) {
      let square = 0;
      let mark = 0;
      for (let sy = 0; sy < SUPERSAMPLE; sy += 1) {
        for (let sx = 0; sx < SUPERSAMPLE; sx += 1) {
          const px = x + (sx + 0.5) * step;
          const py = y + (sy + 0.5) * step;
          if (!insideRoundedSquare(px, py, size)) continue;
          square += 1;
          if (insidePolygon(px, py, points)) mark += 1;
        }
      }

      const at = (y * size + x) * 4;
      if (square === 0) continue;

      // The mark is only ever drawn where the square already is, so its
      // coverage is a fraction of the square's rather than of the pixel —
      // which is what keeps the rounded corners clean.
      const alpha = square / samples;
      const inked = mark / square;
      for (let c = 0; c < 3; c += 1) {
        pixels[at + c] = Math.round(BACKGROUND[c] + (INK[c] - BACKGROUND[c]) * inked);
      }
      pixels[at + 3] = Math.round(alpha * 255);
    }
  }
  return pixels;
}

// --- PNG ------------------------------------------------------------------

const CRC_TABLE = (() => {
  const table = new Int32Array(256);
  for (let n = 0; n < 256; n += 1) {
    let c = n;
    for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c;
  }
  return table;
})();

function crc32(buf) {
  let c = -1;
  for (const byte of buf) c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}

function chunk(type, data) {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, 'latin1'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([length, body, crc]);
}

function toPng(pixels, size) {
  const header = Buffer.alloc(13);
  header.writeUInt32BE(size, 0);
  header.writeUInt32BE(size, 4);
  header[8] = 8;   // bit depth
  header[9] = 6;   // RGBA
  header[10] = 0;  // deflate
  header[11] = 0;  // adaptive filtering
  header[12] = 0;  // no interlacing

  // One filter byte per scanline; the shapes are flat colour, so "none" plus
  // deflate is already within a few bytes of what filtering would win.
  const raw = Buffer.alloc(size * (size * 4 + 1));
  for (let y = 0; y < size; y += 1) {
    raw[y * (size * 4 + 1)] = 0;
    pixels.copy(raw, y * (size * 4 + 1) + 1, y * size * 4, (y + 1) * size * 4);
  }

  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', header),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

// --- ICO ------------------------------------------------------------------

function toIco(frames) {
  const directory = Buffer.alloc(6 + frames.length * 16);
  directory.writeUInt16LE(0, 0);              // reserved
  directory.writeUInt16LE(1, 2);              // 1 = icon
  directory.writeUInt16LE(frames.length, 4);

  let offset = directory.length;
  frames.forEach(({ size, png }, i) => {
    const at = 6 + i * 16;
    // 256 is stored as 0: the field is one byte and the format predates it.
    directory[at] = size === 256 ? 0 : size;
    directory[at + 1] = size === 256 ? 0 : size;
    directory[at + 2] = 0;                    // palette entries
    directory[at + 3] = 0;                    // reserved
    directory.writeUInt16LE(1, at + 4);       // colour planes
    directory.writeUInt16LE(32, at + 6);      // bits per pixel
    directory.writeUInt32LE(png.length, at + 8);
    directory.writeUInt32LE(offset, at + 12);
    offset += png.length;
  });

  return Buffer.concat([directory, ...frames.map((f) => f.png)]);
}

const frames = SIZES.map((size) => ({ size, png: toPng(render(size), size) }));
const ico = toIco(frames);

await fs.mkdir(path.dirname(OUTPUT), { recursive: true });
await fs.writeFile(OUTPUT, ico);

console.log(`wrote ${path.relative(ROOT, OUTPUT)}  ${(ico.length / 1024).toFixed(1)} KB`);
for (const { size, png } of frames) {
  console.log(`  ${String(size).padStart(3)}px  ${String(png.length).padStart(6)} bytes`);
}
