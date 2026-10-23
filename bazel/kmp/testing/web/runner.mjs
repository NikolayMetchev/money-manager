// Runs a Kotlin/JS or Kotlin/Wasm test program in Node and reports each test as a TeamCity service message.
import { existsSync } from "node:fs";
import { registerHooks } from "node:module";
import { pathToFileURL } from "node:url";

// The npm packages the program's klibs name (kotlinx-datetime's `@js-joda/core`) are laid out as npm installs them
// next to the program (web.bzl's node_modules), and its bare imports resolve from there.
const modules = process.argv[2].replace(/\/test\.mjs$/, "_node_modules");
if (existsSync(modules)) {
  const parentURL = pathToFileURL(`${modules}/program.mjs`).href;
  const bare = /^(?![./]|[a-z]+:)/;
  registerHooks({
    resolve: (specifier, context, next) => next(specifier, bare.test(specifier) ? { ...context, parentURL } : context),
  });
}

// A browser's global object is also `window`, and some libraries reach browser globals through it (Molecule's clock
// reads `window.performance`). Node has the same globals but no `window`, and none of the DOM, so libraries that
// look for a browser through `window.document` or `window.addEventListener` still see Node.
globalThis.window ??= globalThis;

const root = { name: "", suites: [], tests: [], skip: false };
let current = root;
const filter = process.env.TESTBRIDGE_TEST_ONLY || "";

function suite(name, fn, skip) {
  const s = { name, suites: [], tests: [], skip: skip || current.skip };
  current.suites.push(s);
  const parent = current;
  current = s;
  try { fn(); } finally { current = parent; }
}
function test(name, fn, skip) {
  current.tests.push({ name, fn, skip: skip || current.skip });
}
// kotlin-test's hook for wrapping its adapter: wasm reads the namespace, js calls the transformer when it's there.
globalThis.kotlinTest = { adapterTransformer: (adapter) => adapter };
globalThis.describe = (name, fn) => suite(name, fn, false);
globalThis.xdescribe = (name, fn) => suite(name, fn, true);
globalThis.it = (name, fn) => test(name, fn, false);
globalThis.xit = (name, fn) => test(name, fn, true);

const escape = (s) => String(s).replace(/[|'\[\]\n\r]/g, (c) => "|" + ({ "\n": "n", "\r": "r" }[c] || c));
const message = (type, attrs) =>
  console.log(`##teamcity[${type}${Object.entries(attrs).map(([k, v]) => ` ${k}='${escape(v)}'`).join("")}]`);

let failed = 0;
async function run(s, path) {
  const name = path.filter(Boolean).join(".");
  if (s.name) message("testSuiteStarted", { name });
  for (const t of s.tests) {
    if (filter && !`${name}#${t.name}`.includes(filter)) continue;
    if (t.skip) { message("testIgnored", { name: t.name }); continue; }
    message("testStarted", { name: t.name });
    const start = Date.now();
    try {
      await t.fn();
    } catch (e) {
      failed++;
      message("testFailed", { name: t.name, message: e?.message ?? String(e), details: e?.stack ?? String(e) });
    }
    message("testFinished", { name: t.name, duration: Date.now() - start });
  }
  for (const child of s.suites) await run(child, [...path, child.name]);
  if (s.name) message("testSuiteFinished", { name });
}

const program = await import(pathToFileURL(process.argv[2]).href);
program.startUnitTests?.();
await run(root, []);
process.exitCode = failed ? 1 : 0;
