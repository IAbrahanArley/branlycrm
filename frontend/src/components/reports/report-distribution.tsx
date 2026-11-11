import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

const number = new Intl.NumberFormat("pt-BR");

export function ReportDistribution({ id, title, rows }: {
  id: string; title: string; rows: { key: string; label: string; count: number }[];
}) {
  const max = Math.max(0, ...rows.map(({ count }) => count));
  return (
    <Card aria-labelledby={id}>
      <CardHeader><CardTitle id={id}>{title}</CardTitle></CardHeader>
      <CardContent>
        {max === 0 && <p className="mb-4 text-sm text-muted-foreground">Nenhum lead neste período.</p>}
        <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,0.8fr)]">
          <ul aria-hidden="true" className="min-w-0 space-y-3 rounded-lg bg-muted/55 p-4">
            {rows.map(({ key, label, count }) => (
              <li key={key} className="space-y-1.5">
                <div className="flex justify-between gap-3 text-sm">
                  <span className="min-w-0 wrap-anywhere">{label}</span>
                  <span className="font-semibold tabular-nums">{number.format(count)}</span>
                </div>
                <div className="h-2 rounded-full bg-border">
                  <div className="h-full rounded-full bg-primary" style={{ width: `${max ? count / max * 100 : 0}%` }} />
                </div>
              </li>
            ))}
          </ul>
          <table className="w-full self-start text-sm">
            <caption className="sr-only">{title} por categoria, em quantidade de leads</caption>
            <thead className="border-b text-left text-muted-foreground">
              <tr><th scope="col" className="pb-2">Categoria</th><th scope="col" className="pb-2 text-right">Leads</th></tr>
            </thead>
            <tbody className="divide-y">
              {rows.map(({ key, label, count }) => (
                <tr key={key}>
                  <th scope="row" className="py-2 pr-2 text-left font-medium wrap-anywhere">{label}</th>
                  <td className="py-2 text-right tabular-nums">{number.format(count)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </CardContent>
    </Card>
  );
}
