package com.agentstore.platform.execution;

/** Who a run belongs to and whether money moves (spec §4.1). Only PRODUCTION runs exist until the sandbox work (WS6). */
public enum RunMode { PRODUCTION, SANDBOX, REVIEW, TRIAL }
