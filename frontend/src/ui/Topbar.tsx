import styles from "./Topbar.module.css";
import { AccountMenu } from "./AccountMenu";

export function Topbar({ orgName, fullName, email }: { orgName: string; fullName: string; email: string }) {
  return (
    <header className={styles.top}>
      <span className={styles.org}>{orgName}</span>
      <AccountMenu fullName={fullName} email={email} orgName={orgName} />
    </header>
  );
}
