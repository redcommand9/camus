import assert from "node:assert/strict";
import { test } from "node:test";
import { adjacentPdfLeaf, buildPdfSequence, insertPdfNoteAfter } from "../lib/pdf-sequence.ts";

test("an inserted sheet becomes page 6, with original PDF page 6 shifted to page 7", () => {
  const sheet = { id: "sheet-1", afterPage: 5 };
  const sequence = buildPdfSequence(8, [sheet]);
  assert.equal(sequence[4].key, "page-5");
  assert.equal(sequence[5].key, "note-sheet-1");
  assert.equal(sequence[6].key, "page-6");
  assert.equal(adjacentPdfLeaf(sequence, "note-sheet-1", -1)?.key, "page-5");
  assert.equal(adjacentPdfLeaf(sequence, "note-sheet-1", 1)?.key, "page-6");
  assert.equal(adjacentPdfLeaf(sequence, "page-6", -1)?.key, "note-sheet-1");
  assert.equal(adjacentPdfLeaf(sequence, "page-5", 1)?.key, "note-sheet-1");
});

test("adding another sheet after a page or sheet preserves physical order", () => {
  const first = { id: "first", afterPage: 5 };
  const second = { id: "second", afterPage: 5 };
  const third = { id: "third", afterPage: 5 };
  const afterPage = insertPdfNoteAfter([first], second, "page-5");
  assert.deepEqual(buildPdfSequence(6, afterPage).slice(4).map((item) => item.key), [
    "page-5", "note-second", "note-first", "page-6",
  ]);
  const afterNote = insertPdfNoteAfter(afterPage, third, "note-second");
  assert.deepEqual(buildPdfSequence(6, afterNote).slice(4).map((item) => item.key), [
    "page-5", "note-second", "note-third", "note-first", "page-6",
  ]);
});
