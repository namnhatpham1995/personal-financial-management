"use client";

import { useEffect, useState } from "react";
import { Moon, Sun } from "lucide-react";
import { useTheme } from "next-themes";
import { cn } from "@/lib/utils";

interface AuthThemeToggleProps {
  className?: string;
}

export function AuthThemeToggle({ className }: AuthThemeToggleProps) {
  const [mounted, setMounted] = useState(false);
  const [documentTheme, setDocumentTheme] = useState<"dark" | "light">("light");
  const { theme, resolvedTheme, setTheme } = useTheme();

  useEffect(() => {
    setMounted(true);
  }, []);

  useEffect(() => {
    if (!mounted) return;
    const syncDocumentTheme = () => {
      setDocumentTheme(document.documentElement.classList.contains("dark") ? "dark" : "light");
    };
    const observer = new MutationObserver(syncDocumentTheme);

    syncDocumentTheme();
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ["class"] });

    return () => observer.disconnect();
  }, [mounted, resolvedTheme, theme]);

  const activeTheme = mounted ? documentTheme : resolvedTheme ?? (theme === "system" ? documentTheme : theme) ?? documentTheme;
  const isDark = activeTheme === "dark";
  const label = isDark ? "Switch to light theme" : "Switch to dark theme";

  return (
    <button
      type="button"
      aria-label={label}
      onClick={() => setTheme(isDark ? "light" : "dark")}
      disabled={!mounted}
      className={cn(
        "inline-flex h-9 w-9 items-center justify-center rounded-lg border border-border bg-surface-raised text-muted-foreground shadow-sm transition-colors",
        "hover:bg-hover-surface hover:text-foreground focus:outline-none focus:ring-2 focus:ring-primary/40 disabled:pointer-events-none disabled:opacity-70",
        className
      )}
    >
      {mounted && isDark ? <Sun aria-hidden="true" className="h-4 w-4" /> : <Moon aria-hidden="true" className="h-4 w-4" />}
    </button>
  );
}
