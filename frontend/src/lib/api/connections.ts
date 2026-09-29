import { api } from "./http";
import type { ConnectionSummary } from "./types";

export const listMyConnections = () => api<ConnectionSummary[]>("/api/v1/connections/mine");
