"use client";

import React, { createContext, useContext, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { apiClient, clearTokens, setTokens } from "./api-client";
import { getLocaleCookie, setLocaleCookie } from "./locale-preference";
import { isSupportedLocale } from "@/i18n/config";

interface AuthUser {
  id: number;
  email: string;
  firstName: string;
  lastName: string;
  preferredLanguage: string | null;
}

interface AuthContextValue {
  user: AuthUser | null;
  isLoading: boolean;
  login: (email: string, password: string) => Promise<void>;
  register: (payload: RegisterPayload) => Promise<void>;
  logout: () => Promise<void>;
}

interface RegisterPayload {
  firstName: string;
  lastName: string;
  email: string;
  password: string;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const router = useRouter();

  // A non-null server-side preference that differs from the local cookie wins —
  // it means the user set their language on another device.
  const reconcileLocale = (preferredLanguage: string | null) => {
    if (!isSupportedLocale(preferredLanguage)) return;
    if (getLocaleCookie() === preferredLanguage) return;
    setLocaleCookie(preferredLanguage);
    router.refresh();
  };

  // Restore session from stored tokens on mount
  useEffect(() => {
    const token = typeof window !== "undefined" ? localStorage.getItem("accessToken") : null;
    if (!token) {
      setIsLoading(false);
      return;
    }
    apiClient
      .get<AuthUser>("/auth/me")
      .then((r) => {
        setUser(r.data);
        reconcileLocale(r.data.preferredLanguage);
      })
      .catch(() => clearTokens())
      .finally(() => setIsLoading(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const login = async (email: string, password: string) => {
    const { data } = await apiClient.post<{
      accessToken: string;
      refreshToken: string;
      user: AuthUser;
    }>("/auth/login", { email, password });
    setTokens(data.accessToken, data.refreshToken);
    setUser(data.user);
    reconcileLocale(data.user.preferredLanguage);
  };

  const register = async (payload: RegisterPayload) => {
    const { data } = await apiClient.post<{
      accessToken: string;
      refreshToken: string;
      user: AuthUser;
    }>("/auth/register", payload);
    setTokens(data.accessToken, data.refreshToken);
    setUser(data.user);
    reconcileLocale(data.user.preferredLanguage);
  };

  const logout = async () => {
    const refreshToken = localStorage.getItem("refreshToken");
    try {
      await apiClient.post("/auth/logout", { refreshToken });
    } finally {
      clearTokens();
      setUser(null);
    }
  };

  return (
    <AuthContext.Provider value={{ user, isLoading, login, register, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used inside AuthProvider");
  return ctx;
}
