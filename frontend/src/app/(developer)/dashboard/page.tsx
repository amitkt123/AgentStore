import Link from "next/link";
import { backend } from "@/lib/backend";
import type { MyAgentsResponse } from "@/lib/types";
import { DashboardList } from "./DashboardList";

export default async function DashboardPage() {
  const agents = await backend.get<MyAgentsResponse[]>("/api/v1/agents/mine");
  return (
    <div>
      <div className="flex justify-between items-center mb-6">
        <h1 className="font-serif text-2xl">Your agents</h1>
        <Link href="/dashboard/new" className="bg-green text-white rounded-lg px-4 py-2 font-medium text-sm">
          Submit a new agent
        </Link>
      </div>
      <DashboardList agents={agents} />
    </div>
  );
}
