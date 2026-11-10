import { Button } from "@/components/ui/button";

type PanelStateProps = {
  kind: "loading" | "empty" | "error" | "forbidden" | "conflict";
  title: string;
  message?: string;
  onRetry?: () => void;
};

export function PanelState({ kind, title, message, onRetry }: PanelStateProps) {
  const role = kind === "loading" || kind === "empty" ? "status" : "alert";

  return (
    <div className="rounded-xl border border-border bg-card p-5 text-card-foreground sm:p-7">
      <div role={role}>
        <h2 className="text-lg font-semibold text-balance">{title}</h2>
        {message && <p className="mt-2 max-w-prose text-sm leading-relaxed text-muted-foreground wrap-anywhere">{message}</p>}
      </div>
      {onRetry && (
        <Button type="button" variant="outline" className="mt-4 min-h-11 touch-manipulation" onClick={onRetry}>
          {kind === "conflict" ? "Recarregar dados" : "Tentar novamente"}
        </Button>
      )}
    </div>
  );
}
