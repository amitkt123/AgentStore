import { getVerification } from "@/lib/api/verification";
import { ApiError } from "@/lib/api/http";
import { VerificationView } from "@/features/verification/VerificationView";
import { ErrorState } from "@/ui/ErrorState";

export default async function Page() {
  try {
    const initial = await getVerification();
    return <VerificationView initial={initial} />;
  } catch (e) {
    if (e instanceof ApiError) return <ErrorState message={e.message} />;
    throw e;
  }
}
