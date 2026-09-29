"use client";
import { useEffect, useRef, useState } from "react";

export function useRunPolling<T>(url: string, opts: { intervalMs: number; initial: T; isDone?: (d: T) => boolean }) {
  const [data, setData] = useState<T>(opts.initial);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const stopped = useRef(false);
  const isDone = useRef(opts.isDone);
  isDone.current = opts.isDone;

  useEffect(() => {
    stopped.current = false;
    const tick = async () => {
      if (stopped.current || document.hidden) return;
      try {
        const res = await fetch(url, { cache: "no-store" });
        if (res.status === 401) { window.location.assign("/login"); stopped.current = true; return; }
        if (res.status === 403) { setForbidden(true); stopped.current = true; return; }
        if (!res.ok) { setError(`Couldn't refresh (${res.status})`); return; }
        const next = (await res.json()) as T;
        setError(null);
        setData(next);
        if (isDone.current?.(next)) stopped.current = true;
      } catch {
        setError("Couldn't refresh. Retrying.");
      }
    };
    const id = setInterval(tick, opts.intervalMs);
    return () => { stopped.current = true; clearInterval(id); };
  }, [url, opts.intervalMs]);

  return { data, error, forbidden };
}
