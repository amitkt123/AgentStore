import styles from "./Topbar.module.css";
import { Icon } from "./Icon";

export function Topbar({ orgName, initials }: { orgName: string; initials: string }) {
  return (
    <header className={styles.top}>
      <span className={styles.org}>{orgName}</span>
      <div className={styles.search} role="search">
        <Icon name="search" />
        <input aria-label="Search" placeholder="Search is coming soon" disabled />
      </div>
      <span className={styles.avatar} aria-label="Your account">{initials}</span>
    </header>
  );
}
