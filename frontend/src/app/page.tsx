import Link from "next/link";
import { ArrowRight, Landmark, ShieldCheck, WalletCards } from "lucide-react";

const details = [
  {
    title: "Every balance in view",
    copy: "Accounts, budgets, and recent movement stay in one calm ledger.",
    Icon: WalletCards,
  },
  {
    title: "Built for review",
    copy: "Numbers use tabular spacing so trends and outliers are easy to scan.",
    Icon: Landmark,
  },
  {
    title: "Private by design",
    copy: "A restrained dashboard for people who want exact money context.",
    Icon: ShieldCheck,
  },
];

export default function HomePage() {
  return (
    <main className="min-h-screen bg-background text-foreground">
      <section className="relative isolate overflow-hidden bg-reserve text-ivory">
        <div className="absolute inset-0 -z-10 bg-[radial-gradient(circle_at_24%_18%,oklch(var(--ivory-on-green)/0.12),transparent_28%),radial-gradient(circle_at_78%_10%,oklch(var(--gold)/0.14),transparent_24%),linear-gradient(135deg,oklch(var(--reserve)/1),oklch(var(--reserve-deep)/1))]" />
        <div className="mx-auto grid min-h-[88dvh] max-w-7xl items-center gap-12 px-6 py-12 sm:px-8 lg:grid-cols-[1fr_440px] lg:px-10">
          <div className="min-w-0 max-w-3xl pt-12 lg:pt-0">
            <Link
              href="/dashboard"
              className="mb-8 inline-flex items-center gap-2 rounded-full border border-ivory/20 bg-ivory/10 px-4 py-2 text-sm font-medium text-ivory transition-colors hover:bg-ivory/20 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-gold/70"
            >
              Fintrack
              <span className="h-1.5 w-1.5 rounded-full bg-gold" aria-hidden="true" />
            </Link>
            <h1 className="max-w-4xl text-balance font-display text-5xl font-medium leading-[0.96] tracking-normal text-ivory sm:text-6xl lg:text-7xl">
              Money, held with{" "}
              <span className="font-display italic text-gold">reserve</span>
              .
            </h1>
            <p className="mt-7 max-w-2xl text-pretty text-lg leading-8 text-ivory/80 sm:text-xl">
              Fintrack turns income, spending, budgets, and saved receipts into a
              quiet daily review. No noise, no casino shine, just exact context
              for decisions that deserve patience.
            </p>
            <div className="mt-10 flex flex-col gap-3 sm:flex-row">
              <Link
                href="/register"
                className="inline-flex w-full items-center justify-center gap-2 rounded-full bg-gold px-7 py-3.5 text-sm font-semibold text-reserve-deep shadow-[0_18px_45px_oklch(var(--gold)/0.18)] transition-colors hover:bg-gold-deep focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-gold focus-visible:ring-offset-2 focus-visible:ring-offset-reserve sm:w-auto"
              >
                Create your account
                <ArrowRight aria-hidden="true" size={18} />
              </Link>
              <Link
                href="/login"
                className="inline-flex w-full items-center justify-center rounded-full border border-ivory/20 px-7 py-3.5 text-sm font-semibold text-ivory transition-colors hover:bg-ivory/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-gold/70 sm:w-auto"
              >
                Sign in
              </Link>
            </div>
          </div>

          <div className="relative mx-auto w-full min-w-0 max-w-[calc(100vw-3rem)] pb-14 sm:max-w-[440px] lg:pb-0">
            <div className="rounded-[2rem] border border-ivory/20 bg-ivory/10 p-3 shadow-[0_28px_80px_oklch(var(--reserve-deep)/0.55)] backdrop-blur">
              <div className="rounded-[1.45rem] border border-ivory/20 bg-ivory p-5 text-reserve-deep shadow-card">
                <div className="flex items-start justify-between gap-6">
                  <div>
                    <p className="text-sm font-medium text-reserve/70">
                      Total balance
                    </p>
                    <p className="mt-3 font-mono text-4xl font-bold tabular-nums tracking-normal">
                      $84,726.40
                    </p>
                  </div>
                  <div className="rounded-full bg-reserve px-3 py-1.5 text-xs font-semibold text-ivory">
                    +4.8%
                  </div>
                </div>
                <div className="mt-8 space-y-4">
                  <div>
                    <div className="mb-2 flex items-center justify-between text-xs font-medium text-reserve/70">
                      <span>Monthly budget</span>
                      <span className="font-mono tabular-nums">$3,218 left</span>
                    </div>
                    <div className="h-2 rounded-full bg-reserve/10">
                      <div className="h-2 w-[64%] rounded-full bg-gold-deep" />
                    </div>
                  </div>
                  <div className="grid grid-cols-2 gap-3">
                    <div className="rounded-2xl bg-reserve/10 p-4">
                      <p className="text-xs font-medium text-reserve/70">
                        Income
                      </p>
                      <p className="mt-2 font-mono text-lg font-semibold tabular-nums">
                        +$9,420
                      </p>
                    </div>
                    <div className="rounded-2xl bg-reserve/10 p-4">
                      <p className="text-xs font-medium text-reserve/70">
                        Spent
                      </p>
                      <p className="mt-2 font-mono text-lg font-semibold tabular-nums">
                        -$4,106
                      </p>
                    </div>
                  </div>
                </div>
              </div>
            </div>
            <div className="absolute -bottom-1 left-4 w-[min(82%,320px)] -rotate-2 rounded-2xl border border-border bg-card p-4 text-card-foreground shadow-card sm:left-5 sm:w-[min(78%,320px)]">
              <p className="text-xs font-medium text-muted-foreground">
                Latest review
              </p>
              <p className="mt-2 text-sm font-semibold">
                Coffee subscriptions increased by{" "}
                <span className="font-mono tabular-nums">18%</span>.
              </p>
            </div>
          </div>
        </div>
      </section>

      <section className="border-t border-border bg-background px-6 py-12 sm:px-8 lg:px-10">
        <div className="mx-auto grid max-w-7xl gap-4 md:grid-cols-3">
          {details.map(({ title, copy, Icon }) => (
            <article
              key={title}
              className="rounded-lg border border-border bg-card p-5 shadow-card transition-all duration-200 hover:border-primary/30 hover:bg-hover-surface hover:shadow-card-hover"
            >
              <div className="mb-5 inline-flex h-10 w-10 items-center justify-center rounded-md bg-primary/10 text-primary">
                <Icon aria-hidden="true" size={20} strokeWidth={1.8} />
              </div>
              <h2 className="font-display text-xl font-medium tracking-normal text-foreground">
                {title}
              </h2>
              <p className="mt-2 text-sm leading-6 text-muted-foreground">
                {copy}
              </p>
            </article>
          ))}
        </div>
      </section>
    </main>
  );
}
