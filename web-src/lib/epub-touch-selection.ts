type EpubContents = {
  document: Document;
  window: Window & { Highlight?: new (...ranges: Range[]) => unknown; CSS?: { highlights?: Map<string, unknown> } };
  cfiFromRange: (range: Range) => string;
};

const selectionName = "camus-reader-touch-selection";

export function paintEpubSelection(contents: EpubContents, range: Range) {
  const highlights = contents.window.CSS?.highlights;
  const Highlight = contents.window.Highlight;
  if (highlights && Highlight) highlights.set(selectionName, new Highlight(range.cloneRange()));
  return () => { highlights?.delete(selectionName); };
}

// Android's selection toolbar belongs to the browser and cannot be replaced by
// an iframe menu. On touch screens, own the long press and draw the selected
// range ourselves; ordinary scrolling and page turns still start immediately.
export function installEpubTouchSelection(
  contents: EpubContents,
  onSelect: (cfi: string, range: Range, clearPreview: () => void) => void,
  onBegin: () => void,
) {
  const doc = contents.document;
  if (!doc) return () => undefined;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let startPoint: { x: number; y: number } | undefined;
  let word: Range | undefined;
  let selected: Range | undefined;
  let clearInk: () => void = () => undefined;

  // The handles live in the EPUB iframe, so their coordinates stay aligned
  // with the text when the reader zooms or changes its page layout.
  const overlay = doc.createElement("div");
  overlay.className = "camus-reader-selection-handles";
  overlay.style.cssText = "position:fixed;inset:0;z-index:2147483647;pointer-events:none;";
  const makeHandle = (edge: "start" | "end") => {
    const button = doc.createElement("button");
    button.type = "button";
    button.className = `camus-reader-selection-handle camus-reader-selection-handle-${edge}`;
    button.setAttribute("aria-label", `Drag to adjust ${edge} of selection`);
    button.style.cssText = "position:absolute;width:42px;height:44px;border:0;padding:0;background:transparent;pointer-events:auto;touch-action:none;cursor:grab;";
    const pin = doc.createElement("span");
    pin.style.cssText = `position:absolute;top:7px;${edge === "start" ? "left" : "right"}:14px;width:14px;height:23px;border:2px solid white;border-radius:${edge === "start" ? "2px 9px 9px 9px" : "9px 2px 9px 9px"};background:#2475d6;box-shadow:0 1px 5px #122c54a8;transform:rotate(${edge === "start" ? "-20" : "20"}deg);`;
    button.appendChild(pin);
    overlay.appendChild(button);
    return button;
  };
  const startHandle = makeHandle("start");
  const endHandle = makeHandle("end");

  const clearPreview = () => {
    clearInk();
    clearInk = () => undefined;
    overlay.remove();
    selected = undefined;
  };
  const placeHandles = (range: Range) => {
    const rects = Array.from(range.getClientRects()).filter((rect) => rect.width && rect.height);
    if (!rects.length) return;
    if (!overlay.isConnected) doc.documentElement.appendChild(overlay);
    const position = (button: HTMLButtonElement, x: number, y: number) => {
      button.style.left = `${Math.max(0, Math.min(contents.window.innerWidth - 42, x - 21))}px`;
      button.style.top = `${Math.max(0, Math.min(contents.window.innerHeight - 44, y - 5))}px`;
    };
    position(startHandle, rects[0].left, rects[0].bottom);
    position(endHandle, rects[rects.length - 1].right, rects[rects.length - 1].bottom);
  };
  const paint = (range: Range) => {
    selected = range;
    clearInk();
    clearInk = paintEpubSelection(contents, range);
    placeHandles(range);
  };
  const publish = () => {
    if (selected && !selected.collapsed) onSelect(contents.cfiFromRange(selected), selected.cloneRange(), clearPreview);
  };

  const cancelTimer = () => { if (timer) clearTimeout(timer); timer = undefined; };
  const caretAt = (x: number, y: number): Range | undefined => {
    const documentWithCaret = doc as Document & {
      caretRangeFromPoint?: (x: number, y: number) => Range | null;
      caretPositionFromPoint?: (x: number, y: number) => { offsetNode: Node; offset: number } | null;
    };
    const range = documentWithCaret.caretRangeFromPoint?.(x, y);
    if (range) return range;
    const position = documentWithCaret.caretPositionFromPoint?.(x, y);
    if (!position) return undefined;
    const next = doc.createRange();
    next.setStart(position.offsetNode, position.offset);
    next.collapse(true);
    return next;
  };
  const wordAt = (x: number, y: number): Range | undefined => {
    const caret = caretAt(x, y);
    if (!caret || caret.startContainer.nodeType !== Node.TEXT_NODE) return undefined;
    const value = caret.startContainer.textContent ?? "";
    let start = caret.startOffset;
    let end = start;
    const isWord = (character: string) => /[\p{L}\p{N}_'-]/u.test(character);
    while (start > 0 && isWord(value[start - 1])) start--;
    while (end < value.length && isWord(value[end])) end++;
    if (start === end) return undefined;
    const range = doc.createRange();
    range.setStart(caret.startContainer, start);
    range.setEnd(caret.startContainer, end);
    return range;
  };
  const extendTo = (x: number, y: number) => {
    if (!word) return;
    const endWord = wordAt(x, y) ?? caretAt(x, y);
    if (!endWord) return;
    const range = doc.createRange();
    if (endWord.compareBoundaryPoints(Range.START_TO_START, word) < 0) {
      range.setStart(endWord.startContainer, endWord.startOffset);
      range.setEnd(word.endContainer, word.endOffset);
    } else {
      range.setStart(word.startContainer, word.startOffset);
      range.setEnd(endWord.endContainer, endWord.endOffset);
    }
    paint(range);
  };
  const adjustHandle = (edge: "start" | "end", x: number, y: number) => {
    if (!selected) return;
    // The finger sits below the marked glyph. Sample the caret over the glyph.
    const caret = caretAt(x, y - 18);
    if (!caret) return;
    const range = selected.cloneRange();
    if (edge === "start") range.setStart(caret.startContainer, caret.startOffset);
    else range.setEnd(caret.startContainer, caret.startOffset);
    if (!range.collapsed && range.toString().trim()) paint(range);
  };
  const bindHandle = (button: HTMLButtonElement, edge: "start" | "end") => {
    let dragging = false;
    button.addEventListener("pointerdown", (event) => {
      event.preventDefault();
      event.stopPropagation();
      dragging = true;
      button.setPointerCapture(event.pointerId);
    });
    button.addEventListener("pointermove", (event) => {
      if (!dragging) return;
      event.preventDefault();
      event.stopPropagation();
      adjustHandle(edge, event.clientX, event.clientY);
    });
    button.addEventListener("pointerup", (event) => {
      if (!dragging) return;
      event.preventDefault();
      event.stopPropagation();
      dragging = false;
      publish();
    });
    button.addEventListener("pointercancel", () => { dragging = false; publish(); });
  };
  bindHandle(startHandle, "start");
  bindHandle(endHandle, "end");
  const onTouchStart = (event: TouchEvent) => {
    cancelTimer();
    if (event.touches.length !== 1 || (event.target as Element)?.closest?.("a, button, input, textarea, [contenteditable]")) return;
    const touch = event.touches[0];
    startPoint = { x: touch.clientX, y: touch.clientY };
    word = undefined;
    timer = setTimeout(() => {
      timer = undefined;
      if (!startPoint) return;
      const nextWord = wordAt(startPoint.x, startPoint.y);
      if (!nextWord) return;
      onBegin();
      clearPreview();
      word = nextWord;
      extendTo(startPoint.x, startPoint.y);
    }, 360);
  };
  const onTouchMove = (event: TouchEvent) => {
    const touch = event.touches[0];
    if (!touch || !startPoint) return;
    if (!word) {
      if (Math.hypot(touch.clientX - startPoint.x, touch.clientY - startPoint.y) > 9) cancelTimer();
      return;
    }
    event.preventDefault();
    extendTo(touch.clientX, touch.clientY);
  };
  const onTouchEnd = (event: TouchEvent) => {
    cancelTimer();
    if (word && selected) {
      const touch = event.changedTouches[0];
      if (touch) extendTo(touch.clientX, touch.clientY);
      publish();
    }
    startPoint = undefined;
    word = undefined;
  };
  const onTouchCancel = () => {
    cancelTimer();
    startPoint = undefined;
    word = undefined;
  };
  const onContextMenu = (event: Event) => event.preventDefault();
  doc.addEventListener("touchstart", onTouchStart, { passive: true });
  doc.addEventListener("touchmove", onTouchMove, { passive: false });
  doc.addEventListener("touchend", onTouchEnd, { passive: true });
  doc.addEventListener("touchcancel", onTouchCancel);
  doc.addEventListener("contextmenu", onContextMenu);
  return () => {
    onTouchCancel();
    clearPreview();
    doc.removeEventListener("touchstart", onTouchStart);
    doc.removeEventListener("touchmove", onTouchMove);
    doc.removeEventListener("touchend", onTouchEnd);
    doc.removeEventListener("touchcancel", onTouchCancel);
    doc.removeEventListener("contextmenu", onContextMenu);
  };
}
