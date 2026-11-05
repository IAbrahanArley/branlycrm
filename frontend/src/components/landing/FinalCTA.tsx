import { ArrowUpRight } from "lucide-react";
export function FinalCTA() {
  return (
    <section className="closing-premium section-pad" id="conhecer">
      <div className="shell">
        <h2 data-reveal>
          Relações merecem
          <br />
          continuidade.
        </h2>
        <div className="closing-bottom" data-reveal>
          <p>
            Mais do que uma planilha: uma proposta para conectar sua operação
            comercial, do contato ao próximo encontro.
          </p>
          <a className="primary-action action-dark" href="#demonstracao">
            Voltar à demonstração <ArrowUpRight size={21} />
          </a>
        </div>
        <p className="closing-note">
          Branly está em desenvolvimento. Teste comercial, cadastro e
          contratação ainda não estão disponíveis.
        </p>
      </div>
    </section>
  );
}
