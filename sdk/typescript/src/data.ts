import type { Api } from "./api.js";
import { AgentStoreError } from "./errors.js";

export interface FileHandle {
  resource: string;
  url: string;
  expiresAt?: string;
  read(): Promise<Uint8Array>;
}

export class Data {
  constructor(private readonly api: Api) {}

  async *records(resource: string, opts: { pageSize?: number } = {}): AsyncGenerator<Record<string, unknown>> {
    let cursor: string | undefined;
    for (;;) {
      const page = await this.api.records(resource, cursor, opts.pageSize);
      for (const r of page.records ?? []) yield r as Record<string, unknown>;
      const next = page.nextCursor;
      if (!next) return;
      if (next === cursor) throw new AgentStoreError(`pagination cursor for '${resource}' did not advance ('${next}')`);
      cursor = next;
    }
  }

  async write(resource: string, records: Record<string, unknown>[]): Promise<{ accepted: number; staged: boolean }> {
    if (records.length === 0) return { accepted: 0, staged: false };
    const r = await this.api.write(resource, records);
    return { accepted: r.accepted ?? 0, staged: r.staged ?? false };
  }

  async file(resource: string): Promise<FileHandle> {
    const link = await this.api.fileLink(resource);
    return { resource, url: link.url!, expiresAt: link.expiresAt, read: () => this.api.fileContent(resource) };
  }
}
