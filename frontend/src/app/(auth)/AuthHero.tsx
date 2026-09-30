import styles from "./auth.module.css";

const steps = [
  { title: "Reads your statement", note: "412 lines, read only", cls: "first", icon: <><rect x="3" y="4" width="18" height="16" rx="2" /><path d="M3 10h18M9 4v16" /></> },
  { title: "Matches to invoices", note: "142 matches found", cls: "", icon: <><circle cx="12" cy="12" r="3" /><path d="M12 3v3M12 18v3M3 12h3M18 12h3" /></> },
  { title: "Drafts entries", note: "Held, not posted", cls: "", icon: <><path d="M12 3l9 5-9 5-9-5 9-5z" /><path d="M3 13l9 5 9-5" /></> },
  { title: "Asks you", note: "Approve or reject", cls: "gate", icon: <><path d="M12 3l8 3v6c0 4.5-3.4 8.3-8 9-4.6-.7-8-4.5-8-9V6l8-3z" /><path d="M9 12l2 2 4-4" /></> },
  { title: "Posts to your ledger", note: "Only what you approved", cls: "out", icon: <path d="M5 12.5l4.5 4.5L19 7.5" /> },
];

export function Logo() {
  return <span className={styles.logo} aria-hidden="true"><i /><i /><i /><i /></span>;
}

export function AuthHero() {
  return (
    <section className={styles.hero}>
      <div className={styles.brand}><Logo />Karya</div>
      <p className={styles.headline} role="heading" aria-level={2}>Agents that do the finance work. You approve what matters.</p>
      <p className={styles.lede}>Every agent is checked by Karya before you see it, works only on the files you plug in, and stops to ask before it writes anything.</p>
      <div className={styles.stack} aria-hidden="true">
        {steps.map((s, i) => (
          <div key={s.title}>
            {i > 0 && <span className={styles.lnk} style={{ left: 53 + i * 80, top: 64 + (i - 1) * 86 }} />}
            <div className={`${styles.sb} ${s.cls ? styles[s.cls] : ""}`} style={{ left: i * 80, top: i * 86 }}>
              <span className={styles.bi}>
                <svg className={styles.ic} viewBox="0 0 24 24">{s.icon}</svg>
              </span>
              <span><b>{s.title}</b><small>{s.note}</small></span>
            </div>
          </div>
        ))}
      </div>
      <div className={styles.foot}>Made in India for Indian finance teams. Your data stays in India.</div>
    </section>
  );
}
