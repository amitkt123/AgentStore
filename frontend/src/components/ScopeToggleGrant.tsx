"use client";

import { useState } from "react";
import type { Scope } from "@/lib/types";

interface Props {
  scopes: Scope[];
  onChange: (grantedResources: string[]) => void;
}

export function ScopeToggleGrant({ scopes, onChange }: Props) {
  const [granted, setGranted] = useState<Set<string>>(() => new Set(scopes.map((s) => s.resource)));

  function toggle(resource: string) {
    setGranted((prev) => {
      const next = new Set(prev);
      if (next.has(resource)) next.delete(resource);
      else next.add(resource);
      onChange([...next]);
      return next;
    });
  }

  return (
    <div className="border border-line rounded-lg overflow-hidden">
      <div className="grid grid-cols-[1fr_100px] gap-3 bg-paper2 px-4 py-2 text-xs text-muted">
        <span>What it reaches</span>
        <span>Granted</span>
      </div>
      {scopes.map((scope) => {
        const isGranted = granted.has(scope.resource);
        return (
          <div key={scope.resource} className="grid grid-cols-[1fr_100px] gap-3 items-center px-4 py-3 border-t border-line">
            <div>
              <b className="font-medium">{scope.resource}</b>
              <div className="text-xs text-ink2">
                {scope.description} · {scope.type === "READ" ? "Read only" : "Write"}
              </div>
            </div>
            <button
              type="button"
              role="switch"
              aria-checked={isGranted}
              aria-label={`Grant access to ${scope.resource}`}
              onClick={() => toggle(scope.resource)}
              className={`justify-self-end w-11 h-6 rounded-full relative ${isGranted ? "bg-green" : "bg-paper2 border border-line"}`}
            >
              <span
                className={`absolute top-0.5 w-5 h-5 rounded-full bg-doc transition-all ${isGranted ? "left-[22px]" : "left-0.5"}`}
              />
            </button>
          </div>
        );
      })}
    </div>
  );
}
