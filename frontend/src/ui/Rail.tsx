import Link from "next/link";
import styles from "./Rail.module.css";
import { Icon, type IconName } from "./Icon";

export type RailItem = { href: string; label: string; icon: IconName; badge?: number };

export function buyerRailItems(needsYou: number): RailItem[] {
  return [
    { href: "/home", label: "Workspace", icon: "home" },
    { href: "/store", label: "Agent store", icon: "store" },
    { href: "/runs", label: "Runs", icon: "runs", badge: needsYou > 0 ? needsYou : undefined },
    { href: "/files", label: "Files", icon: "files" },
  ];
}

export function Rail({ items, activePath }: { items: RailItem[]; activePath: string }) {
  return (
    <nav className={styles.rail} aria-label="Main">
      <div className={styles.logo} aria-hidden="true"><i /><i /><i /><i /></div>
      {items.map((item) => {
        const active = activePath === item.href || activePath.startsWith(item.href + "/");
        const label = item.badge ? `${item.label}, ${item.badge} need you` : item.label;
        return (
          <Link key={item.href} href={item.href} aria-label={label} aria-current={active ? "page" : undefined} className={`${styles.nav} ${active ? styles.on : ""}`}>
            <Icon name={item.icon} />
            {item.badge ? <span className={styles.badge}>{item.badge}</span> : null}
          </Link>
        );
      })}
    </nav>
  );
}
