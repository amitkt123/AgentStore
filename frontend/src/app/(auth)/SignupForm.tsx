"use client";
import { useState } from "react";
import { useFormState, useFormStatus } from "react-dom";
import { signupAction } from "./actions";
import { AuthTabs } from "./AuthTabs";
import styles from "./auth.module.css";

type Side = "BUYER" | "DEVELOPER";

const copy: Record<Side, { org: string; hint: string; cta: string; after: string }> = {
  BUYER: {
    org: "Company",
    hint: "Meridian Traders Pvt Ltd",
    cta: "Create workspace",
    after: "Next you add teammates and choose who approves. Adding money to the wallet comes last.",
  },
  DEVELOPER: {
    org: "Your name or studio",
    hint: "Ledgerline Labs",
    cta: "Create developer account",
    after: "Build and test in the sandbox for free, right away. Get verified when you want to list an agent and get paid.",
  },
};

function Submit({ children }: { children: React.ReactNode }) {
  const { pending } = useFormStatus();
  return <button type="submit" className={styles.btn} disabled={pending} aria-busy={pending || undefined}>{children}</button>;
}

export function SignupForm() {
  const [state, action] = useFormState(signupAction, {});
  const [side, setSide] = useState<Side>("BUYER");
  const c = copy[side];
  return (
    <>
      <AuthTabs active="signup" />
      <form action={action} className={styles.form}>
        <div>
          <h1>Create your account</h1>
          <div className={styles.sub}>Pick what you&apos;re here to do. You can add the other later.</div>
        </div>
        <fieldset className={styles.roles}>
          <legend className="sr-only">What are you here to do</legend>
          <label className={styles.role}>
            <input type="radio" name="side" value="BUYER" checked={side === "BUYER"} onChange={() => setSide("BUYER")} />
            <span className={styles.bi}><svg className={styles.ic} viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="6" width="18" height="13" rx="3" /><path d="M3 10h18M16 14.5h2" /></svg></span>
            <b>I want to run an Agent</b>
            <small>Use agents on my company&apos;s books</small>
          </label>
          <label className={styles.role}>
            <input type="radio" name="side" value="DEVELOPER" checked={side === "DEVELOPER"} onChange={() => setSide("DEVELOPER")} />
            <span className={styles.bi}><svg className={styles.ic} viewBox="0 0 24 24" aria-hidden="true"><path d="M9 7l-5 5 5 5M15 7l5 5-5 5" /></svg></span>
            <b>I build agents</b>
            <small>Publish agents and get paid</small>
          </label>
        </fieldset>
        <div className={styles.field}>
          <label htmlFor="fullName">Full name</label>
          <input id="fullName" name="fullName" type="text" autoComplete="name" placeholder="Priya Shah" required />
        </div>
        <div className={styles.field}>
          <label htmlFor="orgName">{c.org}</label>
          <input id="orgName" name="orgName" type="text" autoComplete="organization" placeholder={c.hint} required />
        </div>
        <div className={styles.field}>
          <label htmlFor="email">Work email</label>
          <input id="email" name="email" type="email" autoComplete="email" placeholder="priya@meridiantraders.in" required />
        </div>
        <div className={styles.field}>
          <label htmlFor="password">Password</label>
          <input id="password" name="password" type="password" autoComplete="new-password" minLength={8} placeholder="At least 8 characters" required />
        </div>
        {state.error && <p role="alert" className={styles.err}>{state.error}</p>}
        <Submit>{c.cta}</Submit>
        <div className={styles.note}>{c.after}</div>
      </form>
    </>
  );
}
