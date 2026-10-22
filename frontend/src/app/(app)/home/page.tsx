import { getMe } from "@/lib/api/me";
import { listRuns } from "@/lib/api/runs";
import { listMyConnections } from "@/lib/api/connections";
import { getSession } from "@/lib/session";
import { isBuyer } from "@/lib/roles";
import { HomeView } from "@/features/home/HomeView";

export default async function Page({ searchParams }: { searchParams: { added?: string } }) {
  const session = (await getSession())!;
  const me = await getMe();
  if (!isBuyer(session.role)) {
    return <HomeView fullName={me.fullName} role={session.role} needsYou={[]} connections={[]} />;
  }
  const [runs, connections] = await Promise.all([listRuns({ group: "needs_you", size: 10 }), listMyConnections()]);
  return <HomeView fullName={me.fullName} role={session.role} needsYou={runs.items} connections={connections} added={searchParams.added} />;
}
