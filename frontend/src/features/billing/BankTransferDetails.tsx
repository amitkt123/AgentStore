"use client";
import { useState } from "react";
import styles from "./billing.module.css";
import { Button } from "@/ui/Button";
import type { BankTransferDetails as Details } from "@/lib/api/types";

function CopyRow({ label, value }: { label: string; value: string }) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try { await navigator.clipboard.writeText(value); setCopied(true); } catch { /* clipboard blocked: the value is still on screen */ }
  };
  return (
    <div className={styles.copyRow}>
      <dt>{label}</dt>
      <dd className="mono">{value}</dd>
      <Button type="button" variant="ghost" onClick={copy} aria-label={`Copy ${label}`}>{copied ? "Copied" : "Copy"}</Button>
    </div>
  );
}

export function BankTransferDetails({ details }: { details: Details }) {
  return (
    <div>
      <dl className={styles.bank}>
        <CopyRow label="Account number" value={details.accountNumber} />
        <CopyRow label="IFSC" value={details.ifsc} />
        <CopyRow label="Beneficiary" value={details.beneficiaryName} />
      </dl>
      <p className={styles.note}>Money sent here is added to your wallet automatically, usually within a few minutes.</p>
    </div>
  );
}
