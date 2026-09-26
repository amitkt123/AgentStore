import { StatusPill } from "@/components/StatusPill";
import type { BuildStatus, BuildSummary } from "@/lib/types";

const EXPLANATION: Record<BuildStatus, string> = {
  QUEUED: "Waiting for the build runner.",
  BUILDING: "Installing dependencies, scanning for secrets and vulnerabilities, and testing the entrypoint.",
  SUCCEEDED: "Your code is built. You can submit this version.",
  FAILED: "The build failed. Fix the problem below and push again.",
};

export function BuildPanel({ build }: { build: BuildSummary }) {
  return (
    <section aria-label="Build" className="mt-6 rounded-lg border border-line p-4">
      <div className="mb-2 flex items-center gap-2">
        <h2 className="font-medium">Build</h2>
        <StatusPill status={build.status} />
      </div>
      <p className="text-ink2">{EXPLANATION[build.status]}</p>
      {build.status === "FAILED" && build.failureCode && (
        <p className="mt-2 text-red">
          <span className="font-mono">{build.failureCode}</span>
          {build.failureReason ? <span>: {build.failureReason}</span> : null}
        </p>
      )}
      {build.logUrl && (
        <a className="mt-2 inline-block underline" href={build.logUrl} target="_blank" rel="noreferrer">
          View build log
        </a>
      )}
    </section>
  );
}
