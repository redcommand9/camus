type EpubContents = {
  document: Document;
  window: Window & { getSelection: () => Selection | null };
};

function caretAtPoint(doc: Document, x: number, y: number) {
  const isBookText = (node: Node) =>
    node.nodeType === Node.TEXT_NODE &&
    doc.body.contains(node) &&
    !node.parentElement?.closest("script, style, [aria-label='Adjust selected text']");
  const caretDocument = doc as Document & {
    caretRangeFromPoint?: (x: number, y: number) => Range | null;
    caretPositionFromPoint?: (x: number, y: number) => { offsetNode: Node; offset: number } | null;
  };
  const nativeRange = caretDocument.caretRangeFromPoint?.(x, y);
  if (nativeRange && isBookText(nativeRange.startContainer)) return nativeRange;
  const nativePosition = caretDocument.caretPositionFromPoint?.(x, y);
  if (nativePosition && isBookText(nativePosition.offsetNode)) {
    const range = doc.createRange();
    range.setStart(nativePosition.offsetNode, nativePosition.offset);
    range.collapse(true);
    return range;
  }

  const walker = doc.createTreeWalker(doc.body, 4);
  let nearest: { node: Node; offset: number; score: number } | null = null;
  let node = walker.nextNode();
  while (node) {
    if (isBookText(node)) {
      const contents = doc.createRange();
      contents.selectNodeContents(node);
      const lines = Array.from(contents.getClientRects());
      if (lines.some((line) => y >= line.top - 3 && y <= line.bottom + 3)) {
        const value = node.textContent ?? "";
        const character = doc.createRange();
        for (let index = 0; index < value.length; index++) {
          character.setStart(node, index);
          character.setEnd(node, index + 1);
          const rect = character.getBoundingClientRect();
          if (!rect.width || !rect.height) continue;
          const verticalDistance = y < rect.top ? rect.top - y : y > rect.bottom ? y - rect.bottom : 0;
          const horizontalDistance = x < rect.left ? rect.left - x : x > rect.right ? x - rect.right : 0;
          const score = verticalDistance * 8 + horizontalDistance;
          if (!nearest || score < nearest.score) {
            const offset = x >= (rect.left + rect.right) / 2 ? index + 1 : index;
            nearest = { node, offset, score };
            if (score === 0) break;
          }
        }
      }
    }
    node = walker.nextNode();
  }
  if (!nearest) return null;
  const range = doc.createRange();
  range.setStart(nearest.node, nearest.offset);
  range.collapse(true);
  return range;
}

export function installEpubSelectionControls(contents: EpubContents, onChange: (range: Range) => void, onDragStart: () => void) {
  const { document: doc, window: win } = contents;
  const overlay = doc.createElement("div");
  overlay.setAttribute("aria-label", "Adjust selected text");
  overlay.style.cssText = "position:fixed;inset:0;z-index:2147483647;pointer-events:none;";
  const selectedRange = { current: null as Range | null };
  let activeHandle: "start" | "end" | null = null;
  let dragOffset = { x: 9, y: 13 };
  const followPointer = (event: PointerEvent) => {
    const button = activeHandle === "start" ? startHandle : endHandle;
    button.style.left = `${Math.max(0, Math.min(win.innerWidth - 18, event.clientX - dragOffset.x))}px`;
    button.style.top = `${Math.max(0, Math.min(win.innerHeight - 26, event.clientY - dragOffset.y))}px`;
  };
  const moveSelectionEndpoint = (event: PointerEvent) => {
    const edge = activeHandle;
    if (!edge || !selectedRange.current) return;
    event.preventDefault();
    event.stopPropagation();
    const caret = caretAtPoint(doc, event.clientX, event.clientY - 10);
    if (!caret) return;

    const range = selectedRange.current.cloneRange();
    const comparison = caret.compareBoundaryPoints(Range.START_TO_START, range);
    try {
      if (edge === "start") {
        if (caret.compareBoundaryPoints(Range.START_TO_END, range) > 0) return;
        range.setStart(caret.startContainer, caret.startOffset);
      } else {
        if (comparison < 0) return;
        range.setEnd(caret.startContainer, caret.startOffset);
      }
    } catch {
      return;
    }
    if (range.collapsed || !range.toString().trim()) return;
    const selection = win.getSelection();
    const liveRange = selection?.rangeCount ? selection.getRangeAt(0) : null;
    if (liveRange) {
      if (edge === "start") liveRange.setStart(range.startContainer, range.startOffset);
      else liveRange.setEnd(range.endContainer, range.endOffset);
      update(liveRange);
    } else {
      update(range);
      selection?.addRange(range.cloneRange());
    }
    followPointer(event);
  };
  const finishDragging = () => {
    if (!activeHandle) return;
    const button = activeHandle === "start" ? startHandle : endHandle;
    activeHandle = null;
    button.style.transition = "left 140ms ease-out, top 140ms ease-out";
    if (selectedRange.current) {
      update(selectedRange.current);
      onChange(selectedRange.current.cloneRange());
    }
  };

  const makeHandle = (edge: "start" | "end") => {
    const button = doc.createElement("button");
    button.type = "button";
    button.setAttribute("aria-label", `Adjust selection ${edge}`);
    button.style.cssText = "position:absolute;width:18px;height:26px;padding:0;border:0;background:transparent;pointer-events:auto;touch-action:none;cursor:ew-resize;";
    const grip = doc.createElement("span");
    grip.style.cssText = "position:absolute;left:5px;top:8px;width:9px;height:9px;border:2px solid #fff;border-radius:50%;background:#d7a974;box-shadow:0 1px 5px #16181bb8;";
    button.appendChild(grip);
    overlay.appendChild(button);
    button.addEventListener("pointerdown", (event) => {
      event.preventDefault();
      event.stopPropagation();
      activeHandle = edge;
      const rect = button.getBoundingClientRect();
      dragOffset = { x: event.clientX - rect.left, y: event.clientY - rect.top };
      button.style.transition = "none";
      button.setPointerCapture(event.pointerId);
      onDragStart();
    });
    button.addEventListener("pointerup", (event) => {
      event.preventDefault();
      event.stopPropagation();
    });
    return button;
  };

  const startHandle = makeHandle("start");
  const endHandle = makeHandle("end");

  function update(range: Range) {
    selectedRange.current = range.cloneRange();
    const rects = Array.from(range.getClientRects()).filter((rect) => rect.width && rect.height);
    if (!rects.length) return;
    if (!overlay.isConnected) doc.documentElement.appendChild(overlay);
    const place = (button: HTMLButtonElement, x: number, y: number) => {
      button.style.left = `${Math.max(0, Math.min(win.innerWidth - 18, x - 9))}px`;
      button.style.top = `${Math.max(0, Math.min(win.innerHeight - 26, y - 9))}px`;
    };
    place(startHandle, rects[0].left, rects[0].bottom);
    place(endHandle, rects[rects.length - 1].right, rects[rects.length - 1].bottom);
  }

  update(selectedRange.current ?? doc.createRange());
  doc.addEventListener("pointermove", moveSelectionEndpoint, true);
  doc.addEventListener("pointerup", finishDragging, true);
  doc.addEventListener("pointercancel", finishDragging, true);
  return {
    update,
    isDragging: () => activeHandle !== null,
    destroy() {
      activeHandle = null;
      selectedRange.current = null;
      doc.removeEventListener("pointermove", moveSelectionEndpoint, true);
      doc.removeEventListener("pointerup", finishDragging, true);
      doc.removeEventListener("pointercancel", finishDragging, true);
      overlay.remove();
    },
  };
}
