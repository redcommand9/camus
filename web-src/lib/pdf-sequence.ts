export type PdfSequenceItem<T extends { id: string; afterPage?: number }> =
  | { kind: "page"; page: number; key: string }
  | { kind: "note"; note: T; key: string };

export function buildPdfSequence<T extends { id: string; afterPage?: number }>(pageCount: number, notes: T[]): PdfSequenceItem<T>[] {
  const sequence: PdfSequenceItem<T>[] = [];
  for (let page = 1; page <= pageCount; page += 1) {
    sequence.push({ kind: "page", page, key: `page-${page}` });
    for (const note of notes) {
      if (note.afterPage === page) sequence.push({ kind: "note", note, key: `note-${note.id}` });
    }
  }
  return sequence;
}

export function adjacentPdfLeaf<T extends { id: string; afterPage?: number }>(
  sequence: PdfSequenceItem<T>[],
  key: string,
  direction: -1 | 1,
): PdfSequenceItem<T> | undefined {
  const index = sequence.findIndex((item) => item.key === key);
  if (index < 0) return undefined;
  return sequence[Math.max(0, Math.min(sequence.length - 1, index + direction))];
}

export function insertPdfNoteAfter<T extends { id: string; afterPage?: number }>(
  notes: T[],
  note: T,
  currentKey: string,
): T[] {
  const anchor = note.afterPage;
  if (anchor === undefined) return [...notes, note];
  const currentNoteId = currentKey.startsWith("note-") ? currentKey.slice(5) : null;
  const existingIndex = currentNoteId ? notes.findIndex((item) => item.id === currentNoteId) : -1;
  if (existingIndex >= 0 && notes[existingIndex].afterPage === anchor) {
    return [...notes.slice(0, existingIndex + 1), note, ...notes.slice(existingIndex + 1)];
  }
  const firstIndex = notes.findIndex((item) => item.afterPage === anchor);
  if (firstIndex >= 0) return [...notes.slice(0, firstIndex), note, ...notes.slice(firstIndex)];
  return [...notes, note];
}
