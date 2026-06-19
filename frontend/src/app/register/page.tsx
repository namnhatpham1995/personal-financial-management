"use client";

import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useAuth } from "@/lib/auth-context";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { toast } from "sonner";

const schema = z.object({
  firstName: z.string().min(1),
  lastName: z.string().min(1),
  email: z.string().email(),
  password: z.string().min(8, "At least 8 characters"),
});

type FormValues = z.infer<typeof schema>;

const inputCls =
  "w-full rounded-lg border border-border bg-card px-3 py-2 text-base text-foreground placeholder:text-muted-foreground/50 focus:outline-none focus:ring-2 focus:ring-primary/40 focus:border-primary/40 transition-colors";

export default function RegisterPage() {
  const { register: registerUser } = useAuth();
  const router = useRouter();
  const { register, handleSubmit, formState: { errors, isSubmitting } } =
    useForm<FormValues>({ resolver: zodResolver(schema) });

  const onSubmit = async (values: FormValues) => {
    try {
      await registerUser(values);
      router.push("/dashboard");
    } catch (err: unknown) {
      const status = (err as { response?: { status?: number } })?.response?.status;
      if (status === 409) toast.error("Email is already in use.");
      else if (status === 400) toast.error("Invalid registration details. Please check your input.");
      else if (status === 500) toast.error("Server error. Please try again later.");
      else toast.error("Registration failed. Please try again.");
    }
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-background">
      <div className="w-full max-w-md rounded-xl border border-border bg-card p-8 shadow-card">
        <div className="mb-8">
          <span className="text-xl font-bold tracking-tight text-emerald-600 dark:text-emerald-400">
            Fintrack
          </span>
          <h1 className="mt-3 text-2xl font-bold tracking-tight text-foreground">Create your account</h1>
          <p className="mt-1 text-sm text-muted-foreground">Start tracking your finances today.</p>
        </div>
        <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="mb-1.5 block text-xs font-medium uppercase tracking-wide text-muted-foreground">First name</label>
              <input {...register("firstName")} className={inputCls} />
              {errors.firstName && <p className="mt-1 text-xs text-rose-600 dark:text-rose-400">{errors.firstName.message}</p>}
            </div>
            <div>
              <label className="mb-1.5 block text-xs font-medium uppercase tracking-wide text-muted-foreground">Last name</label>
              <input {...register("lastName")} className={inputCls} />
              {errors.lastName && <p className="mt-1 text-xs text-rose-600 dark:text-rose-400">{errors.lastName.message}</p>}
            </div>
          </div>
          <div>
            <label className="mb-1.5 block text-xs font-medium uppercase tracking-wide text-muted-foreground">Email</label>
            <input type="email" {...register("email")} className={inputCls} />
            {errors.email && <p className="mt-1 text-xs text-rose-600 dark:text-rose-400">{errors.email.message}</p>}
          </div>
          <div>
            <label className="mb-1.5 block text-xs font-medium uppercase tracking-wide text-muted-foreground">Password</label>
            <input type="password" {...register("password")} className={inputCls} />
            {errors.password && <p className="mt-1 text-xs text-rose-600 dark:text-rose-400">{errors.password.message}</p>}
          </div>
          <button
            type="submit"
            disabled={isSubmitting}
            className="w-full rounded-lg bg-emerald-500/10 border border-emerald-500/20 px-4 py-2.5 text-sm font-medium text-emerald-600 dark:text-emerald-400 hover:bg-emerald-500/20 disabled:opacity-50 transition-colors"
          >
            {isSubmitting ? "Creating account…" : "Create account"}
          </button>
        </form>
        <p className="mt-6 text-center text-sm text-muted-foreground">
          Already have an account?{" "}
          <Link href="/login" className="font-medium text-emerald-600 dark:text-emerald-400 hover:underline transition-colors">
            Sign in
          </Link>
        </p>
      </div>
    </div>
  );
}
