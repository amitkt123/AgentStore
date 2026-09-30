import styles from "./approve.module.css";

export default function ApproveLayout({ children }: { children: React.ReactNode }) {
  return (
    <main className={styles.page}>
      <div className={styles.brand}>Karya</div>
      {children}
    </main>
  );
}
