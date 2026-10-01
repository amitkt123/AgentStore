import Link from "next/link";
import { notFound } from "next/navigation";
import { getReadiness, getVersion, listMyAgents } from "@/lib/api/agents";
import { ApiError } from "@/lib/api/http";
import { Declared } from "@/features/studio/Declared";
import { ReadinessBanner } from "@/features/studio/ReadinessBanner";
import { statusLabel, statusTone } from "@/features/agents/model";
import styles from "@/features/studio/studio.module.css";
import { ErrorState } from "@/ui/ErrorState";
import { Pill } from "@/ui/Pill";

export default async function Page({ params }: { params: { agentId: string; versionId: string } }) {
  const { agentId, versionId } = params;
  try {
    const [detail, readiness, agents] = await Promise.all([
      getVersion(agentId, versionId),
      getReadiness(agentId, versionId),
      // The category is a nicety. Losing it must not take the Studio down, but a sign-in redirect must still go through.
      listMyAgents().catch((e) => { if (e instanceof ApiError) return []; throw e; }),
    ]);
    const category = agents.find((a) => a.agentId === agentId)?.category;
    return (
      <>
        <Link href="/developer/agents" className={styles.back}>← Agents</Link>
        <header className={styles.head}>
          <h1>{detail.agentName}</h1>
          <p className={styles.meta}>
            {category && <span>{category} ·</span>}
            <span>Version <span className="mono">{detail.versionNumber}</span></span>
            <Pill tone={statusTone[detail.status]}>{statusLabel[detail.status]}</Pill>
          </p>
        </header>
        <ReadinessBanner readiness={readiness} build={detail.build ?? null} />
        <Declared manifest={detail.manifest} />
        <p className={styles.note}>What the agent actually did will appear here once sandbox runs are available.</p>
      </>
    );
  } catch (e) {
    if (e instanceof ApiError) {
      // The backend answers 403 or 404 for a version that is not yours; both look like "not found".
      if (e.status === 403 || e.status === 404) notFound();
      return <ErrorState message={e.message} />;
    }
    throw e;
  }
}
