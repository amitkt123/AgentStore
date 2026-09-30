import { listApiKeys } from "@/lib/api/apikeys";
import { ApiError } from "@/lib/api/http";
import { ApiKeysView } from "@/features/apikeys/ApiKeysView";
import { ErrorState } from "@/ui/ErrorState";

export default async function Page() {
  try {
    const keys = await listApiKeys();
    return (
      <>
        <h1>API keys</h1>
        <p>A key lets the Karya CLI act as you. Treat it like a password.</p>
        <ApiKeysView keys={keys} />
      </>
    );
  } catch (e) {
    if (e instanceof ApiError) return <ErrorState message={e.message} />;
    throw e;
  }
}
