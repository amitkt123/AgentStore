"use client";
import { useSearchParams } from "next/navigation";
import { Suspense } from "react";
import { useFormState } from "react-dom";
import { Button } from "@/ui/Button";
import { Field } from "@/ui/Field";
import { resetAction } from "../actions";
import styles from "../auth.module.css";

function Form() {
  const token = useSearchParams().get("token") ?? "";
  const [state, action] = useFormState(resetAction, {});
  return (
    <form action={action} className={styles.form}>
      <h1>Choose a new password</h1>
      <input type="hidden" name="token" value={token} />
      <Field label="New password" name="password" type="password" minLength={8} autoComplete="new-password" required />
      {state.error && <p role="alert" className={styles.err}>{state.error}</p>}
      <Button type="submit">Save password</Button>
    </form>
  );
}

export default function Page() {
  return <Suspense><Form /></Suspense>;
}
