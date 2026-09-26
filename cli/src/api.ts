import type { Credentials } from "./config.js";
import { CliError } from "./errors.js";

export class ApiClient {
  constructor(private readonly credentials: Credentials, private readonly fetchFn: typeof fetch) {}

  get<T>(path: string): Promise<T> {
    return this.json<T>("GET", path);
  }

  post<T>(path: string, body?: unknown): Promise<T> {
    return this.json<T>("POST", path, body);
  }

  /** PUT to a pre-signed URL. The URL carries a signature, so it is never sent the API key or printed. */
  async putBytes(url: string, bytes: Uint8Array): Promise<void> {
    const res = await this.send(url, { method: "PUT", body: bytes as BodyInit }, "the upload URL");
    if (!res.ok) {
      throw new CliError(`The upload was rejected (HTTP ${res.status}); run \`agentstore push\` again.`);
    }
  }

  /** The text at a pre-signed URL, or null when it is not there (yet). */
  async getText(url: string): Promise<string | null> {
    const res = await this.send(url, { method: "GET" }, "the log URL");
    return res.ok ? await res.text() : null;
  }

  private async json<T>(method: string, path: string, body?: unknown): Promise<T> {
    const res = await this.send(`${this.credentials.apiUrl}${path}`, {
      method,
      headers: {
        Authorization: `Bearer ${this.credentials.apiKey}`,
        "Content-Type": "application/json",
        Accept: "application/json",
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!res.ok) {
      throw await this.failure(res);
    }
    return (await res.json()) as T;
  }

  private async send(url: string, init: RequestInit, what: string = this.credentials.apiUrl): Promise<Response> {
    try {
      return await this.fetchFn(url, init);
    } catch (e) {
      throw new CliError(`Could not reach ${what}: ${(e as Error).message}`);
    }
  }

  private async failure(res: Response): Promise<CliError> {
    if (res.status === 401) {
      return new CliError("API key rejected or revoked. Run `agentstore login` with a valid key.");
    }
    const text = (await res.text().catch(() => "")).trim();
    let message = text.slice(0, 500);
    try {
      const parsed: unknown = JSON.parse(text);
      if (Array.isArray(parsed)) {
        message = parsed.join("; ");
      } else if (parsed && typeof parsed === "object" && typeof (parsed as { message?: unknown }).message === "string") {
        message = (parsed as { message: string }).message;
      }
    } catch {
      // not JSON: keep the raw text
    }
    return new CliError(`Request failed (HTTP ${res.status})${message ? `: ${message}` : ""}`);
  }
}
