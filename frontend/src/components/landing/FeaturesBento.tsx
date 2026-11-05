import {
  ArrowRight,
  CalendarDays,
  Check,
  GitBranch,
  HeartHandshake,
  LayoutDashboard,
  Users,
} from "lucide-react";
export function FeaturesBento() {
  return (
    <section className="features-section section-pad" id="produto">
      <div className="shell">
        <div className="section-heading" data-reveal>
          <h2>
            Uma rotina.
            <br />
            Tudo conectado.
          </h2>
          <p>
            A proposta é simples: informação organizada para a equipe saber como
            seguir.
          </p>
        </div>
        <div className="features-bento">
          <article className="bento bento-wide" data-reveal>
            <LayoutDashboard className="bento-icon" size={24} />
            <h3>Oportunidades à vista.</h3>
            <p>Um funil para acompanhar cada negociação e seu próximo passo.</p>
            <div className="mini-pipeline" aria-hidden="true">
              <span>
                Novo contato
                <i />
              </span>
              <ArrowRight size={18} />
              <span>
                Em atendimento
                <i />
                <i />
              </span>
              <ArrowRight size={18} />
              <span>
                Concluído
                <i />
              </span>
            </div>
            <a href="#demonstracao">
              Explore no exemplo <ArrowRight size={17} />
            </a>
          </article>
          <article className="bento bento-cyan" data-reveal>
            <Users className="bento-icon" size={24} />
            <h3>
              Contexto desde
              <br />o primeiro contato.
            </h3>
            <p>Origem, interesse e responsável conectados à conversa.</p>
            <div className="mini-fields">
              <span>
                Origem registrada
                <Check size={15} />
              </span>
              <span>
                Próxima ação definida
                <Check size={15} />
              </span>
            </div>
          </article>
          <article className="bento" data-reveal>
            <HeartHandshake className="bento-icon" size={24} />
            <h3>O vínculo continua.</h3>
            <p>
              Histórico e atividades para acompanhar o relacionamento depois da
              avaliação.
            </p>
            <div className="mini-timeline" aria-hidden="true">
              <span>Conversa</span>
              <span>Avaliação</span>
              <span>Retorno</span>
            </div>
          </article>
          <article className="bento" data-reveal>
            <CalendarDays className="bento-icon" size={24} />
            <h3>
              O combinado
              <br />
              vira compromisso.
            </h3>
            <p>
              Agenda própria e tarefas fazem parte do escopo em desenvolvimento.
            </p>
            <div className="mini-appointment">
              <span>Próxima atividade</span>
              <strong>Acompanhar avaliação</strong>
            </div>
          </article>
          <article className="bento bento-dark" data-reveal>
            <GitBranch className="bento-icon" size={24} />
            <h3>
              Continuidade
              <br />
              por intenção.
            </h3>
            <p>
              Automações de acompanhamento são uma evolução planejada para uma
              próxima fase.
            </p>
            <span className="feature-status">Conceito futuro</span>
          </article>
          <article className="bento bento-report" data-reveal>
            <div>
              <h3>Indicadores com contexto.</h3>
              <p>
                Uma visão do funil para acompanhar o que está acontecendo, sem
                perder a história por trás de cada oportunidade.
              </p>
              <a href="#demonstracao">
                Explore a demonstração <ArrowRight size={17} />
              </a>
            </div>
            <div className="report-preview" data-ui>
              <span>Distribuição inicial dos quatro contatos fictícios</span>
              <div>
                <span>Novo contato</span>
                <i style={{ width: "50%" }} />
                <strong>2</strong>
              </div>
              <div>
                <span>Em avaliação</span>
                <i style={{ width: "25%" }} />
                <strong>1</strong>
              </div>
              <div>
                <span>Concluído</span>
                <i style={{ width: "25%" }} />
                <strong>1</strong>
              </div>
            </div>
          </article>
        </div>
      </div>
    </section>
  );
}
