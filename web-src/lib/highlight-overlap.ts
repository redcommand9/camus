type PdfMarkBounds = { page: number; x: number; y: number; width: number; height: number };

export function pdfHighlightsOverlap(a: PdfMarkBounds, b: PdfMarkBounds) {
  if (a.page !== b.page || a.width <= 0 || b.width <= 0 || a.height <= 0 || b.height <= 0) return false;
  const width = Math.min(a.x + a.width, b.x + b.width) - Math.max(a.x, b.x);
  const height = Math.min(a.y + a.height, b.y + b.height) - Math.max(a.y, b.y);
  return width > 1e-9 && height > 1e-9;
}

export function epubHighlightsOverlap(a: string, b: string, contents: { range: (cfi: string) => Range }) {
  if (a === b) return true;
  if (a.split("!")[0] !== b.split("!")[0]) return false;
  try {
    const first = contents.range(a);
    const second = contents.range(b);
    // The DOM Range constants compare this range's end to the other's start,
    // then this range's start to the other's end. Both must cross to overlap.
    return !!first && !!second &&
      first.compareBoundaryPoints(Range.START_TO_END, second) > 0 &&
      first.compareBoundaryPoints(Range.END_TO_START, second) < 0;
  } catch { return false; }
}
