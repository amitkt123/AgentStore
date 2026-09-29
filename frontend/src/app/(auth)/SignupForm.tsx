"use client";
import Link from "next/link";
import { useFormState, useFormStatus } from "react-dom";
import { Button } from "@/ui/Button";
import { Field } from "@/ui/Field";
import { signupAction } from "./actions";
import styles from "./auth.module.css";

function Submit() {
  const { pending } = useFormStatus();
  return <Button type="submit" loading={pending}>Create account</Button>;
}

export function SignupForm() {
  const [state, action] = useFormState(signupAction, {});
  return (
    <form action={action} className={styles.form}>
      <h1>Create your account</h1>
      <fieldset className={styles.side}>
        <legend>I want to</legend>
        <label><input type="radio" name="side" value="BUYER" defaultChecked /> Run agents for my team</label>
        <label><input type="radio" name="side" value="DEVELOPER" /> Build agents</label>
      </fieldset>
      <Field label="Your name" name="fullName" autoComplete="name" required />
      <Field label="Company" name="orgName" autoComplete="organization" required />
      <Field label="Email" name="email" type="email" autoComplete="email" required />
      <Field label="Password" name="password" type="password" autoComplete="new-password" minLength={8} required />
      {state.error && <p role="alert" className={styles.err}>{state.error}</p>}
      <Submit />
      <p className={styles.alt}>Have an account? <Link href="/login">Sign in</Link></p>
    </form>
  );
}
