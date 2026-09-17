import Link from "next/link";
import { logoutAction } from "@/lib/authActions";

export default function DeveloperLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="grid grid-cols-[230px_1fr] min-h-screen">
      <aside className="border-r border-line p-5 flex flex-col gap-6">
        <span className="font-serif text-2xl">Karya <span className="text-xs text-muted border border-line rounded px-1.5 py-0.5 ml-1">for developers</span></span>
        <nav className="flex flex-col gap-1">
          <Link href="/dashboard" className="px-3 py-2 rounded-md text-ink2 hover:bg-paper2">Your agents</Link>
          <Link href="/dashboard/new" className="px-3 py-2 rounded-md text-ink2 hover:bg-paper2">New submission</Link>
        </nav>
        <nav className="flex flex-col gap-1 mt-auto">
          <form action={logoutAction}>
            <button type="submit" className="w-full text-left px-3 py-2 rounded-md text-ink2 hover:bg-paper2">
              Sign out
            </button>
          </form>
        </nav>
      </aside>
      <main className="p-8 max-w-5xl">{children}</main>
    </div>
  );
}
