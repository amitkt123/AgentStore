const STYLES: Record<string, string> = {
  LIVE: "bg-greenSoft text-green",
  ACTIVE: "bg-greenSoft text-green",
  COMPLETED: "bg-greenSoft text-green",
  SUCCEEDED: "bg-greenSoft text-green",
  APPROVED: "bg-greenSoft text-green",
  DRAFT: "bg-paper2 text-muted",
  REVOKED: "bg-paper2 text-muted",
  IN_REVIEW: "bg-amberSoft text-amber",
  PENDING: "bg-amberSoft text-amber",
  RUNNING: "bg-amberSoft text-amber",
  QUEUED: "bg-amberSoft text-amber",
  BUILDING: "bg-amberSoft text-amber",
  PENDING_BUILD: "bg-amberSoft text-amber",
  AWAITING_APPROVAL: "bg-amberSoft text-amber",
  REJECTED: "bg-redSoft text-red",
  FAILED: "bg-redSoft text-red",
  CANCELLED: "bg-redSoft text-red",
  TIMED_OUT: "bg-redSoft text-red",
  EXPIRED: "bg-redSoft text-red",
};

export function StatusPill({ status }: { status: string }) {
  const style = STYLES[status] ?? "bg-paper2 text-muted";
  return (
    <span className={`text-xs rounded-full px-2 py-0.5 font-medium inline-block ${style}`}>
      {status.replace(/_/g, " ").toLowerCase()}
    </span>
  );
}
