import Link from "next/link";
import { ArrowUpRight, ChevronDown } from "lucide-react";
import { Brand } from "./Brand";
import { Header } from "./Header";
import { HeroSection } from "./HeroSection";
import { FeaturesBento } from "./FeaturesBento";
import { PatientJourney } from "./PatientJourney";
import { RevenueSimulator } from "./RevenueSimulator";
import { FinalCTA } from "./FinalCTA";
import { MotionObserver } from "../animations/MotionObserver";
import { questions } from "./content";
import "./premium.css";
export function Landing() {
  return (
    <div className="landing landing-premium" id="inicio">
      <a className="skip-link" href="#conteudo">
        Pular para o conteúdo
      </a>
      <Header />
      <main id="conteudo">
        <HeroSection />
        <FeaturesBento />
        <PatientJourney />
        <RevenueSimulator />
        <section className="faq-section section-pad" id="perguntas">
          <div className="shell faq-layout">
            <h2 data-reveal>Antes de seguir.</h2>
            <div className="faq-list">
              {questions.map((item) => (
                <details key={item.question} data-reveal>
                  <summary>
                    {item.question}
                    <ChevronDown size={20} aria-hidden="true" />
                  </summary>
                  <p>{item.answer}</p>
                </details>
              ))}
            </div>
          </div>
        </section>
        <FinalCTA />
      </main>
      <footer className="site-footer">
        <div className="shell footer-top">
          <Brand light />
          <p>Relacionamentos têm continuidade.</p>
          <a href="#inicio">
            Voltar ao início <ArrowUpRight size={18} />
          </a>
        </div>
        <div className="shell footer-bottom">
          <span>Branly · CRM para clínicas de estética</span>
          <nav aria-label="Documentos legais" className="footer-legal">
            <Link href="/privacidade">Privacidade</Link>
            <Link href="/termos">Termos</Link>
            <Link href="/exclusao-de-dados">Exclusão de dados</Link>
          </nav>
          <span>Uma proposta em construção.</span>
        </div>
      </footer>
      <MotionObserver />
    </div>
  );
}
