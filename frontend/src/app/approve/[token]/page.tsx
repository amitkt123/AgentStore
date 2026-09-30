import { getApprovalCard } from "@/lib/api/approvals";
import { ErrorState } from "@/ui/ErrorState";
import { LinkCard } from "./LinkCard";
import { FAILURE_MESSAGE } from "./messages";

export default async function Page({ params }: { params: { token: string } }) {
  const res = await getApprovalCard(params.token);
  if (!res.ok) return <ErrorState message={FAILURE_MESSAGE[res.reason]} />;
  return (
    <>
      <h1>Approval needed</h1>
      <LinkCard token={params.token} card={res.card} />
    </>
  );
}
