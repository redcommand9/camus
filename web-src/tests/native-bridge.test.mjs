import assert from "node:assert/strict";
import { test } from "node:test";
import { getLocalBook, listLocalBooks, nativeBookKey, removeLocalBook, saveLocalBook } from "../lib/local-library.ts";
import { readReaderState, writeReaderState, removeReaderState } from "../lib/reader-state.ts";

test("Android bridge lists, opens and saves books without using browser storage", async () => {
  const entry = { key: "camus-reader:android:example", name: "example.epub", title: "Example", kind: "epub", addedAt: 1, lastOpenedAt: 2 };
  const books = new Map([[entry.key, entry]]);
  const marks = new Map();
  const originalFetch = globalThis.fetch;
  globalThis.window = { CamusReaderNative: {
    listBooks: () => JSON.stringify([...books.values()]),
    book: (key) => JSON.stringify(books.get(key) ?? null),
    saveBook: (json) => { const record = JSON.parse(json); books.set(record.key, record); return true; },
    removeBook: (key) => { books.delete(key); },
    getState: (key) => marks.get(key) ?? null,
    setState: (key, value) => { marks.set(key, value); },
    removeState: (key) => { marks.delete(key); },
  } };
  globalThis.fetch = async () => new Response(new Blob(["book bytes"]), { status: 200 });
  try {
    const shelf = await listLocalBooks();
    assert.equal(shelf.length, 1);
    assert.equal(shelf[0].title, "Example");
    const opened = await getLocalBook(entry.key);
    assert.equal(await opened.file.text(), "book bytes");
    assert.equal(nativeBookKey(opened.file), entry.key);
    await saveLocalBook({ ...opened, title: "Example, updated" });
    assert.equal(books.get(entry.key).title, "Example, updated");
    assert.equal(readReaderState(entry.key), null);
    writeReaderState(entry.key, JSON.stringify({ notes: [{ title: "Saved" }] }));
    assert.equal(JSON.parse(readReaderState(entry.key)).notes[0].title, "Saved");
    removeReaderState(entry.key);
    assert.equal(readReaderState(entry.key), null);
    await removeLocalBook(entry.key);
    assert.equal((await listLocalBooks()).length, 0);
  } finally {
    globalThis.fetch = originalFetch;
    delete globalThis.window;
  }
});
