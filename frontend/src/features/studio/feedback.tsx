"use client";
import Link from "next/link";
import { createContext, useContext, useEffect, useRef, useState } from "react";
import styles from "./studio.module.css";
import { useGuarded } from "@/features/agents/useGuarded";

export type Message = { tone: "ok" | "bad"; text: string; verify?: boolean };
interface Studio {
  message: Message | null;
  setMessage: (m: Message | null) => void;
  busy: boolean;
  run: (fn: () => Promise<void>) => Promise<void>;
}

const Ctx = createContext<Studio | null>(null);

// One provider holds the banner message and a single in-flight guard, so a patch and a submit can never overlap.
export function FeedbackProvider({ children }: { children: React.ReactNode }) {
  const [message, setMessage] = useState<Message | null>(null);
  const { busy, run } = useGuarded();
  return <Ctx.Provider value={{ message, setMessage, busy, run }}>{children}</Ctx.Provider>;
}

export function useStudio(): Studio {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error("useStudio must be used inside FeedbackProvider");
  return ctx;
}

export function FeedbackBanner() {
  const { message } = useStudio();
  const ref = useRef<HTMLDivElement>(null);
  // The Submit button is at the bottom of the page; bring the result into view.
  useEffect(() => { if (message) ref.current?.scrollIntoView?.({ block: "nearest" }); }, [message]);
  if (!message) return null;
  const bad = message.tone === "bad";
  return (
    <div ref={ref} role={bad ? "alert" : "status"} className={`${styles.banner} ${bad ? styles.bad : styles.ok}`}>
      <span>{message.text}</span>
      {message.verify && <Link href="/developer/verification">Get verified</Link>}
    </div>
  );
}
