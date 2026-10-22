// Serves a kt_web_dist site on 127.0.0.1: `node serve.mjs <dir> [port]`. Modules and workers need http, not file:.
import fs from "node:fs";
import http from "node:http";
import path from "node:path";

const [root, port = "8080"] = process.argv.slice(2);
const TYPES = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript",
  ".mjs": "text/javascript",
  ".json": "application/json",
  ".wasm": "application/wasm",
};

http
  .createServer((request, response) => {
    const url = new URL(request.url, "http://127.0.0.1");
    let file = path.join(root, path.normalize(decodeURIComponent(url.pathname)).replace(/^(\.\.[/\\])+/, ""));
    if (fs.existsSync(file) && fs.statSync(file).isDirectory()) file = path.join(file, "index.html");
    if (!fs.existsSync(file)) {
      response.writeHead(404).end("Not found");
      return;
    }
    response.writeHead(200, {
      "Content-Type": TYPES[path.extname(file)] ?? "application/octet-stream",
      "Cache-Control": "no-store",
    });
    fs.createReadStream(file).pipe(response);
  })
  .listen(Number(port), "127.0.0.1", () => console.log(`Serving ${root} at http://127.0.0.1:${port}/`));
