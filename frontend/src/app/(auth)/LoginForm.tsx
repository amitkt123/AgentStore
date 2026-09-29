"use client";
import Link from "next/link";
import { useFormState, useFormStatus } from "react-dom";
import { Button } from "@/ui/Button";
import { Field } from "@/ui/Field";
import { loginAction } from "./actions";
import styles from "./auth.module.css";

function Submit({ children }: { children: React.ReactNode }) {
  const { pending } = useFormStatus();
  return <Button type="submit" loading={pending}>{children}</Button>;
}

export function LoginForm({ next }: { next: string }) {
  const [state, action] = useFormState(loginAction, {});
  return (
    <form action={action} className={styles.form}>
      <h1>Sign in</h1>
      <input type="hidden" name="next" value={next} />
      <Field label="Email" name="email" type="email" autoComplete="email" required />
      <Field label="Password" name="password" type="password" autoComplete="current-password" required />
      <label className={styles.check}><input type="checkbox" name="remember" /> Keep me signed in on this device</label>
      {state.error && <p role="alert" className={styles.err}>{state.error}</p>}
      <Submit>Sign in</Submit>
      <p className={styles.alt}><Link href="/forgot">Forgot your password?</Link></p>
      <p className={styles.alt}>New to Karya? <Link href="/signup">Create an account</Link></p>
    </form>
  );
}
