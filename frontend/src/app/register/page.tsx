"use client";

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useAuth } from "@/lib/auth-context";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { toast } from "sonner";
import { Eye, EyeOff } from "lucide-react";
import { AuthThemeToggle } from "@/components/auth-theme-toggle";
import { Button } from "@/components/ui/button";

const schema = z
  .object({
    firstName: z.string().min(1),
    lastName: z.string().min(1),
    email: z.string().email(),
    password: z.string().min(8, "At least 8 characters"),
    confirmPassword: z.string().min(1, "Confirm your password"),
  })
  .refine((values) => values.password === values.confirmPassword, {
    message: "Passwords do not match",
    path: ["confirmPassword"],
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

export default function RegisterPage() {
  const { register: registerUser } = useAuth();
  const router = useRouter();
  const [showPassword, setShowPassword] = useState(false);
  const [showConfirmPassword, setShowConfirmPassword] = useState(false);
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<FormValues>({ resolver: zodResolver(schema) });

  const onSubmit = async ({
    confirmPassword: _confirmPassword,
    ...payload
  }: FormValues) => {
    try {
      await registerUser(payload);
      router.push("/dashboard");
    } catch (err: unknown) {
      const status = (err as { response?: { status?: number } })?.response?.status;
      if (status === 409) toast.error("Email is already in use.");
      else if (status === 400)
        toast.error("Invalid registration details. Please check your input.");
      else if (status === 500) toast.error("Server error. Please try again later.");
      else toast.error("Registration failed. Please try again.");
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
              Create your account
            </h1>
            <p className="mt-2 text-sm leading-6 text-ivory/80">
              Start with a private-banking calm view of everyday money.
            </p>
          </div>
        </header>

        <div className="bg-card px-6 py-7 sm:px-8">
          <form onSubmit={handleSubmit(onSubmit)} className="space-y-5">
            <div className="grid gap-4 sm:grid-cols-2">
              <div>
                <label htmlFor="firstName" className={labelCls}>
                  First name
                </label>
                <input
                  id="firstName"
                  autoComplete="given-name"
                  {...register("firstName")}
                  className={inputCls}
                />
                {errors.firstName && (
                  <p className={errorCls}>{errors.firstName.message}</p>
                )}
              </div>
              <div>
                <label htmlFor="lastName" className={labelCls}>
                  Last name
                </label>
                <input
                  id="lastName"
                  autoComplete="family-name"
                  {...register("lastName")}
                  className={inputCls}
                />
                {errors.lastName && (
                  <p className={errorCls}>{errors.lastName.message}</p>
                )}
              </div>
            </div>
            <div>
              <label htmlFor="email" className={labelCls}>
                Email
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
                Password
              </label>
              <div className="relative">
                <input
                  id="password"
                  type={showPassword ? "text" : "password"}
                  autoComplete="new-password"
                  {...register("password")}
                  className={passwordInputCls}
                />
                <button
                  type="button"
                  aria-label={showPassword ? "Hide password" : "Show password"}
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
            <div>
              <label htmlFor="confirmPassword" className={labelCls}>
                Confirm password
              </label>
              <div className="relative">
                <input
                  id="confirmPassword"
                  type={showConfirmPassword ? "text" : "password"}
                  autoComplete="new-password"
                  {...register("confirmPassword")}
                  className={passwordInputCls}
                />
                <button
                  type="button"
                  aria-label={
                    showConfirmPassword
                      ? "Hide confirm password"
                      : "Show confirm password"
                  }
                  className={passwordToggleCls}
                  onClick={() => setShowConfirmPassword((current) => !current)}
                >
                  {showConfirmPassword ? (
                    <EyeOff aria-hidden="true" size={18} />
                  ) : (
                    <Eye aria-hidden="true" size={18} />
                  )}
                </button>
              </div>
              {errors.confirmPassword && (
                <p className={errorCls}>{errors.confirmPassword.message}</p>
              )}
            </div>
            <Button
              type="submit"
              size="lg"
              className="w-full rounded-full border-primary bg-primary text-primary-foreground hover:bg-primary/90"
              disabled={isSubmitting}
            >
              {isSubmitting ? "Creating account..." : "Create account"}
            </Button>
          </form>
          <p className="mt-6 text-center text-sm text-muted-foreground">
            Already have an account?{" "}
            <Link
              href="/login"
              className="font-medium text-primary transition-colors hover:text-primary/80 hover:underline"
            >
              Sign in
            </Link>
          </p>
        </div>
      </section>
    </main>
  );
}
