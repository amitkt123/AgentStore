"use client";
import Link from "next/link";
import { useFormState } from "react-dom";
import { Button } from "@/ui/Button";
import { Field } from "@/ui/Field";
import { forgotAction } from "../actions";
import styles from "../auth.module.css";

export default function Page() {
  const [state, action] = useFormState(forgotAction, {});
  return (
    <form action={action} className={styles.form}>
      <h1>Reset your password</h1>
      <Field label="Email" name="email" type="email" required />
      {state.ok && <p role="status">{state.ok}</p>}
      <Button type="submit">Send reset link</Button>
      <p className={styles.alt}><Link href="/login">Back to sign in</Link></p>
    </form>
  );
}
