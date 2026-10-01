"use client";
import Link from "next/link";
import { useState } from "react";
import styles from "./agents.module.css";
import { Button } from "@/ui/Button";
import { EmptyState } from "@/ui/EmptyState";
import { Pill } from "@/ui/Pill";
import { NewAgentForm, NewVersionForm } from "./AgentForms";
import { statusLabel, statusTone } from "./model";
import type { MyAgent } from "@/lib/api/types";

export function AgentsView({ agents }: { agents: MyAgent[] }) {
  const [creating, setCreating] = useState(false);
  const [versionFor, setVersionFor] = useState<string | null>(null);

  return (
    <div className={styles.wrap}>
      {!creating && (
        <div className={styles.bar}>
          <Button onClick={() => setCreating(true)}>New agent</Button>
        </div>
      )}
      {creating && (
        <section className={styles.panel}>
          <h2 className={styles.h}>New agent</h2>
          <NewAgentForm onDone={() => setCreating(false)} />
        </section>
      )}
      {agents.length === 0 && !creating && <EmptyState title="No agents yet" body="Create your first agent, then add a version with its manifest." />}
      <ul className={styles.list}>
        {agents.map((a) => (
          <li key={a.agentId} className={styles.card}>
            <div className={styles.head}>
              <div>
                <strong>{a.agentName}</strong>
                <span className={styles.cat}>{a.category}</span>
              </div>
              <Button
                variant="ghost"
                aria-label={`New version for ${a.agentName}`}
                aria-expanded={versionFor === a.agentId}
                onClick={() => setVersionFor(versionFor === a.agentId ? null : a.agentId)}
              >
                New version
              </Button>
            </div>
            {a.versions.length === 0 ? (
              <p className={styles.muted}>No versions yet.</p>
            ) : (
              <ul className={styles.chips}>
                {a.versions.map((v) => (
                  <li key={v.id}>
                    <Link
                      href={`/developer/agents/${a.agentId}/versions/${v.id}`}
                      className={styles.chip}
                      aria-label={`Open version ${v.versionNumber}, ${statusLabel[v.status]}`}
                    >
                      <span className="mono">{v.versionNumber}</span>
                      <Pill tone={statusTone[v.status]}>{statusLabel[v.status]}</Pill>
                    </Link>
                  </li>
                ))}
              </ul>
            )}
            {versionFor === a.agentId && <NewVersionForm agentId={a.agentId} onDone={() => setVersionFor(null)} />}
          </li>
        ))}
      </ul>
    </div>
  );
}
