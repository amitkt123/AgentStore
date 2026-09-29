import { errorFromProblem, parseRetryAfter } from "./errors.js";

export interface RetryOptions {
  maxRetries?: number;
  baseDelayMs?: number;
  maxDelayMs?: number;
  sleep?: (ms: number) => Promise<void>;
  jitter?: () => number;
  fetchImpl?: typeof fetch;
}

// POST /usage charges on every call and has no idempotency key, so a 5xx whose response was lost after the server
// committed would double-charge on retry. 429 is safe: the rate limiter rejects before the request is handled.
const NO_5XX_RETRY_PATHS = ["/runtime/v1/usage"];

const retryable = (status: number, request: Request) =>
  status === 429 || (status >= 500 && status !== 501 && !NO_5XX_RETRY_PATHS.includes(new URL(request.url).pathname));

/** fetch with one shared 429/5xx retry policy (429 only for POST /usage); a final >=400 response becomes a typed error. */
export function createFetch(opts: RetryOptions = {}): typeof fetch {
  const {
    maxRetries = 3, baseDelayMs = 500, maxDelayMs = 8000, jitter = Math.random,
    sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms)),
    fetchImpl = globalThis.fetch,
  } = opts;
  const delay = (attempt: number, retryAfter?: number) =>
    retryAfter !== undefined
      ? Math.min(retryAfter * 1000, 30_000)
      : Math.min(maxDelayMs, baseDelayMs * 2 ** (attempt - 1)) * (0.5 + jitter() / 2);

  return async (input, init) => {
    const request = input instanceof Request ? input : new Request(input as string | URL, init);
    for (let attempt = 0; ; attempt++) {
      const response = await fetchImpl(request.clone());
      if (response.status < 400) return response;
      const retryAfterHeader = response.headers.get("retry-after");
      if (retryable(response.status, request) && attempt < maxRetries) {
        await response.body?.cancel();
        await sleep(delay(attempt + 1, parseRetryAfter(retryAfterHeader)));
        continue;
      }
      const text = await response.text();
      let body: unknown;
      try { body = JSON.parse(text); } catch { body = { detail: text.slice(0, 500) }; }
      throw errorFromProblem(response.status, body, retryAfterHeader);
    }
  };
}
