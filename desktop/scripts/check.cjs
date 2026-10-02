const fs = require("node:fs");
const path = require("node:path");
const { spawnSync } = require("node:child_process");
const root = path.resolve(__dirname, "..");
let count = 0;
function walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (["node_modules", "out", "dist", ".git"].includes(entry.name)) continue;
    const file = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(file);
    else if (/\.(?:js|cjs|mjs)$/.test(file)) {
      const rendererModule = file.startsWith(path.join(root, "renderer") + path.sep);
      const result = spawnSync(process.execPath, rendererModule ? ["--input-type=module", "--check"] : ["--check", file], {
        encoding: "utf8",
        ...(rendererModule ? { input: fs.readFileSync(file, "utf8") } : {}),
      });
      if (result.status !== 0) {
        process.stderr.write(`${path.relative(root, file)}: ${result.error?.message || result.stderr || "Syntax check failed"}\n`);
        process.exitCode = 1;
      }
      count++;
    }
  }
}
walk(root);
console.log(`Checked ${count} JavaScript modules.`);
