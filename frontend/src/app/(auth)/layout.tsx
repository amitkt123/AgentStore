import { AuthHero, Logo } from "./AuthHero";
import styles from "./auth.module.css";

export default function AuthLayout({ children }: { children: React.ReactNode }) {
  return (
    <main className={styles.page}>
      <AuthHero />
      <section className={styles.card}>
        <div className={styles.mobileBrand}><Logo />Karya</div>
        <div className={styles.formWrap}>{children}</div>
      </section>
    </main>
  );
}
