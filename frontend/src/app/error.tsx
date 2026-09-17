"use client";

import Link from "next/link";

export default function GlobalError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  return (
    <div className="min-h-screen flex items-center justify-center p-8">
      <div className="max-w-sm text-center">
        <h1 className="font-serif text-2xl mb-2">Something went wrong.</h1>
        <p className="text-ink2 mb-6 text-sm">
          This can happen if your session expired. Try again, or sign in again.
        </p>
        <div className="flex justify-center gap-3">
          <button onClick={() => reset()} className="border border-line rounded-lg px-5 py-2 font-medium">
            Try again
          </button>
          <Link href="/login" className="bg-green text-white rounded-lg px-5 py-2 font-medium">
            Go to login
          </Link>
        </div>
      </div>
    </div>
  );
}
