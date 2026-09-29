import assert from "node:assert/strict";
import { test } from "node:test";
import { epubHighlightsOverlap, pdfHighlightsOverlap } from "../lib/highlight-overlap.ts";

globalThis.Range ??= { START_TO_END: 1, END_TO_START: 3 };

function contentsFor(bounds) {
  return { range(cfi) {
    const [start, end] = bounds[cfi];
    return {
      compareBoundaryPoints(how, other) {
        return how === Range.START_TO_END ? Math.sign(end - other.start) : Math.sign(start - other.end);
      },
      start,
      end,
    };
  } };
}

test("a repeated or partially overlapping EPUB selection is one highlight", () => {
  const a = "epubcfi(/6/2!start)";
  const b = "epubcfi(/6/2!repeated)";
  const c = "epubcfi(/6/2!partial)";
  const next = "epubcfi(/6/2!next)";
  const contents = contentsFor({ [a]: [10, 20], [b]: [10, 20], [c]: [18, 25], [next]: [20, 30] });
  assert.equal(epubHighlightsOverlap(a, b, contents), true);
  assert.equal(epubHighlightsOverlap(a, c, contents), true);
  assert.equal(epubHighlightsOverlap(c, a, contents), true);
  assert.equal(epubHighlightsOverlap(a, next, contents), false);
});

test("PDF highlights reject even a small overlap while adjacent marks remain separate", () => {
  const original = { page: 1, x: .1, y: .2, width: .2, height: .03 };
  assert.equal(pdfHighlightsOverlap(original, { ...original, x: .299 }), true);
  assert.equal(pdfHighlightsOverlap(original, { ...original, x: .3 }), false);
  assert.equal(pdfHighlightsOverlap(original, { ...original, page: 2 }), false);
});
