const QR_VERSION = 2;
const QR_SIZE = 25;
const DATA_CODEWORDS = 34;
const ECC_CODEWORDS = 10;

const EXP = new Array(512);
const LOG = new Array(256);

let value = 1;
for (let i = 0; i < 255; i++) {
  EXP[i] = value;
  LOG[value] = i;
  value <<= 1;
  if (value & 0x100) value ^= 0x11d;
}
for (let i = 255; i < 512; i++) EXP[i] = EXP[i - 255];

function gfMul(a, b) {
  if (a === 0 || b === 0) return 0;
  return EXP[LOG[a] + LOG[b]];
}

function generatorPoly(degree) {
  let poly = [1];
  for (let i = 0; i < degree; i++) {
    const next = new Array(poly.length + 1).fill(0);
    for (let j = 0; j < poly.length; j++) {
      next[j] ^= poly[j];
      next[j + 1] ^= gfMul(poly[j], EXP[i]);
    }
    poly = next;
  }
  return poly;
}

function reedSolomon(data) {
  const gen = generatorPoly(ECC_CODEWORDS);
  const rem = new Array(ECC_CODEWORDS).fill(0);
  for (const byte of data) {
    const factor = byte ^ rem.shift();
    rem.push(0);
    for (let i = 0; i < ECC_CODEWORDS; i++) {
      rem[i] ^= gfMul(gen[i + 1], factor);
    }
  }
  return rem;
}

function bytesToDataCodewords(text) {
  const bytes = [...new TextEncoder().encode(text)];
  if (bytes.length > 32) {
    throw new Error("Pairing address is too long for the compact QR encoder.");
  }

  const bits = [];
  const pushBits = (val, len) => {
    for (let i = len - 1; i >= 0; i--) bits.push((val >>> i) & 1);
  };

  pushBits(0x4, 4); // byte mode
  pushBits(bytes.length, 8);
  bytes.forEach((byte) => pushBits(byte, 8));
  const terminator = Math.min(4, DATA_CODEWORDS * 8 - bits.length);
  for (let i = 0; i < terminator; i++) bits.push(0);
  while (bits.length % 8 !== 0) bits.push(0);

  const codewords = [];
  for (let i = 0; i < bits.length; i += 8) {
    codewords.push(bits.slice(i, i + 8).reduce((acc, bit) => (acc << 1) | bit, 0));
  }
  for (let pad = 0xec; codewords.length < DATA_CODEWORDS; pad = pad === 0xec ? 0x11 : 0xec) {
    codewords.push(pad);
  }
  return codewords;
}

function makeMatrix() {
  const modules = Array.from({ length: QR_SIZE }, () => Array(QR_SIZE).fill(false));
  const reserved = Array.from({ length: QR_SIZE }, () => Array(QR_SIZE).fill(false));

  const set = (row, col, dark, reserve = true) => {
    if (row < 0 || row >= QR_SIZE || col < 0 || col >= QR_SIZE) return;
    modules[row][col] = !!dark;
    if (reserve) reserved[row][col] = true;
  };

  const finder = (row, col) => {
    for (let y = -1; y <= 7; y++) {
      for (let x = -1; x <= 7; x++) {
        const r = row + y;
        const c = col + x;
        const separator = x === -1 || x === 7 || y === -1 || y === 7;
        const edge = x === 0 || x === 6 || y === 0 || y === 6;
        const core = x >= 2 && x <= 4 && y >= 2 && y <= 4;
        set(r, c, !separator && (edge || core));
      }
    }
  };

  finder(0, 0);
  finder(0, QR_SIZE - 7);
  finder(QR_SIZE - 7, 0);

  for (let i = 8; i < QR_SIZE - 8; i++) {
    set(6, i, i % 2 === 0);
    set(i, 6, i % 2 === 0);
  }

  for (let y = -2; y <= 2; y++) {
    for (let x = -2; x <= 2; x++) {
      const dist = Math.max(Math.abs(x), Math.abs(y));
      set(18 + y, 18 + x, dist !== 1);
    }
  }

  set(4 * QR_VERSION + 9, 8, true);

  const reserveFormat = () => {
    for (let i = 0; i <= 5; i++) set(i, 8, false);
    set(7, 8, false);
    set(8, 8, false);
    set(8, 7, false);
    for (let i = 9; i < 15; i++) set(8, 14 - i, false);
    for (let i = 0; i < 8; i++) set(8, QR_SIZE - 1 - i, false);
    for (let i = 8; i < 15; i++) set(QR_SIZE - 15 + i, 8, false);
    set(QR_SIZE - 8, 8, true);
  };
  reserveFormat();

  return { modules, reserved, set };
}

function formatBits(mask) {
  const eclLow = 1;
  const data = (eclLow << 3) | mask;
  let rem = data << 10;
  for (let i = 14; i >= 10; i--) {
    if (((rem >>> i) & 1) !== 0) rem ^= 0x537 << (i - 10);
  }
  return ((data << 10) | rem) ^ 0x5412;
}

function drawFormatBits(set, mask) {
  const bits = formatBits(mask);
  const bit = (i) => ((bits >>> i) & 1) !== 0;

  for (let i = 0; i <= 5; i++) set(i, 8, bit(i));
  set(7, 8, bit(6));
  set(8, 8, bit(7));
  set(8, 7, bit(8));
  for (let i = 9; i < 15; i++) set(8, 14 - i, bit(i));

  for (let i = 0; i < 8; i++) set(8, QR_SIZE - 1 - i, bit(i));
  for (let i = 8; i < 15; i++) set(QR_SIZE - 15 + i, 8, bit(i));
  set(QR_SIZE - 8, 8, true);
}

function encodeQr(text) {
  const data = bytesToDataCodewords(text);
  const codewords = [...data, ...reedSolomon(data)];
  const bits = [];
  codewords.forEach((byte) => {
    for (let i = 7; i >= 0; i--) bits.push((byte >>> i) & 1);
  });

  const { modules, reserved, set } = makeMatrix();
  const mask = (row, col) => (row + col) % 2 === 0;
  let bitIndex = 0;
  let upward = true;

  for (let col = QR_SIZE - 1; col > 0; col -= 2) {
    if (col === 6) col--;
    for (let rowOffset = 0; rowOffset < QR_SIZE; rowOffset++) {
      const row = upward ? QR_SIZE - 1 - rowOffset : rowOffset;
      for (let c = col; c >= col - 1; c--) {
        if (reserved[row][c]) continue;
        let dark = bitIndex < bits.length ? bits[bitIndex++] === 1 : false;
        if (mask(row, c)) dark = !dark;
        modules[row][c] = dark;
      }
    }
    upward = !upward;
  }

  drawFormatBits(set, 0);
  return modules;
}

export function renderQrCode(canvas, text) {
  const ctx = canvas.getContext("2d");
  const modules = encodeQr(text);
  const quiet = 4;
  const scale = 8;
  const size = (QR_SIZE + quiet * 2) * scale;

  canvas.width = size;
  canvas.height = size;
  ctx.fillStyle = "#ffffff";
  ctx.fillRect(0, 0, size, size);
  ctx.fillStyle = "#151412";

  modules.forEach((row, r) => {
    row.forEach((dark, c) => {
      if (dark) ctx.fillRect((c + quiet) * scale, (r + quiet) * scale, scale, scale);
    });
  });
}
