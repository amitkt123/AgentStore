export function PushInstructions({ agentId, versionNumber }: { agentId: string; versionNumber: string }) {
  return (
    <section aria-label="Push your code" className="mt-6 rounded-lg border border-line p-4">
      <h2 className="mb-2 font-medium">Push your code</h2>
      <p className="mb-2 text-ink2">
        This draft has no code yet. In a project whose <code className="font-mono">agentstore.yaml</code> holds this agent&apos;s id, run:
      </p>
      <pre className="overflow-x-auto rounded bg-paper2 p-3 font-mono text-sm">agentstore push --wait</pre>
      <p className="mt-2 text-sm text-ink2">
        Agent id: <code className="font-mono">{agentId}</code>
      </p>
      <p className="mt-2 text-sm text-ink2">
        The <code className="font-mono">version</code> in your bundle&apos;s <code className="font-mono">manifest.json</code> must be{" "}
        <code className="font-mono">{versionNumber}</code> to fill this draft. The manifest inside the bundle replaces the one you typed here.
      </p>
    </section>
  );
}
