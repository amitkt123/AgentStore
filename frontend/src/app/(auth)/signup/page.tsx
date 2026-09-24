"use client";

import { useFormState, useFormStatus } from "react-dom";
import { signupAction, type SignupState } from "./actions";

const initialState: SignupState = { error: null };

function SubmitButton() {
  const { pending } = useFormStatus();
  return (
    <button type="submit" disabled={pending} className="bg-green text-white rounded-lg px-5 py-2 font-medium w-full disabled:opacity-50">
      {pending ? "Creating account…" : "Create account"}
    </button>
  );
}

export default function SignupPage() {
  const [state, formAction] = useFormState(signupAction, initialState);
  return (
    <div className="max-w-sm mx-auto mt-24">
      <h1 className="font-serif text-2xl mb-6">Create your account</h1>
      <form action={formAction} className="space-y-4">
        <fieldset className="grid grid-cols-2 gap-2">
          <legend className="text-sm text-ink2 mb-2">Pick what you&apos;re here to do. You can add the other later.</legend>
          <label className="border border-line rounded-lg px-3 py-2 text-sm">
            <input type="radio" name="side" value="BUYER" defaultChecked className="mr-2" />I run finance
          </label>
          <label className="border border-line rounded-lg px-3 py-2 text-sm">
            <input type="radio" name="side" value="DEVELOPER" className="mr-2" />I build agents
          </label>
        </fieldset>
        <input name="fullName" required placeholder="Full name" className="border border-line rounded-lg px-3 py-2 w-full" />
        <input name="orgName" required placeholder="Company, or your name or studio" className="border border-line rounded-lg px-3 py-2 w-full" />
        <input name="email" type="email" required placeholder="Work email" className="border border-line rounded-lg px-3 py-2 w-full" />
        <input name="password" type="password" required minLength={8} placeholder="Password" className="border border-line rounded-lg px-3 py-2 w-full" />
        {state.error && <p className="text-red text-sm">{state.error}</p>}
        <SubmitButton />
      </form>
      <a href="/login" className="block text-center text-sm text-ink2 mt-4">Already have an account? Sign in</a>
    </div>
  );
}
