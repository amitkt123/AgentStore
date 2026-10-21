"use server";
import { revalidatePath } from "next/cache";
import { ApiError } from "@/lib/api/http";
import {
  createRefund, createTopUp, getBankTransferDetails, getRefundable, payBill, simulatePayment,
} from "@/lib/api/money";
import { getSession } from "@/lib/session";
import type {
  BankTransferDetails, Checkout, PaymentMethod, Refund, Refundable, SimulateOutcome,
} from "@/lib/api/types";

const fail = (e: unknown): { error: string } => {
  if (e instanceof ApiError) return { error: e.message };
  throw e;
};

const orgId = async () => (await getSession())!.orgId;

export async function startTopUp(amount: number, method: PaymentMethod): Promise<{ checkout: Checkout } | { error: string }> {
  try {
    return { checkout: await createTopUp(await orgId(), { amount, method }) };
  } catch (e) { return fail(e); }
}

export async function startBillPayment(billId: string, method: PaymentMethod): Promise<{ checkout: Checkout } | { error: string }> {
  try {
    return { checkout: await payBill(await orgId(), billId, method) };
  } catch (e) { return fail(e); }
}

export async function loadBankDetails(): Promise<{ details: BankTransferDetails } | { error: string }> {
  try {
    return { details: await getBankTransferDetails(await orgId()) };
  } catch (e) { return fail(e); }
}

// The server rejects an amount over the cap with 422; the message is shown as is.
export async function previewRefund(amount: number): Promise<{ refundable: Refundable } | { error: string }> {
  try {
    return { refundable: await getRefundable(await orgId(), amount) };
  } catch (e) { return fail(e); }
}

export async function requestRefund(amount: number): Promise<{ refund: Refund } | { error: string }> {
  try {
    const refund = await createRefund(await orgId(), { requestId: crypto.randomUUID(), amount });
    revalidatePath("/billing");
    return { refund };
  } catch (e) { return fail(e); }
}

// orderId is the payment_order id (CheckoutResponse.order.id), which the dev endpoint looks up.
export async function simulate(orderId: string, outcome: SimulateOutcome): Promise<{ outcome: string } | { error: string }> {
  try {
    const res = await simulatePayment(orderId, outcome);
    revalidatePath("/billing");
    return { outcome: res.outcome };
  } catch (e) { return fail(e); }
}
