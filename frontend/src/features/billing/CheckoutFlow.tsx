"use client";
import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import styles from "./billing.module.css";
import { Button } from "@/ui/Button";
import { simulate } from "@/app/(app)/billing/actions";
import { openRazorpayCheckout } from "./razorpayCheckout";
import type { Checkout } from "@/lib/api/types";

const POLL_MS = 3000;
const POLL_FOR_MS = 60000;

/** Refreshes the page every 3s for up to 60s, stopping once the watched figure changes. */
function useBalancePoll(active: boolean, watch: number) {
  const router = useRouter();
  const start = useRef(watch);
  const latest = useRef(watch);
  latest.current = watch;
  useEffect(() => {
    if (!active) return;
    start.current = latest.current;
    let elapsed = 0;
    const id = setInterval(() => {
      elapsed += POLL_MS;
      if (latest.current !== start.current || elapsed > POLL_FOR_MS) { clearInterval(id); return; }
      router.refresh();
    }, POLL_MS);
    return () => clearInterval(id);
  }, [active, router]);
}

export function CheckoutFlow({ checkout, watch }: { checkout: Checkout; watch: number }) {
  const [state, setState] = useState<"waiting" | "paid" | "closed">("waiting");
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const started = useRef(false);
  const razorpay = checkout.provider === "razorpay";

  useBalancePoll(state === "paid", watch);

  const open = async () => {
    setState("waiting");
    setMessage(null);
    try {
      setState(await openRazorpayCheckout(checkout.checkout));
    } catch (e) {
      setState("closed");
      setMessage(e instanceof Error ? e.message : "Could not open checkout");
    }
  };

  useEffect(() => {
    if (!razorpay || started.current) return;
    started.current = true;
    void open();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const run = async (outcome: "PAID" | "FAILED") => {
    setBusy(true);
    setMessage(null);
    const res = await simulate(checkout.order.id, outcome);
    setBusy(false);
    if ("error" in res) { setMessage(res.error); return; }
    if (outcome === "PAID") setState("paid");
    else setMessage("Payment failed.");
  };

  if (state === "paid") {
    return <p role="status" className={styles.ok}>Payment received. Your balance updates within a minute.</p>;
  }
  return (
    <div className={styles.checkout}>
      {razorpay ? (
        state === "closed" && <Button type="button" onClick={open}>Open checkout</Button>
      ) : (
        <>
          <p className={styles.note}>Test mode: no real money moves</p>
          <div className={styles.actions}>
            <Button type="button" loading={busy} onClick={() => run("PAID")}>Simulate payment</Button>
            <Button type="button" variant="ghost" loading={busy} onClick={() => run("FAILED")}>Simulate failure</Button>
          </div>
        </>
      )}
      {message && <p role="alert" className={styles.err}>{message}</p>}
    </div>
  );
}
