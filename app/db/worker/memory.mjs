// The handler over in-memory databases, in-process: what the browser targets' tests open their databases with.
import sqlite3InitModule from "@sqlite.org/sqlite-wasm";
import { createHandler, memoryStore } from "./handler.mjs";

export async function createMemoryHandler() {
  const sqlite3 = await sqlite3InitModule();
  return createHandler(sqlite3, memoryStore(sqlite3));
}
