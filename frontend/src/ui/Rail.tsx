import Link from "next/link";
import styles from "./Rail.module.css";
import { Icon, type IconName } from "./Icon";
import { railFor, type UserRole } from "@/lib/roles";

export type RailItem = { href: string; label: string; icon: IconName; badge?: number };

export function buyerRailItems(needsYou: number): RailItem[] {
  return [
    { href: "/home", label: "Workspace", icon: "home" },
    { href: "/store", label: "Agent store", icon: "store" },
    { href: "/runs", label: "Runs", icon: "runs", badge: needsYou > 0 ? needsYou : undefined },
    { href: "/files", label: "Files", icon: "files" },
    { href: "/billing", label: "Billing", icon: "wallet" },
  ];
}

export function developerRailItems(): RailItem[] {
  return [
    { href: "/developer/agents", label: "Agents", icon: "agents" },
    { href: "/developer/earnings", label: "Earnings", icon: "wallet" },
    { href: "/developer/verification", label: "Get verified", icon: "shield" },
    { href: "/developer/api-keys", label: "API keys", icon: "key" },
  ];
}

export function adminRailItems(): RailItem[] {
  return [{ href: "/admin/money", label: "Money", icon: "wallet" }];
}

// Sandbox runs and Test files join developerRailItems in P3c.
export function railItemsFor(role: UserRole, needsYou: number): RailItem[] {
  switch (railFor(role)) {
    case "developer": return developerRailItems();
    // Billing is for buyer admins; members cannot open it.
    case "buyer": return role === "BUYER_MEMBER"
      ? buyerRailItems(needsYou).filter((item) => item.href !== "/billing")
      : buyerRailItems(needsYou);
    case "admin": return adminRailItems();
  }
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
            <span className={styles.label} aria-hidden="true">{item.label}</span>
            {item.badge ? <span className={styles.badge}>{item.badge}</span> : null}
          </Link>
        );
      })}
    </nav>
  );
}
