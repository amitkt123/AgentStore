export function WizardRail({ steps, currentIndex }: { steps: string[]; currentIndex: number }) {
  return (
    <div className="flex border border-line rounded-lg overflow-hidden mb-6 bg-doc">
      {steps.map((label, i) => (
        <div
          key={label}
          className={`flex-1 px-3 py-2 text-xs border-r border-line last:border-r-0 flex items-center gap-2 ${
            i === currentIndex ? "text-ink" : i < currentIndex ? "text-green" : "text-muted"
          }`}
        >
          <span
            className={`w-5 h-5 rounded-full border flex items-center justify-center text-[11px] flex-none ${
              i < currentIndex ? "bg-green border-green text-white" : i === currentIndex ? "border-ink font-semibold" : "border-line"
            }`}
          >
            {i + 1}
          </span>
          {label}
        </div>
      ))}
    </div>
  );
}
