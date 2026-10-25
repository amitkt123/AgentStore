"use client";
import { ErrorState } from "@/ui/ErrorState";

export default function Error({ reset }: { error: Error; reset: () => void }) {
  return <ErrorState message="Something went wrong loading this page." onRetry={reset} />;
}
