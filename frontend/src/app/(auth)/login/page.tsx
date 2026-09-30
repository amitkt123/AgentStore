import { LoginForm } from "../LoginForm";
import { safeNext } from "@/lib/safeNext";

export default function Page({ searchParams }: { searchParams: { next?: string } }) {
  return <LoginForm next={safeNext(searchParams.next)} />;
}
