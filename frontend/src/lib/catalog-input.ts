const displayMoney = new Intl.NumberFormat("pt-BR", { style: "currency", currency: "BRL" });

export function parseCatalogPrice(value: string) {
  const text = value.trim();
  const match = /^(\d+)(?:[,.](\d{1,2}))?$/.exec(text)
    ?? /^(\d{1,3}(?:\.\d{3})+),(\d{1,2})$/.exec(text);
  if (!match) return null;
  const cents = Number(match[1].replaceAll(".", "")) * 100 + Number((match[2] ?? "").padEnd(2, "0"));
  return Number.isSafeInteger(cents) && cents <= 1_000_000_000 ? cents : null;
}

export function formatCatalogPrice(cents: number) {
  return displayMoney.format(cents / 100);
}
