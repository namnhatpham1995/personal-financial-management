/**
 * Tests for MoneyText component formatting (task 4.3).
 * Covers: positive, negative (sign reversal via Math.abs), zero, large amounts,
 * and transaction-type color/sign decoration.
 */
import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { MoneyText } from "@/components/ui/money-text";

describe("MoneyText", () => {
  it("renders a positive amount with 2 decimal places", () => {
    render(<MoneyText amount={42.5} />);
    expect(screen.getByText(/42\.50/)).toBeInTheDocument();
  });

  it("renders zero as 0.00", () => {
    render(<MoneyText amount={0} />);
    expect(screen.getByText(/0\.00/)).toBeInTheDocument();
  });

  it("renders the absolute value (MoneyText never shows a negative sign)", () => {
    render(<MoneyText amount={-99.99} />);
    // Math.abs is applied internally — the rendered text must not start with -
    const el = screen.getByText(/99\.99/);
    expect(el.textContent).not.toMatch(/^-/);
  });

  it("renders a large amount without losing precision", () => {
    render(<MoneyText amount={1000000.0} />);
    expect(screen.getByText(/1[,.]?000[,.]?000\.00/)).toBeInTheDocument();
  });

  it("INCOME type prepends + sign", () => {
    render(<MoneyText amount={100} type="INCOME" />);
    expect(screen.getByText(/\+/)).toBeInTheDocument();
  });

  it("EXPENSE type prepends − sign", () => {
    render(<MoneyText amount={50} type="EXPENSE" />);
    expect(screen.getByText(/−/)).toBeInTheDocument();
  });

  it("TRANSFER type shows no sign prefix", () => {
    render(<MoneyText amount={200} type="TRANSFER" />);
    const el = screen.getByText(/200/);
    expect(el.textContent).not.toMatch(/[+−]/);
  });

  it("applies INCOME green color class", () => {
    render(<MoneyText amount={10} type="INCOME" />);
    const el = screen.getByText(/10/);
    expect(el.className).toMatch(/emerald/);
  });

  it("applies EXPENSE red color class", () => {
    render(<MoneyText amount={10} type="EXPENSE" />);
    const el = screen.getByText(/10/);
    expect(el.className).toMatch(/rose/);
  });
});
