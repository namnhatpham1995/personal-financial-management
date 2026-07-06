"use client";

import { useState, useEffect, useCallback } from "react";
import { Menu } from "lucide-react";
import { AuthGuard } from "@/components/auth-guard";
import { Sidebar } from "@/components/sidebar";

export default function DashboardLayout({ children }: { children: React.ReactNode }) {
  const [drawerOpen, setDrawerOpen] = useState(false);

  const closeDrawer = useCallback(() => setDrawerOpen(false), []);

  useEffect(() => {
    if (!drawerOpen) return;

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") closeDrawer();
    };

    document.addEventListener("keydown", handleKeyDown);
    document.body.style.overflow = "hidden";

    return () => {
      document.removeEventListener("keydown", handleKeyDown);
      document.body.style.overflow = "";
    };
  }, [drawerOpen, closeDrawer]);

  return (
    <AuthGuard>
      <div className="flex h-screen overflow-hidden bg-background">
        <Sidebar open={drawerOpen} onClose={closeDrawer} />

        {drawerOpen && (
          <div
            className="fixed inset-0 z-40 bg-black/60 backdrop-blur-sm md:hidden"
            onClick={closeDrawer}
            aria-hidden="true"
          />
        )}

        <div className="flex flex-1 flex-col overflow-hidden">
          <header className="flex items-center gap-3 border-b border-border bg-card/95 px-4 py-3 md:hidden">
            <button
              onClick={() => setDrawerOpen(true)}
              aria-label="Open navigation"
              className="inline-flex min-h-11 min-w-11 items-center justify-center rounded-sm text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40"
            >
              <Menu className="h-5 w-5" />
            </button>
            <span className="text-lg font-semibold tracking-tight text-foreground">
              Fintrack
            </span>
          </header>

          <main className="flex-1 overflow-y-auto px-4 py-5 md:px-7 md:py-6">{children}</main>
        </div>
      </div>
    </AuthGuard>
  );
}
