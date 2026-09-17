import Link from "next/link";
import type { ReviewItemResponse } from "@/lib/types";

export function ReviewQueueList({ items }: { items: ReviewItemResponse[] }) {
  if (items.length === 0) return <p className="text-muted py-8">Nothing waiting for review.</p>;
  return (
    <div className="border-t border-line">
      {items.map((item) => (
        <Link key={item.id} href={`/review/${item.id}`} className="grid grid-cols-[1fr_auto] gap-4 items-center py-4 border-b border-line hover:bg-paper2">
          <div>
            <div className="font-semibold">{item.agentName}</div>
            <div className="text-sm text-ink2">v{item.versionNumber}</div>
          </div>
          <span className="text-sm font-medium">Review</span>
        </Link>
      ))}
    </div>
  );
}
