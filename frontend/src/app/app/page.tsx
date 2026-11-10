import { LayoutGrid } from "lucide-react";
import { Card, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

export default function PanelPage() {
  return (
    <Card aria-labelledby="clinic-modules-title">
      <CardHeader>
        <span className="grid size-10 place-items-center rounded-lg bg-[#e9f0fd] text-[#244d8c]">
          <LayoutGrid size={19} aria-hidden="true" />
        </span>
        <CardTitle id="clinic-modules-title" className="pt-1">Módulos da clínica</CardTitle>
        <CardDescription>Os módulos aparecerão aqui conforme forem liberados para sua clínica.</CardDescription>
      </CardHeader>
    </Card>
  );
}
