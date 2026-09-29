"use client";

import { useMemo, useState, type DragEvent } from "react";
import { BookOpen, ChevronRight, Compass, FileText, Grid2X2, List, Moon, MoreHorizontal, Plus, Search, Settings2, Sun, X } from "lucide-react";
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from "@/components/ui/alert-dialog";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import type { LocalBook } from "@/lib/local-library";

export type LibraryItem = { book: LocalBook; percent: number | null; page: number | null; noteCount: number };

const coverColors = ["linen", "clay", "mist", "olive", "slate"] as const;

function BookCover({ book, compact = false }: { book: LocalBook; compact?: boolean }) {
  const tint = coverColors[Math.abs([...book.key].reduce((hash, char) => (hash * 31 + char.charCodeAt(0)) | 0, 0)) % coverColors.length];
  return <div className={`library-cover library-cover-${tint} ${compact ? "library-cover-compact" : ""}`} aria-hidden="true">
    <span className="library-cover-imprint">{book.kind.toUpperCase()}</span>
    <span className="library-cover-title">{book.title}</span>
    <span className="library-cover-rule" />
  </div>;
}

export function LibraryHome({ items, dark, loading, error, onToggleTheme, onSettings, onExplore, onImport, onImportFile, onOpen, onRemove }: {
  items: LibraryItem[];
  dark: boolean;
  loading: boolean;
  error: string;
  onToggleTheme: () => void;
  onSettings: () => void;
  onExplore: () => void;
  onImport: () => void;
  onImportFile: (file: File) => void;
  onOpen: (key: string) => void;
  onRemove: (key: string) => Promise<void>;
}) {
  const [search, setSearch] = useState("");
  const [filter, setFilter] = useState<"all" | "pdf" | "epub">("all");
  const [sort, setSort] = useState<"recent" | "title" | "added">("recent");
  const [view, setView] = useState<"grid" | "list">("grid");
  const [removeKey, setRemoveKey] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);
  const removeTitle = items.find((item) => item.book.key === removeKey)?.book.title;
  const shown = useMemo(() => {
    const matching = items.filter(({ book }) => (filter === "all" || book.kind === filter) && book.title.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase()));
    return [...matching].sort((a, b) => sort === "title" ? a.book.title.localeCompare(b.book.title) : sort === "added" ? b.book.addedAt - a.book.addedAt : b.book.lastOpenedAt - a.book.lastOpenedAt);
  }, [filter, items, search, sort]);
  const recent = [...items].filter(({ book }) => book.lastOpenedAt > 0).sort((a, b) => b.book.lastOpenedAt - a.book.lastOpenedAt).slice(0, 3);

  const dropBook = (event: DragEvent<HTMLElement>) => {
    event.preventDefault();
    setDragging(false);
    const file = event.dataTransfer.files[0];
    if (!file) return;
    onImportFile(file);
  };

  return <div className={`library-home ${dragging ? "library-is-dragging" : ""}`} data-dark={dark} onDragEnter={(event) => { if (event.dataTransfer.types.includes("Files")) setDragging(true); }} onDragOver={(event) => { if (event.dataTransfer.types.includes("Files")) event.preventDefault(); }} onDragLeave={(event) => { if (!event.currentTarget.contains(event.relatedTarget as Node)) setDragging(false); }} onDrop={dropBook}>
    <div className="library-main">
      <header className="library-topbar">
        <div className="library-brand"><BookOpen size={22} strokeWidth={1.6} /><span>camus reader<span className="library-brand-dot">.</span></span></div>
        <div className="library-search"><Search size={18} aria-hidden="true" /><input aria-label="Search your books" placeholder="Search your books" value={search} onChange={(event) => setSearch(event.target.value)} />{search && <button type="button" aria-label="Clear search" onClick={() => setSearch("")}><X size={16} /></button>}</div>
        <div className="library-top-actions"><button type="button" className="library-explore-button" aria-label="Explore Gutenberg books" onClick={onExplore}><Compass size={18} /><span>Explore</span></button><button type="button" className="library-settings-button" aria-label="Settings" title="Settings" onClick={onSettings}><Settings2 size={19} /></button><button type="button" className="library-theme-button" aria-label={dark ? "Use light mode" : "Use dark mode"} onClick={onToggleTheme}>{dark ? <Sun size={19} /> : <Moon size={19} />}</button><button type="button" className="library-add-button" onClick={onImport}><Plus size={18} /> <span>Add book</span></button></div>
      </header>
      <div className="library-scroll"><div className="library-content">
        <div className="library-heading-row"><h1>Library</h1><span className="library-count">{items.length} {items.length === 1 ? "book" : "books"}</span></div>
        {error && <p role="alert" className="library-error">{error}</p>}
        {items.length > 0 && <div className="library-controls"><div className="library-filters" aria-label="Filter books">{(["all", "pdf", "epub"] as const).map((choice) => <button key={choice} type="button" className={filter === choice ? "is-active" : ""} aria-pressed={filter === choice} onClick={() => setFilter(choice)}>{choice === "all" ? `All (${items.length})` : choice.toUpperCase()}</button>)}</div><div className="library-view-controls"><label>Sort <select aria-label="Sort books" value={sort} onChange={(event) => setSort(event.target.value as typeof sort)}><option value="recent">Recently read</option><option value="title">Title</option><option value="added">Recently added</option></select></label><div className="library-view-switch"><button type="button" aria-label="Grid view" aria-pressed={view === "grid"} onClick={() => setView("grid")}><Grid2X2 size={17} /></button><button type="button" aria-label="List view" aria-pressed={view === "list"} onClick={() => setView("list")}><List size={18} /></button></div></div></div>}
        {!search.trim() && filter === "all" && recent.length > 0 && <section className="library-section library-continue"><div className="library-section-heading"><h2>Continue reading</h2></div><div className="library-recent-grid">{recent.map(({ book, percent, page }) => <button type="button" className="library-recent-card" key={book.key} onClick={() => onOpen(book.key)}><BookCover book={book} compact /><div className="library-recent-copy"><span className="library-format">{book.kind.toUpperCase()}</span><strong>{book.title}</strong><div className="library-recent-bottom">{percent !== null ? <><span>{percent}% read{page && book.kind === "pdf" ? ` · page ${page}` : ""}</span><div className="library-progress"><span style={{ width: `${percent}%` }} /></div></> : <span>Ready to read</span>}<span className="library-resume">Resume <ChevronRight size={15} /></span></div></div></button>)}</div></section>}
        <section className="library-section">{items.length > 0 && <div className="library-section-heading"><h2>{search.trim() ? "Search results" : "All books"}</h2><span>{shown.length} {shown.length === 1 ? "book" : "books"}</span></div>}
          {shown.length ? <div className={`library-books ${view === "list" ? "library-books-list" : ""}`}>{shown.map(({ book, percent, noteCount }) => <article className="library-book" key={book.key}><button type="button" className="library-book-open" onClick={() => onOpen(book.key)} aria-label={`Read ${book.title}`}><BookCover book={book} /><strong>{book.title}</strong><span>{book.kind.toUpperCase()}{percent !== null ? ` · ${percent}% read` : ""}</span>{percent !== null && <div className="library-progress"><span style={{ width: `${percent}%` }} /></div>}</button><DropdownMenu><DropdownMenuTrigger asChild><button type="button" className="library-book-more" aria-label={`Options for ${book.title}`}><MoreHorizontal size={19} /></button></DropdownMenuTrigger><DropdownMenuContent align="end"><DropdownMenuItem onClick={() => onOpen(book.key)}>Open book</DropdownMenuItem><DropdownMenuItem onClick={() => setRemoveKey(book.key)}>Remove from library</DropdownMenuItem></DropdownMenuContent></DropdownMenu>{noteCount > 0 && <span className="library-book-notes">{noteCount} {noteCount === 1 ? "sheet" : "sheets"}</span>}</article>)}</div> : <div className="library-empty"><div className="library-empty-icon"><FileText size={26} /></div><h4>{items.length ? "No books found" : "No books yet"}</h4><p>{items.length ? "Try a different title or format." : "Add a PDF or EPUB to start your library."}</p></div>}
        </section>
        <p className="library-local-note">{(window as Window & { CamusReaderNative?: unknown }).CamusReaderNative ? "Your library and reading marks stay in your protected profile on this device." : "Books and reading marks are stored in this browser on this device."}</p>
      </div></div>
    </div>
    {dragging && <div className="library-drop-cue" aria-hidden="true">Drop a PDF or EPUB to add it</div>}
    {loading && <div className="library-loading" role="status">Loading book…</div>}
    <AlertDialog open={removeKey !== null} onOpenChange={(open) => { if (!open) setRemoveKey(null); }}><AlertDialogContent><AlertDialogHeader><AlertDialogTitle>Remove from library?</AlertDialogTitle><AlertDialogDescription>{removeTitle} will be removed from this browser. The original file on your device will stay where it is.</AlertDialogDescription></AlertDialogHeader><AlertDialogFooter><AlertDialogCancel>Cancel</AlertDialogCancel><AlertDialogAction onClick={() => { if (removeKey) void onRemove(removeKey); setRemoveKey(null); }}>Remove</AlertDialogAction></AlertDialogFooter></AlertDialogContent></AlertDialog>
  </div>;
}
