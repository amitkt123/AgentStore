"use client";
import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import { Button } from "@/ui/Button";
import { startRunAction } from "@/app/(app)/connect/actions";

/** Starts a run on a connection the workspace already has, then opens the run. */
export function RunNow({ connectionId }: { connectionId: string }) {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();
  const run = () =>
    start(async () => {
      setError(null);
      const res = await startRunAction(connectionId);
      if ("error" in res) setError(res.error);
      else router.push(`/runs/${res.displayId}`);
    });
  return (
    <span>
      <Button onClick={run} loading={pending}>Run now</Button>
      {error && <span role="alert" style={{ display: "block", color: "var(--bad)", fontSize: 13, marginTop: 4 }}>{error}</span>}
    </span>
  );
}
