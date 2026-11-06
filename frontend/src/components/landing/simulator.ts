export type ScenarioInput = {
  leads: number;
  conversion: number;
  targetConversion: number;
  ticket: number;
};
export function calculateScenario(input: ScenarioInput) {
  const { leads, conversion, targetConversion, ticket } = input;
  if (
    ![leads, conversion, targetConversion, ticket].every(Number.isFinite) ||
    leads < 0 ||
    !Number.isInteger(leads) ||
    ticket < 0 ||
    conversion < 0 ||
    conversion > 100 ||
    targetConversion < 0 ||
    targetConversion > 100
  )
    throw new RangeError("Invalid scenario");
  const ticketCents = Math.round(ticket * 100);
  const currentCustomers = (leads * conversion) / 100;
  const targetCustomers = (leads * targetConversion) / 100;
  const currentRevenueCents = Math.round(currentCustomers * ticketCents);
  const targetRevenueCents = Math.round(targetCustomers * ticketCents);
  return {
    currentCustomers,
    targetCustomers,
    currentRevenueCents,
    targetRevenueCents,
    differenceCents: targetRevenueCents - currentRevenueCents,
  };
}
