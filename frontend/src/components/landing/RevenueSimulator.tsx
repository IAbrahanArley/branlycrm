"use client";
import { useState } from "react";
import { ArrowUpRight, Info } from "lucide-react";
import { AnimatedCounter } from "../animations/AnimatedCounter";
import { calculateScenario } from "./simulator";
import { money } from "./demo-data";
export function RevenueSimulator() {
  const [leads, setLeads] = useState(100);
  const [conversion, setConversion] = useState(10);
  const [ticket, setTicket] = useState(500);
  const [target, setTarget] = useState(15);
  const scenario = calculateScenario({
    leads,
    conversion,
    ticket,
    targetConversion: target,
  });
  return (
    <section className="simulator-section section-pad" id="simulador">
      <div className="shell">
        <div className="section-heading" data-reveal>
          <h2>
            E se o cenário
            <br />
            fosse diferente?
          </h2>
          <p>
            Explore a relação entre contatos, conversão e ticket. Você escolhe
            todas as premissas.
          </p>
        </div>
        <div className="simulator" data-reveal>
          <div className="simulator-controls">
            <div className="simulator-heading">
              <span>Seu cenário</span>
              <span>Valores ilustrativos e editáveis</span>
            </div>
            <Slider
              id="leads"
              label="Contatos por mês"
              value={leads}
              min={0}
              max={1000}
              step={10}
              text={String(leads)}
              onChange={setLeads}
            />
            <Slider
              id="conversion"
              label="Conversão atual"
              value={conversion}
              min={0}
              max={100}
              step={1}
              text={`${conversion}%`}
              onChange={setConversion}
            />
            <Slider
              id="ticket"
              label="Ticket médio"
              value={ticket}
              min={0}
              max={5000}
              step={50}
              text={money(ticket * 100)}
              onChange={setTicket}
            />
            <Slider
              id="target"
              label="Conversão no cenário hipotético"
              value={target}
              min={0}
              max={100}
              step={1}
              text={`${target}%`}
              onChange={setTarget}
            />
            <p className="scenario-assumption">
              A diferença entre as duas taxas é uma premissa escolhida por você,
              não uma estimativa de melhoria do Branly.
            </p>
          </div>
          <div className="simulator-result">
            <span className="simulation-label">
              <Info size={15} />
              Simulação matemática, não previsão
            </span>
            <div className="simulator-main-value">
              <span>
                {scenario.differenceCents === 0
                  ? "Sem variação no cenário"
                  : scenario.differenceCents > 0
                    ? "Variação positiva no cenário"
                    : "Variação negativa no cenário"}
              </span>
              <strong data-simulation-difference>
                <AnimatedCounter
                  value={scenario.differenceCents}
                  format={money}
                />
              </strong>
              <span>por mês, sob as premissas informadas</span>
            </div>
            <div className="scenario-comparison">
              <div>
                <span>Receita bruta no cenário atual</span>
                <strong>{money(scenario.currentRevenueCents)}</strong>
                <i style={{ width: `${conversion}%` }} />
              </div>
              <div>
                <span>Receita bruta no cenário hipotético</span>
                <strong>{money(scenario.targetRevenueCents)}</strong>
                <i style={{ width: `${target}%` }} />
              </div>
            </div>
            <p>
              Contatos × taxa de conversão × ticket médio. Valores esperados
              podem corresponder a frações de conversões. Não considera custos,
              impostos, capacidade ou cancelamentos.
            </p>
            <a href="#perguntas">
              Entenda os limites <ArrowUpRight size={17} />
            </a>
          </div>
        </div>
        <p className="simulation-disclaimer">
          Não é promessa de faturamento, garantia de resultado ou dado de
          desempenho do produto. A simulação é local e não salva suas
          informações.
        </p>
      </div>
    </section>
  );
}
function Slider({
  id,
  label,
  value,
  min,
  max,
  step,
  text,
  onChange,
}: {
  id: string;
  label: string;
  value: number;
  min: number;
  max: number;
  step: number;
  text: string;
  onChange: (value: number) => void;
}) {
  return (
    <div className="scenario-slider">
      <div>
        <label htmlFor={`scenario-${id}`}>{label}</label>
        <output htmlFor={`scenario-${id}`}>{text}</output>
      </div>
      <input
        id={`scenario-${id}`}
        type="range"
        value={value}
        min={min}
        max={max}
        step={step}
        aria-valuetext={text}
        onChange={(event) => onChange(Number(event.target.value))}
      />
    </div>
  );
}
