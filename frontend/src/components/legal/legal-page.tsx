import Link from "next/link";
import { LEGAL_ENTITY, type LegalDocument } from "./legal-entity";

const PAGES = [
  { href: "/privacidade", label: "Política de privacidade" },
  { href: "/termos", label: "Termos de uso" },
  { href: "/exclusao-de-dados", label: "Exclusão de dados" },
] as const;

/** A legal document page: readable column, table of contents and links to the other pages. */
export function LegalPage({ document, current }: { document: LegalDocument; current: string }) {
  return (
    <div className="min-h-dvh bg-[#f4f6f6] text-[#011127]">
      <a
        className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:rounded-md focus:bg-white focus:px-3 focus:py-2"
        href="#conteudo"
      >
        Pular para o conteúdo
      </a>
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-3xl items-center justify-between px-4 py-4 sm:px-6">
          <Link
            href="/"
            className="font-[family-name:var(--font-branly)] text-lg font-extrabold focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-[#01247d]"
            translate="no"
          >
            {LEGAL_ENTITY.product}
          </Link>
          <Link
            href="/login"
            className="text-sm font-semibold text-[#01247d] underline-offset-4 hover:underline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-[#01247d]"
          >
            Entrar
          </Link>
        </div>
      </header>
      <main id="conteudo" className="mx-auto max-w-3xl px-4 py-10 sm:px-6 sm:py-14">
        <article className="space-y-8">
          <div className="space-y-3">
            <h1 className="text-3xl font-extrabold tracking-tight sm:text-4xl">{document.title}</h1>
            <p className="text-sm text-slate-600">Última atualização: {LEGAL_ENTITY.updatedAt}</p>
            <p className="text-base leading-7 text-slate-700">{document.intro}</p>
          </div>
          <nav aria-label="Seções" className="rounded-xl border border-slate-200 bg-white p-5">
            <h2 className="text-sm font-bold uppercase tracking-wide text-slate-600">Nesta página</h2>
            <ol className="mt-3 list-decimal space-y-1 pl-5 text-sm">
              {document.sections.map((section) => (
                <li key={section.id}>
                  <a
                    className="text-[#01247d] underline-offset-4 hover:underline focus-visible:outline-2 focus-visible:outline-[#01247d]"
                    href={`#${section.id}`}
                  >
                    {section.title}
                  </a>
                </li>
              ))}
            </ol>
          </nav>
          {document.sections.map((section, index) => (
            <section key={section.id} id={section.id} className="scroll-mt-6 space-y-3">
              <h2 className="text-xl font-bold">
                {index + 1}. {section.title}
              </h2>
              {section.blocks.map((block, blockIndex) =>
                typeof block === "string" ? (
                  <p key={blockIndex} className="leading-7 text-slate-700">
                    {block}
                  </p>
                ) : (
                  <ul key={blockIndex} className="list-disc space-y-1.5 pl-6 leading-7 text-slate-700">
                    {block.map((item) => (
                      <li key={item}>{item}</li>
                    ))}
                  </ul>
                ),
              )}
            </section>
          ))}
        </article>
      </main>
      <footer className="border-t border-slate-200 bg-white">
        <nav
          aria-label="Documentos legais"
          className="mx-auto flex max-w-3xl flex-wrap gap-x-6 gap-y-2 px-4 py-6 text-sm sm:px-6"
        >
          {PAGES.map((page) => (
            <Link
              key={page.href}
              href={page.href}
              aria-current={page.href === current ? "page" : undefined}
              className="font-medium text-[#01247d] underline-offset-4 hover:underline aria-[current=page]:font-bold aria-[current=page]:text-[#011127] focus-visible:outline-2 focus-visible:outline-[#01247d]"
            >
              {page.label}
            </Link>
          ))}
          <span className="text-slate-500">
            {LEGAL_ENTITY.company} · CNPJ {LEGAL_ENTITY.cnpj}
          </span>
        </nav>
      </footer>
    </div>
  );
}
