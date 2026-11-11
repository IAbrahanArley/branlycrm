const maxCents = 1_000_000_000;
const currency = new Intl.NumberFormat("pt-BR", { style: "currency", currency: "BRL" });

export function parseSaleAmount(value: string) {
  const text = value.trim();
  const match = /^(\d+)(?:[,.](\d{1,2}))?$/.exec(text)
    ?? /^(\d{1,3}(?:\.\d{3})+),(\d{1,2})$/.exec(text);
  if (!match) return null;

  const reais = Number(match[1].replaceAll(".", ""));
  const centavos = Number((match[2] ?? "").padEnd(2, "0"));
  const amount = reais * 100 + centavos;
  return Number.isSafeInteger(amount) && amount > 0 && amount <= maxCents ? amount : null;
}

export function formatSaleAmount(cents: number) {
  return currency.format(cents / 100);
}
