"use client";
import { useEffect, useRef } from "react";
import styles from "./Drawer.module.css";
import { Icon } from "./Icon";

export function Drawer({ open, title, onClose, children }: { open: boolean; title: string; onClose: () => void; children: React.ReactNode }) {
  const closeRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (!open) return;
    closeRef.current?.focus();
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open, onClose]);
  if (!open) return null;
  return (
    <div className={styles.scrim} onClick={onClose}>
      <aside role="dialog" aria-modal="true" aria-label={title} className={styles.panel} onClick={(e) => e.stopPropagation()}>
        <header>
          <h2>{title}</h2>
          <button ref={closeRef} type="button" onClick={onClose} aria-label="Close"><Icon name="x" /></button>
        </header>
        {children}
      </aside>
    </div>
  );
}
