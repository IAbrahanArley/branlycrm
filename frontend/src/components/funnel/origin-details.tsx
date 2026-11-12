import { originSources, originTypeLabel, type LeadOrigin } from "@/lib/lead-origin";

export function OriginDetails({ origin }: { origin: LeadOrigin }) {
  const fields = [
    ["Tipo", originTypeLabel(origin.type)],
    ["Fonte", Object.hasOwn(originSources, origin.source) ? originSources[origin.source] : "Fonte indisponível"],
    ["Código", origin.code],
    ["Título", origin.title],
  ];
  return (
    <dl className="grid gap-3 text-sm sm:grid-cols-2">
      {fields.map(([label, value]) => (
        <div className="min-w-0" key={label}>
          <dt className="text-muted-foreground">{label}</dt>
          <dd className="font-semibold whitespace-pre-wrap wrap-anywhere">{value ?? "—"}</dd>
        </div>
      ))}
    </dl>
  );
}
