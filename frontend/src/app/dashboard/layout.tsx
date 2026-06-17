"use client";

import { useState, useEffect, useCallback } from "react";
import { Menu } from "lucide-react";
import { AuthGuard } from "@/components/auth-guard";
import { Sidebar } from "@/components/sidebar";

export default function DashboardLayout({ children }: { children: React.ReactNode }) {
  const [drawerOpen, setDrawerOpen] = useState(false);

  const closeDrawer = useCallback(() => setDrawerOpen(false), []);

  // Escape key closes drawer; body scroll locked while open
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
      <div className="flex h-screen overflow-hidden">
        <Sidebar open={drawerOpen} onClose={closeDrawer} />

        {/* Mobile backdrop */}
        {drawerOpen && (
          <div
            className="fixed inset-0 z-40 bg-black/50 md:hidden"
            onClick={closeDrawer}
            aria-hidden="true"
          />
        )}

        <div className="flex flex-1 flex-col overflow-hidden">
          {/* Mobile top bar */}
          <header className="flex items-center gap-3 border-b border-border bg-card px-4 py-3 md:hidden">
            <button
              onClick={() => setDrawerOpen(true)}
              aria-label="Open navigation"
              className="rounded-md p-1 text-muted-foreground hover:bg-accent hover:text-accent-foreground"
            >
              <Menu className="h-5 w-5" />
            </button>
            <span className="text-lg font-bold text-primary">Fintrack</span>
          </header>

          <main className="flex-1 overflow-y-auto bg-muted/40 p-4 md:p-6">{children}</main>
        </div>
      </div>
    </AuthGuard>
  );
}
