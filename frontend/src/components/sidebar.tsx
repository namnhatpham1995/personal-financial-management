"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect } from "react";
import { useTheme } from "next-themes";
import { useAuth } from "@/lib/auth-context";
import { cn } from "@/lib/utils";
import {
  LayoutDashboard,
  Wallet,
  ArrowLeftRight,
  Tag,
  LogOut,
  Sun,
  Moon,
} from "lucide-react";

const navItems = [
  { href: "/dashboard", label: "Overview", icon: LayoutDashboard },
  { href: "/dashboard/accounts", label: "Accounts", icon: Wallet },
  { href: "/dashboard/transactions", label: "Transactions", icon: ArrowLeftRight },
  { href: "/dashboard/categories", label: "Categories", icon: Tag },
];

interface SidebarProps {
  open?: boolean;
  onClose?: () => void;
}

export function Sidebar({ open = false, onClose }: SidebarProps) {
  const pathname = usePathname();
  const { user, logout } = useAuth();

  useEffect(() => {
    onClose?.();
  }, [pathname]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <>
      <aside className="hidden md:flex h-full w-56 flex-shrink-0 flex-col border-r border-slate-800/60 bg-slate-900/80 px-4 py-6">
        <SidebarContent pathname={pathname} user={user} logout={logout} onClose={onClose} />
      </aside>

      <aside
        className={cn(
          "fixed inset-y-0 left-0 z-50 flex w-64 flex-col border-r border-slate-800/60 bg-slate-900/95 backdrop-blur-sm px-4 py-6 transition-transform duration-300 md:hidden",
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
  const { theme, setTheme } = useTheme();

  return (
    <>
      <div className="mb-8">
        <span className="text-xl font-bold tracking-tight text-emerald-400">Fintrack</span>
      </div>

      <nav className="flex-1 space-y-1">
        {navItems.map(({ href, label, icon: Icon }) => (
          <Link
            key={href}
            href={href}
            onClick={() => onClose?.()}
            className={cn(
              "flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-all duration-150",
              pathname === href
                ? "bg-emerald-500/10 text-emerald-400 border border-emerald-500/20"
                : "text-slate-400 hover:bg-slate-800/60 hover:text-slate-100"
            )}
          >
            <Icon className="h-4 w-4 flex-shrink-0" />
            {label}
          </Link>
        ))}
      </nav>

      <div className="border-t border-slate-800/60 pt-4 space-y-1">
        <p className="mb-2 truncate text-xs text-slate-500">{user?.email}</p>

        {/* Theme toggle */}
        <button
          onClick={() => setTheme(theme === "dark" ? "light" : "dark")}
          className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-sm text-slate-400 hover:bg-slate-800/60 hover:text-slate-100 transition-colors"
        >
          {theme === "dark" ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
          {theme === "dark" ? "Light mode" : "Dark mode"}
        </button>

        <button
          onClick={() => logout()}
          className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-sm text-slate-400 hover:bg-slate-800/60 hover:text-slate-100 transition-colors"
        >
          <LogOut className="h-4 w-4" />
          Sign out
        </button>
      </div>
    </>
  );
}
