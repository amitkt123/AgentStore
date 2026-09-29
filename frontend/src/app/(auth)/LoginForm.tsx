"use client";
import Link from "next/link";
import { useFormState, useFormStatus } from "react-dom";
import { loginAction } from "./actions";
import { AuthTabs } from "./AuthTabs";
import styles from "./auth.module.css";

function Submit() {
  const { pending } = useFormStatus();
  return <button type="submit" className={styles.btn} disabled={pending} aria-busy={pending || undefined}>Sign in</button>;
}

export function LoginForm({ next }: { next: string }) {
  const [state, action] = useFormState(loginAction, {});
  return (
    <>
      <AuthTabs active="signin" />
      <form action={action} className={styles.form}>
        <div>
          <h1>Welcome back</h1>
          <div className={styles.sub}>Sign in to your workspace.</div>
        </div>
        <input type="hidden" name="next" value={next} />
        <button type="button" className={`${styles.btn} ${styles.sec}`} disabled title="Google sign-in is not available yet">
          <svg viewBox="0 0 24 24" aria-hidden="true" width="16" height="16"><circle cx="12" cy="12" r="9" fill="none" stroke="#2B5CE6" strokeWidth="2" /><path d="M12 12h8" stroke="#2B5CE6" strokeWidth="2" /></svg>
          Continue with Google
        </button>
        <div className={styles.or}>or with email</div>
        <div className={styles.field}>
          <label htmlFor="email">Work email</label>
          <input id="email" name="email" type="email" autoComplete="email" placeholder="you@company.in" required />
        </div>
        <div className={styles.field}>
          <label htmlFor="password">Password</label>
          <input id="password" name="password" type="password" autoComplete="current-password" placeholder="Your password" required />
        </div>
        <div className={styles.keepRow}>
          <label className={styles.chk} htmlFor="keep"><input id="keep" type="checkbox" name="remember" defaultChecked />Keep me signed in on this device</label>
          <Link className={styles.tl} href="/forgot">Forgot password?</Link>
        </div>
        {state.error && <p role="alert" className={styles.err}>{state.error}</p>}
        <Submit />
        <div className={styles.note}>Your admin turned on single sign-on? Use Continue with Google or ask them for the link.</div>
      </form>
    </>
  );
}
