import { backend } from "@/lib/backend";
import type { ReviewItemResponse } from "@/lib/types";
import { ReviewQueueList } from "./ReviewQueueList";

export default async function ReviewQueuePage() {
  const items = await backend.get<ReviewItemResponse[]>("/api/v1/review/queue");
  return (
    <div>
      <h1 className="font-serif text-2xl mb-6">Review queue</h1>
      <ReviewQueueList items={items} />
    </div>
  );
}
