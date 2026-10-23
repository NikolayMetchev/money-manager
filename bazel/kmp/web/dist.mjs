// Lays out a browser site for kt_web_dist (web.bzl): each program in its own directory with the runtime jars' files
// beside it, the npm packages under node_modules, and the static files at the root. Browsers don't resolve bare
// imports (`@js-joda/core`), and a worker can't use an import map, so every bare import in the site's modules is
// rewritten to a relative path into node_modules, resolved as a bundler would for the browser.
//
// node dist.mjs <out> <manifest>, the manifest one tab-separated entry per line:
//   program <dir> <src dir>   a linked program's directory, copied to <out>/<dir>
//   jar <dir> <jar>           every file of the jar but META-INF, to <out>/<dir>
//   file <dest> <src>         one file, to <out>/<dest>
//   package <name>            an npm package laid out at <out>/node_modules/<name>
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";

const [out, manifest] = process.argv.slice(2);
const packages = new Set();

function write(dest, bytes) {
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  fs.writeFileSync(dest, bytes);
}

function copyTree(src, dest) {
  for (const entry of fs.readdirSync(src, { withFileTypes: true })) {
    const from = path.join(src, entry.name);
    const to = path.join(dest, entry.name);
    if (fs.statSync(from).isDirectory()) copyTree(from, to);
    else write(to, fs.readFileSync(from));
  }
}

// Just enough of a zip reader for a jar: the central directory, stored or deflated entries.
function unzip(jar, dest) {
  const zip = fs.readFileSync(jar);
  let end = zip.length - 22;
  while (zip.readUInt32LE(end) !== 0x06054b50) end--;
  let entry = zip.readUInt32LE(end + 16);
  for (let i = zip.readUInt16LE(end + 10); i > 0; i--) {
    const method = zip.readUInt16LE(entry + 10);
    const size = zip.readUInt32LE(entry + 20);
    const nameLength = zip.readUInt16LE(entry + 28);
    const next = entry + 46 + nameLength + zip.readUInt16LE(entry + 30) + zip.readUInt16LE(entry + 32);
    const name = zip.toString("utf8", entry + 46, entry + 46 + nameLength);
    const local = zip.readUInt32LE(entry + 42);
    if (!name.endsWith("/") && !name.startsWith("META-INF/")) {
      const start = local + 30 + zip.readUInt16LE(local + 26) + zip.readUInt16LE(local + 28);
      const data = zip.subarray(start, start + size);
      write(path.join(dest, name), method === 0 ? data : zlib.inflateRawSync(data));
    }
    entry = next;
  }
}

for (const line of fs.readFileSync(manifest, "utf8").split("\n").filter(Boolean)) {
  const [kind, a, b] = line.split("\t");
  if (kind === "program") copyTree(b, path.join(out, a));
  else if (kind === "jar") unzip(b, path.join(out, a));
  else if (kind === "file") write(path.join(out, a), fs.readFileSync(b));
  else if (kind === "package") packages.add(a);
  else throw new Error(`unknown manifest entry: ${line}`);
}

// A package's entry for the browser: its `exports` (the browser condition first), else `module`, else `main`.
function condition(target) {
  if (typeof target === "string" || target == null) return target;
  for (const key of ["browser", "import", "module", "default"]) {
    if (key in target) return condition(target[key]);
  }
  return undefined;
}

function resolve(specifier) {
  const scoped = specifier.startsWith("@");
  const parts = specifier.split("/");
  const name = parts.slice(0, scoped ? 2 : 1).join("/");
  if (!packages.has(name)) return undefined;
  const root = path.join(out, "node_modules", name);
  const json = JSON.parse(fs.readFileSync(path.join(root, "package.json"), "utf8"));
  const subpath = ["."].concat(parts.slice(scoped ? 2 : 1)).join("/");
  let entry;
  if (json.exports !== undefined) {
    const exports = typeof json.exports === "string" || !Object.keys(json.exports).some((k) => k.startsWith("."))
      ? { ".": json.exports }
      : json.exports;
    entry = condition(exports[subpath]);
  } else {
    entry = subpath === "." ? json.module ?? json.main ?? "index.js" : subpath;
  }
  if (entry === undefined) throw new Error(`${name} exports nothing for ${subpath}`);
  return path.join(root, entry);
}

// Static imports and re-exports (`from '…'`, `import '…'`) and literal dynamic imports. A bare name no package
// provides (a Node-only import such as ktor's `ws`) is left as it is: it only runs in Node.
const IMPORT = /(\bfrom\s*|\bimport\s*\(\s*|\bimport\s+)(["'])([@\w][^"'\s]*)\2/g;

function rewrite(file) {
  const source = fs.readFileSync(file, "utf8");
  const result = source.replace(IMPORT, (match, keyword, quote, specifier) => {
    const target = resolve(specifier);
    if (target === undefined) return match;
    let relative = path.relative(path.dirname(file), target).split(path.sep).join("/");
    if (!relative.startsWith(".")) relative = "./" + relative;
    return keyword + quote + relative + quote;
  });
  if (result !== source) fs.writeFileSync(file, result);
}

function walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const file = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(file);
    else if (/\.m?js$/.test(entry.name)) rewrite(file);
  }
}

walk(out);
