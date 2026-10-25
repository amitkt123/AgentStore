import styles from "./AccountMenu.module.css";
import { logoutAction } from "@/app/(auth)/actions";

const initialsOf = (name: string) => name.split(/\s+/).filter(Boolean).map((w) => w[0]).slice(0, 2).join("").toUpperCase();

export function AccountMenu({ fullName, email, orgName }: { fullName: string; email: string; orgName: string }) {
  return (
    <details className={styles.menu}>
      <summary className={styles.avatar} aria-label="Your account">{initialsOf(fullName)}</summary>
      <div className={styles.panel}>
        <strong>{fullName}</strong>
        <span className={styles.sub}>{email}</span>
        <span className={styles.sub}>{orgName}</span>
        <form action={logoutAction}>
          <button type="submit" className={styles.out}>Sign out</button>
        </form>
      </div>
    </details>
  );
}
