import Link from "next/link";
import { EmptyState } from "@/ui/EmptyState";

export default function NotFound() {
  return <EmptyState title="We can't find that page" body="It may have moved, or the link may be wrong." action={<Link href="/">Go home</Link>} />;
}
