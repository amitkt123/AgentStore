import Link from "next/link";
import styles from "./MonthNav.module.css";
import { shiftMonth } from "@/lib/month";

export function MonthNav({ basePath, month }: { basePath: string; month: string }) {
  return (
    <nav className={styles.months} aria-label="Month">
      <Link href={`${basePath}?month=${shiftMonth(month, -1)}`}>Previous month</Link>
      <span className={styles.month}>{month}</span>
      <Link href={`${basePath}?month=${shiftMonth(month, 1)}`}>Next month</Link>
    </nav>
  );
}
