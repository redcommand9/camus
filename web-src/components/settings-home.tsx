"use client";

import { useEffect, useState } from "react";
import { ArrowLeft, BookOpen, CloudDownload, Database, FolderOpen, Info, Moon, Shield, Sun, Type, UserRound } from "lucide-react";

const sections = [
  { id: "profile", title: "Profile & vault", icon: UserRound, heading: "Local reader profile", description: "Your books, position, and annotations belong to this browser.", rows: [
    { title: "Reading metrics", description: "Optional reading statistics and milestones.", state: "Planned" },
  ] },
  { id: "network", title: "Gutenberg network", icon: CloudDownload, heading: "Gutenberg network", description: "Browse and bring public-domain books into your library.", rows: [
    { title: "Catalog access", description: "Search the Project Gutenberg collection from Camus Reader.", state: "Planned" },
    { title: "Offline downloads", description: "Keep downloaded books in this browser.", state: "Planned" },
  ] },
  { id: "storage", title: "Storage", icon: FolderOpen, heading: "Storage", description: "Choose how this device keeps your reading collection.", rows: [
    { title: "Book files", description: "Imported PDFs and EPUBs are kept in this browser.", state: "Local" },
    { title: "Storage management", description: "Inspect space and manage stored copies.", state: "Planned" },
  ] },
  { id: "typography", title: "Typography", icon: Type, heading: "Typography", description: "Set comfortable defaults for new reading sessions.", rows: [
    { title: "Reading font", description: "Select a default typeface for reflowable books.", state: "Planned" },
    { title: "Line spacing & margins", description: "Adjust the shape of the page to your screen.", state: "Planned" },
  ] },
  { id: "data", title: "Data operations", icon: Database, heading: "Data operations", description: "Move or remove your reading data when you choose.", rows: [
    { title: "Export library", description: "Download books and annotations together.", state: "Planned" },
    { title: "Clear local data", description: "Remove books and reading marks from this browser.", state: "Planned" },
  ] },
  { id: "about", title: "About Camus Reader", icon: Info, heading: "About Camus Reader", description: "A quieter place for books and the notes you make in them.", rows: [
    { title: "Website edition", description: "Read PDF and EPUB files from your local library.", state: "Camus Reader" },
  ] },
] as const;

type SectionId = typeof sections[number]["id"];

type CamusReaderNative = {
  lock(): void;
  profileName(): string;
  encryptionEnabled(): boolean;
  requestEnableEncryption(): void;
  requestDisableEncryption(): void;
};

export function SettingsHome({ dark, onToggleTheme, onBack }: { dark: boolean; onToggleTheme: () => void; onBack: () => void }) {
  const [sectionId, setSectionId] = useState<SectionId>("profile");
  const section = sections.find((item) => item.id === sectionId)!;
  const Icon = section.icon;
  const native = (window as Window & { CamusReaderNative?: CamusReaderNative }).CamusReaderNative;

  // Encryption is off unless a profile password has been set; the native side owns the real state,
  // this just mirrors it so the row reflects reality instead of always claiming "On".
  const [encryptionOn, setEncryptionOn] = useState(() => native?.encryptionEnabled() ?? false);
  const [pending, setPending] = useState(false);

  useEffect(() => {
    if (!native) return;
    const refresh = () => { setEncryptionOn(native.encryptionEnabled()); setPending(false); };
    refresh();
    window.addEventListener("camus-reader:encryption-changed", refresh);
    return () => window.removeEventListener("camus-reader:encryption-changed", refresh);
  }, [native]);

  const toggleEncryption = () => {
    if (!native || pending) return;
    setPending(true);
    if (encryptionOn) native.requestDisableEncryption();
    else native.requestEnableEncryption();
  };

  return <div className="settings-home" data-dark={dark}>
    <header className="settings-topbar">
      <button type="button" className="settings-wordmark" onClick={onBack} aria-label="Back to Library"><BookOpen size={22} strokeWidth={1.6} /> camus reader<span>.</span></button>
      <span className="settings-top-title">Settings</span>
      <button type="button" className="settings-theme" onClick={onToggleTheme} aria-label={dark ? "Use light mode" : "Use dark mode"}>{dark ? <Sun size={19} /> : <Moon size={19} />}</button>
    </header>
    <main className="settings-layout">
      <div className="settings-intro">
        <button type="button" className="settings-back" onClick={onBack}><ArrowLeft size={17} /> Library</button>
        <p className="settings-breadcrumb">LIBRARY <span>/</span> SETTINGS</p>
        <h1>Settings</h1>
        <p>Choose how Camus Reader feels and how your books live on this device.</p>
        {!native && <span className="settings-preview-badge">Interface preview</span>}
      </div>
      <div className="settings-columns">
        <nav className="settings-index" aria-label="Settings sections">
          <span className="settings-index-label">PREFERENCES</span>
          {sections.map((item) => { const ItemIcon = item.icon; return <button key={item.id} type="button" className={item.id === sectionId ? "is-active" : ""} onClick={() => setSectionId(item.id)} aria-current={item.id === sectionId ? "page" : undefined}><ItemIcon size={18} strokeWidth={1.7} /><span>{item.title}</span></button>; })}
        </nav>
        <section className="settings-panel" aria-labelledby="settings-panel-title" key={section.id}>
          <div className="settings-panel-heading"><div className="settings-panel-icon"><Icon size={22} strokeWidth={1.7} /></div><div><h2 id="settings-panel-title">{section.heading}</h2><p>{native && section.id === "profile" ? "Your books, position, and annotations belong to this device." : section.description}</p></div></div>
          {section.id === "profile" && <div className="settings-identity"><div className="settings-avatar"><UserRound size={25} strokeWidth={1.5} /></div><div><strong>{(native && encryptionOn && native.profileName()) || "This device"}</strong><span>Your private reading shelf</span></div><span className="settings-identity-tag">LOCAL</span></div>}
          <div className="settings-rows">
            {section.id === "profile" && <div className="settings-row">
              <div>
                <h3>Device encryption</h3>
                <p>{encryptionOn
                  ? "Your library details, bookmarks, notes, and highlights are encrypted with your profile password."
                  : native
                    ? "Reading data is stored on this device without encryption. Turn this on to protect it with a password."
                    : "Protect reading data with a device password."}</p>
              </div>
              {native
                ? <button type="button" className="settings-toggle" role="switch" aria-checked={encryptionOn} aria-label="Device encryption" disabled={pending} onClick={toggleEncryption} />
                : <span className="settings-planned">Planned</span>}
            </div>}
            {section.rows.map((row) => <div className="settings-row" key={row.title}><div><h3>{row.title}</h3><p>{row.description}</p></div><span className="settings-planned">{row.state}</span></div>)}
          </div>
          {section.id === "profile" && native && encryptionOn && <button type="button" className="settings-back" onClick={() => native.lock()}><Shield size={16} /> Lock Camus Reader</button>}
          {!native && <p className="settings-panel-foot"><Shield size={15} /> Settings controls will be connected in a later update.</p>}
        </section>
      </div>
    </main>
  </div>;
}
