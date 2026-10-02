export function fmtBytes(bytes) {
  if (!bytes || bytes <= 0) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let i = 0, n = bytes;
  while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; }
  return `${n.toFixed(n >= 10 || i === 0 ? 0 : 1)} ${units[i]}`;
}

export function fmtSpeed(bps) {
  if (!bps || bps <= 0) return { v: "0", u: "B/s" };
  const units = ["B/s", "KB/s", "MB/s", "GB/s"];
  let i = 0, n = bps;
  while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; }
  return { v: n.toFixed(n >= 100 ? 0 : 1), u: units[i] };
}

export function fmtTime(ts) {
  if (!ts) return "-";
  const diff = (Date.now() - ts) / 1000;
  if (diff < 60) return "just now";
  if (diff < 3600) return `${Math.floor(diff / 60)} min ago`;
  if (diff < 86400) return `${Math.floor(diff / 3600)} h ago`;
  return new Date(ts).toLocaleDateString();
}

export function fmtFullTime(ts) { return ts ? new Date(ts).toLocaleString() : ""; }

/** "14:02" in the user's locale. */
export function fmtClock(ts) {
  return ts ? new Date(ts).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" }) : "";
}

/** "OCT 1" style stamp date, locale aware, upper-cased by CSS where needed. */
export function fmtStampDate(ts) {
  return ts ? new Date(ts).toLocaleDateString(undefined, { month: "short", day: "numeric" }) : "";
}

export function fmtUptime(ms) {
  const t = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(t / 3600);
  const m = Math.floor((t % 3600) / 60);
  if (h > 0) return `${h} h ${m} min`;
  if (m > 0) return `${m} min`;
  return `${t} s`;
}

export function escHtml(s) {
  return String(s ?? "").replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c])
  );
}

export function entryName(e) { return e?.originalName || e?.fileName || "Untitled"; }
export function entryTime(e) { return e?.timestamp || e?.time || 0; }
export function entryKey(e) { return `${e?.bucketName || ""}/${entryName(e)}/${entryTime(e)}`; }
export function isVideo(name) { return /\.(mp4|mov|avi|mkv|webm|m4v|3gp)$/i.test(name || ""); }
export function isPhoto(name) { return /\.(heic|heif|jpe?g|png|gif|webp|tif?f|bmp|raw|nef|cr2|arw|dng)$/i.test(name || ""); }

/** "photo" | "video" | "other". */
export function entryKind(e) {
  const name = entryName(e);
  if (isVideo(name)) return "video";
  if (isPhoto(name)) return "photo";
  return "other";
}

/** Phosphor icon name for a file. */
export function fileIcon(name) {
  if (isVideo(name)) return "video-camera";
  if (isPhoto(name)) return "image";
  return "file";
}

/** "1,284" with the user's grouping. */
export function n(v) { return Number(v || 0).toLocaleString(); }

/** "1 photo" / "3 photos". */
export function plural(count, one, many = `${one}s`) {
  return `${n(count)} ${count === 1 ? one : many}`;
}

// Compact, scheme-less QR payload the phone scans: "ip:port?t=token". Kept short so it fits the
// tiny built-in QR encoder's byte budget. Token gates destructive (delete) operations on the PC.
export function qrPayloadFor(ip, port, token) {
  if (!ip || ip === "-") return "";
  const base = `${ip}:${port}`;
  return token ? `${base}?t=${token}` : base;
}

export function sortedIPs(ips) {
  const score = (ip) => (/^192\.168\./.test(ip) ? 0 : /^10\./.test(ip) ? 1 : /^172\./.test(ip) ? 2 : 3);
  return [...(ips || [])].sort((a, b) => score(a) - score(b));
}

export function primaryIP(ips) {
  const sorted = sortedIPs(ips);
  return sorted[0] || "-";
}

export function sameDay(a, b) {
  const x = new Date(a), y = new Date(b);
  return x.getFullYear() === y.getFullYear() && x.getMonth() === y.getMonth() && x.getDate() === y.getDate();
}

/** "Today", "Yesterday", or "Mon 29 Sep" style. */
export function dayLabel(ts) {
  if (!ts) return "Earlier";
  const now = Date.now();
  if (sameDay(ts, now)) return "Today";
  if (sameDay(ts, now - 86400000)) return "Yesterday";
  return new Date(ts).toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short", year: new Date(ts).getFullYear() === new Date().getFullYear() ? undefined : "numeric" });
}
