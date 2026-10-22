// The browser's database: SQLite (sqlite-wasm) behind one message per request, which the Kotlin driver
// (`WebSqliteDriver`, app/db/core) sends. The worker (worker.mjs) runs it over OPFS; tests run it in-process over
// memory (memory.mjs), so both share every line here.
//
// Request:  {id, type, db, sql?, params?, types?, bytes?}, where type is one of
//   open, close, exists, delete   a database by name (opening a missing one creates it, empty)
//   exec                          one statement, with its parameters
//   size, read                    a database's size and bytes, open or not
//   snapshot                      an open database's bytes, compacted
//   restore                       replaces a closed database with `bytes`
// Response: {id, error} or {id, rows?, changes?, exists?, bytes?, size?}
//
// 64-bit integers never become a JavaScript number, which holds only 53 bits (an amount is stored at 1e18 scale):
// a parameter of type "i" arrives as decimal text and binds as a BigInt, and an INTEGER column returns as SQLite's
// own decimal text. A REAL column returns as text too (JavaScript's shortest round-trip form), so every value in a
// row is null, a string or an Int8Array (a BLOB).

export function createHandler(sqlite3, store) {
  if (!sqlite3.wasm.bigIntEnabled) throw new Error("sqlite-wasm was built without BigInt support");
  const capi = sqlite3.capi;
  const open = new Map();

  const database = (name) => {
    const db = open.get(name);
    if (!db) throw new Error(`Database ${name} is not open`);
    return db;
  };

  const bind = (value, type) => {
    switch (type) {
      case "n": return null;
      case "i": return BigInt(value);
      case "r": return Number(value);
      case "t": return value;
      case "b": return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
      default: throw new Error(`Unknown parameter type ${type}`);
    }
  };

  const column = (stmt, index) => {
    switch (capi.sqlite3_column_type(stmt.pointer, index)) {
      case capi.SQLITE_NULL: return null;
      case capi.SQLITE_INTEGER: return capi.sqlite3_column_text(stmt.pointer, index);
      case capi.SQLITE_FLOAT: return String(capi.sqlite3_column_double(stmt.pointer, index));
      case capi.SQLITE_BLOB: {
        const bytes = stmt.get(index, capi.SQLITE_BLOB);
        return new Int8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength);
      }
      default: return stmt.get(index, capi.SQLITE_TEXT);
    }
  };

  const exec = (db, sql, params = [], types = "") => {
    const stmt = db.prepare(sql);
    try {
      if (params.length > 0) stmt.bind(params.map((value, i) => bind(value, types[i])));
      const rows = [];
      while (stmt.step()) {
        const row = new Array(stmt.columnCount);
        for (let i = 0; i < row.length; i++) row[i] = column(stmt, i);
        rows.push(row);
      }
      // sqlite3_changes still holds the last INSERT, UPDATE or DELETE's count after any other statement.
      return { rows, changes: stmt.isReadOnly() ? 0 : capi.sqlite3_changes(db.pointer) };
    } finally {
      stmt.finalize();
    }
  };

  // A compacted copy, as VACUUM INTO makes on the other platforms: vacuumed in memory, so the live file is untouched.
  const snapshot = (db) => {
    const copy = deserialize(sqlite3, capi.sqlite3_js_db_export(db.pointer));
    try {
      copy.exec("VACUUM");
      return capi.sqlite3_js_db_export(copy.pointer);
    } finally {
      copy.close();
    }
  };

  const close = (name) => {
    const db = open.get(name);
    if (db) {
      open.delete(name);
      store.close(name, db);
    }
  };

  const handlers = {
    open: ({ db }) => {
      if (!open.has(db)) open.set(db, store.open(db));
      return {};
    },
    close: ({ db }) => {
      close(db);
      return {};
    },
    exec: ({ db, sql, params, types }) => exec(database(db), sql, params, types),
    exists: ({ db }) => ({ exists: open.has(db) || store.exists(db) }),
    list: () => ({ names: store.list() }),
    delete: ({ db }) => {
      const handle = open.get(db);
      if (handle) {
        open.delete(db);
        handle.close();
      }
      store.delete(db);
      return {};
    },
    size: ({ db }) => {
      if (open.has(db)) {
        const [[pages]] = exec(database(db), "PRAGMA page_count").rows;
        const [[pageSize]] = exec(database(db), "PRAGMA page_size").rows;
        return { size: Number(pages) * Number(pageSize) };
      }
      return { size: store.exists(db) ? store.read(db).byteLength : null };
    },
    read: ({ db }) => {
      if (!open.has(db) && !store.exists(db)) throw new Error(`No database ${db}`);
      return { bytes: toInt8(open.has(db) ? capi.sqlite3_js_db_export(database(db).pointer) : store.read(db)) };
    },
    snapshot: ({ db }) => ({ bytes: toInt8(snapshot(database(db))) }),
    restore: async ({ db, bytes }) => {
      if (open.has(db)) throw new Error(`Database ${db} is open`);
      await store.write(db, new Uint8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength));
      return {};
    },
  };

  return {
    async handle(request) {
      try {
        const handler = handlers[request.type];
        if (!handler) throw new Error(`Unknown request ${request.type}`);
        return { id: request.id, ...(await handler(request)) };
      } catch (e) {
        return { id: request.id, error: String(e?.message ?? e) };
      }
    },
  };
}

const toInt8 = (bytes) => new Int8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength);

// A new in-memory database holding `bytes` (a whole database file).
export function deserialize(sqlite3, bytes) {
  const { capi, wasm } = sqlite3;
  const db = new sqlite3.oo1.DB(":memory:");
  try {
    const pointer = wasm.allocFromTypedArray(bytes);
    const flags = capi.SQLITE_DESERIALIZE_FREEONCLOSE | capi.SQLITE_DESERIALIZE_RESIZEABLE;
    db.checkRc(capi.sqlite3_deserialize(db.pointer, "main", pointer, bytes.byteLength, bytes.byteLength, flags));
    return db;
  } catch (e) {
    db.close();
    throw e;
  }
}

// Databases as files in the opfs-sahpool VFS's pool (`pool` is its utility object). A database open in one tab
// holds the pool's access handles, so the worker takes a Web Lock before installing it.
export function opfsStore(pool) {
  // Opening a file stores it under its name resolved as a URL path ("a.db" is "/a.db"), but the pool's other calls
  // (importDb, exportFile, unlink, getFileNames) take that stored path as given, so every name is resolved first.
  // Otherwise a restore writes a second file beside the one the database opens.
  const path = (name) => new URL(name, "file://localhost/").pathname;
  const exists = (name) => pool.getFileNames().includes(path(name));
  return {
    // The databases, not their journals.
    list: () => pool.getFileNames().filter((name) => !name.endsWith("-journal")),
    open: (name) => {
      const db = new pool.OpfsSAHPoolDb(name);
      // The pool has no WAL (no shared memory between handles), and a rollback journal it keeps itself.
      db.exec("PRAGMA journal_mode = DELETE");
      return db;
    },
    close: (name, db) => db.close(),
    exists,
    delete: (name) => {
      for (const file of [name, `${name}-journal`]) if (exists(file)) pool.unlink(path(file));
    },
    read: (name) => pool.exportFile(path(name)),
    write: (name, bytes) => pool.importDb(path(name), bytes),
  };
}

// Databases in memory, by name, for tests: a closed database is kept as its bytes, so it reopens with its data.
export function memoryStore(sqlite3) {
  const files = new Map();
  return {
    open: (name) => {
      const bytes = files.get(name);
      const db = bytes && bytes.byteLength > 0 ? deserialize(sqlite3, bytes) : new sqlite3.oo1.DB(":memory:");
      files.set(name, files.get(name) ?? new Uint8Array(0));
      return db;
    },
    close: (name, db) => {
      files.set(name, sqlite3.capi.sqlite3_js_db_export(db.pointer));
      db.close();
    },
    exists: (name) => files.has(name),
    list: () => [...files.keys()],
    delete: (name) => files.delete(name),
    read: (name) => files.get(name),
    write: (name, bytes) => files.set(name, bytes.slice()),
  };
}
