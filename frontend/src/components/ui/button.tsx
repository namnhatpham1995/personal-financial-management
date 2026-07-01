import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "@/lib/utils";

/**
 * Shared button vocabulary for the app.
 *
 * `primary` carries the Register-Green accent (the `primary` token) for the
 * one "go" action per surface — never a raw semantic color like emerald, which
 * is reserved for transaction direction. Every variant ships a visible
 * focus-visible ring so keyboard users always see where they are.
 */
const buttonVariants = cva(
  "inline-flex items-center justify-center gap-2 rounded-lg font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40 disabled:pointer-events-none disabled:opacity-50",
  {
    variants: {
      variant: {
        primary:
          "border border-primary/20 bg-primary/10 text-primary hover:bg-primary/20",
        secondary:
          "border border-border text-muted-foreground hover:bg-secondary hover:text-foreground",
        ghost:
          "text-muted-foreground hover:bg-secondary hover:text-foreground",
        destructive:
          "border border-destructive/20 bg-destructive/10 text-destructive hover:bg-destructive/20",
      },
      size: {
        sm: "px-3 py-1.5 text-xs",
        md: "px-4 py-2 text-sm",
        lg: "px-4 py-2.5 text-sm",
        icon: "p-1.5",
      },
    },
    defaultVariants: { variant: "primary", size: "md" },
  }
);

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {}

export function Button({ className, variant, size, ...props }: ButtonProps) {
  return <button className={cn(buttonVariants({ variant, size }), className)} {...props} />;
}

export { buttonVariants };
