"use client";

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useTranslations } from "next-intl";
import { useAuth } from "@/lib/auth-context";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { toast } from "sonner";
import { Eye, EyeOff } from "lucide-react";
import { AuthThemeToggle } from "@/components/auth-theme-toggle";
import { Button } from "@/components/ui/button";
import { classifyAuthError } from "@/lib/auth-error";

const schema = z.object({
  email: z.string().email(),
  password: z.string().min(1),
});

type FormValues = z.infer<typeof schema>;

const inputCls =
  "w-full rounded-md border border-input bg-card px-3.5 py-2.5 text-base text-foreground placeholder:text-muted-foreground/80 transition-colors focus:border-primary/40 focus:outline-none focus:ring-2 focus:ring-primary/40";
const passwordInputCls = `${inputCls} pr-12`;
const passwordToggleCls =
  "absolute right-1 top-1/2 inline-flex h-11 w-11 -translate-y-1/2 items-center justify-center rounded-sm text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus:outline-none focus:ring-2 focus:ring-primary/40";
const labelCls =
  "mb-1.5 block text-xs font-medium uppercase tracking-wide text-muted-foreground";
const errorCls =
  "mt-1.5 text-xs font-medium text-destructive dark:text-destructive-foreground";

export default function LoginPage() {
  const { login } = useAuth();
  const router = useRouter();
  const t = useTranslations("auth");
  const [showPassword, setShowPassword] = useState(false);
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<FormValues>({ resolver: zodResolver(schema) });

  const onSubmit = async (values: FormValues) => {
    try {
      await login(values.email, values.password);
      router.push("/dashboard");
    } catch (error: unknown) {
      toast.error(t(`errors.${classifyAuthError(error, "login").kind}`));
    }
  };

  return (
    <main className="flex min-h-screen items-center justify-center bg-background px-4 py-10 text-foreground">
      <section className="w-full max-w-md overflow-hidden rounded-lg border border-border bg-card shadow-card">
        <header className="bg-reserve px-6 py-7 text-ivory sm:px-8">
          <div className="flex items-start justify-between gap-5">
            <Link
              href="/"
              className="inline-flex items-center gap-2 rounded-full border border-ivory/20 bg-ivory/10 px-3.5 py-1.5 text-sm font-semibold text-ivory transition-colors hover:bg-ivory/20 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-gold/70"
            >
              Fintrack
              <span className="h-1.5 w-1.5 rounded-full bg-gold" aria-hidden="true" />
            </Link>
            <AuthThemeToggle className="border-ivory/20 bg-ivory/10 text-ivory shadow-none hover:bg-ivory/20 hover:text-ivory focus:ring-gold/70" />
          </div>
          <div className="mt-9 border-t border-gold/40 pt-6">
            <h1 className="font-display text-3xl font-medium tracking-normal text-ivory">
              {t("signIn.title")}
            </h1>
            <p className="mt-2 text-sm leading-6 text-ivory/80">
              {t("signIn.subtitle")}
            </p>
          </div>
        </header>

        <div className="bg-card px-6 py-7 sm:px-8">
          <form onSubmit={handleSubmit(onSubmit)} className="space-y-5">
            <div>
              <label htmlFor="email" className={labelCls}>
                {t("fields.email")}
              </label>
              <input
                id="email"
                type="email"
                autoComplete="email"
                {...register("email")}
                className={inputCls}
              />
              {errors.email && <p className={errorCls}>{errors.email.message}</p>}
            </div>
            <div>
              <label htmlFor="password" className={labelCls}>
                {t("fields.password")}
              </label>
              <div className="relative">
                <input
                  id="password"
                  type={showPassword ? "text" : "password"}
                  autoComplete="current-password"
                  {...register("password")}
                  className={passwordInputCls}
                />
                <button
                  type="button"
                  aria-label={showPassword ? t("password.hide") : t("password.show")}
                  className={passwordToggleCls}
                  onClick={() => setShowPassword((current) => !current)}
                >
                  {showPassword ? (
                    <EyeOff aria-hidden="true" size={18} />
                  ) : (
                    <Eye aria-hidden="true" size={18} />
                  )}
                </button>
              </div>
              {errors.password && (
                <p className={errorCls}>{errors.password.message}</p>
              )}
            </div>
            <Button
              type="submit"
              size="lg"
              className="w-full rounded-full border-primary bg-primary text-primary-foreground hover:bg-primary/90"
              disabled={isSubmitting}
            >
              {isSubmitting ? t("signIn.submitting") : t("signIn.submit")}
            </Button>
          </form>
          <p className="mt-6 text-center text-sm text-muted-foreground">
            {t("signIn.noAccount")}{" "}
            <Link
              href="/register"
              className="font-medium text-primary transition-colors hover:text-primary/80 hover:underline"
            >
              {t("signIn.registerLink")}
            </Link>
          </p>
        </div>
      </section>
    </main>
  );
}
