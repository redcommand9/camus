"use client";

import { useEffect, useState, type MouseEvent } from "react";
import { ArrowLeft, ArrowUpRight, BookOpen, ChevronDown, LoaderCircle, Search, X } from "lucide-react";

type GutenbergBook = {
  id: number;
  title: string;
  authors: { name: string }[];
  languages: string[];
  download_count: number;
  media_type: string;
};
type CatalogPage = { count: number; next: boolean; results: GutenbergBook[]; source: string };

const topics = ["All books", "Fiction", "History", "Philosophy", "Science", "Poetry"] as const;
const languages = [
  { value: "", label: "All languages" },
  { value: "en", label: "English" },
  { value: "fr", label: "French" },
  { value: "de", label: "German" },
  { value: "es", label: "Spanish" },
  { value: "hi", label: "Hindi" },
];
const colors = ["sand", "slate", "clay", "sage", "blue"] as const;

function externalBookUrl(id: number) {
  return `https://www.gutenberg.org/ebooks/${id}`;
}

function openInBrowser(event: MouseEvent<HTMLAnchorElement>) {
  const native = (window as Window & { CamusReaderNative?: { openExternal(url: string): void } }).CamusReaderNative;
  if (native) {
    event.preventDefault();
    native.openExternal(event.currentTarget.href);
  }
}

export function GutenbergCatalog({ dark, onBack }: { dark: boolean; onBack: () => void }) {
  const [query, setQuery] = useState("");
  const [search, setSearch] = useState("");
  const [topic, setTopic] = useState<(typeof topics)[number]>("All books");
  const [language, setLanguage] = useState("");
  const [sort, setSort] = useState<"popular" | "descending">("popular");
  const [page, setPage] = useState(1);
  const [books, setBooks] = useState<GutenbergBook[]>([]);
  const [total, setTotal] = useState<number | null>(null);
  const [hasNext, setHasNext] = useState(false);
  const [source, setSource] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [retry, setRetry] = useState(0);

  useEffect(() => {
    const timeout = window.setTimeout(() => { setSearch(query.trim()); setPage(1); setBooks([]); setTotal(null); }, 500);
    return () => window.clearTimeout(timeout);
  }, [query]);

  useEffect(() => {
    const controller = new AbortController();
    const params = new URLSearchParams({ page: String(page), sort });
    if (search) params.set("search", search);
    if (topic !== "All books") params.set("topic", topic);
    if (language) params.set("language", language);
    queueMicrotask(() => { if (!controller.signal.aborted) { setLoading(true); setError(""); } });
    fetch(`/api/gutenberg?${params}`, { signal: controller.signal })
      .then(async (response) => {
        const body = await response.json() as CatalogPage & { error?: string };
        if (!response.ok) throw new Error(body.error || "Could not load books.");
        return body as CatalogPage;
      })
      .then((data) => {
        setTotal(data.count);
        setHasNext(data.next);
        setSource(data.source);
        setBooks((current) => page === 1 ? data.results : [...current, ...data.results]);
      })
      .catch((cause) => { if (cause.name !== "AbortError") setError(cause.message || "Could not load books."); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [search, topic, language, sort, page, retry]);

  return <div className="catalog-home" data-dark={dark}>
    <header className="catalog-topbar"><button type="button" className="catalog-brand" onClick={onBack} aria-label="Back to Library"><BookOpen size={22} strokeWidth={1.6} /> camus reader<span>.</span></button><span className="catalog-top-label">Explore books</span><button type="button" className="catalog-return" onClick={onBack}><ArrowLeft size={17} /> Library</button></header>
    <main className="catalog-content">
      <div className="catalog-heading"><div><p className="catalog-eyebrow">THE OPEN SHELF</p><h1>Explore books</h1><p>Browse Project Gutenberg’s catalog. Downloads open on its website.</p></div><a href="https://www.gutenberg.org/" target="_blank" rel="noopener noreferrer" className="catalog-source" onClick={openInBrowser}>Project Gutenberg <ArrowUpRight size={15} /></a></div>
      <div className="catalog-controls"><div className="catalog-search"><Search size={19} /><input type="text" role="searchbox" aria-label="Search Gutenberg books" placeholder="Search title or author" value={query} onChange={(event) => setQuery(event.target.value)} />{query && <button type="button" aria-label="Clear search" onClick={() => setQuery("")}><X size={17} /></button>}</div><label className="catalog-select"><span>Language</span><select value={language} onChange={(event) => { setLanguage(event.target.value); setPage(1); setBooks([]); setTotal(null); }} aria-label="Language">{languages.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}</select><ChevronDown size={15} aria-hidden="true" /></label><label className="catalog-select"><span>Sort</span><select value={sort} onChange={(event) => { setSort(event.target.value as typeof sort); setPage(1); setBooks([]); setTotal(null); }} aria-label="Sort books"><option value="popular">Catalog order</option><option value="descending">Newest IDs</option></select><ChevronDown size={15} aria-hidden="true" /></label></div>
      <div className="catalog-topics" aria-label="Book subjects">{topics.map((item) => <button type="button" key={item} className={topic === item ? "is-active" : ""} aria-pressed={topic === item} onClick={() => { setTopic(item); setPage(1); setBooks([]); setTotal(null); }}>{item}</button>)}</div>
      <div className="catalog-results-heading"><h2>{search ? `Results for “${search}”` : topic === "All books" ? "Browse the catalog" : topic}</h2><span>{total === null ? "" : `${total.toLocaleString()} titles`}</span></div>
      {error && <div className="catalog-feedback" role="alert"><p>{error}</p><div><button type="button" onClick={() => setRetry((value) => value + 1)} aria-label="Retry catalog request">Retry</button><a href="https://www.gutenberg.org/ebooks/" target="_blank" rel="noopener noreferrer" onClick={openInBrowser}>Open Gutenberg catalog <ArrowUpRight size={15} /></a></div></div>}
      {!error && books.length === 0 && !loading && <div className="catalog-feedback"><p>{source.includes("awaiting first refresh") ? "The catalog is preparing its first local index. Check again shortly." : "No books found. Try another search or category."}</p></div>}
      <div className="catalog-grid">{books.map((book) => <article className="catalog-book" key={book.id}><div className={`catalog-cover catalog-cover-${colors[book.id % colors.length]}`} aria-hidden="true"><span>PROJECT GUTENBERG</span><strong>{book.title}</strong><small>{book.authors[0]?.name || "Unknown author"}</small></div><div className="catalog-book-details"><span className="catalog-book-meta">#{book.id} · {book.languages[0]?.toUpperCase() || "BOOK"}</span><h3>{book.title}</h3><p>{book.authors.map((author) => author.name).join(", ") || "Unknown author"}</p><a href={externalBookUrl(book.id)} target="_blank" rel="noopener noreferrer" aria-label={`Download options for ${book.title} on Project Gutenberg`} onClick={openInBrowser}>Download options <ArrowUpRight size={16} /></a></div></article>)}</div>
      {loading && <div className="catalog-loading" role="status"><LoaderCircle size={20} className="catalog-spinner" /> Loading books…</div>}
      {!loading && !error && hasNext && <button type="button" className="catalog-more" onClick={() => setPage((value) => value + 1)}>Load more books</button>}
      <p className="catalog-disclaimer">Catalog information{source ? ` from ${source}` : ""}. Ebook files are served by Project Gutenberg and are not kept by Camus Reader.</p>
    </main>
  </div>;
}
