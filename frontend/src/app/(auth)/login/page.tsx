"use client";

import { useFormState, useFormStatus } from "react-dom";
import { loginAction, type LoginState } from "./actions";

const initialState: LoginState = { error: null, needsWorkspace: false };

function SubmitButton() {
  const { pending } = useFormStatus();
  return (
    <button type="submit" disabled={pending} className="bg-green text-white rounded-lg px-5 py-2 font-medium w-full disabled:opacity-50">
      {pending ? "Signing in…" : "Sign in"}
    </button>
  );
}

export default function LoginPage() {
  const [state, formAction] = useFormState(loginAction, initialState);
  return (
    <div className="max-w-sm mx-auto mt-24">
      <h1 className="font-serif text-2xl mb-6">Sign in to Karya</h1>
      <form action={formAction} className="space-y-4">
        <input name="email" type="email" required placeholder="Email" className="border border-line rounded-lg px-3 py-2 w-full" />
        <input name="password" type="password" required placeholder="Password" className="border border-line rounded-lg px-3 py-2 w-full" />
        {state.needsWorkspace && (
          <>
            <fieldset className="grid grid-cols-2 gap-2">
              <legend className="text-sm text-ink2 mb-2">Pick what you&apos;re here to do. You can add the other later.</legend>
              <label className="border border-line rounded-lg px-3 py-2 text-sm">
                <input type="radio" name="side" value="BUYER" defaultChecked className="mr-2" />I run finance
              </label>
              <label className="border border-line rounded-lg px-3 py-2 text-sm">
                <input type="radio" name="side" value="DEVELOPER" className="mr-2" />I build agents
              </label>
            </fieldset>
            <input name="orgName" required placeholder="Company, or your name or studio" className="border border-line rounded-lg px-3 py-2 w-full" />
          </>
        )}
        {state.error && <p className="text-red text-sm">{state.error}</p>}
        <SubmitButton />
      </form>
      <a href="/signup" className="block text-center text-sm text-ink2 mt-4">Create a buyer account</a>
    </div>
  );
}
