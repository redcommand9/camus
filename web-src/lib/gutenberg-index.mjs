const CATALOG_URL = "https://www.gutenberg.org/cache/epub/feeds/pg_catalog.csv.gz";
const CATALOG_PAGE_SIZE = 24;

export function normalizeCatalogWords(value) {
  return value.normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLocaleLowerCase().split(/[^\p{L}\p{N}]+/u).filter((word) => word.length > 1);
}

function* csvRows(csv) {
  let field = "";
  let row = [];
  let quoted = false;
  for (let index = 0; index < csv.length; index += 1) {
    const character = csv[index];
    if (quoted) {
      if (character === '"' && csv[index + 1] === '"') {
        field += '"';
        index += 1;
      } else if (character === '"') {
        quoted = false;
      } else {
        field += character;
      }
    } else if (character === '"' && field.length === 0) {
      quoted = true;
    } else if (character === ",") {
      row.push(field);
      field = "";
    } else if (character === "\n") {
      row.push(field.replace(/\r$/, ""));
      field = "";
      if (row.some((value) => value.length)) yield row;
      row = [];
    } else {
      field += character;
    }
  }
  if (field.length || row.length) {
    row.push(field.replace(/\r$/, ""));
    yield row;
  }
}

function splitValues(value) {
  return value.split(";").map((item) => item.trim()).filter(Boolean);
}

export function* parseCatalogRows(csv, generation = 1) {
  const iterator = csvRows(csv);
  const headers = iterator.next().value?.map((header) => header.replace(/^\uFEFF/, ""));
  if (!headers?.length || headers[0] !== "Text#") throw new Error("Project Gutenberg catalog has an unexpected CSV header.");
  const column = new Map(headers.map((header, index) => [header, index]));
  const get = (row, key) => row[column.get(key)] ?? "";
  for (const row of iterator) {
    if (get(row, "Type") !== "Text") continue;
    const id = Number.parseInt(get(row, "Text#"), 10);
    const title = get(row, "Title").replace(/\s+/g, " ").trim();
    if (!Number.isSafeInteger(id) || id <= 0 || !title) continue;
    const authors = splitValues(get(row, "Authors"));
    const languages = splitValues(get(row, "Language"));
    const subjects = [...splitValues(get(row, "Subjects")), ...splitValues(get(row, "Bookshelves"))];
    yield {
      id,
      title,
      authors: JSON.stringify(authors),
      languages: JSON.stringify(languages),
      subjects: subjects.join(" | "),
      issued: get(row, "Issued").trim(),
      search_text: ` ${normalizeCatalogWords([title, ...authors].join(" ")).join(" ")} `,
      author_search: ` ${normalizeCatalogWords(authors.join(" ")).join(" ")} `,
      generation,
    };
  }
}

export function parseCatalogCsv(csv, generation = 1) {
  return [...parseCatalogRows(csv, generation)];
}

const insertCatalogChunk = `
  INSERT INTO gutenberg_books (
    id, title, authors, languages, subjects, issued, search_text, author_search, generation
  )
  SELECT
    CAST(json_extract(value, '$.id') AS INTEGER),
    json_extract(value, '$.title'),
    json_extract(value, '$.authors'),
    json_extract(value, '$.languages'),
    json_extract(value, '$.subjects'),
    json_extract(value, '$.issued'),
    json_extract(value, '$.search_text'),
    json_extract(value, '$.author_search'),
    CAST(json_extract(value, '$.generation') AS INTEGER)
  FROM json_each(?) WHERE 1
  ON CONFLICT(id) DO UPDATE SET
    title = excluded.title,
    authors = excluded.authors,
    languages = excluded.languages,
    subjects = excluded.subjects,
    issued = excluded.issued,
    search_text = excluded.search_text,
    author_search = excluded.author_search,
    generation = excluded.generation
`;

/** Download Project Gutenberg's machine-readable catalog and atomically mark the new generation current. */
export async function refreshGutenbergIndex(db, fetcher = fetch) {
  if (!db) throw new Error("The Gutenberg catalog database binding is unavailable.");
  const generation = Date.now();
  const response = await fetcher(CATALOG_URL, {
    headers: { "User-Agent": "Camus Reader catalog mirror (https://folio-reader.jamaica-kidyneon.chatgpt.site)" },
    signal: AbortSignal.timeout(120_000),
  });
  if (!response.ok || !response.body) throw new Error(`Project Gutenberg catalog fetch failed (${response.status}).`);
  const csv = await new Response(response.body.pipeThrough(new DecompressionStream("gzip"))).text();
  let count = 0;
  let records = [];
  let statements = [];
  for (const book of parseCatalogRows(csv, generation)) {
    count += 1;
    records.push(book);
    if (records.length === 200) {
      statements.push(db.prepare(insertCatalogChunk).bind(JSON.stringify(records)));
      records = [];
    }
    if (statements.length === 10) {
      await db.batch(statements);
      statements = [];
    }
  }
  if (records.length) statements.push(db.prepare(insertCatalogChunk).bind(JSON.stringify(records)));
  if (statements.length) await db.batch(statements);
  if (count < 10_000) throw new Error(`Project Gutenberg catalog was incomplete (${count} text records).`);

  const refreshedAt = new Date().toISOString();
  await db.batch([
    db.prepare("DELETE FROM gutenberg_books WHERE generation != ?").bind(generation),
    db.prepare("INSERT INTO gutenberg_catalog_state (key, value) VALUES ('generation', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value").bind(String(generation)),
    db.prepare("INSERT INTO gutenberg_catalog_state (key, value) VALUES ('last_refreshed_at', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value").bind(refreshedAt),
    db.prepare("INSERT INTO gutenberg_catalog_state (key, value) VALUES ('book_count', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value").bind(String(count)),
    db.prepare("DELETE FROM gutenberg_catalog_state WHERE key = 'refresh_lock'"),
  ]);
  return { count, refreshedAt };
}

export async function refreshGutenbergIndexIfDue(db, now = Date.now(), fetcher = fetch) {
  if (!db) throw new Error("The Gutenberg catalog database binding is unavailable.");
  const current = await db.prepare("SELECT value FROM gutenberg_catalog_state WHERE key = 'last_refreshed_at'").first();
  const timestamp = Date.parse(String(current?.value ?? ""));
  if (Number.isFinite(timestamp) && now - timestamp < 7 * 24 * 60 * 60 * 1000) {
    return { skipped: true, refreshedAt: String(current.value) };
  }
  const lock = await db.prepare(`
    INSERT INTO gutenberg_catalog_state (key, value) VALUES ('refresh_lock', ?)
    ON CONFLICT(key) DO UPDATE SET value = excluded.value
    WHERE CAST(gutenberg_catalog_state.value AS INTEGER) < ?
  `).bind(String(now), String(now - 15 * 60 * 1000)).run();
  if (Number(lock.meta?.changes ?? 0) === 0) return { skipped: true, refreshing: true };
  try {
    return await refreshGutenbergIndex(db, fetcher);
  } catch (error) {
    await db.prepare("DELETE FROM gutenberg_catalog_state WHERE key = 'refresh_lock'").run();
    console.error("Weekly Gutenberg index refresh failed:", error instanceof Error ? error.message : "unknown error");
    return { skipped: true, refreshFailed: true };
  }
}

function readJsonArray(value) {
  try {
    const parsed = JSON.parse(value);
    return Array.isArray(parsed) ? parsed.filter((item) => typeof item === "string") : [];
  } catch {
    return [];
  }
}

/** Search the local catalog snapshot; no third-party search provider is queried by visitors. */
export async function searchGutenbergIndex(db, filters) {
  const terms = normalizeCatalogWords(filters.search).slice(0, 8);
  const base = [];
  const baseValues = [];
  if (filters.language) {
    base.push("instr(lower(languages), char(34) || ? || char(34)) > 0");
    baseValues.push(filters.language.toLowerCase());
  }
  if (filters.topic) {
    base.push("instr(lower(subjects), ?) > 0");
    baseValues.push(filters.topic.toLowerCase());
  }

  const commonWhere = base.length ? `(${base.join(" AND ")})` : "1 = 1";
  const authorWhere = terms.length ? terms.map(() => "instr(author_search, ' ' || ? || ' ') > 0").join(" AND ") : "1 = 1";
  const titleWhere = terms.length ? terms.map(() => "instr(search_text, ' ' || ? || ' ') > 0").join(" AND ") : "1 = 1";
  const termValues = [...terms];
  const authorCount = terms.length
    ? await db.prepare(`SELECT COUNT(*) AS count FROM gutenberg_books WHERE ${commonWhere} AND ${authorWhere}`).bind(...baseValues, ...termValues).first()
    : { count: 0 };
  const preferAuthors = Number(authorCount?.count ?? 0) > 0;
  const resultWhere = terms.length ? (preferAuthors ? authorWhere : titleWhere) : "1 = 1";
  const values = terms.length ? [...baseValues, ...termValues] : baseValues;
  const countRow = await db.prepare(`SELECT COUNT(*) AS count FROM gutenberg_books WHERE ${commonWhere} AND ${resultWhere}`).bind(...values).first();
  const count = Number(countRow?.count ?? 0);
  const offset = (filters.page - 1) * CATALOG_PAGE_SIZE;
  const direction = filters.sort === "descending" ? "DESC" : "ASC";
  const rows = await db.prepare(`
    SELECT id, title, authors, languages
    FROM gutenberg_books
    WHERE ${commonWhere} AND ${resultWhere}
    ORDER BY id ${direction}
    LIMIT ? OFFSET ?
  `).bind(...values, CATALOG_PAGE_SIZE, offset).all();
  const results = (rows.results ?? []).map((row) => ({
    id: Number(row.id),
    title: String(row.title),
    authors: readJsonArray(String(row.authors)).map((name) => ({ name })),
    languages: readJsonArray(String(row.languages)),
    download_count: 0,
    media_type: "Text",
  }));
  const lastRefreshed = await db.prepare("SELECT value FROM gutenberg_catalog_state WHERE key = 'last_refreshed_at'").first();
  const bookCount = await db.prepare("SELECT value FROM gutenberg_catalog_state WHERE key = 'book_count'").first();
  return {
    count,
    next: offset + results.length < count,
    results,
    source: `Project Gutenberg weekly index${lastRefreshed?.value ? ` · updated ${String(lastRefreshed.value).slice(0, 10)}` : " · awaiting first refresh"}`,
    indexedCount: Number(bookCount?.value ?? 0),
  };
}
