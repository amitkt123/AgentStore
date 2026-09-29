import createClient from "openapi-fetch";
import type { components, paths } from "./generated/schema.js";
import { createFetch, type RetryOptions } from "./http.js";

type S = components["schemas"];

export class Api {
  private readonly c;

  constructor(opts: { baseUrl: string; token: string; retry?: RetryOptions }) {
    this.c = createClient<paths>({
      baseUrl: opts.baseUrl.replace(/\/$/, ""),
      headers: { Authorization: `Bearer ${opts.token}` },
      fetch: createFetch(opts.retry),
    });
  }

  async context(): Promise<S["ContextResponse"]> {
    return (await this.c.GET("/runtime/v1/context")).data!;
  }

  async records(resource: string, cursor?: string, limit?: number): Promise<S["RecordPage"]> {
    return (await this.c.GET("/runtime/v1/resources/{resource}/records", {
      params: { path: { resource }, query: { cursor, limit } },
    })).data!;
  }

  async write(resource: string, records: Record<string, unknown>[]): Promise<S["WriteResult"]> {
    return (await this.c.POST("/runtime/v1/resources/{resource}/records", {
      params: { path: { resource } }, body: { records },
    })).data!;
  }

  async fileLink(resource: string): Promise<S["FileLink"]> {
    return (await this.c.GET("/runtime/v1/resources/{resource}/file", { params: { path: { resource } } })).data!;
  }

  async fileContent(resource: string): Promise<Uint8Array> {
    const { data } = await this.c.GET("/runtime/v1/resources/{resource}/file/content", {
      params: { path: { resource } }, parseAs: "arrayBuffer",
    });
    return new Uint8Array(data as ArrayBuffer);
  }

  async usage(quantity: number, unit: string, outcomeRef?: string): Promise<S["UsageResponse"]> {
    return (await this.c.POST("/runtime/v1/usage", { body: { quantity, unit, ...(outcomeRef ? { outcomeRef } : {}) } })).data!;
  }

  async llmChat(body: Record<string, unknown>): Promise<Record<string, any>> {
    return (await this.c.POST("/runtime/v1/llm/chat", { body })).data as Record<string, any>;
  }

  async createGate(body: S["CreateGateRequest"]): Promise<S["GateCreatedResponse"]> {
    return (await this.c.POST("/runtime/v1/gates", { body })).data!;
  }

  async getGate(gateId: string, wait: number): Promise<S["GateView"]> {
    return (await this.c.GET("/runtime/v1/gates/{gateId}", {
      params: { path: { gateId }, query: { wait } },
    })).data!;
  }

  async heartbeat(): Promise<void> {
    await this.c.POST("/runtime/v1/heartbeat");
  }

  async logs(entries: S["LogEntry"][]): Promise<void> {
    await this.c.POST("/runtime/v1/logs", { body: { entries } });
  }

  async complete(outputs?: Record<string, unknown>): Promise<S["RunStateResponse"]> {
    return (await this.c.POST("/runtime/v1/complete", outputs ? { body: { outputs } } : {})).data!;
  }

  async fail(error: string, retriable = false): Promise<S["RunStateResponse"]> {
    return (await this.c.POST("/runtime/v1/fail", { body: { error: error.slice(0, 2000), retriable } })).data!;
  }
}
