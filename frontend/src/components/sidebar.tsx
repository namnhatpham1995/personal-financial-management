"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect } from "react";
import { useAuth } from "@/lib/auth-context";
import { cn } from "@/lib/utils";
import {
  LayoutDashboard,
  Wallet,
  ArrowLeftRight,
  PieChart,
  RefreshCw,
  BarChart3,
  LogOut,
} from "lucide-react";

const navItems = [
  { href: "/dashboard", label: "Overview", icon: LayoutDashboard },
  { href: "/dashboard/accounts", label: "Accounts", icon: Wallet },
  { href: "/dashboard/transactions", label: "Transactions", icon: ArrowLeftRight },
  { href: "/dashboard/budgets", label: "Budgets", icon: PieChart },
  { href: "/dashboard/recurring", label: "Recurring", icon: RefreshCw },
  { href: "/dashboard/analytics", label: "Analytics", icon: BarChart3 },
];

interface SidebarProps {
  open?: boolean;
  onClose?: () => void;
}

export function Sidebar({ open = false, onClose }: SidebarProps) {
  const pathname = usePathname();
  const { user, logout } = useAuth();

  // Close drawer on route change
  useEffect(() => {
    onClose?.();
  }, [pathname]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <>
      {/* Desktop: static sidebar (always visible on md+) */}
      <aside className="hidden md:flex h-full w-56 flex-shrink-0 flex-col border-r border-border bg-card px-4 py-6">
        <SidebarContent pathname={pathname} user={user} logout={logout} onClose={onClose} />
      </aside>

      {/* Mobile: off-canvas drawer */}
      <aside
        className={cn(
          "fixed inset-y-0 left-0 z-50 flex w-64 flex-col border-r border-border bg-card px-4 py-6 transition-transform duration-300 md:hidden",
          open ? "translate-x-0" : "-translate-x-full"
        )}
      >
        <SidebarContent pathname={pathname} user={user} logout={logout} onClose={onClose} />
      </aside>
    </>
  );
}

function SidebarContent({
  pathname,
  user,
  logout,
  onClose,
}: {
  pathname: string;
  user: { email?: string } | null;
  logout: () => void;
  onClose?: () => void;
}) {
  return (
    <>
      <div className="mb-8">
        <span className="text-xl font-bold text-primary">Fintrack</span>
      </div>

      <nav className="flex-1 space-y-1">
        {navItems.map(({ href, label, icon: Icon }) => (
          <Link
            key={href}
            href={href}
            onClick={() => onClose?.()}
            className={cn(
              "flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors",
              pathname === href
                ? "bg-primary text-primary-foreground"
                : "text-muted-foreground hover:bg-accent hover:text-accent-foreground"
            )}
          >
            <Icon className="h-4 w-4" />
            {label}
          </Link>
        ))}
      </nav>

      <div className="border-t border-border pt-4">
        <p className="mb-2 truncate text-xs text-muted-foreground">{user?.email}</p>
        <button
          onClick={() => logout()}
          className="flex w-full items-center gap-2 rounded-md px-3 py-2 text-sm text-muted-foreground hover:bg-accent hover:text-accent-foreground"
        >
          <LogOut className="h-4 w-4" />
          Sign out
        </button>
      </div>
    </>
  );
}
