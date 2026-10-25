// Public page. Keep in step with docs/legal/developer-agreement.md (the copy for counsel).
export const metadata = { title: "Developer Distribution Agreement 2.2" };

const SECTIONS: { heading: string; paragraphs?: string[]; items?: string[] }[] = [
  {
    heading: "1. Distribution",
    paragraphs: [
      "Your agents only touch what their manifest declares, and buyers see exactly that. Karya keeps 20% of what buyers pay; AI usage through Karya is charged at cost. Buyer data is never yours to keep, copy or train on. Karya can pause a version that misbehaves, and decides buyer disputes.",
    ],
  },
  {
    heading: "2. Payments to you",
    items: [
      "Karya resells your agents' services to buyers. You are paid for the services Karya resells, at the share set out above.",
      "Your earnings balance is money Karya owes you for services. It is not a deposit, it does not earn interest, and it is not held in trust.",
      "You may withdraw your earnings balance only to a verified payout account in your own name. A payout account must pass verification before the first withdrawal, and changing it needs verification again.",
      "Karya may freeze withdrawals while it investigates fraud or a dispute. A freeze can also stop new payout accounts and buyer refunds. Withdrawals waiting during a freeze resume when it is lifted.",
      "Karya may withhold tax from payments when the law requires it.",
      "If your earnings balance becomes negative (for example after a refund or adjustment), you owe that amount to Karya and no withdrawal is possible until it is cleared.",
    ],
  },
];

export default function DeveloperAgreementPage() {
  return (
    <main style={{ maxWidth: 720, margin: "0 auto", padding: "48px 16px", lineHeight: 1.6 }}>
      <h1>Developer Distribution Agreement</h1>
      <p>Version 2.2. Draft wording, pending legal review.</p>
      {SECTIONS.map((s) => (
        <section key={s.heading}>
          <h2>{s.heading}</h2>
          {s.paragraphs?.map((p) => <p key={p}>{p}</p>)}
          {s.items && <ol>{s.items.map((i) => <li key={i}>{i}</li>)}</ol>}
        </section>
      ))}
    </main>
  );
}
