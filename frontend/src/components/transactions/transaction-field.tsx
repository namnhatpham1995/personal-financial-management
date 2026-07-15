import { cn } from "@/lib/utils";

export const inputCls =
  "w-full rounded-md border border-border bg-card px-3.5 py-2.5 text-base text-foreground placeholder:text-muted-foreground/50 focus:outline-none focus:ring-2 focus:ring-primary/40 focus:border-primary/40 transition-colors";

export function fieldInputCls(disabled?: boolean) {
  return cn(inputCls, disabled && "cursor-not-allowed opacity-50");
}

/**
 * Labeled field wrapper shared by the transaction form and the transfer From/To panels.
 * The label wraps its control so the association is implicit (no id plumbing needed
 * through children), keeping every field screen-reader- and getByLabelText-addressable.
 */
export function Field({ label, error, children }: { label: string; error?: string; children: React.ReactNode }) {
  return (
    <div>
      <label className="block">
        <span className="mb-1.5 block text-xs font-medium uppercase tracking-wide text-muted-foreground">{label}</span>
        {children}
      </label>
      {error && <p className="mt-1 text-xs text-destructive">{error}</p>}
    </div>
  );
}
