import { ArrowDown, ArrowUpRight } from "lucide-react";
import { DashboardPreview } from "./DashboardPreview";
import { ParallaxElement } from "../animations/ParallaxElement";
export function HeroSection() {
  return (
    <section className="hero-premium">
      <div className="hero-ambient" aria-hidden="true" />
      <div className="shell">
        <div className="hero-intro">
          <h1 data-reveal>
            Cada contato.
            <br />
            Um próximo passo.
          </h1>
          <div className="hero-support" data-reveal>
            <p>
              O CRM pensado para conectar oportunidades e relacionamentos na
              rotina da sua clínica de estética.
            </p>
            <div className="hero-actions">
              <a className="primary-action" href="#demonstracao">
                Explore o CRM <ArrowDown size={19} aria-hidden="true" />
              </a>
              <a className="secondary-action" href="#simulador">
                Simule um cenário <ArrowUpRight size={18} aria-hidden="true" />
              </a>
            </div>
          </div>
        </div>
        <div className="hero-product" id="demonstracao" data-reveal>
          <div className="preview-context">
            <span>Conheça a experiência proposta</span>
            <span>
              <span />
              Produto em desenvolvimento
            </span>
          </div>
          <ParallaxElement>
            <DashboardPreview />
          </ParallaxElement>
          <p className="preview-disclosure">
            Demonstração interativa com dados fictícios. Não representa uma
            clínica real nem funcionalidades já disponíveis para contratação.
          </p>
        </div>
      </div>
    </section>
  );
}
