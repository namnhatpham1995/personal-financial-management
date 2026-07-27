import { useEffect, useMemo, useRef, useState } from "react";
import { exchangeRateService } from "@/services/exchange-rate-service";
import { formatCurrency, formatDate, formatRate } from "@/lib/utils";
import type { Account } from "@/services/account-service";

const DEBOUNCE_MS = 500;

interface FetchedConversion {
  fromCurrency: string;
  toCurrency: string;
  sourceAmount: number;
  convertedAmount: string;
  value: number;
  asOf: string | null;
}

export interface ConversionLine {
  mode: "fetched" | "custom";
  amountText: string;
  destText: string;
  rateText: string;
  asOfText: string | null;
  fetchedRateText: string | null;
  fetchedAsOfText: string | null;
  fetchedDestinationText: string | null;
}

function minorUnitDigits(currency: string): number {
  try {
    return new Intl.NumberFormat("en-US", { style: "currency", currency }).resolvedOptions().maximumFractionDigits ?? 2;
  } catch {
    return 2;
  }
}

function roundToMinorUnits(value: number, currency: string): string {
  return value.toFixed(minorUnitDigits(currency));
}

/**
 * Automatically converts the source amount into the destination currency for a
 * cross-currency transfer, debounced, with a manual-override escape hatch: any
 * value the user types into the destination field stops further auto-fill until
 * they explicitly restore the suggested amount. Supersedes the manual
 * "Auto Convert" button from the previous iteration of this form.
 */
export function useAutoConversion(params: {
  sourceAccount: Account | undefined;
  destAccount: Account | undefined;
  amount: string | undefined;
  destinationAmount: string | undefined;
  crossCurrency: boolean;
  initialOverridden: boolean;
  locale: string;
  setDestinationAmount: (value: string) => void;
}) {
  const { sourceAccount, destAccount, amount, destinationAmount, crossCurrency, initialOverridden, locale, setDestinationAmount } = params;
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState(false);
  const [overridden, setOverridden] = useState(initialOverridden);
  const [fetchedConversion, setFetchedConversion] = useState<FetchedConversion | null>(null);
  const requestSeq = useRef(0);

  const markOverridden = () => setOverridden(true);
  const revert = () => {
    setError(false);
    setFetchedConversion(null);
    setOverridden(false);
  };

  useEffect(() => {
    if (!crossCurrency) {
      setOverridden(initialOverridden);
      setError(false);
      setFetchedConversion(null);
      return;
    }
    if (overridden || !sourceAccount || !destAccount) return;
    setFetchedConversion(null);
    const amountNum = Number(amount);
    if (!amount || Number.isNaN(amountNum) || amountNum <= 0) return;

    const seq = ++requestSeq.current;
    const timer = setTimeout(async () => {
      setIsLoading(true);
      setError(false);
      try {
        const result = await exchangeRateService.convert(sourceAccount.currency, destAccount.currency, amount);
        if (seq !== requestSeq.current) return; // a newer request superseded this one
        const convertedAmount = roundToMinorUnits(result.convertedAmount, destAccount.currency);
        setDestinationAmount(convertedAmount);
        setFetchedConversion({
          fromCurrency: sourceAccount.currency,
          toCurrency: destAccount.currency,
          sourceAmount: amountNum,
          convertedAmount,
          value: result.rate,
          asOf: result.asOf,
        });
      } catch {
        if (seq === requestSeq.current) {
          setError(true);
          setOverridden(true);
        }
      } finally {
        if (seq === requestSeq.current) setIsLoading(false);
      }
    }, DEBOUNCE_MS);

    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [crossCurrency, overridden, sourceAccount?.id, destAccount?.id, amount]);

  const conversionLine = useMemo<ConversionLine | null>(() => {
    if (!crossCurrency || !sourceAccount || !destAccount) return null;
    const amountNum = Number(amount);
    const destNum = Number(destinationAmount);
    if (!amount || !destinationAmount || Number.isNaN(amountNum) || Number.isNaN(destNum) || amountNum <= 0) return null;

    const effectiveRate = destNum / amountNum;
    const matchingFetchedConversion =
      fetchedConversion?.fromCurrency === sourceAccount.currency &&
      fetchedConversion.toCurrency === destAccount.currency
        ? fetchedConversion
        : null;
    const fetchedRateText = matchingFetchedConversion
      ? formatRate(sourceAccount.currency, destAccount.currency, matchingFetchedConversion.value, locale)
      : null;
    const fetchedAsOfText = matchingFetchedConversion?.asOf ? formatDate(matchingFetchedConversion.asOf, locale) : null;
    const isFetchedAmount = !overridden && !!matchingFetchedConversion;

    return {
      mode: isFetchedAmount ? "fetched" : "custom",
      amountText: formatCurrency(amountNum, sourceAccount.currency, locale),
      destText: formatCurrency(destNum, destAccount.currency, locale),
      rateText: formatRate(
        sourceAccount.currency,
        destAccount.currency,
        isFetchedAmount ? matchingFetchedConversion.value : effectiveRate,
        locale
      ),
      asOfText: isFetchedAmount ? fetchedAsOfText : null,
      fetchedRateText: overridden ? fetchedRateText : null,
      fetchedAsOfText: overridden ? fetchedAsOfText : null,
      fetchedDestinationText:
        overridden && matchingFetchedConversion && matchingFetchedConversion.sourceAmount === amountNum
          ? formatCurrency(Number(matchingFetchedConversion.convertedAmount), destAccount.currency, locale)
          : null,
    };
  }, [crossCurrency, sourceAccount, destAccount, amount, destinationAmount, fetchedConversion, locale, overridden]);

  return { isLoading, error, overridden, markOverridden, revert, conversionLine };
}
