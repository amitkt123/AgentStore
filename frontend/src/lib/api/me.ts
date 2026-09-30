import { api } from "./http";
import type { MeResponse } from "./types";

export const getMe = () => api<MeResponse>("/api/v1/me");
