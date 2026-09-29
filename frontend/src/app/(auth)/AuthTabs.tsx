import Link from "next/link";
import styles from "./auth.module.css";

export function AuthTabs({ active }: { active: "signin" | "signup" }) {
  return (
    <nav className={styles.seg} aria-label="Sign in or create account">
      <Link href="/login" className={active === "signin" ? styles.on : ""} aria-current={active === "signin" ? "page" : undefined}>Sign in</Link>
      <Link href="/signup" className={active === "signup" ? styles.on : ""} aria-current={active === "signup" ? "page" : undefined}>Create account</Link>
    </nav>
  );
}
