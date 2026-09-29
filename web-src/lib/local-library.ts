/** The document itself stays in this browser. Annotations remain under the existing book key. */
export type LocalBook = {
  key: string;
  file: File;
  title: string;
  kind: "pdf" | "epub";
  addedAt: number;
  lastOpenedAt: number;
  pageCount?: number;
};

const DATABASE_NAME = "camus-reader-local-library";
const STORE_NAME = "books";

type NativeBook = Omit<LocalBook, "file"> & { name: string };
type CamusReaderBridge = {
  listBooks(): string;
  book(key: string): string;
  saveBook(value: string): boolean;
  removeBook(key: string): void;
};

const native = () => (window as Window & { CamusReaderNative?: CamusReaderBridge }).CamusReaderNative;
const nativeFile = (entry: NativeBook, contents: BlobPart[] = []): File => {
  const file = new File(contents, entry.name, {
    type: entry.kind === "pdf" ? "application/pdf" : "application/epub+zip",
  });
  Object.defineProperty(file, "camusReaderNativeKey", { value: entry.key });
  return file;
};

export function nativeBookKey(file: File): string | undefined {
  return (file as File & { camusReaderNativeKey?: string }).camusReaderNativeKey;
}

function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DATABASE_NAME, 1);
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains(STORE_NAME)) request.result.createObjectStore(STORE_NAME, { keyPath: "key" });
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error("Library unavailable"));
  });
}

async function withStore<T>(mode: IDBTransactionMode, action: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  const db = await openDatabase();
  try {
    return await new Promise<T>((resolve, reject) => {
      const transaction = db.transaction(STORE_NAME, mode);
      const request = action(transaction.objectStore(STORE_NAME));
      transaction.oncomplete = () => resolve(request.result);
      transaction.onerror = () => reject(transaction.error ?? new Error("Library unavailable"));
      transaction.onabort = () => reject(transaction.error ?? new Error("Library unavailable"));
    });
  } finally {
    db.close();
  }
}

export async function listLocalBooks(): Promise<LocalBook[]> {
  if (native()) {
    const entries = JSON.parse(native()!.listBooks()) as NativeBook[];
    return entries.map((entry) => ({ ...entry, file: nativeFile(entry) }));
  }
  return withStore<LocalBook[]>("readonly", (store) => store.getAll());
}

export async function getLocalBook(key: string): Promise<LocalBook | undefined> {
  if (native()) {
    const raw = native()!.book(key);
    if (!raw) return undefined;
    const entry = JSON.parse(raw) as NativeBook;
    const response = await fetch(`/native/book?key=${encodeURIComponent(key)}`);
    if (!response.ok) throw new Error("The book file is unavailable on this device.");
    return { ...entry, file: nativeFile(entry, [await response.blob()]) };
  }
  return withStore<LocalBook | undefined>("readonly", (store) => store.get(key));
}

export async function saveLocalBook(book: LocalBook): Promise<void> {
  if (native()) {
    if (!native()!.saveBook(JSON.stringify({ key: book.key, name: book.file.name, title: book.title, kind: book.kind, addedAt: book.addedAt, lastOpenedAt: book.lastOpenedAt, pageCount: book.pageCount }))) {
      throw new Error("The imported book could not be saved to this device.");
    }
    return;
  }
  await withStore<IDBValidKey>("readwrite", (store) => store.put(book));
}

export async function removeLocalBook(key: string): Promise<void> {
  if (native()) { native()!.removeBook(key); return; }
  await withStore<undefined>("readwrite", (store) => store.delete(key));
}
