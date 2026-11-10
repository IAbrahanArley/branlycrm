"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";

export function RecoveryCodes({ codes, onContinue }: { codes: string[]; onContinue: () => void }) {
  const [saved, setSaved] = useState(false);
  const [notice, setNotice] = useState("");

  async function copy() {
    try {
      await navigator.clipboard.writeText(codes.join("\n"));
      setNotice("Códigos copiados.");
    } catch {
      setNotice("Não foi possível copiar. Você pode baixar o arquivo de texto.");
    }
  }

  function download() {
    const url = URL.createObjectURL(new Blob([codes.join("\n") + "\n"], { type: "text/plain;charset=utf-8" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = "branly-codigos-de-recuperacao.txt";
    link.click();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  return (
    <section className="space-y-4" aria-labelledby="recovery-title">
      <h2 id="recovery-title" className="text-xl font-semibold">Guarde seus códigos de recuperação</h2>
      <p className="text-sm leading-relaxed text-slate-700">Esta é a única vez que estes códigos serão exibidos. Guarde-os em um lugar seguro para recuperar o acesso sem o aplicativo.</p>
      <ul className="grid gap-2 rounded-lg border border-slate-200 bg-slate-50 p-3 font-mono text-sm sm:grid-cols-2">
        {codes.map((code, index) => <li key={index} translate="no" className="break-all">{code}</li>)}
      </ul>
      <div className="flex flex-wrap gap-2">
        <Button type="button" variant="outline" onClick={copy} className="min-h-11">Copiar códigos</Button>
        <Button type="button" variant="outline" onClick={download} className="min-h-11">Baixar arquivo .txt</Button>
      </div>
      <p role="status" className="text-sm">{notice}</p>
      <label className="flex min-h-11 cursor-pointer items-center gap-2 text-sm font-medium">
        <input type="checkbox" checked={saved} onChange={(event) => setSaved(event.target.checked)} className="size-4 accent-current" />
        Guardei meus códigos de recuperação.
      </label>
      <Button type="button" disabled={!saved} onClick={onContinue} className="min-h-11">Continuar</Button>
    </section>
  );
}
