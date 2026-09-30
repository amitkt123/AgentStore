"use server";
import { ApiError } from "@/lib/api/http";
import { patchVerification, submitVerification, uploadPanDocument } from "@/lib/api/verification";
import { toFailure, type StepResult } from "@/features/verification/errors";
import type { DeveloperVerification, VerificationStep } from "@/lib/api/types";

async function run(fn: () => Promise<DeveloperVerification>): Promise<StepResult> {
  try {
    return { view: await fn() };
  } catch (e) {
    if (e instanceof ApiError) return { failure: toFailure(e) };
    throw e;
  }
}

export async function saveStepAction(step: VerificationStep, fields: Record<string, string>): Promise<StepResult> {
  return run(() => patchVerification(step, fields));
}

export async function uploadPanAction(fd: FormData): Promise<StepResult> {
  return run(() => uploadPanDocument(fd));
}

export async function submitAction(): Promise<StepResult> {
  return run(() => submitVerification());
}
