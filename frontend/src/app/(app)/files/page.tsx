import { listDatasets, getStorage } from "@/lib/api/datasets";
import { getSession } from "@/lib/session";
import { FilesView } from "@/features/files/FilesView";
import { ErrorState } from "@/ui/ErrorState";
import { ApiError } from "@/lib/api/http";

export default async function Page() {
  const session = (await getSession())!;
  try {
    const [datasets, storage] = await Promise.all([listDatasets(), getStorage(session.orgId)]);
    return (
      <>
        <h1>Files</h1>
        <p>An agent only sees the files you plug into it.</p>
        <FilesView datasets={datasets} storage={storage} />
      </>
    );
  } catch (e) {
    if (e instanceof ApiError) return <ErrorState message={e.message} />;
    throw e;
  }
}
