import type { LinkFailure } from "@/lib/api/approvals";

export const FAILURE_MESSAGE: Record<LinkFailure, string> = {
  gone: "This link is no longer valid. It may have been used already or expired.",
  "not-approver": "You're no longer an approver for this, so you can't answer it.",
  busy: "Too many attempts. Try again in a minute.",
  error: "Something went wrong. Try again.",
};
