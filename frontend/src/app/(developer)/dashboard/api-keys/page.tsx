import { backend } from "@/lib/backend";
import type { ApiKeySummary } from "@/lib/types";
import { ApiKeysView } from "./ApiKeysView";

export default async function ApiKeysPage() {
  const keys = await backend.get<ApiKeySummary[]>("/api/v1/developer/api-keys");
  return <ApiKeysView keys={keys} />;
}
