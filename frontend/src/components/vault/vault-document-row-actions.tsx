"use client";

import { useState } from "react";
import * as DropdownMenu from "@radix-ui/react-dropdown-menu";
import { useTranslations } from "next-intl";
import { MoreVertical, MoveRight, Trash2 } from "lucide-react";
import type { Account } from "@/services/account-service";
import type { VaultDocument } from "@/services/vault-service";
import { VaultReassignmentDialog } from "@/components/vault/vault-reassignment-dialog";

interface Props {
  document: VaultDocument;
  accounts: Account[];
  onReassigned: () => void;
  onDelete: () => void;
}

/**
 * Overflow menu carrying the reassignment and delete actions for one document row.
 * The reassignment dialog is rendered as a sibling of the menu (controlled open state) rather
 * than nested inside a DropdownMenu.Item — nesting a full Dialog trigger inside a menu item is
 * fragile with Radix (the menu's own click/focus handling can swallow the trigger's click).
 */
export function VaultDocumentRowActions({ document, accounts, onReassigned, onDelete }: Props) {
  const t = useTranslations("vault");
  const [reassignOpen, setReassignOpen] = useState(false);
  const fileName = document.originalFilename ?? t("downloadFallback");

  return (
    <>
      <DropdownMenu.Root>
        <DropdownMenu.Trigger asChild>
          <button
            type="button"
            aria-label={t("workspace.rowMenuAria", { fileName })}
            className="inline-flex min-h-9 min-w-9 shrink-0 items-center justify-center rounded-full text-muted-foreground transition-colors hover:bg-hover-surface hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40"
          >
            <MoreVertical className="h-4 w-4" />
          </button>
        </DropdownMenu.Trigger>
        <DropdownMenu.Portal>
          <DropdownMenu.Content
            align="end"
            className="z-50 min-w-[10rem] rounded-lg border border-border bg-card p-1 shadow-lg"
          >
            <DropdownMenu.Item
              onSelect={() => setReassignOpen(true)}
              className="flex cursor-pointer items-center gap-2 rounded-md px-2.5 py-2 text-sm text-foreground outline-none transition-colors hover:bg-hover-surface focus-visible:bg-hover-surface"
            >
              <MoveRight className="h-3.5 w-3.5" />
              {t("reassignment.open")}
            </DropdownMenu.Item>
            <DropdownMenu.Item
              onSelect={onDelete}
              className="flex cursor-pointer items-center gap-2 rounded-md px-2.5 py-2 text-sm text-destructive outline-none transition-colors hover:bg-destructive/10 focus-visible:bg-destructive/10"
            >
              <Trash2 className="h-3.5 w-3.5" />
              {t("delete")}
            </DropdownMenu.Item>
          </DropdownMenu.Content>
        </DropdownMenu.Portal>
      </DropdownMenu.Root>

      <VaultReassignmentDialog
        document={document}
        accounts={accounts}
        onSuccess={onReassigned}
        hideTrigger
        open={reassignOpen}
        onOpenChange={setReassignOpen}
      />
    </>
  );
}
