import { useMemo, useState } from "react";
import { exchangeRateService } from "@/services/exchange-rate-service";
import type { Account } from "@/services/account-service";

/**
 * Owns the manual "Auto Convert" button's state for a cross-currency transfer:
 * fetches a rate on demand and fills whichever amount is missing. Superseded
 * by an automatic debounced hook in a follow-up PR; kept isolated here so the
 * form component stays under the modularization line threshold.
 */
export function useManualConversion(params: {
  sourceAccount: Account | undefined;
  destAccount: Account | undefined;
  amount: string | undefined;
  destinationAmount: string | undefined;
  locale: string;
  crossCurrency: boolean;
  setAmount: (value: string) => void;
  setDestinationAmount: (value: string | undefined) => void;
}) {
  const { sourceAccount, destAccount, amount, destinationAmount, locale, crossCurrency, setAmount, setDestinationAmount } = params;
  const [isConverting, setIsConverting] = useState(false);
  const [convertError, setConvertError] = useState(false);
  const [lastRate, setLastRate] = useState<{ rate: number; asOf: string | null } | null>(null);

  const reset = () => {
    setDestinationAmount(undefined);
    setLastRate(null);
  };

  const handleAutoConvert = async () => {
    if (!sourceAccount || !destAccount) return;
    const sourceFilled = Boolean(amount && amount.trim() !== "" && !Number.isNaN(Number(amount)));
    const destFilled = Boolean(destinationAmount && destinationAmount.trim() !== "" && !Number.isNaN(Number(destinationAmount)));
    if (!sourceFilled && !destFilled) return;

    setIsConverting(true);
    setConvertError(false);
    try {
      if (destFilled && !sourceFilled) {
        const result = await exchangeRateService.convert(destAccount.currency, sourceAccount.currency, destinationAmount!);
        // setValue stores whatever type is passed in react-hook-form's internal state (used by
        // the zod resolver on submit) — the DOM input's own string coercion doesn't cover this,
        // so a raw number here fails validation with "Expected string, received number" on save.
        setAmount(String(result.convertedAmount));
        setLastRate({ rate: result.rate, asOf: result.asOf });
      } else {
        // source-only, or both filled — source side wins
        const result = await exchangeRateService.convert(sourceAccount.currency, destAccount.currency, amount!);
        setDestinationAmount(String(result.convertedAmount));
        setLastRate({ rate: result.rate, asOf: result.asOf });
      }
    } catch {
      setConvertError(true);
    } finally {
      setIsConverting(false);
    }
  };

  const conversionLine = useMemo(() => {
    if (!crossCurrency || !sourceAccount || !destAccount) return null;
    const amountNum = Number(amount);
    const destNum = Number(destinationAmount);
    if (!amount || !destinationAmount || Number.isNaN(amountNum) || Number.isNaN(destNum) || amountNum <= 0) return null;
    const rate = destNum / amountNum;
    return {
      amountText: amountNum.toLocaleString(locale, { maximumFractionDigits: 4 }),
      destText: destNum.toLocaleString(locale, { maximumFractionDigits: 4 }),
      rateText: rate.toLocaleString(locale, { maximumFractionDigits: 6 }),
      asOfText: lastRate?.asOf ? new Date(lastRate.asOf).toLocaleDateString(locale) : null,
    };
  }, [crossCurrency, sourceAccount, destAccount, amount, destinationAmount, locale, lastRate]);

  return { isConverting, convertError, conversionLine, handleAutoConvert, reset };
}
