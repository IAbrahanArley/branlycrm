"use client";
import { useEffect, useRef, useState } from "react";
import { ArrowRight, Check, Circle } from "lucide-react";
import { journeySteps } from "./content";
export function PatientJourney() {
  const [active, setActive] = useState(0);
  const root = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const observer = new IntersectionObserver(
      (entries) =>
        entries.forEach((entry) => {
          if (entry.isIntersecting)
            setActive(Number((entry.target as HTMLElement).dataset.step));
        }),
      { rootMargin: "-30% 0px -35% 0px", threshold: 0 },
    );
    root.current
      ?.querySelectorAll("[data-step]")
      .forEach((element) => observer.observe(element));
    return () => observer.disconnect();
  }, []);
  return (
    <section className="journey-premium section-pad" id="jornada">
      <div className="shell">
        <div className="section-heading" data-reveal>
          <h2>
            A jornada é humana.
            <br />O contexto fica.
          </h2>
          <p>Do interesse ao próximo encontro, cada etapa tem uma intenção.</p>
        </div>
        <div className="journey-story" ref={root}>
          <div className="journey-sticky">
            <div className="journey-visual" data-ui>
              <div className="journey-visual-top">
                <span className="live-dot" />
                Jornada ilustrativa
              </div>
              <div key={active} className="journey-state">
                <span className="journey-symbol">
                  <HeartSymbol />
                </span>
                <h3>{journeySteps[active].state}</h3>
                <p>{journeySteps[active].field}</p>
                <div className="journey-next">
                  <Check size={18} />
                  {journeySteps[active].action}
                </div>
              </div>
              <div
                className="journey-progress"
                aria-label={`Etapa ${active + 1} de ${journeySteps.length}`}
              >
                {journeySteps.map((step, index) => (
                  <span
                    className={index <= active ? "complete" : ""}
                    key={step.title}
                  />
                ))}
              </div>
              <p className="journey-visual-note">
                Fluxo conceitual. Não envia mensagens ou cria agendamentos.
              </p>
            </div>
          </div>
          <div className="journey-story-steps">
            {journeySteps.map((step, index) => (
              <article
                className={
                  active === index ? "story-step active" : "story-step"
                }
                key={step.title}
                data-step={index}
              >
                <button
                  onClick={() => setActive(index)}
                  aria-pressed={active === index}
                  aria-label={`Explorar etapa: ${step.title}`}
                >
                  <span className="story-marker">
                    {index < active ? (
                      <Check size={16} />
                    ) : (
                      <Circle size={14} />
                    )}
                  </span>
                  <span>
                    <h3>{step.title}</h3>
                    <p>{step.detail}</p>
                    <span className="story-action">
                      {step.action}
                      <ArrowRight size={17} />
                    </span>
                  </span>
                </button>
              </article>
            ))}
          </div>
        </div>
      </div>
    </section>
  );
}
function HeartSymbol() {
  return (
    <svg viewBox="0 0 32 32" fill="none" aria-hidden="true">
      <path
        d="M16 26 5.8 16.2C-.8 9.6 8.6 1.6 16 9c7.4-7.4 16.8.6 10.2 7.2L16 26Z"
        stroke="currentColor"
        strokeWidth="1.7"
      />
      <path d="M8 16h5l2-4 3 8 2-4h5" stroke="currentColor" strokeWidth="1.7" />
    </svg>
  );
}
