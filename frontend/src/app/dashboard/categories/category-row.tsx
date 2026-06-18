"use client";

import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { Check, Lock, Pencil, Trash2, X } from "lucide-react";
import { Category } from "@/services/category-service";

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
  "rounded-lg border border-slate-800/60 bg-slate-900/60 px-2 py-1 text-xs text-slate-200 focus:outline-none focus:ring-2 focus:ring-emerald-500/40 focus:border-emerald-500/40 transition-colors";

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
            <span className="text-xs text-rose-400">
              {renameForm.formState.errors.name.message}
            </span>
          )}
          <button
            type="submit"
            disabled={isRenamePending}
            className="rounded-lg bg-emerald-500/10 border border-emerald-500/20 px-2.5 py-1.5 text-emerald-400 hover:bg-emerald-500/20 disabled:opacity-50 transition-colors"
          >
            <Check className="h-3.5 w-3.5" />
          </button>
          <button
            type="button"
            onClick={() => {
              renameForm.reset();
              onEditCancel();
            }}
            className="rounded-lg border border-slate-800/60 px-2.5 py-1.5 text-slate-400 hover:bg-slate-800/60 transition-colors"
          >
            <X className="h-3.5 w-3.5" />
          </button>
        </form>
      </div>
    );
  }

  if (isConfirmingDelete) {
    return (
      <div className="px-4 py-3">
        <div className="flex items-center justify-between gap-4">
          <p className="text-sm text-slate-400">
            Delete <span className="font-medium text-slate-100">{category.name}</span>? Transactions,
            budgets, and recurring items will move to{" "}
            <span className="font-medium text-slate-300">Uncategorized</span>.
          </p>
          <div className="flex shrink-0 gap-2">
            <button
              onClick={onDeleteConfirm}
              disabled={isDeletePending}
              className="rounded-lg bg-rose-500/10 border border-rose-500/20 px-3 py-1.5 text-xs text-rose-400 hover:bg-rose-500/20 disabled:opacity-50 transition-colors"
            >
              {isDeletePending ? "Deleting..." : "Delete"}
            </button>
            <button
              onClick={onDeleteCancel}
              className="rounded-lg border border-slate-800/60 px-3 py-1.5 text-xs text-slate-400 hover:bg-slate-800/60 transition-colors"
            >
              Cancel
            </button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="px-4 py-2.5">
      <div className="flex items-center justify-between gap-3">
        <span className="font-medium truncate text-slate-200">{category.name}</span>

        <div className="flex shrink-0 items-center gap-2">
          {readonly ? (
            <Lock className="h-3.5 w-3.5 text-slate-600" aria-label="Read-only" />
          ) : (
            <div className="flex gap-1">
              <button
                onClick={() => {
                  renameForm.reset({ name: category.name });
                  onEditStart();
                }}
                className="rounded p-1.5 text-slate-500 hover:bg-slate-800/60 hover:text-slate-200 transition-colors"
                aria-label="Rename"
              >
                <Pencil className="h-3.5 w-3.5" />
              </button>
              <button
                onClick={onDeleteRequest}
                className="rounded p-1.5 text-slate-500 hover:bg-rose-500/10 hover:text-rose-400 transition-colors"
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
