import { apiClient } from "@/lib/api-client";

export interface ConvertResult {
  from: string;
  to: string;
  /** BigDecimal fields serialize as JSON numbers, not strings. */
  amount: number;
  convertedAmount: number;
  rate: number;
  asOf: string | null;
}

export const exchangeRateService = {
  convert: (from: string, to: string, amount: string) =>
    apiClient
      .get<ConvertResult>("/exchange-rates/convert", { params: { from, to, amount } })
      .then((r) => r.data),
};
