"use client";
import { usePathname } from "next/navigation";
import styles from "./AppShell.module.css";
import { Rail, type RailItem } from "./Rail";
import { Topbar } from "./Topbar";

export function AppShell({ items, orgName, fullName, email, children }: { items: RailItem[]; orgName: string; fullName: string; email: string; children: React.ReactNode }) {
  const path = usePathname() ?? "";
  return (
    <div className={styles.frame}>
      <Rail items={items} activePath={path} />
      <div className={styles.main}>
        <Topbar orgName={orgName} fullName={fullName} email={email} />
        <main className={styles.content}>{children}</main>
      </div>
    </div>
  );
}
