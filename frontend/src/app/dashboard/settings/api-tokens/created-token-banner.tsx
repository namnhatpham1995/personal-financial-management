"use client";

import { useState } from "react";
import { Check, Copy, TriangleAlert } from "lucide-react";
import { Button } from "@/components/ui/button";

export interface CreatedTokenBannerProps {
  plaintextToken: string;
  onDismiss: () => void;
}

/** Shown exactly once, right after token creation — the plaintext is never retrievable again. */
export function CreatedTokenBanner({ plaintextToken, onDismiss }: CreatedTokenBannerProps) {
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    await navigator.clipboard.writeText(plaintextToken);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="rounded-lg border border-primary/30 bg-primary/5 p-5">
      <div className="mb-3 flex items-start gap-2">
        <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
        <p className="text-sm text-foreground">
          Copy this token now — you won&apos;t be able to see it again after you leave this page.
        </p>
      </div>

      <div className="flex items-center gap-2">
        <code className="flex-1 overflow-x-auto rounded-md border border-border bg-card px-3 py-2 text-xs text-foreground">
          {plaintextToken}
        </code>
        <Button
          type="button"
          size="sm"
          variant="secondary"
          onClick={handleCopy}
          aria-label="Copy token"
        >
          {copied ? <Check className="h-3.5 w-3.5" /> : <Copy className="h-3.5 w-3.5" />}
          {copied ? "Copied" : "Copy"}
        </Button>
      </div>

      <div className="mt-4 flex justify-end">
        <Button type="button" size="sm" variant="ghost" onClick={onDismiss}>
          Done
        </Button>
      </div>
    </div>
  );
}
