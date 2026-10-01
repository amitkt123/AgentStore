// Wire names of the backend enums the developer screens read or send. enums.test.ts pins each list to the Java source.
export const SCOPE_TYPES = ["READ", "WRITE"] as const;
export const DATA_FORMATS = ["CSV", "JSON", "PDF"] as const;
export const PRICING_UNITS = ["METERED", "OUTCOME", "SUBSCRIPTION"] as const;
export const LLM_MODES = ["PLATFORM", "BYOK_PROXY", "BYOK_DIRECT"] as const;
export const VERSION_STATUSES = ["DRAFT", "IN_REVIEW", "LIVE", "REJECTED"] as const;
export const BUILD_STATUSES = ["QUEUED", "BUILDING", "SUCCEEDED", "FAILED"] as const;

export type LlmMode = (typeof LLM_MODES)[number];
export type VersionStatus = (typeof VERSION_STATUSES)[number];
export type BuildStatus = (typeof BUILD_STATUSES)[number];
