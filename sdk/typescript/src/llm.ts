import type { Api } from "./api.js";

export interface ChatRequest {
  model: string;
  messages: Array<{ role: string; content: string | Array<Record<string, unknown>> }>;
  maxTokens: number;
  system?: string;
  tools?: Array<Record<string, unknown>>;
  temperature?: number;
}

export interface ChatResponse {
  content: Array<Record<string, any>>;
  stopReason: string;
  usage: { inputTokens: number; outputTokens: number };
  model?: string;
  text: string;
}

export class Llm {
  constructor(private readonly api: Api) {}

  async chat(req: ChatRequest): Promise<ChatResponse> {
    const body: Record<string, unknown> = { model: req.model, messages: req.messages, maxTokens: req.maxTokens };
    if (req.system !== undefined) body.system = req.system;
    if (req.tools?.length) body.tools = req.tools;
    if (req.temperature !== undefined) body.temperature = req.temperature;
    const r = await this.api.llmChat(body);
    const content: Array<Record<string, any>> = r.content ?? [];
    return {
      content,
      stopReason: r.stopReason ?? "end_turn",
      usage: { inputTokens: r.usage?.inputTokens ?? 0, outputTokens: r.usage?.outputTokens ?? 0 },
      model: r.model,
      text: content.find((b) => b.type === "text")?.text ?? "",
    };
  }
}
