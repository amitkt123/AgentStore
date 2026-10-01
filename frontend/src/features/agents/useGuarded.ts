import { useRef, useState } from "react";

// React 18 does not hold isPending across an async transition, so a second click is dropped by hand.
export function useGuarded() {
  const inFlight = useRef(false);
  const [busy, setBusy] = useState(false);
  const run = async (fn: () => Promise<void>) => {
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    try { await fn(); } finally { inFlight.current = false; setBusy(false); }
  };
  return { busy, run };
}
