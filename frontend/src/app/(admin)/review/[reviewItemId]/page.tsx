import { backend } from "@/lib/backend";
import type { ReviewItemDetailResponse } from "@/lib/types";
import { ReviewItemDetailView } from "./ReviewItemDetailView";

export default async function ReviewItemPage({ params }: { params: Promise<{ reviewItemId: string }> }) {
  const { reviewItemId } = await params;
  const item = await backend.get<ReviewItemDetailResponse>(`/api/v1/review/${reviewItemId}`);
  return <ReviewItemDetailView item={item} />;
}
