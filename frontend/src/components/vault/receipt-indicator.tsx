"use client";

import { Receipt } from "lucide-react";
import { cn } from "@/lib/utils";

interface Props {
  /** Whether this transaction has an attached receipt in the vault. */
  hasReceipt: boolean;
  className?: string;
}

/**
 * Small icon shown in transaction rows when a receipt is attached.
 * Pass hasReceipt derived from the vault/by-transactions batch call.
 */
export function ReceiptIndicator({ hasReceipt, className }: Props) {
  if (!hasReceipt) return null;
  return (
    <span
      title="Receipt attached"
      className={cn("inline-flex text-amber-500", className)}
    >
      <Receipt className="h-3.5 w-3.5" />
    </span>
  );
}
