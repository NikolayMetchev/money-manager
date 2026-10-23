// Starts the Kotlin/Wasm build where the browser runs WebAssembly GC, and the Kotlin/JS build everywhere else.
// `?target=js` (or `?target=wasmJs`) picks one by hand.

// The smallest module declaring a GC struct type: only a browser with WasmGC validates it.
const WASM_GC = new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0, 1, 5, 1, 95, 1, 120, 0]);

function chooseTarget() {
  const forced = new URLSearchParams(location.search).get("target");
  if (forced === "js" || forced === "wasmJs") return forced;
  return typeof WebAssembly === "object" && WebAssembly.validate(WASM_GC) ? "wasmJs" : "js";
}

const status = document.getElementById("status");
const target = chooseTarget();
// The program reads it back to show which build is running next to the version.
document.documentElement.dataset.target = target;
console.info(`Money Manager: ${target}`);
try {
  // Each program renders into the page's body on import; the status line goes once it starts.
  await import(`./${target}/money-manager.mjs`);
  status.remove();
} catch (error) {
  console.error(error);
  status.textContent = `Money Manager could not start (${target}): ${error}`;
}
