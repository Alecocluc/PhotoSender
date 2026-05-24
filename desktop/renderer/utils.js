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
  if (!ts) return "—";
  const diff = (Date.now() - ts) / 1000;
  if (diff < 60) return "just now";
  if (diff < 3600) return `${Math.floor(diff / 60)} min${Math.floor(diff / 60) === 1 ? "" : "s"} ago`;
  if (diff < 86400) return `${Math.floor(diff / 3600)} hour${Math.floor(diff / 3600) === 1 ? "" : "s"} ago`;
  return new Date(ts).toLocaleDateString();
}

export function fmtFullTime(ts) { return ts ? new Date(ts).toLocaleString() : ""; }

export function fmtUptime(ms) {
  const t = Math.max(0, Math.floor(ms / 1000));
  const h = String(Math.floor(t / 3600)).padStart(2, "0");
  const m = String(Math.floor((t % 3600) / 60)).padStart(2, "0");
  const s = String(t % 60).padStart(2, "0");
  return `${h}:${m}:${s}`;
}

export function escHtml(s) {
  return String(s ?? "").replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c])
  );
}

export function entryName(e) { return e?.originalName || e?.fileName || "Untitled"; }
export function entryTime(e) { return e?.timestamp || e?.time || 0; }
export function isVideo(name) { return /\.(mp4|mov|avi|mkv|webm|m4v)$/i.test(name || ""); }

export function fileIcon(name) {
  if (isVideo(name)) return "videocam";
  if (/\.(heic|heif|jpe?g|png|gif|webp|tif?f|raw|nef|cr2|arw|dng)$/i.test(name || "")) return "image";
  if (/\.(wav|mp3|m4a|flac|aac|ogg)$/i.test(name || "")) return "graphic_eq";
  return "draft";
}

export function primaryIP(ips) {
  if (!ips || ips.length === 0) return "—";
  const sorted = [...ips].sort((a, b) => {
    const score = (ip) =>
      /^192\.168\./.test(ip) ? 0 : /^10\./.test(ip) ? 1 : /^172\./.test(ip) ? 2 : 3;
    return score(a) - score(b);
  });
  return sorted[0];
}
