"use client";

import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { Check, Lock, Pencil, Trash2, X } from "lucide-react";
import { Category } from "@/services/category-service";
import { Button } from "@/components/ui/button";

const renameSchema = z.object({ name: z.string().min(1, "Name is required").max(100) });
type RenameValues = z.infer<typeof renameSchema>;

export interface CategoryRowProps {
  category: Category;
  isEditing: boolean;
  isConfirmingDelete: boolean;
  onEditStart: () => void;
  onEditCancel: () => void;
  onRename: (name: string) => void;
  onDeleteRequest: () => void;
  onDeleteCancel: () => void;
  onDeleteConfirm: () => void;
  isRenamePending: boolean;
  isDeletePending: boolean;
  readonly?: boolean;
}

const inputCls =
  "rounded-lg border border-border bg-card px-2 py-1 text-xs text-foreground focus:outline-none focus:ring-2 focus:ring-primary/40 focus:border-primary/40 transition-colors";

export function CategoryRow({
  category,
  isEditing,
  isConfirmingDelete,
  onEditStart,
  onEditCancel,
  onRename,
  onDeleteRequest,
  onDeleteCancel,
  onDeleteConfirm,
  isRenamePending,
  isDeletePending,
  readonly = false,
}: CategoryRowProps) {
  const renameForm = useForm<RenameValues>({
    resolver: zodResolver(renameSchema),
    defaultValues: { name: category.name },
  });

  if (isEditing) {
    return (
      <div className="px-4 py-3">
        <form
          onSubmit={renameForm.handleSubmit((values) => onRename(values.name))}
          className="flex items-center gap-2"
        >
          <input {...renameForm.register("name")} autoFocus className={`flex-1 ${inputCls}`} />
          {renameForm.formState.errors.name && (
            <span className="text-xs text-rose-600 dark:text-rose-400">
              {renameForm.formState.errors.name.message}
            </span>
          )}
          <Button type="submit" size="sm" className="px-2.5" disabled={isRenamePending} aria-label="Save name">
            <Check className="h-3.5 w-3.5" />
          </Button>
          <Button
            type="button"
            variant="secondary"
            size="sm"
            className="px-2.5"
            aria-label="Cancel rename"
            onClick={() => {
              renameForm.reset();
              onEditCancel();
            }}
          >
            <X className="h-3.5 w-3.5" />
          </Button>
        </form>
      </div>
    );
  }

  if (isConfirmingDelete) {
    return (
      <div className="px-4 py-3">
        <div className="flex items-center justify-between gap-4">
          <p className="text-sm text-muted-foreground">
            Delete <span className="font-medium text-foreground">{category.name}</span>? Transactions,
            budgets, and recurring items will move to{" "}
            <span className="font-medium text-foreground">Uncategorized</span>.
          </p>
          <div className="flex shrink-0 gap-2">
            <Button variant="destructive" size="sm" onClick={onDeleteConfirm} disabled={isDeletePending}>
              {isDeletePending ? "Deleting..." : "Delete"}
            </Button>
            <Button variant="secondary" size="sm" onClick={onDeleteCancel}>
              Cancel
            </Button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="px-4 py-2.5">
      <div className="flex items-center justify-between gap-3">
        <span className="font-medium truncate text-foreground">{category.name}</span>

        <div className="flex shrink-0 items-center gap-2">
          {readonly ? (
            <Lock className="h-3.5 w-3.5 text-muted-foreground/40" aria-label="Read-only" />
          ) : (
            <div className="flex gap-1">
              <button
                onClick={() => {
                  renameForm.reset({ name: category.name });
                  onEditStart();
                }}
                className="rounded p-1.5 text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors"
                aria-label="Rename"
              >
                <Pencil className="h-3.5 w-3.5" />
              </button>
              <button
                onClick={onDeleteRequest}
                className="rounded p-1.5 text-muted-foreground hover:bg-rose-500/10 hover:text-rose-500 dark:hover:text-rose-400 transition-colors"
                aria-label="Delete"
              >
                <Trash2 className="h-3.5 w-3.5" />
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
