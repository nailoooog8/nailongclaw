#!/usr/bin/env node
/**
 * Read pixels out of an Android `screencap` PNG without a vision model.
 *
 * A full 1260x2800 screenshot costs roughly 4,700 image tokens to look at and
 * still cannot settle "is this button red" — that is a sub-pixel colour
 * question. This answers it in milliseconds from numbers, which is why
 * QA_CHECKLIST's pixel evidence (H24, H28, E-series) is measured rather than
 * eyeballed. zlib is the only dependency.
 *
 * Usage:
 *   node scripts/pngpix.js --px 1178 2624 shot.png      # rgb at one point
 *   node scripts/pngpix.js --col 1178 shot.png          # vertical colour runs
 *   node scripts/pngpix.js --row 900 shot.png           # horizontal colour runs
 *   node scripts/pngpix.js --diff a.png b.png           # did the screen change?
 *   node scripts/pngpix.js --scale 0.5 shot.png out.png # shrink before vision
 *   node scripts/pngpix.js --capture --px 1178 2624     # pull from device first
 *
 * Reads a PNG file, or `-` / no file with --capture to stream one straight off
 * the device via `adb exec-out screencap -p` (no intermediate device file).
 */
import { inflateSync, deflateSync } from "node:zlib";
import { readFileSync, writeFileSync, realpathSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { spawn } from "node:child_process";

const PNG_SIG = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);

/** Paeth predictor, as specified by the PNG filter definitions. */
function paeth(a, b, c) {
  const p = a + b - c;
  const pa = Math.abs(p - a);
  const pb = Math.abs(p - b);
  const pc = Math.abs(p - c);
  if (pa <= pb && pa <= pc) return a;
  if (pb <= pc) return b;
  return c;
}

/**
 * Decode an 8-bit truecolour or truecolour+alpha PNG into raw RGBA rows.
 * Throws on anything else rather than silently misreading pixels — a wrong
 * colour is worse than a failure here.
 */
export function decodePng(buf) {
  if (!buf.subarray(0, 8).equals(PNG_SIG)) throw new Error("not a PNG");
  let offset = 8;
  let width = 0;
  let height = 0;
  let depth = 0;
  let colorType = 0;
  const idat = [];
  while (offset + 8 <= buf.length) {
    const length = buf.readUInt32BE(offset);
    const type = buf.toString("ascii", offset + 4, offset + 8);
    const data = buf.subarray(offset + 8, offset + 8 + length);
    if (type === "IHDR") {
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
      depth = data[8];
      colorType = data[9];
      if (data[12] !== 0) throw new Error("interlaced PNG not supported");
    } else if (type === "IDAT") {
      idat.push(Buffer.from(data));
    } else if (type === "IEND") {
      break;
    }
    offset += 12 + length;
  }
  if (depth !== 8) throw new Error(`bit depth ${depth} not supported`);
  const channels = colorType === 6 ? 4 : colorType === 2 ? 3 : 0;
  if (!channels) throw new Error(`colour type ${colorType} not supported`);
  if (!width || !height) throw new Error("PNG has no dimensions");

  const raw = inflateSync(Buffer.concat(idat));
  const stride = width * channels;
  const out = Buffer.alloc(stride * height);
  let pos = 0;
  for (let y = 0; y < height; y += 1) {
    const filter = raw[pos];
    pos += 1;
    const line = raw.subarray(pos, pos + stride);
    pos += stride;
    const cur = out.subarray(y * stride, (y + 1) * stride);
    const prev = y === 0 ? null : out.subarray((y - 1) * stride, y * stride);
    for (let x = 0; x < stride; x += 1) {
      const rawByte = line[x];
      const left = x >= channels ? cur[x - channels] : 0;
      const up = prev ? prev[x] : 0;
      const upLeft = prev && x >= channels ? prev[x - channels] : 0;
      let value;
      switch (filter) {
        case 0:
          value = rawByte;
          break;
        case 1:
          value = rawByte + left;
          break;
        case 2:
          value = rawByte + up;
          break;
        case 3:
          value = rawByte + ((left + up) >> 1);
          break;
        case 4:
          value = rawByte + paeth(left, up, upLeft);
          break;
        default:
          throw new Error(`unknown PNG filter ${filter} on row ${y}`);
      }
      cur[x] = value & 0xff;
    }
  }
  return { width, height, channels, data: out };
}

function crc32(buf) {
  let crc = 0xffffffff;
  for (let n = 0; n < buf.length; n += 1) {
    let c = (crc ^ buf[n]) & 0xff;
    for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    crc = c ^ (crc >>> 8);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([length, body, crc]);
}

/** Encode raw RGBA back to a PNG. Used only by --scale. */
export function encodePng(rgba, width, height) {
  const stride = width * 4;
  const raw = Buffer.alloc((stride + 1) * height);
  for (let y = 0; y < height; y += 1) {
    raw[y * (stride + 1)] = 0;
    rgba.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  return Buffer.concat([
    PNG_SIG,
    chunk("IHDR", ihdr),
    chunk("IDAT", deflateSync(raw, { level: 9 })),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}

function pixelAt(img, x, y) {
  if (x < 0 || y < 0 || x >= img.width || y >= img.height) return null;
  const i = (y * img.width + x) * img.channels;
  return [img.data[i], img.data[i + 1], img.data[i + 2]];
}

function key([r, g, b]) {
  return `${r},${g},${b}`;
}

/**
 * Collapse a scan line into runs of identical colour. Runs shorter than 2px are
 * dropped: on a real screenshot those are anti-aliasing edges, and printing
 * every one of them buries the structure the scan was for.
 */
function runs(getPixel, length, axisLabel) {
  const out = [];
  let start = 0;
  let current = getPixel(0);
  for (let i = 1; i <= length; i += 1) {
    const next = i < length ? getPixel(i) : null;
    if (!next || key(next) !== key(current)) {
      if (i - start >= 2) out.push(`${axisLabel}${start}..${i - 1} rgb(${key(current)})`);
      if (next) {
        current = next;
        start = i;
      }
    }
  }
  return out;
}

/**
 * Resolve adb the same way scripts/ui_probe.py does: SDK path first (reliable
 * even when the npm shim on PATH is broken again), then whatever is on PATH.
 */
function resolveAdb() {
  const roots = [
    process.env.ANDROID_SDK_ROOT,
    process.env.ANDROID_HOME,
    process.env.LOCALAPPDATA && `${process.env.LOCALAPPDATA}/Android/Sdk`,
  ].filter((root) => root && root.trim());
  for (const root of roots) {
    const candidate = `${root}/platform-tools/adb${process.platform === "win32" ? ".exe" : ""}`;
    if (existsSync(candidate)) return candidate;
  }
  return "adb";
}

function captureFromDevice() {
  return new Promise((resolve, reject) => {
    const child = spawn(resolveAdb(), ["exec-out", "screencap", "-p"], {
      stdio: ["ignore", "pipe", "pipe"],
    });
    const chunks = [];
    child.stdout.on("data", (c) => chunks.push(c));
    child.on("error", reject);
    child.on("close", (code) =>
      code === 0 ? resolve(Buffer.concat(chunks)) : reject(new Error(`screencap exited ${code}`)),
    );
  });
}

function readPng(path) {
  return decodePng(readFileSync(path));
}

async function main(argv) {
  // Flags that take values must consume them here, or a coordinate such as
  // `--px 100 100` leaves `100` behind and it gets mistaken for the PNG path.
  // Coordinates are integers; --scale is the one real-valued flag.
  const VALUED = {
    "--px": { count: 2, integer: true },
    "--col": { count: 1, integer: true },
    "--row": { count: 1, integer: true },
    "--scale": { count: 1, integer: false },
  };
  const BARE = new Set(["--capture", "--diff"]);
  const opts = {};
  const paths = [];
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (BARE.has(arg)) {
      opts[arg.slice(2)] = true;
    } else if (arg in VALUED) {
      const { count, integer } = VALUED[arg];
      const values = argv.slice(i + 1, i + 1 + count);
      if (values.length < count || values.some((v) => v === undefined || v.startsWith("--"))) {
        console.error(`${arg} needs ${count} value(s)`);
        return 2;
      }
      // Reject a non-numeric value here rather than letting a filename become
      // NaN and surfacing later as a confusing "no PNG path" error.
      if (values.some((v) => !Number.isFinite(Number(v)))) {
        console.error(`${arg} needs ${count} number(s), got: ${values.join(" ")}`);
        return 2;
      }
      // A fractional coordinate would be silently truncated by Buffer indexing,
      // which reads a neighbouring pixel and reports it as the answer.
      if (integer && values.some((v) => !Number.isInteger(Number(v)))) {
        console.error(`${arg} needs integer coordinate(s), got: ${values.join(" ")}`);
        return 2;
      }
      opts[arg.slice(2)] = values.map(Number);
      i += count;
    } else if (arg.startsWith("--")) {
      console.error(`unknown flag ${arg}`);
      return 2;
    } else {
      paths.push(arg);
    }
  }

  let buf;
  if (opts.capture) {
    buf = await captureFromDevice();
  } else if (paths.length) {
    buf = readFileSync(paths[0]);
  } else {
    console.error("give a PNG path, or --capture to pull one off the device");
    return 2;
  }

  if (opts.scale) {
    const src = decodePng(buf);
    const outPath = paths[1];
    const factor = opts.scale[0];
    if (!(factor > 0)) {
      console.error("--scale needs a factor > 0");
      return 2;
    }
    const w = Math.max(1, Math.round(src.width * factor));
    const h = Math.max(1, Math.round(src.height * factor));
    // Output is always RGBA. A 3-channel source has no alpha byte to copy, so
    // the buffer starts opaque and only the colour bytes are filled in —
    // hardcoding a 4-byte source stride here walks off the end of an RGB image.
    const dst = Buffer.alloc(w * h * 4, 255);
    const srcChannels = src.channels;
    // Nearest-neighbour: good enough for layout/flow checks, and it keeps the
    // mapping from output pixel back to device pixel exact for --px callers.
    for (let y = 0; y < h; y += 1) {
      const sy = Math.min(src.height - 1, Math.floor(y / factor));
      for (let x = 0; x < w; x += 1) {
        const sx = Math.min(src.width - 1, Math.floor(x / factor));
        const from = (sy * src.width + sx) * srcChannels;
        const to = (y * w + x) * 4;
        for (let c = 0; c < Math.min(srcChannels, 3); c += 1) dst[to + c] = src.data[from + c];
      }
    }
    const png = encodePng(dst, w, h);
    if (outPath) {
      writeFileSync(outPath, png);
      console.log(`${w}x${h} -> ${outPath} (${png.length} bytes, was ${buf.length})`);
    } else {
      process.stdout.write(png);
    }
    return 0;
  }

  if (opts.diff) {
    const [pathA, pathB] = paths;
    if (!pathA || !pathB) {
      console.error("--diff needs two PNG paths");
      return 2;
    }
    const a = decodePng(readFileSync(pathA));
    const b = decodePng(readFileSync(pathB));
    if (a.width !== b.width || a.height !== b.height) {
      console.log(`CHANGED size ${a.width}x${a.height} -> ${b.width}x${b.height}`);
      return 0;
    }
    let changed = 0;
    let minX = a.width;
    let minY = a.height;
    let maxX = -1;
    let maxY = -1;
    for (let y = 0; y < a.height; y += 1) {
      for (let x = 0; x < a.width; x += 1) {
        const i = (y * a.width + x) * a.channels;
        if (
          a.data[i] !== b.data[i] ||
          a.data[i + 1] !== b.data[i + 1] ||
          a.data[i + 2] !== b.data[i + 2]
        ) {
          changed += 1;
          if (x < minX) minX = x;
          if (y < minY) minY = y;
          if (x > maxX) maxX = x;
          if (y > maxY) maxY = y;
        }
      }
    }
    if (!changed) {
      console.log(`IDENTICAL ${a.width}x${a.height} — screen did not change`);
    } else {
      const pct = ((changed / (a.width * a.height)) * 100).toFixed(2);
      console.log(`CHANGED ${changed} px (${pct}%) in bbox [${minX},${minY}][${maxX},${maxY}]`);
    }
    return 0;
  }

  const img = decodePng(buf);

  if (opts.px) {
    const [x, y] = opts.px;
    const p = pixelAt(img, x, y);
    console.log(p ? `(${x},${y}) rgb(${key(p)})` : `(${x},${y}) out of bounds`);
    return 0;
  }
  if (opts.col) {
    const x = opts.col[0];
    if (x < 0 || x >= img.width) {
      console.error(`x=${x} is outside 0..${img.width - 1}`);
      return 2;
    }
    console.log(`column x=${x} of ${img.width}x${img.height}`);
    for (const line of runs((i) => pixelAt(img, x, i), img.height, "y")) console.log(`  ${line}`);
    return 0;
  }
  if (opts.row) {
    const y = opts.row[0];
    if (y < 0 || y >= img.height) {
      console.error(`y=${y} is outside 0..${img.height - 1}`);
      return 2;
    }
    console.log(`row y=${y} of ${img.width}x${img.height}`);
    for (const line of runs((i) => pixelAt(img, i, y), img.width, "x")) console.log(`  ${line}`);
    return 0;
  }

  console.log(`${img.width}x${img.height} ${img.channels === 4 ? "RGBA" : "RGB"}`);
  return 0;
}

/**
 * True when this file is the entry point, so importing it (tests, reuse) does
 * not run the CLI.
 */
function isEntryPoint() {
  const entry = process.argv[1];
  if (!entry) return false;
  try {
    return realpathSync(fileURLToPath(import.meta.url)) === realpathSync(entry);
  } catch {
    return false;
  }
}

if (isEntryPoint()) {
  main(process.argv.slice(2))
    .then((code) => process.exit(code ?? 0))
    .catch((error) => {
      console.error(error.message);
      process.exit(1);
    });
}
