"use client";

import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type PointerEvent as ReactPointerEvent,
} from "react";
import PdfJsWorkerUrl from "pdfjs-dist/build/pdf.worker.min.mjs?url";
import { LibraryHome, type LibraryItem } from "@/components/library-home";
import { SettingsHome } from "@/components/settings-home";
import { GutenbergCatalog } from "@/components/gutenberg-catalog";
import { getLocalBook, listLocalBooks, nativeBookKey, removeLocalBook, saveLocalBook, type LocalBook } from "@/lib/local-library";
import { readReaderState, writeReaderState, removeReaderState } from "@/lib/reader-state";
import { installEpubTouchSelection, paintEpubSelection } from "@/lib/epub-touch-selection";
import { epubHighlightsOverlap, pdfHighlightsOverlap } from "@/lib/highlight-overlap";
import { adjacentPdfLeaf, buildPdfSequence, insertPdfNoteAfter } from "@/lib/pdf-sequence";
import {
  Bookmark,
  BookmarkCheck,
  BookOpen,
  Check,
  ChevronLeft,
  ChevronRight,
  Eraser,
  Focus,
  Highlighter,
  Library,
  Moon,
  Minus,
  Minimize2,
  PanelLeft,
  Plus,
  ScrollText,
  SlidersHorizontal,
  Sun,
  Trash2,
  Upload,
  X,
} from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
} from "@/components/ui/sheet";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip";

type BookKind = "pdf" | "epub";
type ReadingMode = "page" | "scroll";
type HighlightMode = "draw" | "erase" | null;
type PageTurn = { fromKey: string; toKey: string; direction: -1 | 1; started: boolean; backNoteId?: string };
type PageColor = "paper" | "cream" | "sage" | "slate" | "night";
type HighlightColor = "amber" | "rose" | "blue";

type BookmarkItem = {
  id: string;
  page?: number;
  noteId?: string;
  cfi?: string;
  label: string;
};

type NotePage = {
  id: string;
  afterPage?: number;
  displayPageNumber?: number;
  cfi?: string;
  anchorLabel: string;
  title: string;
  content: string;
  backTitle?: string;
  backContent?: string;
};

type PdfHighlight = {
  id: string;
  page: number;
  color: HighlightColor;
  x: number;
  y: number;
  width: number;
  height: number;
  note?: string;
};

type EpubHighlight = {
  id: string;
  cfi: string;
  color: HighlightColor;
  kind?: "highlight" | "strike";
  text?: string;
  note?: string;
};

type StoredBookState = {
  bookmarks: BookmarkItem[];
  notes: NotePage[];
  pdfHighlights: PdfHighlight[];
  epubHighlights: EpubHighlight[];
  lastPage?: number;
  lastCursorKey?: string;
  lastCfi?: string;
  lastProgress?: number;
};

type Theme = { label: string; page: string; ink: string; shell: string; tint: string };

const pageThemes: Record<PageColor, Theme> = {
  paper: { label: "Paper", page: "#fffdf7", ink: "#25231f", shell: "#e9e5dc", tint: "#fffdf7" },
  cream: { label: "Cream", page: "#f3e5c5", ink: "#34291f", shell: "#d9c9aa", tint: "#efdcb6" },
  sage: { label: "Sage", page: "#e3ead9", ink: "#283127", shell: "#cbd4c1", tint: "#dce6d1" },
  slate: { label: "Slate", page: "#e2e7eb", ink: "#232a30", shell: "#c8d0d6", tint: "#dce3e8" },
  night: { label: "Night", page: "#202426", ink: "#eee9dc", shell: "#121516", tint: "#283034" },
};

const highlightColors: Record<HighlightColor, string> = {
  amber: "#f6c84f",
  rose: "#f59aaa",
  blue: "#83bff0",
};

const highlightCursors = Object.fromEntries(Object.entries(highlightColors).map(([color, ink]) => {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32" viewBox="0 0 32 32"><path d="M4 27 7 18 19 6 28 15 16 27 11 24Z" fill="${ink}" stroke="white" stroke-width="4" stroke-linejoin="round"/><path d="m7 18 12-12 9 9-12 12-5-3-7 3 3-9Zm0 0 9 9M16 9l9 9" fill="${ink}" stroke="#25282a" stroke-width="1.7" stroke-linejoin="round"/></svg>`;
  return [color, `url("data:image/svg+xml,${encodeURIComponent(svg)}") 4 27, text`];
})) as Record<HighlightColor, string>;
const eraserCursorSvg = `<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32" viewBox="0 0 32 32"><path d="m5 19 13-13a3 3 0 0 1 4.2 0l4.8 4.8a3 3 0 0 1 0 4.2L15 27H9L5 23a3 3 0 0 1 0-4Z" fill="#f18d9b" stroke="white" stroke-width="4" stroke-linejoin="round"/><path d="m5 19 13-13a3 3 0 0 1 4.2 0l4.8 4.8a3 3 0 0 1 0 4.2L15 27H9L5 23a3 3 0 0 1 0-4Zm0 0 10 8M18 6l9 9M16 27h12" fill="#f18d9b" stroke="#25282a" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>`;
const eraserCursor = `url("data:image/svg+xml,${encodeURIComponent(eraserCursorSvg)}") 7 25, pointer`;

function applyEpubHighlightPointer(contents: any, mode: HighlightMode, color: HighlightColor) {
  const doc = contents?.document as Document | undefined;
  if (!doc?.head) return;
  let style = doc.getElementById("camus-reader-highlight-pointer");
  if (!style) {
    style = doc.createElement("style");
    style.id = "camus-reader-highlight-pointer";
    doc.head.appendChild(style);
  }
  const cursor = mode === "draw" ? highlightCursors[color] : mode === "erase" ? eraserCursor : "";
  style.textContent = cursor ? `html, body, body * { cursor: ${cursor} !important; }` : "";
}

function applyResponsiveEpubLayout(contents: any) {
  const doc = contents?.document as Document | undefined;
  if (!doc?.head || doc.getElementById("camus-reader-mobile-layout")) return;
  const style = doc.createElement("style");
  style.id = "camus-reader-mobile-layout";
  style.textContent = `
    ::highlight(camus-reader-touch-selection) { background: rgb(113 180 222 / 48%); }
    @media (pointer: coarse) {
      html, body, body *:not(input):not(textarea) {
        -webkit-user-select: none !important;
        user-select: none !important;
        -webkit-touch-callout: none !important;
      }
    }
    @media (max-width: 800px) {
      html, body { max-width: 100% !important; min-width: 0 !important; box-sizing: border-box !important; }
      body { margin: 0 !important; padding-inline: clamp(1rem, 5vw, 1.5rem) !important; overflow-wrap: anywhere !important; line-height: 1.58 !important; }
      p, li, blockquote { max-width: 100% !important; text-align: start !important; hyphens: auto !important; }
      img, svg, table, pre, figure { max-width: 100% !important; height: auto; }
    }
  `;
  doc.head.appendChild(style);
}

function epubTextSize(width: number, zoom: number) {
  return `${(width < 600 ? 17 : 18) * zoom / 100}px`;
}

const makeId = () => `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;

type PdfRaster = { canvas: HTMLCanvasElement; ratio: number };
const pdfRasterCache = new WeakMap<object, Map<string, Promise<PdfRaster>>>();

function cachedPdfPage(pdfDoc: object & { getPage: (page: number) => Promise<any> }, pageNumber: number, scale = 1.55): Promise<PdfRaster> {
  const key = `${pageNumber}:${scale}`;
  let pages = pdfRasterCache.get(pdfDoc);
  if (!pages) {
    pages = new Map();
    pdfRasterCache.set(pdfDoc, pages);
  }
  const existing = pages.get(key);
  if (existing) {
    pages.delete(key);
    pages.set(key, existing);
    return existing;
  }
  const raster = pdfDoc.getPage(pageNumber).then(async (page: any) => {
    const viewport = page.getViewport({ scale });
    const canvas = document.createElement("canvas");
    canvas.width = Math.floor(viewport.width);
    canvas.height = Math.floor(viewport.height);
    const context = canvas.getContext("2d", { alpha: false });
    if (!context) throw new Error("Unable to render PDF page");
    await page.render({ canvas, canvasContext: context, viewport }).promise;
    return { canvas, ratio: viewport.height / viewport.width };
  }).catch((error: unknown) => {
    pages?.delete(key);
    throw error;
  });
  pages.set(key, raster);
  while (pages.size > (scale > 2.5 ? 4 : 8)) pages.delete(pages.keys().next().value!);
  return raster;
}

function bookStorageKey(file: File) {
  return nativeBookKey(file) ?? `camus-reader:book:${file.name}:${file.size}:${file.lastModified}`;
}

function locationPercent(location: unknown) {
  const value = Number(location);
  return Number.isFinite(value) ? Math.round(value * 100) : 0;
}

function IconButton({
  label,
  children,
  active = false,
  onClick,
  disabled = false,
}: {
  label: string;
  children: React.ReactNode;
  active?: boolean;
  onClick?: () => void;
  disabled?: boolean;
}) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          aria-label={label}
          aria-pressed={active}
          onClick={onClick}
          disabled={disabled}
          className={active ? "bg-[#e7c879] text-[#342910] hover:bg-[#e7c879]" : "text-[#69645b] hover:bg-black/5"}
        >
          {children}
        </Button>
      </TooltipTrigger>
      <TooltipContent side="bottom">{label}</TooltipContent>
    </Tooltip>
  );
}

function PdfPage({
  pdfDoc,
  pageNumber,
  theme,
  highlights,
  highlightMode,
  highlightColor,
  onAddHighlight,
  onRemoveHighlights,
  onSelectHighlight,
  onVisible,
  priority = false,
  renderScale = 1.55,
}: {
  pdfDoc: any;
  pageNumber: number;
  theme: PageColor;
  highlights: PdfHighlight[];
  highlightMode: HighlightMode;
  highlightColor: HighlightColor;
  onAddHighlight: (item: PdfHighlight) => void;
  onRemoveHighlights: (ids: string[]) => void;
  onSelectHighlight: (item: PdfHighlight) => void;
  onVisible: (page: number) => void;
  priority?: boolean;
  renderScale?: number;
}) {
  const hostRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [isNear, setIsNear] = useState(priority);
  const [ratio, setRatio] = useState(1.414);
  const [draft, setDraft] = useState<PdfHighlight | null>(null);
  const draftRef = useRef<PdfHighlight | null>(null);
  const previewFrameRef = useRef<number | null>(null);
  const startRef = useRef<{ x: number; y: number } | null>(null);

  useEffect(() => () => { if (previewFrameRef.current !== null) cancelAnimationFrame(previewFrameRef.current); }, []);

  useEffect(() => {
    const node = hostRef.current;
    if (!node) return;
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) setIsNear(true);
          if (entry.intersectionRatio > 0.55) onVisible(pageNumber);
        }
      },
      { rootMargin: "900px 0px", threshold: [0, 0.55] },
    );
    observer.observe(node);
    return () => observer.disconnect();
  }, [onVisible, pageNumber]);

  useLayoutEffect(() => {
    if (!isNear || !canvasRef.current) return;
    let cancelled = false;
    void cachedPdfPage(pdfDoc, pageNumber, renderScale).then(({ canvas: source, ratio: nextRatio }) => {
      if (cancelled || !canvasRef.current) return;
      setRatio(nextRatio);
      const canvas = canvasRef.current;
      const context = canvas.getContext("2d", { alpha: false });
      if (!context) return;
      canvas.width = source.width;
      canvas.height = source.height;
      context.drawImage(source, 0, 0);
    }).catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [isNear, pageNumber, pdfDoc, renderScale]);

  const pointFromEvent = (event: ReactPointerEvent<HTMLDivElement>) => {
    const rect = event.currentTarget.getBoundingClientRect();
    return {
      x: Math.max(0, Math.min(1, (event.clientX - rect.left) / rect.width)),
      y: Math.max(0, Math.min(1, (event.clientY - rect.top) / rect.height)),
    };
  };

  const beginHighlight = (event: ReactPointerEvent<HTMLDivElement>) => {
    const point = pointFromEvent(event);
    if (highlightMode === "erase") {
      const ids = highlights.filter((item) => point.x >= item.x && point.x <= item.x + item.width && point.y >= item.y && point.y <= item.y + item.height).map((item) => item.id);
      if (ids.length) onRemoveHighlights(ids);
      return;
    }
    if (highlightMode !== "draw") return;
    event.currentTarget.setPointerCapture(event.pointerId);
    startRef.current = point;
    const next = { id: "draft", page: pageNumber, color: highlightColor, x: point.x, y: point.y, width: 0, height: 0 };
    draftRef.current = next;
    setDraft(next);
  };

  const moveHighlight = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (!startRef.current || highlightMode !== "draw") return;
    const point = pointFromEvent(event);
    const start = startRef.current;
    const next: PdfHighlight = {
      id: "draft",
      page: pageNumber,
      color: highlightColor,
      x: Math.min(start.x, point.x),
      y: Math.min(start.y, point.y),
      width: Math.abs(point.x - start.x),
      height: Math.abs(point.y - start.y),
    };
    draftRef.current = next;
    if (previewFrameRef.current === null) previewFrameRef.current = requestAnimationFrame(() => {
      previewFrameRef.current = null;
      setDraft(draftRef.current);
    });
  };

  const endHighlight = () => {
    if (previewFrameRef.current !== null) cancelAnimationFrame(previewFrameRef.current);
    previewFrameRef.current = null;
    const completed = draftRef.current;
    startRef.current = null;
    if (!completed || completed.width <= 0.015 || completed.height <= 0.006) {
      draftRef.current = null;
      setDraft(null);
    } else setDraft({ ...completed });
  };

  const clearDraft = () => { draftRef.current = null; startRef.current = null; setDraft(null); };

  const displayed = draft ? [...highlights, draft] : highlights;

  return (
    <article
      ref={hostRef}
      data-pdf-page={pageNumber}
      className={`pdf-page group relative mx-auto w-full max-w-[840px] overflow-hidden rounded-[3px] shadow-[0_18px_55px_rgba(42,35,24,.16)] ${theme}`}
      style={{ aspectRatio: `1 / ${ratio}` }}
      aria-label={`Page ${pageNumber}`}
    >
      {isNear ? <canvas ref={canvasRef} className="block h-full w-full" /> : <div className="h-full w-full animate-pulse bg-black/5" />}
      <div className="pointer-events-none absolute inset-0 page-tint" aria-hidden="true" />
      <div
        className={`absolute inset-0 ${highlightMode === "draw" || highlightMode === "erase" ? "touch-none" : "pointer-events-none"}`}
        style={highlightMode === "draw" ? { cursor: highlightCursors[highlightColor] } : highlightMode === "erase" ? { cursor: eraserCursor } : undefined}
        onPointerDown={beginHighlight}
        onPointerMove={moveHighlight}
        onPointerUp={endHighlight}
        onPointerCancel={() => { startRef.current = null; draftRef.current = null; setDraft(null); }}
      >
        <svg className="pdf-highlight-ink pointer-events-none absolute inset-0 h-full w-full" viewBox="0 0 1 1" preserveAspectRatio="none" aria-hidden="true">
          <g opacity="0.38">
            {displayed.map((item) => {
              const radius = Math.min(item.width / 2, item.height * ratio / 2, .018);
              return <rect key={item.id} x={item.x} y={item.y} width={item.width} height={item.height} rx={radius} ry={radius / ratio} fill={highlightColors[item.color]} />;
            })}
          </g>
        </svg>
        {highlights.map((item) => (
          <span
            key={item.id}
            className="pdf-highlight-hitbox pointer-events-auto absolute"
            role="button"
            tabIndex={0}
            aria-label={`${item.note ? "Open note for" : "Add note to"} highlight on page ${pageNumber}`}
            onPointerDown={(event) => { if (highlightMode !== "erase") event.stopPropagation(); }}
            onClick={(event) => { event.stopPropagation(); if (highlightMode !== "erase") onSelectHighlight(item); }}
            onKeyDown={(event) => { if ((event.key === "Enter" || event.key === " ") && highlightMode !== "erase") { event.preventDefault(); onSelectHighlight(item); } }}
            style={{
              left: `${item.x * 100}%`,
              top: `${item.y * 100}%`,
              width: `${item.width * 100}%`,
              height: `${item.height * 100}%`,
              pointerEvents: "auto",
            }}
          />
        ))}
        {draft && !startRef.current && <div className="highlight-confirm" style={{ left: `${Math.min(draft.x + draft.width, .74) * 100}%`, top: `${Math.min(draft.y + draft.height, .9) * 100}%` }} onPointerDown={(event) => event.stopPropagation()}>
          <button type="button" onClick={(event) => { event.stopPropagation(); onAddHighlight({ ...draft, id: makeId() }); clearDraft(); }}><Highlighter className="size-4" /> Highlight</button>
          <button type="button" aria-label="Cancel highlight" onClick={(event) => { event.stopPropagation(); clearDraft(); }}><X className="size-4" /></button>
        </div>}
      </div>
      <span className="absolute bottom-3 right-4 rounded-full bg-black/55 px-2 py-1 text-[11px] font-medium text-white opacity-0 transition-opacity group-hover:opacity-100">
        {pageNumber}
      </span>
    </article>
  );
}

function NoteSheet({
  note,
  theme,
  face = "front",
  onChange,
  onDelete,
  onClose,
  onFlip,
  pageNumber,
  focusOnOpen = true,
}: {
  note: NotePage;
  theme: Theme;
  face?: "front" | "back";
  onChange: (note: NotePage) => void;
  onDelete: () => void;
  onClose?: () => void;
  onFlip?: () => void;
  pageNumber?: number;
  focusOnOpen?: boolean;
}) {
  return (
    <article
      data-note-id={note.id}
      className="note-sheet relative mx-auto flex min-h-[min(76vh,880px)] w-full max-w-[760px] flex-col overflow-hidden rounded-[4px] px-[clamp(28px,8vw,86px)] py-[clamp(40px,8vh,82px)] shadow-[0_20px_65px_rgba(35,28,18,.18)]"
      style={{ background: theme.page, color: theme.ink }}
    >
      <div className={`absolute top-0 h-full w-3 border-dashed border-black/15 bg-black/5 ${face === "back" ? "right-0 border-l" : "left-0 border-r"}`} />
      <div className="note-sheet-toolbar mb-9 flex items-center justify-between gap-4 border-b border-current/15 pb-4">
        <span className="text-xs font-semibold uppercase tracking-[.17em] opacity-50" aria-label={pageNumber ? `Page ${pageNumber}, ${face} of sheet` : `Inserted after ${note.anchorLabel}, ${face} of sheet`}>
          <span className="note-sheet-location-full">{pageNumber ? `Page ${pageNumber} · ${face} of sheet` : `Inserted after ${note.anchorLabel} · ${face}`}</span>
          <span className="note-sheet-location-compact" aria-hidden="true">Sheet · {face}</span>
        </span>
        <div className="flex items-center gap-1">
          {onFlip && <Button type="button" variant="ghost" size="sm" onClick={onFlip} aria-label={`Turn to ${face === "front" ? "back" : "front"} of sheet`} className="hover:bg-black/5">Turn to {face === "front" ? "back" : "front"}</Button>}
          {onClose && (
            <Button type="button" variant="ghost" size="icon-sm" aria-label="Return to book" onClick={onClose} className="hover:bg-black/5">
              <X />
            </Button>
          )}
          <Button type="button" variant="ghost" size="icon-sm" aria-label="Delete note page" onClick={onDelete} className="hover:bg-red-500/10 hover:text-red-700">
            <Trash2 />
          </Button>
        </div>
      </div>
      <input
        value={face === "front" ? note.title : note.backTitle ?? ""}
        onChange={(event) => onChange({ ...note, [face === "front" ? "title" : "backTitle"]: event.target.value })}
        placeholder="Untitled page"
        aria-label={`${face} of note page title`}
        className="mb-5 w-full bg-transparent font-serif text-3xl font-semibold tracking-tight outline-none placeholder:opacity-35"
      />
      <textarea
        autoFocus={focusOnOpen}
        value={face === "front" ? note.content : note.backContent ?? ""}
        onChange={(event) => onChange({ ...note, [face === "front" ? "content" : "backContent"]: event.target.value })}
        placeholder="Write here…"
        aria-label={`${face} of note page content`}
        className="min-h-[420px] flex-1 resize-none bg-transparent font-serif text-lg leading-[2] outline-none placeholder:opacity-35"
        style={{
          backgroundImage: `linear-gradient(to bottom, transparent calc(2em - 1px), color-mix(in srgb, ${theme.ink} 12%, transparent) 0)`,
          backgroundSize: "100% 2em",
          backgroundAttachment: "local",
        }}
      />
    </article>
  );
}

function MarksPanel({
  bookmarks,
  notes,
  highlights,
  onOpenBookmark,
  onOpenNote,
  onDeleteBookmark,
  onDeleteHighlight,
}: {
  bookmarks: BookmarkItem[];
  notes: NotePage[];
  highlights: Array<{ id: string; label: string; color: HighlightColor; kind?: "highlight" | "strike" }>;
  onOpenBookmark: (bookmark: BookmarkItem) => void;
  onOpenNote: (note: NotePage) => void;
  onDeleteBookmark: (id: string) => void;
  onDeleteHighlight: (id: string) => void;
}) {
  return (
    <div className="marks-panel space-y-8">
      <section>
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-xs font-bold uppercase tracking-[.16em] text-[var(--chrome-muted)]">Bookmarks</h2>
          <span className="text-xs tabular-nums text-[var(--chrome-muted)]">{bookmarks.length}</span>
        </div>
        <div className="space-y-1">
          {bookmarks.length === 0 ? (
            <p className="marks-empty px-3 py-2 text-sm leading-6 text-[var(--chrome-muted)]">No bookmarks yet.</p>
          ) : bookmarks.map((item) => (
            <div key={item.id} className="mark-row group flex items-center gap-1 rounded-lg">
              <button type="button" onClick={() => onOpenBookmark(item)} className="min-w-0 flex-1 px-3 py-2.5 text-left">
                <span className="block truncate text-sm font-medium text-[var(--chrome-ink)]">{item.label}</span>
              </button>
              <Button type="button" variant="ghost" size="icon-xs" aria-label={`Delete ${item.label}`} onClick={() => onDeleteBookmark(item.id)} className="mr-2 opacity-0 group-hover:opacity-100 focus:opacity-100">
                <X />
              </Button>
            </div>
          ))}
        </div>
      </section>

      <section>
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-xs font-bold uppercase tracking-[.16em] text-[var(--chrome-muted)]">Text marks</h2>
          <span className="text-xs tabular-nums text-[var(--chrome-muted)]">{highlights.length}</span>
        </div>
        <div className="space-y-1">
          {highlights.length === 0 ? (
            <p className="marks-empty px-3 py-2 text-sm leading-6 text-[var(--chrome-muted)]">No marks yet.</p>
          ) : highlights.map((item) => (
            <div key={item.id} className="mark-row flex items-center gap-2 rounded-lg px-3 py-2">
              <span className="size-3 shrink-0 rounded-full" style={{ background: highlightColors[item.color] }} aria-hidden="true" />
              <span className="min-w-0 flex-1 truncate text-sm text-[var(--chrome-ink)]" title={item.label}>{item.label}</span>
              <Button type="button" variant="ghost" size="icon-xs" aria-label={`Remove ${item.kind === "strike" ? "strikethrough" : "highlight"} ${item.label}`} onClick={() => onDeleteHighlight(item.id)}><X /></Button>
            </div>
          ))}
        </div>
      </section>

      <section>
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-xs font-bold uppercase tracking-[.16em] text-[var(--chrome-muted)]">Torn pages</h2>
          <span className="text-xs tabular-nums text-[var(--chrome-muted)]">{notes.length}</span>
        </div>
        <div className="space-y-1">
          {notes.length === 0 ? (
            <p className="marks-empty px-3 py-2 text-sm leading-6 text-[var(--chrome-muted)]">No inserted sheets yet.</p>
          ) : notes.map((note) => (
            <button key={note.id} type="button" onClick={() => onOpenNote(note)} className="mark-row w-full rounded-lg px-3 py-2.5 text-left">
              <span className="block truncate text-sm font-medium text-[var(--chrome-ink)]">{note.title || "Untitled page"}</span>
              <span className="mt-0.5 block truncate text-xs text-[var(--chrome-muted)]">{note.displayPageNumber ? `Page ${note.displayPageNumber} · inserted sheet` : `After ${note.anchorLabel}`}</span>
            </button>
          ))}
        </div>
      </section>
    </div>
  );
}

function HighlightNotePanel({
  label,
  text,
  note,
  onChange,
  onClose,
}: {
  label: string;
  text?: string;
  note: string;
  onChange: (note: string) => void;
  onClose: () => void;
}) {
  return (
    <aside className="highlight-note-panel" aria-label="Highlight note">
      <div className="highlight-note-header">
        <div>
          <span className="navigation-overline">Private note</span>
          <h2>Note on highlight</h2>
        </div>
        <button type="button" className="navigation-close" aria-label="Close highlight note" onClick={onClose}><X className="size-4" /></button>
      </div>
      <div className="highlight-note-body">
        <p className="highlight-note-location">{label}</p>
        {text && <blockquote className="highlight-note-quote">“{text}”</blockquote>}
        <textarea
          autoFocus
          value={note}
          onChange={(event) => onChange(event.target.value)}
          placeholder="Write a note about this passage…"
          aria-label="Note for highlighted text"
        />
        <p className="highlight-note-hint">Saved with this book</p>
      </div>
    </aside>
  );
}

function ZenGlowBar({ percent }: { percent: number }) {
  return (
    <div
      className="zen-glow-bar"
      role="progressbar"
      aria-label="Reading progress"
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={percent}
      aria-valuetext={`${percent}% read`}
      style={{ "--zen-progress": `${percent}%` } as CSSProperties}
    >
      <span className="zen-glow-baseline" aria-hidden="true" />
      <span className="zen-glow-fill" aria-hidden="true" />
    </div>
  );
}

export default function Home() {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const readerStageRef = useRef<HTMLDivElement>(null);
  const turnPendingRef = useRef(false);
  const lastWheelTurnRef = useRef(0);
  const pdfCursorRef = useRef("page-1");
  const epubHostRef = useRef<HTMLDivElement>(null);
  const epubBookRef = useRef<any>(null);
  const renditionRef = useRef<any>(null);
  const highlightModeRef = useRef<HighlightMode>(null);
  const highlightColorRef = useRef<HighlightColor>("amber");
  const epubHighlightsRef = useRef<EpubHighlight[]>([]);
  const pendingEpubRef = useRef<{ cfi: string; text: string; contents: any; overlaps: boolean; clearPreview: () => void } | null>(null);
  const confirmEpubRef = useRef<((kind: "highlight" | "strike") => void) | null>(null);
  const copyEpubRef = useRef<(() => void) | null>(null);
  const annotatedCfisRef = useRef(new Set<string>());
  const activeHighlightIdRef = useRef<string | null>(null);
  const currentCfiRef = useRef<string | undefined>(undefined);
  const epubNoteReturnRef = useRef<{ noteId: string; nextCfi: string } | null>(null);
  const lightPageColorRef = useRef<PageColor>("paper");

  const [bookKind, setBookKind] = useState<BookKind | null>(null);
  const [bookTitle, setBookTitle] = useState("");
  const [bookKey, setBookKey] = useState<string | null>(null);
  const [hydratedKey, setHydratedKey] = useState<string | null>(null);
  const [pdfDoc, setPdfDoc] = useState<any>(null);
  const [pageCount, setPageCount] = useState(0);
  const [pdfPageRatio, setPdfPageRatio] = useState(1.414);
  const [currentPage, setCurrentPage] = useState(1);
  const [currentCfi, setCurrentCfi] = useState<string>();
  const [currentLabel, setCurrentLabel] = useState("Opening page");
  const [progress, setProgress] = useState(0);
  const [readingMode, setReadingMode] = useState<ReadingMode>("page");
  const [pageColor, setPageColor] = useState<PageColor>("paper");
  const [darkMode, setDarkMode] = useState(false);
  const [highlightMode, setHighlightMode] = useState<HighlightMode>(null);
  const [highlightColor, setHighlightColor] = useState<HighlightColor>("amber");
  const [bookmarks, setBookmarks] = useState<BookmarkItem[]>([]);
  const [notes, setNotes] = useState<NotePage[]>([]);
  const [pdfHighlights, setPdfHighlights] = useState<PdfHighlight[]>([]);
  const [epubHighlights, setEpubHighlights] = useState<EpubHighlight[]>([]);
  const [pendingEpubSelection, setPendingEpubSelection] = useState<{ left: number; top: number } | null>(null);
  const [activeHighlightId, setActiveHighlightId] = useState<string | null>(null);
  const [activeNoteId, setActiveNoteId] = useState<string | null>(null);
  const [pdfCursorKey, setPdfCursorKey] = useState("page-1");
  pdfCursorRef.current = pdfCursorKey;
  const [pageTurn, setPageTurn] = useState<PageTurn | null>(null);
  const [backOpenNoteId, setBackOpenNoteId] = useState<string | null>(null);
  const [frontOpenNoteId, setFrontOpenNoteId] = useState<string | null>(null);
  const [singleNoteFace, setSingleNoteFace] = useState<"front" | "back">("front");
  const [dragging, setDragging] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [mobilePanelOpen, setMobilePanelOpen] = useState(false);
  const [desktopPanelOpen, setDesktopPanelOpen] = useState(false);
  const [zenMode, setZenMode] = useState(false);
  const [panelTab, setPanelTab] = useState<"pages" | "marks">("pages");
  const [pdfLayout, setPdfLayout] = useState<"spread" | "single">("spread");
  const [compactSpread, setCompactSpread] = useState(false);
  const [pdfZoom, setPdfZoom] = useState(100);
  const [zoomDraft, setZoomDraft] = useState("100");
  const epubZoomRef = useRef(pdfZoom);
  epubZoomRef.current = pdfZoom;
  const [pageMotion, setPageMotion] = useState<"forward" | "backward">("forward");
  const [toc, setToc] = useState<Array<{ label: string; href: string }>>([]);
  const [libraryBooks, setLibraryBooks] = useState<LocalBook[]>([]);
  const [libraryError, setLibraryError] = useState("");
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [catalogOpen, setCatalogOpen] = useState(false);

  const refreshLibrary = useCallback(async () => {
    try {
      setLibraryBooks(await listLocalBooks());
      setLibraryError("");
    } catch {
      setLibraryError("This browser could not load your local shelf. You can still choose a file to read.");
    }
  }, []);

  useEffect(() => { void refreshLibrary(); }, [refreshLibrary]);

  const libraryItems = useMemo<LibraryItem[]>(() => libraryBooks.map((book) => {
    let saved: StoredBookState | null = null;
    try { saved = JSON.parse(readReaderState(book.key) || "null") as StoredBookState | null; } catch { /* malformed local state */ }
    const rawProgress = book.kind === "pdf" && book.pageCount
      ? Math.round((Math.min(saved?.lastPage ?? 1, book.pageCount) / book.pageCount) * 100)
      : saved?.lastProgress;
    return {
      book,
      percent: typeof rawProgress === "number" && Number.isFinite(rawProgress) ? Math.max(0, Math.min(100, Math.round(rawProgress))) : null,
      page: book.kind === "pdf" ? saved?.lastPage ?? null : null,
      noteCount: saved?.notes?.length ?? 0,
    };
  }), [libraryBooks, bookKind]);

  const activePageColor = pageColor;
  const theme = pageThemes[activePageColor];
  const pdfRenderScale = Math.min(4, Math.max(1.55, Math.ceil(1.55 * pdfZoom / 100 * 4) / 4));
  const pdfSequence = useMemo(() => bookKind === "pdf" ? buildPdfSequence(pageCount, notes) : [], [bookKind, notes, pageCount]);
  const currentSequenceIndex = Math.max(0, pdfSequence.findIndex((item) => item.key === pdfCursorKey));
  const zenProgressPercent = bookKind === "pdf"
    ? Math.round((currentSequenceIndex + 1) / Math.max(1, pdfSequence.length) * 100)
    : progress;
  const currentPdfLeaf = pdfSequence[currentSequenceIndex];
  const activeNote = bookKind === "pdf"
    ? currentPdfLeaf?.kind === "note" ? currentPdfLeaf.note : null
    : notes.find((note) => note.id === activeNoteId) ?? null;
  const activeHighlight = bookKind === "pdf"
    ? pdfHighlights.find((item) => item.id === activeHighlightId) ?? null
    : epubHighlights.find((item) => item.id === activeHighlightId) ?? null;

  useEffect(() => {
    activeHighlightIdRef.current = activeHighlightId;
  }, [activeHighlightId]);
  const currentBookmarked = bookmarks.some((item) => bookKind === "pdf"
    ? currentPdfLeaf?.kind === "note" ? item.noteId === currentPdfLeaf.note.id : !item.noteId && item.page === currentPage
    : item.cfi === currentCfi);

  const addPdfHighlight = (highlight: PdfHighlight) => {
    setPdfHighlights((items) => items.some((item) => pdfHighlightsOverlap(item, highlight)) ? items : [...items, highlight]);
  };

  const selectHighlight = (id: string) => {
    setActiveHighlightId(id);
  };

  const updateHighlightNote = (note: string) => {
    if (!activeHighlightId) return;
    if (bookKind === "pdf") {
      setPdfHighlights((items) => items.map((item) => item.id === activeHighlightId ? { ...item, note } : item));
    } else {
      const next = epubHighlightsRef.current.map((item) => item.id === activeHighlightId ? { ...item, note } : item);
      epubHighlightsRef.current = next;
      setEpubHighlights(next);
    }
  };

  const removePdfHighlights = (ids: string[]) => {
    setPdfHighlights((items) => items.filter((item) => !ids.includes(item.id)));
    if (activeHighlightId && ids.includes(activeHighlightId)) setActiveHighlightId(null);
  };

  const removeEpubHighlight = (cfi: string) => {
    renditionRef.current?.annotations.remove(cfi, "highlight");
    annotatedCfisRef.current.delete(cfi);
    const removed = epubHighlightsRef.current.find((item) => item.cfi === cfi);
    epubHighlightsRef.current = epubHighlightsRef.current.filter((item) => item.cfi !== cfi);
    if (removed?.id === activeHighlightIdRef.current) setActiveHighlightId(null);
    setEpubHighlights((items) => items.filter((item) => item.cfi !== cfi));
  };

  const dismissEpubSelection = () => {
    pendingEpubRef.current?.clearPreview();
    pendingEpubRef.current?.contents?.window?.getSelection?.()?.removeAllRanges?.();
    pendingEpubRef.current = null;
    setPendingEpubSelection(null);
  };

  useEffect(() => {
    if (highlightMode === "erase" && pendingEpubRef.current) dismissEpubSelection();
  }, [highlightMode]);

  useEffect(() => {
    highlightModeRef.current = highlightMode;
  }, [highlightMode]);

  useEffect(() => {
    highlightColorRef.current = highlightColor;
  }, [highlightColor]);

  useEffect(() => {
    for (const contents of renditionRef.current?.getContents?.() ?? []) applyEpubHighlightPointer(contents, highlightMode, highlightColor);
  }, [highlightMode, highlightColor]);

  useEffect(() => {
    epubHighlightsRef.current = epubHighlights;
  }, [epubHighlights]);

  useEffect(() => {
    const media = window.matchMedia("(max-width: 800px)");
    const update = () => setCompactSpread(media.matches);
    update();
    media.addEventListener("change", update);
    return () => media.removeEventListener("change", update);
  }, []);

  useEffect(() => {
    currentCfiRef.current = currentCfi;
  }, [currentCfi]);

  useEffect(() => {
    try {
      const settings = JSON.parse(readReaderState("camus-reader:settings") || "{}") as { mode?: ReadingMode; color?: PageColor; dark?: boolean; pdfLayout?: "spread" | "single"; pdfZoom?: number };
      if (settings.mode) setReadingMode(settings.mode);
      if (settings.color) setPageColor(settings.color);
      if (settings.pdfLayout === "spread" || settings.pdfLayout === "single") setPdfLayout(settings.pdfLayout);
      if (typeof settings.pdfZoom === "number" && Number.isFinite(settings.pdfZoom) && settings.pdfZoom >= 50 && settings.pdfZoom <= 400) setPdfZoom(settings.pdfZoom);
      setDarkMode(settings.dark ?? window.matchMedia("(prefers-color-scheme: dark)").matches);
    } catch { /* ignore invalid local preferences */ }
  }, []);

  useEffect(() => {
    writeReaderState("camus-reader:settings", JSON.stringify({ mode: readingMode, color: pageColor, dark: darkMode, pdfLayout, pdfZoom }));
    document.documentElement.classList.toggle("dark", darkMode);
  }, [darkMode, pageColor, readingMode, pdfLayout, pdfZoom]);

  useEffect(() => setZoomDraft(String(pdfZoom)), [pdfZoom]);

  const commitZoom = () => {
    const value = Number(zoomDraft);
    if (!Number.isFinite(value) || zoomDraft.trim() === "") {
      setZoomDraft(String(pdfZoom));
      return;
    }
    const next = Math.round(Math.max(50, Math.min(400, value)) * 100) / 100;
    setPdfZoom(next);
    setZoomDraft(String(next));
  };

  useEffect(() => {
    // Retire the previous installable web shell so it cannot serve an older reader after publication.
    if ("serviceWorker" in navigator) {
      void navigator.serviceWorker.getRegistration("/sw.js").then((registration) => {
        if (registration?.active?.scriptURL.endsWith("/sw.js")) return registration.unregister();
      }).catch(() => undefined);
    }
    if ("caches" in window) {
      void caches.keys().then((keys) => Promise.all(keys.filter((key) => key.startsWith("camus-reader-shell-")).map((key) => caches.delete(key)))).catch(() => undefined);
    }
  }, []);

  useEffect(() => {
    if (!bookKey || hydratedKey !== bookKey) return;
    const state: StoredBookState = {
      bookmarks,
      notes,
      pdfHighlights,
      epubHighlights,
      lastPage: currentPage,
      lastCursorKey: bookKind === "pdf" ? pdfCursorKey : undefined,
      lastCfi: currentCfi,
      lastProgress: progress,
    };
    writeReaderState(bookKey, JSON.stringify(state));
  }, [bookKey, bookKind, bookmarks, currentCfi, currentPage, epubHighlights, hydratedKey, notes, pdfCursorKey, pdfHighlights]);

  const hydrateBook = useCallback((key: string) => {
    let stored: StoredBookState = { bookmarks: [], notes: [], pdfHighlights: [], epubHighlights: [] };
    try {
      stored = { ...stored, ...JSON.parse(readReaderState(key) || "{}") };
    } catch { /* start clean */ }
    setBookmarks(stored.bookmarks ?? []);
    setNotes(stored.notes ?? []);
    // Keep existing marks and their notes; the ink layer merges their opacity visually.
    setPdfHighlights(stored.pdfHighlights ?? []);
    const uniqueEpubHighlights = Array.from(new Map((stored.epubHighlights ?? []).map((item) => [item.cfi, item])).values());
    epubHighlightsRef.current = uniqueEpubHighlights;
    setEpubHighlights(uniqueEpubHighlights);
    setActiveHighlightId(null);
    const storedPage = stored.lastPage ?? 1;
    setCurrentPage(storedPage);
    setPdfCursorKey(stored.lastCursorKey ?? `page-${storedPage}`);
    setPageTurn(null);
    turnPendingRef.current = false;
    setBackOpenNoteId(null);
    setCurrentCfi(stored.lastCfi);
    currentCfiRef.current = stored.lastCfi;
    setActiveNoteId(null);
    epubNoteReturnRef.current = null;
    setFrontOpenNoteId(null);
    setSingleNoteFace("front");
    setHydratedKey(key);
    return stored;
  }, []);

  const openFile = useCallback(async (file: File) => {
    const extension = file.name.split(".").pop()?.toLowerCase();
    if (extension !== "pdf" && extension !== "epub") {
      setError("Choose a PDF or EPUB file.");
      return;
    }
    setLoading(true);
    setError("");
    renditionRef.current?.destroy?.();
    epubBookRef.current?.destroy?.();
    setPdfDoc(null);
    const key = bookStorageKey(file);
    setBookKey(key);
    setHydratedKey(null);
    const stored = hydrateBook(key);
    try {
      const data = await file.arrayBuffer();
      let title = file.name.replace(/\.(pdf|epub)$/i, "");
      let documentPages: number | undefined;
      if (extension === "pdf") {
        const pdfjs = await import("pdfjs-dist");
        pdfjs.GlobalWorkerOptions.workerSrc = PdfJsWorkerUrl;
        const document = await pdfjs.getDocument({ data: new Uint8Array(data) }).promise;
        const firstPage = await document.getPage(1);
        const firstViewport = firstPage.getViewport({ scale: 1 });
        setPdfPageRatio(firstViewport.height / firstViewport.width);
        const metadata = await document.getMetadata().catch(() => null);
        const metadataTitle = String((metadata as any)?.info?.Title ?? "").trim();
        title = metadataTitle && metadataTitle.toLowerCase() !== "untitled" ? metadataTitle : file.name.replace(/\.pdf$/i, "");
        setBookTitle(title);
        documentPages = document.numPages;
        setPageCount(document.numPages);
        const openingPage = Math.min(stored.lastPage ?? 1, document.numPages);
        setCurrentPage(openingPage);
        const savedCursor = stored.lastCursorKey;
        const validCursor = savedCursor && buildPdfSequence(document.numPages, stored.notes ?? []).some((leaf) => leaf.key === savedCursor);
        setPdfCursorKey(validCursor ? savedCursor : `page-${openingPage}`);
        setBookKind("pdf");
        setPdfDoc(document);
        setProgress(Math.round(((stored.lastPage ?? 1) / document.numPages) * 100));
        setToc([]);
      } else {
        const module = await import("epubjs");
        const createBook = module.default as any;
        const book = createBook(data);
        epubBookRef.current = book;
        const [metadata, navigation] = await Promise.all([book.loaded.metadata, book.loaded.navigation]);
        title = metadata?.title || file.name.replace(/\.epub$/i, "");
        setBookTitle(title);
        setToc((navigation?.toc ?? []).map((entry: any) => ({ label: entry.label.trim(), href: entry.href })));
        setBookKind("epub");
        setPageCount(0);
        void book.locations.generate(900).catch(() => undefined);
      }
      try {
        const existing = await getLocalBook(key);
        const record: LocalBook = { key, file, title, kind: extension, addedAt: existing?.addedAt ?? Date.now(), lastOpenedAt: Date.now(), pageCount: documentPages };
        await saveLocalBook(record);
        setLibraryBooks((books) => [record, ...books.filter((item) => item.key !== key)]);
        setLibraryError("");
      } catch {
        setLibraryError("This book opened, but the browser could not save it to your shelf. You can import it again later.");
      }
    } catch (cause) {
      console.error("Failed to open book", cause);
      setError("This book could not be opened. It may be damaged, encrypted, or unsupported.");
      setBookKind(null);
    } finally {
      setLoading(false);
    }
  }, [hydrateBook]);

  const openLibraryBook = useCallback(async (key: string) => {
    try {
      const book = await getLocalBook(key);
      if (!book) throw new Error("Missing book");
      await openFile(book.file);
    } catch {
      setLibraryError("That book is no longer available in this browser. Import the original file again.");
    }
  }, [openFile]);

  const addToLibrary = useCallback(async (file: File) => {
    const kind = file.name.split(".").pop()?.toLowerCase();
    if (kind !== "pdf" && kind !== "epub") {
      setLibraryError("Choose a PDF or EPUB file.");
      return;
    }
    setLoading(true);
    setLibraryError("");
    setError("");
    try {
      const key = bookStorageKey(file);
      const existing = await getLocalBook(key);
      const record: LocalBook = {
        key,
        file,
        title: existing?.title ?? file.name.replace(/\.(pdf|epub)$/i, ""),
        kind,
        addedAt: existing?.addedAt ?? Date.now(),
        lastOpenedAt: existing?.lastOpenedAt ?? 0,
        pageCount: existing?.pageCount,
      };
      await saveLocalBook(record);
      setLibraryBooks((books) => [record, ...books.filter((book) => book.key !== key)]);
    } catch {
      setLibraryError("Could not add this book to the shelf. Try again.");
    } finally {
      setLoading(false);
    }
  }, []);

  const removeLibraryBook = useCallback(async (key: string) => {
    try {
      await removeLocalBook(key);
      removeReaderState(key);
      setLibraryBooks((books) => books.filter((book) => book.key !== key));
    } catch {
      setLibraryError("Could not remove this book. Try again.");
    }
  }, []);

  useEffect(() => {
    const launchQueue = (window as any).launchQueue;
    if (!launchQueue?.setConsumer) return;
    launchQueue.setConsumer(async (params: any) => {
      const handle = params?.files?.[0];
      if (!handle?.getFile) return;
      const file = await handle.getFile();
      await openFile(file);
    });
  }, [openFile]);

  useEffect(() => {
    if (bookKind !== "epub" || !epubBookRef.current || !epubHostRef.current) return;
    const host = epubHostRef.current;
    host.innerHTML = "";
    const book = epubBookRef.current;
    const rendition = book.renderTo(host, {
      width: "100%",
      height: "100%",
      flow: readingMode === "scroll" ? "scrolled-doc" : "paginated",
      manager: readingMode === "scroll" ? "continuous" : "default",
      spread: "none",
      allowScriptedContent: false,
    });
    renditionRef.current = rendition;
    const touchCleanups = new Set<() => void>();
    const applyContentStyles = (contents: any) => {
      applyResponsiveEpubLayout(contents);
      applyEpubHighlightPointer(contents, highlightModeRef.current, highlightColorRef.current);
      touchCleanups.add(installEpubTouchSelection(contents, (cfi, range, clearPreview) => {
        showEpubSelection(cfi, contents, range, clearPreview, true);
      }, dismissEpubSelection));
    };
    rendition.hooks.content.register(applyContentStyles);
    annotatedCfisRef.current.clear();
    void rendition.started.then(() => {
      if (renditionRef.current === rendition) rendition.spread("none");
    });
    rendition.themes.register("camus-reader", {
      body: {
        "background-color": `${theme.page} !important`,
        color: `${theme.ink} !important`,
        "font-family": "Georgia, 'Times New Roman', serif !important",
        "line-height": "1.72 !important",
        ...(readingMode === "scroll" ? { padding: "2rem 6% !important", "box-sizing": "border-box !important" } : {}),
      },
      "p, li": { color: `${theme.ink} !important` },
      "a": { color: `${theme.ink} !important` },
      "img, svg, table, pre": { "max-width": "100% !important" },
    });
    rendition.themes.select("camus-reader");
    rendition.themes.override("font-size", epubTextSize(host.clientWidth, pdfZoom), true);
    const annotate = (item: EpubHighlight) => {
      if (annotatedCfisRef.current.has(item.cfi)) return;
      annotatedCfisRef.current.add(item.cfi);
      rendition.annotations.highlight(item.cfi, {}, () => {
        // Dragging a selection handle across existing ink must not open its note.
        if (pendingEpubRef.current || rendition.getContents?.().some((contents: any) => contents.document?.querySelector(".camus-reader-selection-handles"))) return;
        if (highlightModeRef.current === "erase") removeEpubHighlight(item.cfi);
        else setActiveHighlightId(item.id);
      }, item.kind === "strike" ? "camus-reader-strike" : "camus-reader-highlight", {
        fill: item.kind === "strike" ? "#dc2626" : highlightColors[item.color],
        "fill-opacity": "0.3",
        "mix-blend-mode": "normal",
      });
    };
    const onRelocated = (location: any) => {
      if (pendingEpubRef.current) dismissEpubSelection();
      const cfi = location?.start?.cfi;
      const href = location?.start?.href?.split("#")[0];
      const chapter = book.navigation?.toc?.find((item: any) => item.href?.split("#")[0] === href)?.label?.trim();
      const label = location?.start?.displayed?.page
        ? `${chapter ? `${chapter} · ` : ""}Page ${location.start.displayed.page}`
        : chapter || href?.split("/").pop() || "Current position";
      setCurrentCfi(cfi);
      setCurrentLabel(label);
      setProgress(locationPercent(location?.start?.percentage ?? book.locations.percentageFromCfi?.(cfi)));
    };
    const showEpubSelection = (cfiRange: string, contents: any, range: Range, clearPreview: () => void, hasHandles = false) => {
      // A handle drag updates the same selection without removing its handles.
      if (pendingEpubRef.current?.clearPreview !== clearPreview) dismissEpubSelection();
      const overlapping = epubHighlightsRef.current.filter((item) => epubHighlightsOverlap(item.cfi, cfiRange, contents));
      if (highlightModeRef.current === "erase") {
        for (const item of overlapping) removeEpubHighlight(item.cfi);
        clearPreview();
      } else {
        const text = range.toString().trim().replace(/\s+/g, " ");
        if (!text) { clearPreview(); return; }
        const frame = contents?.window?.frameElement?.getBoundingClientRect?.();
        const rects = range.getClientRects();
        const rect = rects[rects.length - 1] ?? range.getBoundingClientRect();
        const hostRect = epubHostRef.current?.getBoundingClientRect();
        const menuWidth = Math.min(318, Math.max(0, (hostRect?.width ?? 334) - 16));
        const left = frame && hostRect ? Math.max(8, Math.min((frame.left + rect.left - hostRect.left), (hostRect.width - menuWidth - 8))) : 8;
        const above = frame && hostRect ? frame.top + rect.top - hostRect.top - 55 : -1;
        const below = frame && hostRect ? frame.top + rect.bottom - hostRect.top + (hasHandles ? 46 : 9) : 12;
        const top = above >= 8 ? above : hostRect ? Math.min(below, hostRect.height - 52) : below;
        pendingEpubRef.current = { cfi: cfiRange, text, contents, overlaps: overlapping.length > 0, clearPreview };
        setPendingEpubSelection({ left, top });
      }
    };
    const onSelected = (cfiRange: string, contents: any) => {
      const selection = contents?.window?.getSelection?.();
      if (!selection?.rangeCount) return;
      const range = selection.getRangeAt(0).cloneRange();
      if (pendingEpubRef.current) dismissEpubSelection();
      showEpubSelection(cfiRange, contents, range, paintEpubSelection(contents, range));
      selection.removeAllRanges();
    };
    confirmEpubRef.current = (kind) => {
      const pending = pendingEpubRef.current;
      if (!pending || pending.overlaps) return;
      if (!epubHighlightsRef.current.some((item) => epubHighlightsOverlap(item.cfi, pending.cfi, pending.contents))) {
        const item: EpubHighlight = { id: makeId(), cfi: pending.cfi, kind, color: highlightColorRef.current, text: pending.text.slice(0, 160) };
        annotate(item);
        epubHighlightsRef.current = [...epubHighlightsRef.current, item];
        setEpubHighlights(epubHighlightsRef.current);
      }
      dismissEpubSelection();
    };
    copyEpubRef.current = () => {
      const text = pendingEpubRef.current?.text;
      if (!text) return;
      const legacyCopy = () => {
        const textarea = document.createElement("textarea");
        textarea.value = text;
        textarea.style.position = "fixed";
        textarea.style.opacity = "0";
        document.body.appendChild(textarea);
        textarea.select();
        document.execCommand("copy");
        textarea.remove();
      };
      if (navigator.clipboard?.writeText) void navigator.clipboard.writeText(text).catch(legacyCopy);
      else legacyCopy();
      dismissEpubSelection();
    };
    rendition.on("relocated", onRelocated);
    rendition.on("selected", onSelected);
    let lastWidth = host.clientWidth;
    let lastHeight = host.clientHeight;
    const resizeObserver = new ResizeObserver(() => {
      const width = host.clientWidth;
      const height = host.clientHeight;
      if (!rendition.manager || !width || !height || (Math.abs(width - lastWidth) < 2 && Math.abs(height - lastHeight) < 2)) return;
      lastWidth = width;
      lastHeight = height;
      rendition.themes.override("font-size", epubTextSize(width, epubZoomRef.current), true);
      rendition.resize(width, height, currentCfiRef.current);
    });
    resizeObserver.observe(host);
    void rendition.display(currentCfiRef.current).then(() => {
      if (renditionRef.current !== rendition) return;
      for (const item of epubHighlightsRef.current) annotate(item);
    });
    return () => {
      confirmEpubRef.current = null;
      copyEpubRef.current = null;
      dismissEpubSelection();
      for (const cleanup of touchCleanups) cleanup();
      resizeObserver.disconnect();
      rendition.hooks.content.deregister(applyContentStyles);
      rendition.off?.("relocated", onRelocated);
      rendition.off?.("selected", onSelected);
      rendition.destroy?.();
      if (renditionRef.current === rendition) renditionRef.current = null;
    };
  // Highlights and color changes update annotations in place; neither should reload the EPUB.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [bookKind, readingMode, theme.ink, theme.page]);

  useEffect(() => {
    if (bookKind === "epub") renditionRef.current?.themes.override("font-size", epubTextSize(epubHostRef.current?.clientWidth ?? 800, pdfZoom), true);
  }, [bookKind, pdfZoom, zenMode]);

  const goToPdfPage = useCallback((page: number) => {
    const target = Math.max(1, Math.min(pageCount, page));
    setPageMotion(target < currentPage ? "backward" : "forward");
    setCurrentPage(target);
    setPdfCursorKey(`page-${target}`);
    setPageTurn(null);
    turnPendingRef.current = false;
    setFrontOpenNoteId(null);
    setBackOpenNoteId(null);
    setSingleNoteFace("front");
    setProgress(pageCount ? Math.round((target / pageCount) * 100) : 0);
    setActiveNoteId(null);
    if (readingMode === "scroll" || zenMode) {
      requestAnimationFrame(() => document.querySelector(`[data-leaf-key="page-${target}"]`)?.scrollIntoView({ behavior: "smooth", block: "start" }));
    }
  }, [currentPage, pageCount, readingMode, zenMode]);

  // Keep the physical leaf sequence in sync with continuous PDF scrolling.
  useLayoutEffect(() => {
    if (bookKind !== "pdf" || !pdfDoc || (readingMode !== "scroll" && !zenMode)) return;
    const stage = readerStageRef.current;
    if (!stage) return;
    stage.querySelector(`[data-leaf-key="${pdfCursorRef.current}"]`)?.scrollIntoView({ behavior: "auto", block: "start" });
  }, [bookKind, pdfDoc, readingMode, zenMode]);

  useEffect(() => {
    if (bookKind !== "pdf" || !pdfDoc || (readingMode !== "scroll" && !zenMode)) return;
    const stage = readerStageRef.current;
    if (!stage) return;
    let frame = 0;
    const updatePosition = () => {
      frame = 0;
      const leaves = stage.querySelectorAll<HTMLElement>("[data-leaf-key]");
      const sightline = stage.getBoundingClientRect().top + stage.clientHeight * .35;
      let low = 0;
      let high = leaves.length;
      while (low < high) {
        const middle = (low + high) >>> 1;
        if (leaves[middle].getBoundingClientRect().top <= sightline) low = middle + 1;
        else high = middle;
      }
      const key = leaves[Math.max(0, low - 1)]?.dataset.leafKey;
      if (!key || key === pdfCursorRef.current) return;
      const leaf = pdfSequence.find((item) => item.key === key);
      if (!leaf) return;
      pdfCursorRef.current = key;
      setPdfCursorKey(key);
      const page = leaf.kind === "page" ? leaf.page : leaf.note.afterPage ?? 1;
      setCurrentPage(page);
      setProgress(pageCount ? Math.round(page / pageCount * 100) : 0);
    };
    const onScroll = () => { if (!frame) frame = requestAnimationFrame(updatePosition); };
    stage.addEventListener("scroll", onScroll, { passive: true });
    return () => { stage.removeEventListener("scroll", onScroll); if (frame) cancelAnimationFrame(frame); };
  }, [bookKind, pdfDoc, readingMode, zenMode, pdfSequence, pageCount]);

  const openBookmark = useCallback((bookmark: BookmarkItem) => {
    setMobilePanelOpen(false);
    setDesktopPanelOpen(false);
    setActiveNoteId(null);
    setPageTurn(null);
    turnPendingRef.current = false;
    if (bookmark.noteId && bookKind === "pdf") {
      const note = notes.find((item) => item.id === bookmark.noteId);
      if (note?.afterPage) {
        setCurrentPage(note.afterPage);
        setPdfCursorKey(`note-${note.id}`);
        setFrontOpenNoteId(note.id);
        setBackOpenNoteId(null);
        setSingleNoteFace("front");
        if (readingMode === "scroll" || zenMode) requestAnimationFrame(() => document.querySelector(`[data-leaf-key="note-${note.id}"]`)?.scrollIntoView({ behavior: "smooth", block: "start" }));
      }
    } else if (bookmark.page) goToPdfPage(bookmark.page);
    else if (bookmark.cfi) void renditionRef.current?.display(bookmark.cfi);
  }, [bookKind, goToPdfPage, notes, readingMode, zenMode]);

  const openBookmarkList = () => {
    setPanelTab("marks");
    if (zenMode || window.matchMedia("(min-width: 1024px)").matches) {
      setDesktopPanelOpen(true);
    } else {
      setMobilePanelOpen(true);
    }
  };

  const toggleBookmark = useCallback(() => {
    if (!bookKind) return;
    const existing = bookmarks.find((item) => bookKind === "pdf"
      ? currentPdfLeaf?.kind === "note" ? item.noteId === currentPdfLeaf.note.id : !item.noteId && item.page === currentPage
      : item.cfi === currentCfi);
    if (existing) {
      setBookmarks((items) => items.filter((item) => item.id !== existing.id));
      return;
    }
    setBookmarks((items) => [...items, {
      id: makeId(),
      page: bookKind === "pdf" && currentPdfLeaf?.kind !== "note" ? currentPage : undefined,
      noteId: bookKind === "pdf" && currentPdfLeaf?.kind === "note" ? currentPdfLeaf.note.id : undefined,
      cfi: bookKind === "epub" ? currentCfi : undefined,
      label: bookKind === "pdf" ? currentPdfLeaf?.kind === "note" ? "Inserted sheet" : `PDF page ${currentPage}` : currentLabel,
    }]);
  }, [bookKind, bookmarks, currentCfi, currentLabel, currentPage, currentPdfLeaf]);

  const insertNote = useCallback((title = "", content = "") => {
    if (!bookKind) return null;
    const note: NotePage = {
      id: makeId(),
      afterPage: bookKind === "pdf" ? currentPage : undefined,
      cfi: bookKind === "epub" ? currentCfi : undefined,
      anchorLabel: bookKind === "pdf" ? `page ${currentPage}` : currentLabel,
      title,
      content,
    };
    setNotes((items) => bookKind === "pdf" ? insertPdfNoteAfter(items, note, pdfCursorKey) : [...items, note]);
    setPageTurn(null);
    turnPendingRef.current = false;
    setFrontOpenNoteId(bookKind === "pdf" && readingMode === "page" && !zenMode ? note.id : null);
    setBackOpenNoteId(null);
    setSingleNoteFace("front");
    setPageMotion("forward");
    if (bookKind === "pdf" && (readingMode === "scroll" || zenMode)) {
      setPdfCursorKey(`note-${note.id}`);
      setActiveNoteId(null);
      requestAnimationFrame(() => requestAnimationFrame(() => {
        document.querySelector(`[data-leaf-key="note-${note.id}"]`)?.scrollIntoView({ behavior: "smooth", block: "start" });
      }));
    } else {
      if (bookKind === "pdf") setPdfCursorKey(`note-${note.id}`);
      else setActiveNoteId(note.id);
    }
    return note;
  }, [bookKind, currentCfi, currentLabel, currentPage, pdfCursorKey, readingMode, zenMode]);

  const updateNote = (next: NotePage) => setNotes((items) => items.map((item) => item.id === next.id ? next : item));
  const deleteNote = (id: string) => {
    const note = notes.find((item) => item.id === id);
    setNotes((items) => items.filter((item) => item.id !== id));
    setBookmarks((items) => items.filter((item) => item.noteId !== id));
    setPageTurn(null);
    turnPendingRef.current = false;
    setFrontOpenNoteId(null);
    setBackOpenNoteId(null);
    if (bookKind === "pdf" && pdfCursorKey === `note-${id}`) setPdfCursorKey(`page-${note?.afterPage ?? currentPage}`);
    setActiveNoteId(null);
  };

  const closeNote = () => {
    if (bookKind === "pdf") setPdfCursorKey(`page-${currentPage}`);
    setPageTurn(null);
    turnPendingRef.current = false;
    setFrontOpenNoteId(null);
    setBackOpenNoteId(null);
    setActiveNoteId(null);
  };

  const navigate = useCallback((direction: -1 | 1) => {
    if (bookKind === "pdf") {
      if (readingMode === "scroll" || zenMode) {
        const target = adjacentPdfLeaf(pdfSequence, pdfCursorKey, direction);
        if (!target) return;
        setPdfCursorKey(target.key);
        const page = target.kind === "page" ? target.page : target.note.afterPage ?? currentPage;
        setCurrentPage(page);
        setProgress(pageCount ? Math.round(page / pageCount * 100) : 0);
        requestAnimationFrame(() => document.querySelector(`[data-leaf-key="${target.key}"]`)?.scrollIntoView({ behavior: "smooth", block: "start" }));
        return;
      }
      if (turnPendingRef.current || pageTurn) return;
      const target = adjacentPdfLeaf(pdfSequence, pdfCursorKey, direction);
      if (!target || target.key === pdfCursorKey) return;
      if (pdfDoc && !window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
        turnPendingRef.current = true;
        const targetIndex = pdfSequence.findIndex((leaf) => leaf.key === target.key);
        const backNoteId = !zenMode && !compactSpread && pdfLayout === "spread" && direction === 1 && currentPdfLeaf?.kind === "note" && frontOpenNoteId === currentPdfLeaf.note.id ? currentPdfLeaf.note.id : undefined;
        const visible = zenMode || compactSpread || pdfLayout === "single" ? [target] : backNoteId
          ? [pdfSequence[targetIndex - 1], target]
          : [target, pdfSequence[targetIndex + 1]];
        const pages = visible.filter((leaf) => leaf?.kind === "page").map((leaf) => cachedPdfPage(pdfDoc, leaf.page, pdfRenderScale));
        void Promise.all(pages).then(() => {
          if (pdfCursorRef.current !== pdfCursorKey) { turnPendingRef.current = false; return; }
          setPageTurn({ fromKey: pdfCursorKey, toKey: target.key, direction, started: false, backNoteId });
          requestAnimationFrame(() => requestAnimationFrame(() => {
            setPageTurn((turn) => turn?.fromKey === pdfCursorRef.current ? { ...turn, started: true } : turn);
          }));
        }).catch(() => { turnPendingRef.current = false; });
        return;
      }
      setPageMotion(direction > 0 ? "forward" : "backward");
      setPdfCursorKey(target.key);
      setFrontOpenNoteId(null);
      setBackOpenNoteId(null);
      setSingleNoteFace("front");
      if (target.kind === "page") {
        setCurrentPage(target.page);
        setProgress(pageCount ? Math.round((target.page / pageCount) * 100) : 0);
      } else {
        const anchorPage = target.note.afterPage ?? currentPage;
        setCurrentPage(anchorPage);
        setProgress(pageCount ? Math.round((anchorPage / pageCount) * 100) : 0);
      }
      return;
    }
    if (bookKind === "epub") {
      if (activeNoteId) {
        const noteId = activeNoteId;
        const index = notes.findIndex((item) => item.id === noteId);
        const sibling = notes.find((item, i) => i === index + direction && item.cfi === notes[index]?.cfi);
        if (sibling) {
          setSingleNoteFace(direction > 0 ? "front" : "back");
          setActiveNoteId(sibling.id);
          return;
        }
        setActiveNoteId(null);
        if (direction < 0) return;
        void renditionRef.current?.next().then(() => {
          const nextCfi = renditionRef.current?.currentLocation()?.start?.cfi;
          if (nextCfi) epubNoteReturnRef.current = { noteId, nextCfi };
        });
        return;
      }
      if (direction < 0 && currentCfi && epubNoteReturnRef.current?.nextCfi === currentCfi) {
        const note = notes.find((item) => item.id === epubNoteReturnRef.current?.noteId);
        if (note) {
          if (note.cfi) void renditionRef.current?.display(note.cfi);
          setSingleNoteFace("back");
          setActiveNoteId(note.id);
          return;
        }
      }
      if (direction > 0) {
        const inserted = notes.find((item) => item.cfi && item.cfi === currentCfi);
        if (inserted) {
          setSingleNoteFace("front");
          setActiveNoteId(inserted.id);
          return;
        }
      }
      epubNoteReturnRef.current = null;
      if (direction > 0) void renditionRef.current?.next();
      else void renditionRef.current?.prev().then(() => {
        const cfi = renditionRef.current?.currentLocation()?.start?.cfi;
        const inserted = [...notes].reverse().find((item) => item.cfi && item.cfi === cfi);
        if (inserted) {
          setSingleNoteFace("back");
          setActiveNoteId(inserted.id);
        }
      });
    }
  }, [activeNoteId, bookKind, compactSpread, currentCfi, currentPage, currentPdfLeaf, frontOpenNoteId, goToPdfPage, notes, pageCount, pageTurn, pdfCursorKey, pdfDoc, pdfLayout, pdfRenderScale, pdfSequence, readingMode, zenMode]);

  const finishPageTurn = useCallback(() => {
    if (!pageTurn) return;
    const target = pdfSequence.find((leaf) => leaf.key === pageTurn.toKey);
    if (!target) { setPageTurn(null); turnPendingRef.current = false; return; }
    setPdfCursorKey(target.key);
    setFrontOpenNoteId(null);
    setBackOpenNoteId(pageTurn.backNoteId ?? null);
    setSingleNoteFace("front");
    const page = target.kind === "page" ? target.page : target.note.afterPage ?? currentPage;
    setCurrentPage(page);
    setProgress(pageCount ? Math.round(page / pageCount * 100) : 0);
    setPageTurn(null);
    turnPendingRef.current = false;
    lastWheelTurnRef.current = Date.now();
  }, [currentPage, pageCount, pageTurn, pdfSequence]);

  useEffect(() => {
    if (!pageTurn?.started) return;
    const timeout = window.setTimeout(finishPageTurn, 850);
    return () => window.clearTimeout(timeout);
  }, [finishPageTurn, pageTurn?.started]);

  useEffect(() => {
    const stage = readerStageRef.current;
    if (!stage || readingMode !== "page" || bookKind !== "pdf" || zenMode) return;
    let movement = 0;
    let lastWheel = 0;
    const onWheel = (event: WheelEvent) => {
      if (event.ctrlKey || event.metaKey || Math.abs(event.deltaX) > Math.abs(event.deltaY)) return;
      const target = event.target as HTMLElement;
      if (target.closest("input, textarea, [contenteditable=true]")) return;
      const direction = event.deltaY > 0 ? 1 : -1;
      for (let node: HTMLElement | null = target; node && node !== stage; node = node.parentElement) {
        if (node.scrollHeight > node.clientHeight + 4 &&
          (direction > 0 ? node.scrollTop + node.clientHeight < node.scrollHeight - 4 : node.scrollTop > 4)) return;
      }
      if (stage.scrollHeight > stage.clientHeight + 4 &&
        (direction > 0 ? stage.scrollTop + stage.clientHeight < stage.scrollHeight - 4 : stage.scrollTop > 4)) return;
      event.preventDefault();
      if (Date.now() - lastWheelTurnRef.current < 420) return;
      if (Date.now() - lastWheel > 240) movement = 0;
      lastWheel = Date.now();
      movement += event.deltaY;
      if (Math.abs(movement) >= 90) {
        movement = 0;
        navigate(direction as -1 | 1);
      }
    };
    stage.addEventListener("wheel", onWheel, { passive: false });
    return () => stage.removeEventListener("wheel", onWheel);
  }, [bookKind, navigate, readingMode, zenMode]);

  useEffect(() => {
    const stage = readerStageRef.current;
    if (!stage || readingMode !== "page" || bookKind !== "pdf") return;
    let start: { x: number; y: number } | null = null;
    const onStart = (event: TouchEvent) => {
      if (event.touches.length !== 1 || (event.target as HTMLElement).closest("input, textarea")) { start = null; return; }
      start = { x: event.touches[0].clientX, y: event.touches[0].clientY };
    };
    const onEnd = (event: TouchEvent) => {
      if (!start || !event.changedTouches.length || stage.scrollWidth > stage.clientWidth + 4) return;
      const dx = event.changedTouches[0].clientX - start.x;
      const dy = event.changedTouches[0].clientY - start.y;
      start = null;
      if (Math.abs(dx) > 65 && Math.abs(dx) > Math.abs(dy) * 1.4) navigate(dx < 0 ? 1 : -1);
    };
    stage.addEventListener("touchstart", onStart, { passive: true });
    stage.addEventListener("touchend", onEnd, { passive: true });
    return () => { stage.removeEventListener("touchstart", onStart); stage.removeEventListener("touchend", onEnd); };
  }, [bookKind, navigate, readingMode]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape" && zenMode) { setZenMode(false); return; }
      const target = event.target as HTMLElement | null;
      if (target?.matches("input, textarea, [contenteditable=true]")) return;
      if (event.key === "ArrowRight" && (readingMode === "page" || zenMode)) { event.preventDefault(); navigate(1); }
      if (event.key === "ArrowLeft" && (readingMode === "page" || zenMode)) { event.preventDefault(); navigate(-1); }
      if (zenMode) return;
      if (event.key.toLowerCase() === "b") toggleBookmark();
      if (event.key.toLowerCase() === "h") setHighlightMode((mode) => mode === "draw" ? null : "draw");
      if (event.key.toLowerCase() === "e") setHighlightMode((mode) => mode === "erase" ? null : "erase");
      if (event.key.toLowerCase() === "n") insertNote();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [insertNote, navigate, readingMode, toggleBookmark, zenMode]);

  useEffect(() => {
    const context = (document as any).modelContext;
    if (!context?.registerTool || !bookKind) return;
    const lifecycle = new AbortController();
    const register = (tool: any) => {
      try { void Promise.resolve(context.registerTool(tool, { signal: lifecycle.signal })).catch(() => undefined); } catch { /* unsupported preview */ }
    };
    register({
      name: "get_reader_state",
      title: "Get reader state",
      description: "Read the currently open book, location, page color, and saved-mark counts.",
      inputSchema: { type: "object", properties: {}, additionalProperties: false },
      annotations: { readOnlyHint: true, untrustedContentHint: false },
      execute: () => ({ title: bookTitle, kind: bookKind, location: bookKind === "pdf" ? `Page ${currentSequenceIndex + 1}` : currentLabel, pageColor, bookmarks: bookmarks.length, notePages: notes.length }),
    });
    register({
      name: "add_bookmark_at_current_location",
      title: "Add bookmark",
      description: "Add a bookmark at the current visible location in the open book.",
      inputSchema: { type: "object", properties: {}, additionalProperties: false },
      annotations: { readOnlyHint: false, untrustedContentHint: false },
      execute: () => { toggleBookmark(); return { location: bookKind === "pdf" ? `Page ${currentSequenceIndex + 1}` : currentLabel }; },
    });
    register({
      name: "insert_note_page",
      title: "Insert note page",
      description: "Insert a writable page after the current reading location.",
      inputSchema: { type: "object", properties: { title: { type: "string" }, content: { type: "string" } }, additionalProperties: false },
      annotations: { readOnlyHint: false, untrustedContentHint: true },
      execute: (input: { title?: string; content?: string }) => {
        const note = insertNote(input?.title ?? "", input?.content ?? "");
        return { id: note?.id, after: note?.anchorLabel };
      },
    });
    return () => lifecycle.abort();
  }, [bookKind, bookTitle, bookmarks.length, currentLabel, currentSequenceIndex, insertNote, notes.length, pageColor, toggleBookmark]);

  const openFromDrop = (files: FileList | null) => {
    const file = files?.[0];
    if (file) void openFile(file);
  };

  const readerStyle = {
    "--reader-page": theme.page,
    "--reader-ink": theme.ink,
    "--reader-shell": theme.shell,
    "--reader-tint": theme.tint,
  } as CSSProperties;

  const toggleDarkMode = () => {
    if (!darkMode) {
      lightPageColorRef.current = pageColor;
      setPageColor("night");
    } else if (pageColor === "night") {
      setPageColor(lightPageColorRef.current);
    }
    setDarkMode(!darkMode);
  };

  const enterZenMode = () => {
    setPageTurn(null);
    turnPendingRef.current = false;
    setMobilePanelOpen(false);
    setHighlightMode(null);
    setZenMode(true);
  };

  const jumpToPdfLeaf = (key: string) => {
    const index = pdfSequence.findIndex((item) => item.key === key);
    if (index < 0) return;
    setPageMotion(index < currentSequenceIndex ? "backward" : "forward");
    const leaf = pdfSequence[index];
    setPageTurn(null);
    turnPendingRef.current = false;
    if (leaf.kind === "page") goToPdfPage(leaf.page);
    else {
      setCurrentPage(leaf.note.afterPage ?? currentPage);
      setPdfCursorKey(leaf.key);
      setFrontOpenNoteId(leaf.note.id);
      setSingleNoteFace("front");
      if (readingMode === "scroll" || zenMode) {
        requestAnimationFrame(() => document.querySelector(`[data-leaf-key="${leaf.key}"]`)?.scrollIntoView({ behavior: "smooth", block: "start" }));
      }
    }
  };

  useEffect(() => {
    const onAndroidBack = () => {
      if (mobilePanelOpen || desktopPanelOpen) { setMobilePanelOpen(false); setDesktopPanelOpen(false); return; }
      if (activeHighlightId) { setActiveHighlightId(null); return; }
      if (zenMode) { setZenMode(false); return; }
      if (activeNoteId) { setActiveNoteId(null); return; }
      if (bookKind) { setBookKind(null); return; }
      if (settingsOpen || catalogOpen) { setSettingsOpen(false); setCatalogOpen(false); return; }
      (window as Window & { CamusReaderNative?: { lock(): void } }).CamusReaderNative?.lock();
    };
    window.addEventListener("camus-reader:android-back", onAndroidBack);
    return () => window.removeEventListener("camus-reader:android-back", onAndroidBack);
  }, [activeHighlightId, activeNoteId, bookKind, catalogOpen, desktopPanelOpen, mobilePanelOpen, settingsOpen, zenMode]);

  if (!bookKind) {
    return (
      <TooltipProvider>
        {catalogOpen
          ? <GutenbergCatalog dark={darkMode} onBack={() => setCatalogOpen(false)} />
          : settingsOpen
            ? <SettingsHome dark={darkMode} onToggleTheme={toggleDarkMode} onBack={() => setSettingsOpen(false)} />
            : <LibraryHome items={libraryItems} dark={darkMode} loading={loading} error={error || libraryError} onToggleTheme={toggleDarkMode} onSettings={() => setSettingsOpen(true)} onExplore={() => setCatalogOpen(true)} onImport={() => fileInputRef.current?.click()} onImportFile={(file) => { void addToLibrary(file); }} onOpen={(key) => { void openLibraryBook(key); }} onRemove={removeLibraryBook} />}
        <input ref={fileInputRef} type="file" accept=".pdf,.epub,application/pdf,application/epub+zip" className="sr-only" onClick={(event) => { event.currentTarget.value = ""; }} onChange={(event) => { const file = event.target.files?.[0]; if (file) void addToLibrary(file); }} />
      </TooltipProvider>
    );
  }

  const marksPanel = (
    <MarksPanel
      bookmarks={bookmarks.map((item) => {
        if (bookKind !== "pdf") return item;
        const key = item.noteId ? `note-${item.noteId}` : `page-${item.page}`;
        const index = pdfSequence.findIndex((leaf) => leaf.key === key);
        return index < 0 ? item : { ...item, label: `Page ${index + 1}${item.noteId ? " · inserted sheet" : ""}` };
      })}
      notes={notes.map((note) => {
        if (bookKind !== "pdf") return note;
        const index = pdfSequence.findIndex((leaf) => leaf.key === `note-${note.id}`);
        return index < 0 ? note : { ...note, displayPageNumber: index + 1 };
      })}
      highlights={bookKind === "pdf"
        ? pdfHighlights.map((item) => ({ id: item.id, label: `Page ${item.page} · ${item.color}`, color: item.color }))
        : epubHighlights.map((item, index) => ({ id: item.id, label: item.kind === "strike" ? `Strikethrough · ${item.text || `passage ${index + 1}`}` : item.text || `EPUB highlight ${index + 1}`, color: item.color, kind: item.kind }))}
      onOpenBookmark={openBookmark}
      onOpenNote={(note) => {
        setPageTurn(null);
        turnPendingRef.current = false;
        setSingleNoteFace("front");
        if (bookKind === "pdf" && note.afterPage) {
          setCurrentPage(note.afterPage);
          setPdfCursorKey(`note-${note.id}`);
          setFrontOpenNoteId(note.id);
          setSingleNoteFace("front");
        }
        if (bookKind === "epub") {
          if (note.cfi) void renditionRef.current?.display(note.cfi);
          setActiveNoteId(note.id);
        }
        setMobilePanelOpen(false);
        setDesktopPanelOpen(false);
      }}
      onDeleteBookmark={(id) => setBookmarks((items) => items.filter((item) => item.id !== id))}
      onDeleteHighlight={(id) => {
        if (bookKind === "pdf") removePdfHighlights([id]);
        else {
          const item = epubHighlights.find((highlight) => highlight.id === id);
          if (item) removeEpubHighlight(item.cfi);
        }
      }}
    />
  );

  const navigationContent = (
    <div className="reader-navigation">
      <div className="navigation-header">
        <div><span className="navigation-overline">In this book</span><h2>Navigation</h2></div>
        <button type="button" aria-label="Close navigation" className="navigation-close" onClick={() => { setDesktopPanelOpen(false); setMobilePanelOpen(false); }}><X className="size-4" /></button>
      </div>
      <div className="navigation-tabs" role="tablist" aria-label="Navigation sections">
        <button type="button" role="tab" aria-selected={panelTab === "pages"} className={panelTab === "pages" ? "is-selected" : ""} onClick={() => setPanelTab("pages")}>{bookKind === "pdf" ? "Pages" : "Contents"}</button>
        <button type="button" role="tab" aria-selected={panelTab === "marks"} className={panelTab === "marks" ? "is-selected" : ""} onClick={() => setPanelTab("marks")}>My marks <span>{bookmarks.length + notes.length + pdfHighlights.length + epubHighlights.length}</span></button>
      </div>
      <div className="navigation-body scrollbar-thin" role="tabpanel">
        {panelTab === "marks" ? marksPanel : bookKind === "pdf" ? (
          <div className="page-list" aria-label="Book pages">
            {pdfSequence.map((leaf, index) => (
              <button key={leaf.key} type="button" className={`page-list-item ${index === currentSequenceIndex ? "is-current" : ""}`} aria-current={index === currentSequenceIndex ? "page" : undefined} onClick={() => { jumpToPdfLeaf(leaf.key); setMobilePanelOpen(false); }}>
                <span className="page-list-number">{index + 1}</span>
                <span className="page-list-label">{leaf.kind === "note" ? leaf.note.title || "Inserted sheet" : `Page ${leaf.page}`}{leaf.kind === "note" && <small>Inserted sheet</small>}</span>
                {leaf.kind === "note" && <span className="page-list-note" aria-hidden="true">+</span>}
              </button>
            ))}
          </div>
        ) : toc.length ? (
          <div className="page-list">{toc.map((entry) => <button key={entry.href} type="button" className="page-list-item" onClick={() => { setActiveNoteId(null); void renditionRef.current?.display(entry.href); setMobilePanelOpen(false); }}><span className="page-list-label">{entry.label}</span></button>)}</div>
        ) : <p className="marks-empty">This EPUB has no table of contents.</p>}
      </div>
    </div>
  );

  const renderSpreadLeaf = (leaf: (typeof pdfSequence)[number] | undefined, selected: boolean, face: "front" | "back" = "front", onFlip?: () => void) => {
    if (!leaf) return <div className="spread-empty" aria-hidden="true" />;
    if (leaf.kind === "note") return (
      <NoteSheet
        note={leaf.note}
        theme={theme}
        face={face}
        pageNumber={pdfSequence.findIndex((item) => item.key === leaf.key) + 1}
        onChange={updateNote}
        onDelete={() => deleteNote(leaf.note.id)}
        onClose={selected ? closeNote : undefined}
        onFlip={onFlip}
        focusOnOpen={selected}
      />
    );
    return (
      <PdfPage
        pdfDoc={pdfDoc}
        pageNumber={leaf.page}
        renderScale={pdfRenderScale}
        theme={activePageColor}
        highlights={pdfHighlights.filter((item) => item.page === leaf.page)}
        highlightMode={highlightMode}
        highlightColor={highlightColor}
        onAddHighlight={addPdfHighlight}
        onRemoveHighlights={removePdfHighlights}
        onSelectHighlight={(item) => selectHighlight(item.id)}
        onVisible={() => undefined}
        priority
      />
    );
  };

  // A newly opened sheet shows its front on the right, beside the page it follows.
  // Turning the page shows its independent back on the left.
  const singleVisibleLeaf = zenMode || pdfLayout === "single" || compactSpread;
  const openedNoteFront = !singleVisibleLeaf && currentPdfLeaf?.kind === "note" && frontOpenNoteId === currentPdfLeaf.note.id;
  const leftSpreadLeaf = openedNoteFront ? pdfSequence[currentSequenceIndex - 1] : currentPdfLeaf;
  const rightSpreadLeaf = openedNoteFront ? currentPdfLeaf : pdfSequence[currentSequenceIndex + 1];
  const destinationIndex = pageTurn ? pdfSequence.findIndex((leaf) => leaf.key === pageTurn.toKey) : -1;
  const destinationLeft = pageTurn?.backNoteId ? pdfSequence[destinationIndex - 1] : pdfSequence[destinationIndex];
  const destinationRight = pageTurn?.backNoteId ? pdfSequence[destinationIndex] : pdfSequence[destinationIndex + 1];
  const previousPdfLeaf = pdfSequence[currentSequenceIndex - 1];
  const showBackOfSheet = !singleVisibleLeaf && currentPdfLeaf?.kind === "page" && previousPdfLeaf?.kind === "note" && backOpenNoteId === previousPdfLeaf.note.id;
  const displayedLeft = showBackOfSheet ? pdfSequence[currentSequenceIndex - 1] : leftSpreadLeaf;
  const displayedRight = showBackOfSheet ? currentPdfLeaf : rightSpreadLeaf;

  const appearanceOptions = (includeZoom: boolean) => <>
    {includeZoom && <div className="mobile-zoom-row" aria-label={bookKind === "epub" ? "EPUB text size" : "PDF zoom"}>
      <span>Size</span>
      <button type="button" aria-label="Zoom out" disabled={pdfZoom <= 50} onClick={() => setPdfZoom((value) => Math.max(50, value - 1))}><Minus className="size-4" /></button>
      <input type="text" inputMode="decimal" aria-label="Zoom percentage" value={zoomDraft} onFocus={(event) => event.target.select()} onChange={(event) => setZoomDraft(event.target.value)} onBlur={commitZoom} onKeyDown={(event) => { event.stopPropagation(); if (event.key === "Enter") event.currentTarget.blur(); }} />
      <span>%</span>
      <button type="button" aria-label="Zoom in" disabled={pdfZoom >= 400} onClick={() => setPdfZoom((value) => Math.min(400, value + 1))}><Plus className="size-4" /></button>
    </div>}
    <DropdownMenuLabel>Page color</DropdownMenuLabel>
    <DropdownMenuRadioGroup value={pageColor} onValueChange={(value) => setPageColor(value as PageColor)}>{(Object.keys(pageThemes) as PageColor[]).map((color) => <DropdownMenuRadioItem key={color} value={color}><span className="size-3 rounded-full border border-black/15" style={{ background: pageThemes[color].page }} />{pageThemes[color].label}</DropdownMenuRadioItem>)}</DropdownMenuRadioGroup>
    <DropdownMenuSeparator /><DropdownMenuLabel>Reading mode</DropdownMenuLabel>
    <DropdownMenuRadioGroup value={readingMode} onValueChange={(value) => { setPageTurn(null); turnPendingRef.current = false; setActiveNoteId(null); setFrontOpenNoteId(null); setBackOpenNoteId(null); setPdfCursorKey(`page-${currentPage}`); setReadingMode(value as ReadingMode); }}><DropdownMenuRadioItem value="page"><BookOpen /> Page</DropdownMenuRadioItem><DropdownMenuRadioItem value="scroll"><ScrollText /> Scroll</DropdownMenuRadioItem></DropdownMenuRadioGroup>
    {bookKind === "pdf" && readingMode === "page" && <><DropdownMenuSeparator /><DropdownMenuLabel>PDF layout</DropdownMenuLabel><DropdownMenuRadioGroup value={pdfLayout} onValueChange={(value) => { setPageTurn(null); turnPendingRef.current = false; setPdfLayout(value as "spread" | "single"); }}><DropdownMenuRadioItem value="spread">Two pages</DropdownMenuRadioItem><DropdownMenuRadioItem value="single">Single page</DropdownMenuRadioItem></DropdownMenuRadioGroup></>}
  </>;

  return (
    <TooltipProvider>
      <main className="reader-shell relative flex h-[100dvh] flex-col overflow-hidden" style={readerStyle} data-dark={darkMode || zenMode} data-zen={zenMode}>
        {!zenMode && <>
        <header className="reader-topbar relative z-30 flex h-[56px] shrink-0 items-center justify-between border-b px-3 sm:px-6">
          <div className="flex min-w-0 items-center gap-3">
            <button type="button" className="library-return" aria-label="Back to library" onClick={() => setBookKind(null)}><ChevronLeft className="size-4" /><span className="hidden sm:inline">Library</span></button>
            <span className="header-divider" />
            <span className="reader-brand">camus reader<span>.</span></span>
          </div>
          <span className="topbar-title min-w-0 truncate" title={bookTitle}>{bookTitle}</span>
          <div className="topbar-actions flex items-center gap-1">
            <IconButton label={darkMode ? "Use light mode" : "Use dark mode"} onClick={toggleDarkMode}>{darkMode ? <Sun /> : <Moon />}</IconButton>
            <button type="button" className="zen-enter" aria-label="Turn on Zen mode" onClick={enterZenMode}><Focus className="size-4" /><span>Zen mode</span></button>
          </div>
        </header>

        <nav aria-label="Reader tools" className="reader-toolbar z-20 flex h-[52px] shrink-0 items-center gap-1 overflow-x-auto border-b px-3 scrollbar-none sm:gap-2 sm:px-6">
          <button type="button" className={`tool-action contents-action desktop-contents ${desktopPanelOpen ? "is-active" : ""}`} aria-label={desktopPanelOpen ? "Close navigation" : "Open navigation"} aria-pressed={desktopPanelOpen} onClick={() => setDesktopPanelOpen((value) => !value)}><PanelLeft className="size-[18px]" /><span>Contents</span></button>
          <Sheet open={mobilePanelOpen} onOpenChange={setMobilePanelOpen}>
            <SheetTrigger asChild><button type="button" className="tool-action contents-action mobile-contents" aria-label="Open contents and notes"><PanelLeft className="size-[18px]" /><span>Contents</span></button></SheetTrigger>
            <SheetContent side="left" className="reader-drawer p-0"><SheetHeader className="sr-only"><SheetTitle>Navigation</SheetTitle><SheetDescription>Pages and marks</SheetDescription></SheetHeader>{navigationContent}</SheetContent>
          </Sheet>
          <button type="button" className="tool-action contents-action" aria-label="Open bookmarks" title="Open bookmarks" onClick={openBookmarkList}><BookmarkCheck className="size-[18px]" /><span>Bookmarks</span></button>
          <span className="toolbar-location hidden text-sm sm:inline">{bookKind === "pdf" ? `Page ${currentSequenceIndex + 1} of ${pdfSequence.length}` : currentLabel}</span>
          <span className="flex-1" />
          <div className="zoom-controls" aria-label={bookKind === "epub" ? "EPUB text size" : "PDF zoom"}><button type="button" aria-label="Zoom out" onClick={() => setPdfZoom((value) => Math.max(50, Math.round((value - 1) * 100) / 100))} disabled={pdfZoom <= 50}><Minus className="size-4" /></button><input type="text" inputMode="decimal" aria-label="Zoom percentage" title="Enter a zoom from 50% to 400%" value={zoomDraft} onChange={(event) => setZoomDraft(event.target.value)} onFocus={(event) => event.target.select()} onBlur={commitZoom} onKeyDown={(event) => { if (event.key === "Enter") event.currentTarget.blur(); if (event.key === "Escape") { setZoomDraft(String(pdfZoom)); event.currentTarget.blur(); } }} /><span aria-hidden="true">%</span><button type="button" aria-label="Zoom in" onClick={() => setPdfZoom((value) => Math.min(400, Math.round((value + 1) * 100) / 100))} disabled={pdfZoom >= 400}><Plus className="size-4" /></button></div>
          <span className="tool-separator" />
          <DropdownMenu>
            <DropdownMenuTrigger asChild><Button type="button" variant="ghost" size="sm" className="display-trigger" aria-label="Reading appearance">Aa <span className="hidden sm:inline">Display</span></Button></DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-56">{appearanceOptions(false)}</DropdownMenuContent>
          </DropdownMenu>
          <button type="button" className="tool-action insert-action" aria-label="Insert a sheet after this page" title="Insert sheet" onClick={() => insertNote()}><Plus className="size-[19px]" /><span className="hidden md:inline">Insert sheet</span></button>
          <button type="button" className={`tool-action ${currentBookmarked ? "is-active" : ""}`} aria-label={currentBookmarked ? "Remove bookmark" : "Bookmark this place"} aria-pressed={currentBookmarked} onClick={toggleBookmark}>
            {currentBookmarked ? <BookmarkCheck className="size-[20px]" /> : <Bookmark className="size-[20px]" />}<span className="sr-only">Bookmark</span>
          </button>
          <button type="button" className={`tool-action ${highlightMode === "draw" ? "is-active" : ""}`} aria-label="Highlight text" aria-pressed={highlightMode === "draw"} title="Highlight text" onClick={() => setHighlightMode((mode) => mode === "draw" ? null : "draw")}><Highlighter className="size-[20px]" /></button>
          <button type="button" className={`tool-action ${highlightMode === "erase" ? "is-active" : ""}`} aria-label="Erase highlights" aria-pressed={highlightMode === "erase"} title="Click a highlight to erase it" onClick={() => setHighlightMode((mode) => mode === "erase" ? null : "erase")}><Eraser className="size-[20px]" /></button>
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button type="button" variant="ghost" size="icon" aria-label="Choose highlight color" className="tool-color-button">
                <span className="size-4 rounded-full border border-black/20" style={{ background: highlightColors[highlightColor] }} />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" className="min-w-40">
              <DropdownMenuLabel>Highlighter</DropdownMenuLabel>
              {(Object.keys(highlightColors) as HighlightColor[]).map((color) => (
                <DropdownMenuItem key={color} onClick={() => { setHighlightColor(color); setHighlightMode("draw"); }}>
                  <span className="size-3 rounded-full" style={{ background: highlightColors[color] }} />
                  <span className="capitalize">{color}</span>
                  {highlightColor === color && <Check className="ml-auto" />}
                </DropdownMenuItem>
              ))}
            </DropdownMenuContent>
          </DropdownMenu>
          <Button type="button" variant="ghost" size="icon" aria-label="Open another book" onClick={() => fileInputRef.current?.click()}><Upload className="size-5" /></Button>
          <input ref={fileInputRef} type="file" accept=".pdf,.epub,application/pdf,application/epub+zip" className="sr-only" onClick={(event) => { event.currentTarget.value = ""; }} onChange={(event) => openFromDrop(event.target.files)} />
        </nav>
        <nav className="mobile-reader-toolbar" aria-label="Reader tools">
          <Sheet open={mobilePanelOpen} onOpenChange={setMobilePanelOpen}>
            <SheetTrigger asChild><button type="button" aria-label="Open contents and bookmarks"><PanelLeft className="size-5" /></button></SheetTrigger>
            <SheetContent side="left" className="reader-drawer p-0"><SheetHeader className="sr-only"><SheetTitle>Navigation</SheetTitle><SheetDescription>Pages and marks</SheetDescription></SheetHeader>{navigationContent}</SheetContent>
          </Sheet>
          <DropdownMenu>
            <DropdownMenuTrigger asChild><button type="button" aria-label="Reading appearance and zoom"><span className="mobile-aa">Aa</span></button></DropdownMenuTrigger>
            <DropdownMenuContent align="start" className="w-60">{appearanceOptions(true)}</DropdownMenuContent>
          </DropdownMenu>
          <button type="button" aria-label="Insert a sheet after this page" onClick={() => insertNote()}><Plus className="size-5" /></button>
          <button type="button" aria-label={currentBookmarked ? "Remove bookmark" : "Bookmark this place"} aria-pressed={currentBookmarked} className={currentBookmarked ? "is-active" : ""} onClick={toggleBookmark}>{currentBookmarked ? <BookmarkCheck className="size-5" /> : <Bookmark className="size-5" />}</button>
          <button type="button" aria-label="Highlight text" aria-pressed={highlightMode === "draw"} className={highlightMode === "draw" ? "is-active" : ""} onClick={() => setHighlightMode((mode) => mode === "draw" ? null : "draw")}><Highlighter className="size-5" /></button>
          <DropdownMenu>
            <DropdownMenuTrigger asChild><button type="button" aria-label="More reading tools"><SlidersHorizontal className="size-5" /></button></DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="min-w-52">
              <DropdownMenuItem onClick={() => openBookmarkList()}><BookmarkCheck className="size-4" /> Bookmarked pages</DropdownMenuItem>
              <DropdownMenuItem onClick={() => setHighlightMode((mode) => mode === "erase" ? null : "erase")}><Eraser className="size-4" /> {highlightMode === "erase" ? "Stop erasing" : "Erase highlights"}</DropdownMenuItem>
              <DropdownMenuSeparator /><DropdownMenuLabel>Highlight color</DropdownMenuLabel>
              {(Object.keys(highlightColors) as HighlightColor[]).map((color) => <DropdownMenuItem key={color} onClick={() => { setHighlightColor(color); setHighlightMode("draw"); }}><span className="size-3 rounded-full" style={{ background: highlightColors[color] }} /><span className="capitalize">{color}</span>{highlightColor === color && <Check className="ml-auto size-4" />}</DropdownMenuItem>)}
              <DropdownMenuSeparator />
              <DropdownMenuItem onClick={() => fileInputRef.current?.click()}><Upload className="size-4" /> Open another book</DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </nav>
        </>}

        {zenMode && <header className="zen-header" aria-label="Zen mode">
          <button type="button" className="zen-header-button" aria-label="Open bookmarks" title="Open bookmarks" onClick={openBookmarkList}><BookmarkCheck className="size-5" /></button>
          <span className="zen-book-title" title={bookTitle}>{bookTitle}</span>
          <button type="button" className="zen-header-button" aria-label="Turn off Zen mode" title="Turn off Zen mode" onClick={() => setZenMode(false)}><Minimize2 className="size-5" /></button>
        </header>}

        <div className="reader-workspace flex min-h-0 flex-1">
          {desktopPanelOpen && <aside className={`reader-sidepanel min-h-0 shrink-0 ${zenMode ? "zen-bookmarks-panel" : "hidden lg:block"}`} aria-label="Book navigation">{navigationContent}</aside>}
          <section className="document-window relative flex h-full min-h-0 flex-col overflow-hidden">
            {zenMode && <aside className="zen-action-rail" aria-label="Reading tools">
              <button type="button" aria-label="Insert a sheet after this page" title="Insert sheet" onClick={() => insertNote()}><Plus className="size-5" /></button>
              <button type="button" className={highlightMode === "draw" ? "is-active" : ""} aria-label="Highlight text" aria-pressed={highlightMode === "draw"} title="Highlight text" onClick={() => setHighlightMode((mode) => mode === "draw" ? null : "draw")}><Highlighter className="size-5" /></button>
              <button type="button" className={highlightMode === "erase" ? "is-active" : ""} aria-label="Erase highlights" aria-pressed={highlightMode === "erase"} title="Erase highlights" onClick={() => setHighlightMode((mode) => mode === "erase" ? null : "erase")}><Eraser className="size-5" /></button>
              <span className="zen-rail-divider" />
              <button type="button" className={currentBookmarked ? "is-bookmarked" : ""} aria-label={currentBookmarked ? "Remove bookmark" : "Bookmark this place"} aria-pressed={currentBookmarked} title="Bookmark this place" onClick={toggleBookmark}>{currentBookmarked ? <BookmarkCheck className="size-5" /> : <Bookmark className="size-5" />}</button>
              <DropdownMenu>
                <DropdownMenuTrigger asChild><button type="button" aria-label="Reading appearance" title="Reading appearance"><SlidersHorizontal className="size-5" /></button></DropdownMenuTrigger>
                <DropdownMenuContent side="right" align="end" className="w-56">
                  <DropdownMenuLabel>Page color</DropdownMenuLabel>
                  <DropdownMenuRadioGroup value={pageColor} onValueChange={(value) => setPageColor(value as PageColor)}>{(Object.keys(pageThemes) as PageColor[]).map((color) => <DropdownMenuRadioItem key={color} value={color}><span className="size-3 rounded-full border border-black/15" style={{ background: pageThemes[color].page }} />{pageThemes[color].label}</DropdownMenuRadioItem>)}</DropdownMenuRadioGroup>
                  <DropdownMenuSeparator /><DropdownMenuLabel>Zoom · {pdfZoom}%</DropdownMenuLabel>
                  <div className="zen-zoom-controls"><button type="button" aria-label="Zoom out" disabled={pdfZoom <= 50} onClick={() => setPdfZoom((value) => Math.max(50, value - 1))}><Minus className="size-4" /></button><input aria-label="Zoom percentage" inputMode="decimal" value={zoomDraft} onChange={(event) => setZoomDraft(event.target.value)} onFocus={(event) => event.target.select()} onBlur={commitZoom} onKeyDown={(event) => { if (event.key === "Enter") event.currentTarget.blur(); if (event.key === "Escape") { setZoomDraft(String(pdfZoom)); event.currentTarget.blur(); } }} /><span>%</span><button type="button" aria-label="Zoom in" disabled={pdfZoom >= 400} onClick={() => setPdfZoom((value) => Math.min(400, value + 1))}><Plus className="size-4" /></button></div>
                </DropdownMenuContent>
              </DropdownMenu>
            </aside>}
            <div ref={readerStageRef} className={`reader-stage ${bookKind === "epub" ? "epub-stage" : "pdf-stage"} min-h-0 flex-1 overflow-auto px-3 py-4 scrollbar-thin sm:px-6 sm:py-5`}>
              {bookKind === "pdf" && pdfDoc ? (
                readingMode === "scroll" || zenMode ? (
                  <div className="pdf-scroll-stack mx-auto pb-24" style={{ "--reader-zoom": pdfZoom / 100 } as CSSProperties}>
                    {pdfSequence.map((leaf, index) => leaf.kind === "page" ? (
                      <div key={leaf.key} data-leaf-key={leaf.key}>
                        <PdfPage pdfDoc={pdfDoc} pageNumber={leaf.page} renderScale={pdfRenderScale} theme={activePageColor}
                          highlights={pdfHighlights.filter((item) => item.page === leaf.page)} highlightMode={highlightMode}
                          highlightColor={highlightColor} onAddHighlight={addPdfHighlight} onRemoveHighlights={removePdfHighlights}
                          onSelectHighlight={(item) => selectHighlight(item.id)} onVisible={() => undefined} />
                      </div>
                    ) : (
                      <div key={leaf.key} data-leaf-key={leaf.key} className="inserted-inline-page">
                        <NoteSheet note={leaf.note} theme={theme} face="front" focusOnOpen={false} pageNumber={index + 1} onChange={updateNote} onDelete={() => deleteNote(leaf.note.id)} />
                        <NoteSheet note={leaf.note} theme={theme} face="back" focusOnOpen={false} pageNumber={index + 1} onChange={updateNote} onDelete={() => deleteNote(leaf.note.id)} />
                      </div>
                    ))}
                  </div>
                ) : (
                  <div className="spread-layout mx-auto flex min-h-full items-center justify-center gap-3 sm:gap-5">
                    <div className="spread-stack" style={{ "--reader-zoom": pdfZoom / 100 } as CSSProperties}>
                      {(pageTurn ? [pageTurn.toKey, pageTurn.fromKey] : [pdfCursorKey]).map((key) => {
                        const outgoing = !!pageTurn && key === pageTurn.fromKey;
                        const incoming = !!pageTurn && !outgoing;
                        const left = incoming ? destinationLeft : displayedLeft;
                        const right = incoming ? destinationRight : displayedRight;
                        return (
                          <div key={key} style={!singleVisibleLeaf ? { aspectRatio: 2 / pdfPageRatio } : undefined}
                            className={`book-spread ${singleVisibleLeaf ? "single-page" : ""} ${outgoing ? "turn-origin" : pageTurn ? `turn-destination turn-${pageTurn.direction === 1 ? "forward" : "backward"} ${pageTurn.started ? "is-turning" : ""}` : ""}`}
                            onAnimationEnd={incoming ? (event) => { if (event.target === event.currentTarget && event.animationName === "camus-reader-reveal-page") finishPageTurn(); } : undefined}>
                            <div className="spread-leaf is-primary">
                              {singleVisibleLeaf ? renderSpreadLeaf(incoming ? destinationLeft : currentPdfLeaf, !pageTurn, singleNoteFace, () => setSingleNoteFace((face) => face === "front" ? "back" : "front")) : renderSpreadLeaf(left, !pageTurn && !openedNoteFront && !showBackOfSheet, "back")}
                            </div>
                            {!singleVisibleLeaf && <div className="spread-leaf spread-second">
                              {renderSpreadLeaf(right, !pageTurn && openedNoteFront, "front")}
                            </div>}
                          </div>
                        );
                      })}
                    </div>
                  </div>
                )
              ) : (
                <div className={`mx-auto min-h-full ${zenMode ? "zen-epub-frame" : "max-w-[940px]"}`}>
                  <div className="epub-layout mx-auto flex w-full min-w-0 items-center justify-center gap-3 sm:gap-5">
                    <button type="button" className="spread-arrow" aria-label="Previous page" onClick={() => navigate(-1)}><ChevronLeft /></button>
                    <div data-page-color={activePageColor} className="epub-page min-h-[360px] min-w-0 flex-1 overflow-hidden rounded-lg bg-[var(--reader-page)]" style={{ height: zenMode ? "calc(100dvh - 5rem)" : "calc(100dvh - 15rem)" }}>
                      <div ref={epubHostRef} className="h-full w-full" aria-label="EPUB reading area" />
                      {pendingEpubSelection && !activeNote && <div className="highlight-confirm epub-highlight-confirm" role="toolbar" aria-label="Selected text" style={{ left: pendingEpubSelection.left, top: pendingEpubSelection.top }} onPointerDown={(event) => { event.preventDefault(); event.stopPropagation(); }}>
                        <button type="button" disabled={pendingEpubRef.current?.overlaps} onClick={() => confirmEpubRef.current?.("highlight")}><Highlighter className="size-4" /> Highlight</button>
                        <button type="button" onClick={() => copyEpubRef.current?.()}>Copy</button>
                        <button type="button" disabled={pendingEpubRef.current?.overlaps} onClick={() => confirmEpubRef.current?.("strike")}><span className="camus-reader-strike-icon" aria-hidden="true">S</span> Strikethrough</button>
                        <button type="button" aria-label="Dismiss selection" onClick={dismissEpubSelection}><X className="size-4" /></button>
                      </div>}
                      {activeNote && <div className="epub-note-layer" style={{ "--reader-zoom": pdfZoom / 100 } as CSSProperties}>
                        <div key={activeNote.id} className="inserted-leaf h-full"><NoteSheet note={activeNote} theme={theme} face={singleNoteFace} onFlip={() => setSingleNoteFace((face) => face === "front" ? "back" : "front")} onChange={updateNote} onDelete={() => deleteNote(activeNote.id)} onClose={closeNote} /></div>
                      </div>}
                    </div>
                    <button type="button" className="spread-arrow" aria-label="Next page" onClick={() => navigate(1)}><ChevronRight /></button>
                  </div>
                </div>
              )}
            </div>
            {((bookKind === "pdf" && (readingMode === "page" || zenMode)) || (bookKind === "epub" && zenMode)) && <div className="fixed-page-arrows" aria-label="Page navigation">
              <button type="button" className="page-edge-arrow" aria-label="Previous page" onClick={() => navigate(-1)} disabled={bookKind === "pdf" && currentSequenceIndex <= 0}><ChevronLeft /></button>
              <button type="button" className="page-edge-arrow" aria-label="Next page" onClick={() => navigate(1)} disabled={bookKind === "pdf" && currentSequenceIndex >= pdfSequence.length - 1}><ChevronRight /></button>
            </div>}
          </section>
          {activeHighlight && <HighlightNotePanel
            label={bookKind === "pdf" ? `PDF page ${(activeHighlight as PdfHighlight).page}` : "EPUB passage"}
            text={bookKind === "epub" ? (activeHighlight as EpubHighlight).text : undefined}
            note={activeHighlight.note ?? ""}
            onChange={updateHighlightNote}
            onClose={() => setActiveHighlightId(null)}
          />}
        </div>

        {!zenMode && <footer className="reader-progress flex h-[58px] shrink-0 items-center justify-between gap-4 border-t px-4 sm:px-6">
          <button type="button" className="mobile-page-turn" aria-label="Previous page" onClick={() => navigate(-1)} disabled={bookKind === "pdf" && currentSequenceIndex <= 0}><ChevronLeft className="size-5" /></button>
          <span className="progress-context hidden min-w-36 text-xs sm:block">{bookKind === "pdf" ? `${pageCount} PDF pages${notes.length ? ` · ${notes.length} inserted` : ""}` : "EPUB"}</span>
          <div className="progress-center mx-auto flex w-full max-w-[680px] flex-col items-center gap-1">
            {bookKind === "pdf" ? <input aria-label="Go to page" className="progress-slider w-full" type="range" min={1} max={Math.max(1, pdfSequence.length)} value={currentSequenceIndex + 1} onChange={(event) => jumpToPdfLeaf(pdfSequence[Number(event.target.value) - 1].key)} style={{ "--progress": `${pdfSequence.length > 1 ? currentSequenceIndex / (pdfSequence.length - 1) * 100 : 0}%` } as CSSProperties} /> : <div className="progress-track w-full"><span style={{ width: `${progress}%` }} /></div>}
            <span className="progress-caption">{bookKind === "pdf" ? `Page ${currentSequenceIndex + 1} of ${pdfSequence.length}` : currentLabel} <span aria-hidden="true">·</span> {bookKind === "pdf" ? Math.round((currentSequenceIndex + 1) / pdfSequence.length * 100) : progress}%</span>
          </div>
          <span className="progress-help hidden min-w-36 text-right text-xs lg:block">← → turn page</span>
          <button type="button" className="mobile-page-turn" aria-label="Next page" onClick={() => navigate(1)} disabled={bookKind === "pdf" && currentSequenceIndex >= pdfSequence.length - 1}><ChevronRight className="size-5" /></button>
        </footer>}

        {zenMode && <ZenGlowBar percent={zenProgressPercent} />}

        {error && <div role="alert" className="fixed bottom-5 right-5 z-50 rounded-xl bg-red-800 px-4 py-3 text-sm font-medium text-white shadow-xl">{error}</div>}
      </main>
    </TooltipProvider>
  );
}
