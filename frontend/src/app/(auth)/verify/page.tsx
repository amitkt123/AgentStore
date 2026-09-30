import Link from "next/link";
import { authApi } from "@/lib/api/auth";

export default async function Page({ searchParams }: { searchParams: { token?: string } }) {
  const ok = searchParams.token ? (await authApi.verifyEmail(searchParams.token)).ok : false;
  return (
    <div>
      <h1>{ok ? "Email confirmed" : "That link didn't work"}</h1>
      <p>{ok ? "Thanks. You can carry on." : "The link is invalid or has expired. Sign in and ask for a new one."}</p>
      <p><Link href="/home">Go to your workspace</Link></p>
    </div>
  );
}
