// The browser's database worker: the handler over the opfs-sahpool VFS, which keeps each database as a file in the
// origin private file system. It needs no cross-origin isolation (COOP/COEP), so it runs on GitHub Pages.
//
// The pool holds its files' access handles exclusively, so a second tab can't open it: the worker takes a Web Lock
// first and answers every request with an error while another tab has it.
import sqlite3InitModule from "@sqlite.org/sqlite-wasm";
import { createHandler, opfsStore } from "./handler.mjs";

const LOCK = "money-manager-database";

const ready = new Promise((resolve, reject) => {
  navigator.locks.request(LOCK, { ifAvailable: true }, async (lock) => {
    if (!lock) {
      reject(new Error("Money Manager is already open in another tab"));
      return;
    }
    try {
      const sqlite3 = await sqlite3InitModule();
      const pool = await sqlite3.installOpfsSAHPoolVfs({ name: "money-manager" });
      resolve(createHandler(sqlite3, opfsStore(pool)));
    } catch (e) {
      reject(e);
      return;
    }
    // Held until the worker ends, with the page.
    await new Promise(() => {});
  });
});

self.onmessage = async ({ data }) => {
  let response;
  try {
    response = await (await ready).handle(data);
  } catch (e) {
    response = { id: data.id, error: String(e?.message ?? e) };
  }
  self.postMessage(response);
};
