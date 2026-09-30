"use client";
import { useRef, useState } from "react";
import styles from "./verification.module.css";
import { Button } from "@/ui/Button";
import { Pill } from "@/ui/Pill";
import { Stepper, type StepperItem } from "@/ui/Stepper";
import { saveStepAction, submitAction, uploadPanAction } from "@/app/(app)/developer/verification/actions";
import { AboutForm, AgreementForm, PhoneForm, TaxForm } from "./StepForms";
import { TOO_LARGE_TEXT, type Failure, type FieldName, type StepResult } from "./errors";
import { STEPS, banner, fieldsFor, initialDraft, isLocked, karyaHint, missingForSubmit, reachable, stepDone, stepHint, stepIndex, validate, type Draft } from "./model";
import type { DeveloperVerification, VerificationStep } from "@/lib/api/types";

const MAX_DOCUMENT_BYTES = 5 * 1024 * 1024;

export function VerificationView({ initial }: { initial: DeveloperVerification }) {
  const [view, setView] = useState(initial);
  const [stepKey, setStepKey] = useState<VerificationStep>(isLocked(initial.status) ? "ABOUT" : initial.currentStep);
  const [draft, setDraft] = useState<Draft>(() => initialDraft(initial));
  const [agreed, setAgreed] = useState(!!initial.agreementAcceptedAt);
  const [errors, setErrors] = useState<Partial<Record<FieldName, string>>>({});
  const [alert, setAlert] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);

  const locked = isLocked(view.status);
  const last = stepKey === "AGREEMENT";
  const meta = STEPS[stepIndex(stepKey)];
  const missing = last ? missingForSubmit(view, agreed) : [];
  const note = banner(view);
  const disabled = locked || busy;
  const formProps = { view, draft, errors, disabled, set: (p: Partial<Draft>) => setDraft((d) => ({ ...d, ...p })) };

  // React 18 does not hold isPending across an async transition, so guard double clicks by hand.
  const run = async (fn: () => Promise<void>) => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    try { await fn(); } finally { busyRef.current = false; setBusy(false); }
  };

  const fail = (f: Failure) => {
    if (f.field) setErrors({ [f.field]: f.text });
    else setAlert(f.text);
    if (f.missingSteps?.length) setStepKey(f.missingSteps[0]);
  };
  const apply = (res: StepResult): boolean => {
    if ("failure" in res) { fail(res.failure); return false; }
    setView(res.view);
    return true;
  };

  const onContinue = () => {
    const found = validate(stepKey, draft, view, agreed);
    setErrors(found);
    setAlert(null);
    if (Object.keys(found).length > 0) return;
    void run(async () => {
      if (!apply(await saveStepAction(stepKey, fieldsFor(stepKey, draft)))) return;
      setDraft((d) => ({ ...d, pan: "", gstin: "", phone: "", editPan: false, editGstin: false, editPhone: false }));
      if (!last) { setStepKey(STEPS[stepIndex(stepKey) + 1].key); return; }
      apply(await submitAction());
    });
  };

  const onPanFile = (file: File) => {
    setAlert(null);
    if (file.size > MAX_DOCUMENT_BYTES) { setAlert(TOO_LARGE_TEXT); return; }
    void run(async () => {
      const fd = new FormData();
      fd.set("file", file);
      apply(await uploadPanAction(fd));
    });
  };

  const items: StepperItem[] = STEPS.map((s) => ({
    key: s.key,
    name: s.name,
    hint: stepHint(view, s.key),
    state: s.key === stepKey ? "cur" : stepDone(view, s.key) ? "done" : "todo",
    disabled: !reachable(view, s.key),
  }));

  return (
    <div className={styles.wrap}>
      <section className={styles.panel} aria-label="Verification steps">
        <h1 className={styles.h}>Get verified</h1>
        <p className={styles.sub}>About 10 minutes. You can stop and come back.</p>
        <Stepper
          items={items}
          note={{ title: "Karya checks it", hint: karyaHint(view.status) }}
          onPick={(k) => { setErrors({}); setAlert(null); setStepKey(k as VerificationStep); }}
        />
      </section>
      <section className={`${styles.panel} ${styles.form}`}>
        {note && (
          <div className={styles.banner} data-testid="banner">
            <Pill tone={note.tone}>{note.label}</Pill>
            <span>{note.text}</span>
          </div>
        )}
        <div>
          <h2 className={styles.title}>{meta.title}</h2>
          <p className={styles.sub}>{meta.sub}</p>
        </div>
        {stepKey === "ABOUT" && <AboutForm {...formProps} />}
        {stepKey === "TAX_IDS" && <TaxForm {...formProps} onPanFile={onPanFile} />}
        {stepKey === "PHONE" && <PhoneForm {...formProps} />}
        {stepKey === "AGREEMENT" && <AgreementForm agreed={agreed} onAgreed={setAgreed} error={errors.agreementVersion} disabled={disabled} />}
        {alert && <p role="alert" className={styles.err}>{alert}</p>}
        {!locked && missing.length > 0 && <p className={styles.sub}>Still needed: {missing.join(", ")}.</p>}
        {!locked && (
          <div className={styles.footer}>
            <Button onClick={onContinue} loading={busy} disabled={missing.length > 0}>{last ? "Submit for review" : "Continue"}</Button>
          </div>
        )}
      </section>
    </div>
  );
}
