import styles from "./studio.module.css";
import { groupBlockers } from "@/features/agents/model";
import type { BlockerCode, BuildSummary, Readiness } from "@/lib/api/types";

const TITLES: Record<BlockerCode, string> = {
  BUILD_NOT_READY: "Build isn't ready",
  UNDECLARED_ACCESS: "Undeclared access",
  COST_ENVELOPE_EXCEEDED: "Over the cost envelope",
};
const BUILD_WORDS = { QUEUED: "queued", BUILDING: "building", SUCCEEDED: "succeeded", FAILED: "failed" } as const;

// Only an http(s) address or a site path is worth linking.
const safeHref = (url: string | null | undefined): string | null => (url && /^(https?:\/\/|\/)/.test(url) ? url : null);

export function ReadinessBanner({ readiness, build }: { readiness: Readiness; build: BuildSummary | null }) {
  if (readiness.ready) {
    return <div role="status" className={`${styles.banner} ${styles.ok}`}>Ready to submit</div>;
  }
  const logHref = safeHref(build?.logUrl);
  return (
    <ul className={styles.blockers} aria-label="Before you can submit">
      {groupBlockers(readiness.blockers).map((b) => (
        <li key={`${b.code}|${b.runId ?? ""}|${b.message}`} className={styles.blocker}>
          <strong>{TITLES[b.code]}</strong>
          {b.code === "BUILD_NOT_READY" && (
            <>
              <p>Build status: {build ? BUILD_WORDS[build.status] : "no build yet"}</p>
              {build?.status === "FAILED" && <p>{build.failureReason ?? "No reason was recorded."}</p>}
              {build?.status === "FAILED" && logHref && <p><a href={logHref} target="_blank" rel="noreferrer">View build log</a></p>}
            </>
          )}
          {b.code === "UNDECLARED_ACCESS" && (
            <>
              <p>{b.message}{b.count > 1 ? ` (${b.count} attempts)` : ""}</p>
              {b.runId && <p>Run <span className="mono">{b.runId}</span></p>}
            </>
          )}
          {b.code === "COST_ENVELOPE_EXCEEDED" && <p>{b.message}</p>}
        </li>
      ))}
    </ul>
  );
}
