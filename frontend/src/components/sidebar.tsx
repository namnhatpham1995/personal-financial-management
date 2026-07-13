"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect } from "react";
import { useTheme } from "next-themes";
import { useTranslations } from "next-intl";
import { useAuth } from "@/lib/auth-context";
import { cn } from "@/lib/utils";
import {
  LayoutDashboard,
  ArrowLeftRight,
  Tag,
  Activity,
  Archive,
  KeyRound,
  Settings,
  LogOut,
  Sun,
  Moon,
} from "lucide-react";

const primaryNavItems = [
  { href: "/dashboard", labelKey: "overview" as const, icon: LayoutDashboard },
  { href: "/dashboard/transactions", labelKey: "transactions" as const, icon: ArrowLeftRight },
  { href: "/dashboard/vault", labelKey: "vault" as const, icon: Archive },
];

const secondaryNavItems = [
  { href: "/dashboard/categories", labelKey: "categories" as const, icon: Tag },
  { href: "/dashboard/activity", labelKey: "activity" as const, icon: Activity },
  { href: "/dashboard/settings/api-tokens", labelKey: "apiTokens" as const, icon: KeyRound },
  { href: "/dashboard/settings", labelKey: "settings" as const, icon: Settings },
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
      <aside className="hidden h-full w-56 flex-shrink-0 flex-col border-r border-border bg-card/95 px-4 py-6 md:flex">
        <SidebarContent pathname={pathname} user={user} logout={logout} onClose={onClose} />
      </aside>

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
  const { theme, setTheme } = useTheme();
  const t = useTranslations("sidebar");

  return (
    <>
      <div className="mb-8 px-1">
        <span className="text-xl font-semibold tracking-tight text-foreground">
          Fintrack
        </span>
      </div>

      <nav className="flex-1 space-y-1">
        {primaryNavItems.map(({ href, labelKey, icon: Icon }) => (
          <NavLink key={href} href={href} label={t(`nav.${labelKey}`)} Icon={Icon} active={pathname === href} onClose={onClose} />
        ))}

        <div className="my-3 border-t border-border" />

        {secondaryNavItems.map(({ href, labelKey, icon: Icon }) => (
          <NavLink key={href} href={href} label={t(`nav.${labelKey}`)} Icon={Icon} active={pathname === href} onClose={onClose} />
        ))}
      </nav>

      <div className="border-t border-border pt-4 space-y-1">
        <p className="mb-2 truncate text-xs text-muted-foreground">{user?.email}</p>

        <button
          onClick={() => setTheme(theme === "dark" ? "light" : "dark")}
          className="flex w-full items-center gap-2 rounded-sm px-3 py-2 text-sm text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40"
        >
          {theme === "dark" ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
          {theme === "dark" ? t("lightMode") : t("darkMode")}
        </button>

        <button
          onClick={() => logout()}
          className="flex w-full items-center gap-2 rounded-sm px-3 py-2 text-sm text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40"
        >
          <LogOut className="h-4 w-4" />
          {t("signOut")}
        </button>
      </div>
    </>
  );
}

function NavLink({
  href,
  label,
  Icon,
  active,
  onClose,
}: {
  href: string;
  label: string;
  Icon: React.ComponentType<{ className?: string }>;
  active: boolean;
  onClose?: () => void;
}) {
  return (
    <Link
      href={href}
      onClick={() => onClose?.()}
      className={cn(
        "flex items-center gap-3 rounded-sm px-3 py-2 text-sm font-medium transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40",
        active
          ? "border border-primary/20 bg-primary/10 text-primary"
          : "text-muted-foreground hover:bg-secondary hover:text-foreground"
      )}
    >
      <Icon className="h-4 w-4 flex-shrink-0" />
      {label}
    </Link>
  );
}
