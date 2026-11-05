"use client";
import { useEffect, useState } from "react";
import { ArrowUpRight, Menu, X } from "lucide-react";
import { Brand } from "./Brand";
import { navigation } from "./content";
export function Header() {
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState("");
  useEffect(() => {
    const observer = new IntersectionObserver(
      (entries) =>
        entries.forEach((entry) => {
          if (entry.isIntersecting) setActive(`#${entry.target.id}`);
        }),
      { rootMargin: "-15% 0px -55% 0px" },
    );
    navigation.forEach((item) => {
      const element = document.querySelector(item.href);
      if (element) observer.observe(element);
    });
    return () => observer.disconnect();
  }, []);
  return (
    <header className="site-header">
      <div className="shell header-inner">
        <Brand />
        <nav className="desktop-nav" aria-label="Navegação principal">
          {navigation.map((item) => (
            <a
              key={item.href}
              href={item.href}
              aria-current={active === item.href ? "location" : undefined}
            >
              {item.label}
            </a>
          ))}
        </nav>
        <a className="nav-cta" href="#demonstracao">
          Explorar demonstração <ArrowUpRight size={17} aria-hidden="true" />
        </a>
        <button
          className="mobile-toggle"
          aria-label={open ? "Fechar menu" : "Abrir menu"}
          aria-expanded={open}
          aria-controls="mobile-navigation"
          onClick={() => setOpen(!open)}
        >
          {open ? <X /> : <Menu />}
        </button>
      </div>
      {open && (
        <nav
          className="mobile-nav shell"
          id="mobile-navigation"
          aria-label="Navegação móvel"
        >
          {navigation.map((item) => (
            <a key={item.href} href={item.href} onClick={() => setOpen(false)}>
              {item.label}
              <ArrowUpRight size={18} aria-hidden="true" />
            </a>
          ))}
        </nav>
      )}
    </header>
  );
}
