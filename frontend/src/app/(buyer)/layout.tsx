import Link from "next/link";
import { ApprovalsBadge } from "@/components/ApprovalsBadge";

export default function BuyerLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="grid grid-cols-[220px_1fr] min-h-screen">
      <aside className="border-r border-line p-5 flex flex-col gap-6">
        <span className="font-serif text-2xl">Karya</span>
        <nav className="flex flex-col gap-1">
          <Link href="/catalog" className="px-3 py-2 rounded-md text-ink2 hover:bg-paper2">Find an agent</Link>
          <Link href="/runs" className="px-3 py-2 rounded-md text-ink2 hover:bg-paper2">Runs</Link>
          <ApprovalsBadge />
          <Link href="/billing" className="px-3 py-2 rounded-md text-ink2 hover:bg-paper2">Billing</Link>
        </nav>
      </aside>
      <main className="p-8 max-w-5xl">{children}</main>
    </div>
  );
}
