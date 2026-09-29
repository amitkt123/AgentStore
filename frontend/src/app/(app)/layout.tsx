import { redirect } from "next/navigation";
import { AppShell } from "@/ui/AppShell";
import { buyerRailItems } from "@/ui/Rail";
import { getMe } from "@/lib/api/me";
import { listRuns } from "@/lib/api/runs";
import { getSession } from "@/lib/session";
import { isBuyer } from "@/lib/roles";

export default async function AppLayout({ children }: { children: React.ReactNode }) {
  const session = await getSession();
  if (!session) redirect("/login");
  const me = await getMe();
  const active = me.memberships.find((m) => m.orgId === me.activeOrgId);
  const initials = me.fullName.split(/\s+/).map((w) => w[0]).slice(0, 2).join("").toUpperCase();
  let needsYou = 0;
  if (isBuyer(session.role)) {
    needsYou = await listRuns({ group: "needs_you", size: 1 }).then((r) => r.counts.needs_you).catch(() => 0);
  }
  // Developer and admin rails arrive in P3 and P4; until then they get the workspace item only.
  const items = isBuyer(session.role) ? buyerRailItems(needsYou) : [{ href: "/home", label: "Workspace", icon: "home" as const }];
  return (
    <AppShell items={items} orgName={active?.orgName ?? "Karya"} initials={initials}>
      {children}
    </AppShell>
  );
}
