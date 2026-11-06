"use client";
import { useMemo, useState } from "react";
import {
  ArrowRight,
  BarChart3,
  CalendarDays,
  Check,
  ChevronDown,
  Circle,
  GitBranch,
  GripVertical,
  HeartHandshake,
  LayoutDashboard,
  RotateCcw,
  Search,
  Sparkles,
  Users,
} from "lucide-react";
import {
  initialOpportunities,
  money,
  stages,
  type DemoModule,
  type DemoOpportunity,
  type Stage,
} from "./demo-data";
const modules = [
  { id: "pipeline", label: "Pipeline", icon: LayoutDashboard },
  { id: "leads", label: "Contatos", icon: Users },
  { id: "relationship", label: "Relacionamento", icon: HeartHandshake },
  { id: "automation", label: "Automações", icon: GitBranch },
  { id: "insights", label: "Indicadores", icon: BarChart3 },
] as const;
export function DashboardPreview() {
  const [active, setActive] = useState<DemoModule>("pipeline");
  const [opportunities, setOpportunities] =
    useState<DemoOpportunity[]>(initialOpportunities);
  const [query, setQuery] = useState("");
  const [selected, setSelected] = useState("demo-a");
  const [automation, setAutomation] = useState(false);
  const [notice, setNotice] = useState("");
  const total = opportunities.reduce((sum, item) => sum + item.amountCents, 0);
  const won = opportunities.filter((item) => item.stage === "won");
  const concluded = won.reduce((sum, item) => sum + item.amountCents, 0);
  const filtered = useMemo(
    () =>
      opportunities.filter((item) =>
        `${item.name} ${item.interest} ${item.origin}`
          .toLocaleLowerCase("pt-BR")
          .includes(query.toLocaleLowerCase("pt-BR")),
      ),
    [opportunities, query],
  );
  const person =
    opportunities.find((item) => item.id === selected) ?? opportunities[0];
  function move(id: string, stage: Stage) {
    setOpportunities((items) =>
      items.map((item) => (item.id === id ? { ...item, stage } : item)),
    );
    setNotice(
      `Oportunidade de exemplo movida para ${stages.find((item) => item.id === stage)?.label}.`,
    );
  }
  function reset() {
    setOpportunities(initialOpportunities);
    setQuery("");
    setSelected("demo-a");
    setAutomation(false);
    setNotice("Dados de exemplo reiniciados.");
  }
  return (
    <div className="dashboard" data-ui>
      <div className="dashboard-topbar">
        <span className="workspace-mark">
          <span className="workspace-symbol" aria-hidden="true">
            B
          </span>
          <strong>Clínica demonstração</strong>
          <ChevronDown size={14} aria-hidden="true" />
        </span>
        <span className="demo-indicator">
          <span />
          Dados fictícios · só nesta página
        </span>
        <button
          onClick={reset}
          className="reset-demo"
          aria-label="Reiniciar demonstração"
        >
          <RotateCcw size={15} />
          <span>Reiniciar</span>
        </button>
      </div>
      <div className="dashboard-layout">
        <div
          className="dashboard-sidebar"
          role="tablist"
          aria-label="Módulos da demonstração"
        >
          {modules.map((module, index) => {
            const Icon = module.icon;
            return (
              <button
                key={module.id}
                id={`module-${module.id}`}
                role="tab"
                aria-selected={active === module.id}
                aria-controls="dashboard-panel"
                tabIndex={active === module.id ? 0 : -1}
                onClick={() => setActive(module.id)}
                onKeyDown={(event) => {
                  if (
                    [
                      "ArrowDown",
                      "ArrowRight",
                      "ArrowUp",
                      "ArrowLeft",
                      "Home",
                      "End",
                    ].includes(event.key)
                  ) {
                    event.preventDefault();
                    const next =
                      event.key === "Home"
                        ? 0
                        : event.key === "End"
                          ? modules.length - 1
                          : (index +
                              (["ArrowDown", "ArrowRight"].includes(event.key)
                                ? 1
                                : -1) +
                              modules.length) %
                            modules.length;
                    setActive(modules[next].id);
                    document
                      .getElementById(`module-${modules[next].id}`)
                      ?.focus();
                  }
                }}
              >
                <Icon size={17} aria-hidden="true" />
                <span>{module.label}</span>
              </button>
            );
          })}
          <span className="sidebar-note">
            Prévia conceitual
            <br />
            Branly CRM
          </span>
        </div>
        <div
          className="dashboard-main"
          id="dashboard-panel"
          role="tabpanel"
          aria-labelledby={`module-${active}`}
        >
          <div className="dashboard-panel-heading">
            <div>
              <p className="ui-breadcrumb">Espaço de trabalho / Demonstração</p>
              <h3>{modules.find((item) => item.id === active)?.label}</h3>
            </div>
            <span className="workspace-avatar" aria-label="Perfil de exemplo">
              DE
            </span>
          </div>
          <div className="demo-stats">
            <div>
              <span>Contatos no exemplo</span>
              <strong>{opportunities.length}</strong>
            </div>
            <div>
              <span>Valor das oportunidades</span>
              <strong>{money(total)}</strong>
            </div>
            <div>
              <span>Concluído no exemplo</span>
              <strong data-demo-won>{money(concluded)}</strong>
            </div>
          </div>
          <div className="module-content" key={active}>
            {active === "pipeline" && (
              <>
                <div className="board-toolbar">
                  <span>
                    <span className="live-dot" />
                    Funil comercial
                  </span>
                  <small>
                    <span className="board-hint-desktop">
                      Arraste ou use “Mover para”
                    </span>
                    <span className="board-hint-mobile">
                      Deslize para ver as etapas
                    </span>
                  </small>
                </div>
                <div className="pipeline-board">
                  {stages.map((stage) => (
                    <section
                      key={stage.id}
                      className={`pipeline-column stage-${stage.id}`}
                      aria-label={stage.label}
                      onDragOver={(event) => event.preventDefault()}
                      onDrop={(event) => {
                        event.preventDefault();
                        const id = event.dataTransfer.getData("text/plain");
                        if (opportunities.some((item) => item.id === id))
                          move(id, stage.id);
                      }}
                    >
                      <div className="stage-label">
                        <span>{stage.label}</span>
                        <span>
                          {
                            opportunities.filter(
                              (item) => item.stage === stage.id,
                            ).length
                          }
                        </span>
                      </div>
                      {opportunities
                        .filter((item) => item.stage === stage.id)
                        .map((item) => (
                          <article
                            className="opportunity-card"
                            key={item.id}
                            draggable
                            onDragStart={(event) =>
                              event.dataTransfer.setData("text/plain", item.id)
                            }
                          >
                            <div className="opportunity-person">
                              <span className="contact-avatar">
                                {item.initials}
                              </span>
                              <strong>{item.name}</strong>
                              <GripVertical size={14} aria-hidden="true" />
                            </div>
                            <p>{item.interest}</p>
                            <div className="opportunity-value">
                              <strong>{money(item.amountCents)}</strong>
                              <span>{item.origin}</span>
                            </div>
                            <label className="move-label">
                              Mover para
                              <select
                                value={item.stage}
                                aria-label={`Mover oportunidade de ${item.name}`}
                                onChange={(event) =>
                                  move(item.id, event.target.value as Stage)
                                }
                              >
                                {stages.map((option) => (
                                  <option key={option.id} value={option.id}>
                                    {option.label}
                                  </option>
                                ))}
                              </select>
                            </label>
                          </article>
                        ))}
                      {!opportunities.some(
                        (item) => item.stage === stage.id,
                      ) && (
                        <p className="empty-column">
                          Mova uma oportunidade para cá.
                        </p>
                      )}
                    </section>
                  ))}
                </div>
              </>
            )}
            {active === "leads" && (
              <>
                <label className="demo-search">
                  <Search size={17} aria-hidden="true" />
                  <input
                    value={query}
                    onChange={(event) => setQuery(event.target.value)}
                    placeholder="Buscar nos contatos de exemplo"
                    aria-label="Buscar nos contatos de exemplo"
                  />
                </label>
                <div
                  className="contacts-table"
                  role="table"
                  aria-label="Contatos fictícios"
                >
                  <div className="contact-table-head" role="row">
                    <span role="columnheader">Contato</span>
                    <span role="columnheader">Interesse</span>
                    <span role="columnheader">Origem</span>
                  </div>
                  {filtered.map((item) => (
                    <div className="contact-table-row" role="row" key={item.id}>
                      <span role="cell">
                        <span className="contact-avatar">{item.initials}</span>
                        {item.name}
                      </span>
                      <span role="cell">{item.interest}</span>
                      <span role="cell">{item.origin}</span>
                    </div>
                  ))}
                  {filtered.length === 0 && (
                    <p className="demo-empty" role="status">
                      Nenhum exemplo corresponde à busca.
                    </p>
                  )}
                </div>
              </>
            )}
            {active === "relationship" && (
              <div className="relationship-view">
                <label>
                  Explore um contato
                  <select
                    value={selected}
                    onChange={(event) => setSelected(event.target.value)}
                  >
                    {opportunities.map((item) => (
                      <option value={item.id} key={item.id}>
                        {item.name}
                      </option>
                    ))}
                  </select>
                </label>
                <div className="relationship-person">
                  <span className="contact-avatar">{person.initials}</span>
                  <div>
                    <strong>{person.name}</strong>
                    <p>{person.interest} · exemplo fictício</p>
                  </div>
                </div>
                <ol className="activity-timeline">
                  <li>
                    <Circle size={14} />
                    <div>
                      <strong>Interesse registrado</strong>
                      <p>Origem: {person.origin}</p>
                    </div>
                  </li>
                  <li>
                    <Check size={14} />
                    <div>
                      <strong>Responsável definido</strong>
                      <p>Equipe comercial de demonstração</p>
                    </div>
                  </li>
                  <li>
                    <CalendarDays size={14} />
                    <div>
                      <strong>Próxima atividade</strong>
                      <p>Combinar um horário de avaliação</p>
                    </div>
                  </li>
                </ol>
              </div>
            )}
            {active === "automation" && (
              <div className="automation-view">
                <p className="planned-label">
                  <Sparkles size={15} />
                  Conceito planejado · fora da versão inicial
                </p>
                <h4>Dar continuidade, no momento certo.</h4>
                <div className="automation-flow">
                  <span>Interesse registrado</span>
                  <ArrowRight size={18} />
                  <span>Revisar acompanhamento</span>
                  <ArrowRight size={18} />
                  <span>Criar tarefa</span>
                </div>
                <div className="automation-toggle">
                  <div>
                    <strong>Fluxo de acompanhamento</strong>
                    <p>Uma simulação visual. Não envia mensagens.</p>
                  </div>
                  <button
                    type="button"
                    role="switch"
                    aria-checked={automation}
                    aria-label="Simular fluxo de acompanhamento"
                    onClick={() => setAutomation(!automation)}
                  >
                    <span />
                  </button>
                </div>
                <p className="automation-result" role="status">
                  {automation
                    ? "Simulação ativada: a tarefa seria encaminhada à equipe. Nenhuma ação foi executada."
                    : "Ative para visualizar o comportamento proposto."}
                </p>
              </div>
            )}
            {active === "insights" && (
              <div className="insights-view">
                <div className="chart-heading">
                  <h4>Oportunidades por etapa</h4>
                  <span>
                    Base: os {opportunities.length} contatos fictícios
                  </span>
                </div>
                <div
                  className="demo-chart"
                  aria-label="Contagem de oportunidades por etapa"
                >
                  {stages.map((stage) => {
                    const count = opportunities.filter(
                      (item) => item.stage === stage.id,
                    ).length;
                    return (
                      <div className="chart-row" key={stage.id}>
                        <span>{stage.label}</span>
                        <div className="chart-track">
                          <div
                            style={{
                              width: `${(count / opportunities.length) * 100}%`,
                            }}
                          />
                        </div>
                        <strong>{count}</strong>
                      </div>
                    );
                  })}
                </div>
                <p className="chart-caption">
                  Conclusão no exemplo:{" "}
                  <strong>
                    {Math.round((won.length / opportunities.length) * 100)}%
                  </strong>
                  . Mova oportunidades no pipeline e veja os valores mudarem.
                </p>
              </div>
            )}
          </div>
          <div className="dashboard-status">
            <span className="status-dot" />
            <span>Demonstração local. Alterações não são salvas.</span>
          </div>
        </div>
      </div>
      <span className="sr-only" role="status">
        {notice}
      </span>
    </div>
  );
}
