"use client";

import { useEffect, useState, type ReactNode } from "react";
import Link from "next/link";
import { Dialog } from "@base-ui/react/dialog";
import { Menu } from "@base-ui/react/menu";
import {
  Activity, BriefcaseBusiness, Building2, ChartNoAxesColumn, ChevronDown, ClipboardCheck, ClipboardList, Columns3, LayoutDashboard, LogOut,
  Menu as MenuIcon, PanelLeftClose, PanelLeftOpen, ReceiptText, ShieldCheck, UserRound, Users, UsersRound, X,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

type NavigationProps = {
  email?: string;
  clinic?: { name: string; role: string };
  canSwitchClinic: boolean;
  hasAgencies: boolean;
  hasClinicArea: boolean;
  canViewContacts: boolean;
  canReadCommercial: boolean;
  canViewTeam: boolean;
  canViewReport: boolean;
  canViewTasks: boolean;
  currentPath: string;
  choosingClinic: boolean;
  signingOut: boolean;
  onChooseClinic: () => void;
  onOverview: () => void;
  onSignOut: () => void;
  children: ReactNode;
};

const sidebarItem = "flex min-h-11 w-full items-center gap-3 rounded-lg px-3 text-left text-sm font-medium text-slate-200 hover:bg-white/10 hover:text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan-300";
const accountItem = "flex min-h-11 w-full cursor-pointer items-center gap-2 rounded-md px-2.5 text-left text-sm font-medium outline-none data-highlighted:bg-slate-100";

function Brand({ compact = false }: { compact?: boolean }) {
  return (
    <div className={cn("flex min-w-0 items-center gap-3", compact && "justify-center")} translate="no">
      <span className="grid size-9 shrink-0 place-items-center rounded-lg bg-cyan-300 text-[#08213e]">
        <Activity size={19} strokeWidth={2.4} aria-hidden="true" />
      </span>
      <span className={cn("font-[family-name:var(--font-branly)] text-xl font-extrabold tracking-tight", compact && "sr-only")}>Branly CRM</span>
    </div>
  );
}

function NavigationLinks({
  compact = false, ready, hasAgencies, hasClinicArea, canViewContacts, canReadCommercial, canViewTeam, canViewReport, canViewTasks, currentPath, canSwitchClinic, choosingClinic, onChooseClinic, onOverview, onNavigate,
}: {
  compact?: boolean;
  ready: boolean;
  hasAgencies: boolean;
  hasClinicArea: boolean;
  canViewContacts: boolean;
  canReadCommercial: boolean;
  canViewTeam: boolean;
  canViewReport: boolean;
  canViewTasks: boolean;
  currentPath: string;
  canSwitchClinic: boolean;
  choosingClinic: boolean;
  onChooseClinic: () => void;
  onOverview: () => void;
  onNavigate?: () => void;
}) {
  if (!ready) return null;
  const item = cn(sidebarItem, compact && "justify-center px-0");
  const label = compact ? "sr-only" : undefined;
  const overview = currentPath === "/app" && !choosingClinic;
  const agency = currentPath === "/agencia";
  const contacts = currentPath === "/app/contatos" || currentPath.startsWith("/app/contatos/");
  const funnel = currentPath === "/app/funil" || currentPath.startsWith("/app/funil/");
  const catalog = currentPath === "/app/catalogo";
  const sales = currentPath === "/app/vendas" || currentPath.startsWith("/app/vendas/");
  const team = currentPath === "/app/equipe";
  const reports = currentPath === "/app/relatorios";
  const tasks = currentPath === "/app/tarefas";
  const active = "bg-cyan-300/12 text-cyan-100 hover:bg-cyan-300/18 hover:text-cyan-50";
  return (
    <nav aria-label="Navegação do painel" className="space-y-6">
      <div className="space-y-1">
        <p className={cn("mb-2 px-3 text-[11px] font-semibold tracking-wider text-slate-400 uppercase", compact && "sr-only")}>Área de trabalho</p>
        {hasClinicArea && <Link
          href="/app" aria-current={overview ? "page" : undefined} title={compact ? "Visão geral" : undefined}
          onNavigate={() => { onOverview(); onNavigate?.(); }}
          className={cn(item, overview && active)}
        >
          <LayoutDashboard className="size-[18px] shrink-0" aria-hidden="true" />
          <span className={label}>{agency ? "Área da clínica" : "Visão geral"}</span>
        </Link>}
        {canViewContacts && (
          <Link href="/app/contatos" aria-current={contacts ? "page" : undefined} title={compact ? "Contatos" : undefined}
            onNavigate={onNavigate} className={cn(item, contacts && active)}>
            <Users className="size-[18px] shrink-0" aria-hidden="true" />
            <span className={label}>Contatos</span>
          </Link>
        )}
        {canReadCommercial && (
          <Link href="/app/funil" aria-current={funnel ? "page" : undefined} title={compact ? "Funil de vendas" : undefined}
            onNavigate={onNavigate} className={cn(item, funnel && active)}>
            <Columns3 className="size-[18px] shrink-0" aria-hidden="true" />
            <span className={label}>Funil de vendas</span>
          </Link>
        )}
        {canReadCommercial && (
          <Link href="/app/catalogo" aria-current={catalog ? "page" : undefined} title={compact ? "Catálogo" : undefined}
            onNavigate={onNavigate} className={cn(item, catalog && active)}>
            <ClipboardList className="size-[18px] shrink-0" aria-hidden="true" />
            <span className={label}>Catálogo</span>
          </Link>
        )}
        {canViewTeam && (
          <Link href="/app/equipe" aria-current={team ? "page" : undefined} title={compact ? "Equipe" : undefined}
            onNavigate={onNavigate} className={cn(item, team && active)}>
            <UsersRound className="size-[18px] shrink-0" aria-hidden="true" />
            <span className={label}>Equipe</span>
          </Link>
        )}
        {canReadCommercial && (
          <Link href="/app/vendas" aria-current={sales ? "page" : undefined} title={compact ? "Vendas" : undefined}
            onNavigate={onNavigate} className={cn(item, sales && active)}>
            <ReceiptText className="size-[18px] shrink-0" aria-hidden="true" />
            <span className={label}>Vendas</span>
          </Link>
        )}
        {canViewTasks && (
          <Link href="/app/tarefas" aria-current={tasks ? "page" : undefined} title={compact ? "Tarefas" : undefined}
            onNavigate={onNavigate} className={cn(item, tasks && active)}>
            <ClipboardCheck className="size-[18px] shrink-0" aria-hidden="true" />
            <span className={label}>Tarefas</span>
          </Link>
        )}
        {canViewReport && (
          <Link href="/app/relatorios" aria-current={reports ? "page" : undefined} title={compact ? "Relatórios" : undefined}
            onNavigate={onNavigate} className={cn(item, reports && active)}>
            <ChartNoAxesColumn className="size-[18px] shrink-0" aria-hidden="true" />
            <span className={label}>Relatórios</span>
          </Link>
        )}
        {canSwitchClinic && (
          <button type="button" aria-current={choosingClinic ? "step" : undefined} title={compact ? "Selecionar clínica" : undefined}
            onClick={() => { onChooseClinic(); onNavigate?.(); }}
            className={cn(item, choosingClinic && "bg-cyan-300/12 text-cyan-100")}
          >
            <Building2 className="size-[18px] shrink-0" aria-hidden="true" />
            <span className={label}>Selecionar clínica</span>
          </button>
        )}
        {hasAgencies && (
          <Link href="/agencia" aria-current={agency ? "page" : undefined} title={compact ? "Carteira da agência" : undefined} onNavigate={onNavigate} className={cn(item, agency && active)}>
            <BriefcaseBusiness className="size-[18px] shrink-0" aria-hidden="true" />
            <span className={label}>Carteira da agência</span>
          </Link>
        )}
      </div>
      <div className="space-y-1">
        <p className={cn("mb-2 px-3 text-[11px] font-semibold tracking-wider text-slate-400 uppercase", compact && "sr-only")}>Preferências</p>
        <Link href="/seguranca" title={compact ? "Segurança da conta" : undefined} onNavigate={onNavigate} className={item}>
          <ShieldCheck className="size-[18px] shrink-0" aria-hidden="true" />
          <span className={label}>Segurança da conta</span>
        </Link>
      </div>
    </nav>
  );
}

function AccountMenu({ email, onSignOut }: { email: string; onSignOut: () => void }) {
  return (
    <Menu.Root>
      <Menu.Trigger
        render={<Button variant="ghost" className="h-11 min-w-11 max-w-64 gap-2 rounded-lg px-1.5 transition-colors sm:px-2" />}
        aria-label="Menu da conta"
      >
        <span className="grid size-8 shrink-0 place-items-center rounded-full bg-[#e7eefb] text-[#173d75]">
          <UserRound size={17} aria-hidden="true" />
        </span>
        <span className="hidden min-w-0 text-left sm:block">
          <span className="block text-[11px] leading-tight text-slate-600">Minha conta</span>
          <span className="block truncate text-sm font-semibold" translate="no">{email}</span>
        </span>
        <ChevronDown className="hidden size-4 shrink-0 text-slate-500 sm:block" aria-hidden="true" />
      </Menu.Trigger>
      <Menu.Portal>
        <Menu.Positioner align="end" sideOffset={8} collisionPadding={12} className="z-30">
          <Menu.Popup className="w-64 max-w-[calc(100vw-1.5rem)] rounded-xl border border-slate-200 bg-white p-1.5 text-slate-900 shadow-lg outline-none">
            <div className="px-2.5 py-2">
              <p className="text-xs font-medium text-slate-600">Sessão ativa</p>
              <p className="mt-1 text-sm font-semibold wrap-anywhere" translate="no">{email}</p>
            </div>
            <Menu.Separator className="my-1 h-px bg-slate-200" />
            <Menu.LinkItem closeOnClick render={<Link href="/seguranca" />} className={accountItem}>
              <ShieldCheck className="size-4 shrink-0" aria-hidden="true" /> Segurança da conta
            </Menu.LinkItem>
            <Menu.Item render={<button type="button" />} nativeButton onClick={onSignOut} className={cn(accountItem, "text-red-700 data-highlighted:bg-red-50")}>
              <LogOut className="size-4 shrink-0" aria-hidden="true" /> Sair
            </Menu.Item>
          </Menu.Popup>
        </Menu.Positioner>
      </Menu.Portal>
    </Menu.Root>
  );
}

export function PanelNavigation({
  email, clinic, canSwitchClinic, hasAgencies, hasClinicArea, canViewContacts, canReadCommercial, canViewTeam, canViewReport, canViewTasks, currentPath, choosingClinic, signingOut, onChooseClinic, onOverview, onSignOut, children,
}: NavigationProps) {
  const [collapsed, setCollapsed] = useState(false);
  const [mobileOpen, setMobileOpen] = useState(false);
  const ready = Boolean(email && !signingOut);

  useEffect(() => {
    const desktop = window.matchMedia("(min-width: 1024px)");
    function closeOnDesktop(event: MediaQueryListEvent) {
      if (event.matches) setMobileOpen(false);
    }
    desktop.addEventListener("change", closeOnDesktop);
    return () => desktop.removeEventListener("change", closeOnDesktop);
  }, []);

  const nav = { ready, canSwitchClinic, hasAgencies, hasClinicArea, canViewContacts, canReadCommercial, canViewTeam, canViewReport, canViewTasks, currentPath, choosingClinic, onChooseClinic, onOverview };

  return (
    <div className="flex min-h-dvh bg-[#f3f6fa] text-[#10233b]">
      <a href="#conteudo-painel" className="sr-only rounded-md bg-white px-4 py-2 focus:not-sr-only focus:fixed focus:top-3 focus:left-3 focus:z-50 focus-visible:outline-2 focus-visible:outline-offset-2">
        Ir para o conteúdo
      </a>
      <aside className={cn("sticky top-0 hidden h-dvh shrink-0 flex-col border-r border-[#152c4a] bg-[#081a34] text-white lg:flex", collapsed ? "w-18" : "w-60")}>
        <div className={cn("flex min-h-18 items-center border-b border-white/10", collapsed ? "justify-center px-2" : "px-5")}>
          <Brand compact={collapsed} />
        </div>
        <div className={cn("flex-1 overflow-y-auto py-5", collapsed ? "px-2" : "px-3")} id="panel-desktop-navigation">
          <NavigationLinks {...nav} compact={collapsed} />
        </div>
        {ready && (
          <div className="border-t border-white/10 p-2">
            <button type="button" aria-expanded={!collapsed} aria-controls="panel-desktop-navigation"
              aria-label={collapsed ? "Expandir barra lateral" : "Recolher barra lateral"} title={collapsed ? "Expandir barra lateral" : undefined}
              onClick={() => setCollapsed((value) => !value)} className={cn(sidebarItem, collapsed && "justify-center px-0")}
            >
              {collapsed ? <PanelLeftOpen className="size-[18px]" aria-hidden="true" /> : <PanelLeftClose className="size-[18px]" aria-hidden="true" />}
              {!collapsed && <span>Recolher menu</span>}
            </button>
          </div>
        )}
      </aside>
      <div className="min-w-0 flex-1">
        <header className="sticky top-0 z-20 border-b border-slate-200 bg-white">
          <div className="mx-auto flex min-h-18 max-w-7xl items-center justify-between gap-2 px-4 sm:gap-4 sm:px-7">
            <div className="flex min-w-0 items-center gap-2 sm:gap-3">
              {ready && (
                <Dialog.Root open={mobileOpen && ready} onOpenChange={setMobileOpen}>
                  <Dialog.Trigger render={<Button variant="ghost" size="icon" className="size-11 shrink-0 transition-colors lg:hidden" />} aria-label="Abrir navegação">
                    <MenuIcon size={21} aria-hidden="true" />
                  </Dialog.Trigger>
                  <Dialog.Portal>
                    <Dialog.Backdrop className="fixed inset-0 z-40 bg-slate-950/55" />
                    <Dialog.Popup className="fixed inset-y-0 left-0 z-50 flex w-72 max-w-[calc(100vw-2.5rem)] flex-col bg-[#081a34] text-white outline-none">
                      <Dialog.Title className="sr-only">Menu do painel</Dialog.Title>
                      <div className="flex min-h-18 items-center justify-between gap-2 border-b border-white/10 px-4">
                        <Brand />
                        <Dialog.Close render={<Button variant="ghost" size="icon" className="size-11 shrink-0 text-white transition-colors hover:bg-white/10 hover:text-white focus-visible:ring-cyan-300" />} aria-label="Fechar navegação">
                          <X size={20} aria-hidden="true" />
                        </Dialog.Close>
                      </div>
                      <div className="flex-1 overflow-y-auto overscroll-contain px-3 py-5">
                        <NavigationLinks {...nav} onNavigate={() => setMobileOpen(false)} />
                      </div>
                    </Dialog.Popup>
                  </Dialog.Portal>
                </Dialog.Root>
              )}
              {clinic && ready && !choosingClinic ? (
                <>
                  <div className="hidden size-9 shrink-0 place-items-center rounded-lg border border-slate-200 bg-slate-50 text-[#24518a] sm:grid">
                    <Building2 size={17} aria-hidden="true" />
                  </div>
                  <div className="min-w-0">
                    <p className="truncate text-[11px] font-medium leading-tight text-slate-600">Clínica atual · {clinic.role}</p>
                    <p className="truncate text-sm font-semibold" title={clinic.name}>{clinic.name}</p>
                  </div>
                  {canSwitchClinic && <Button type="button" variant="outline" onClick={onChooseClinic} className="hidden min-h-11 transition-colors md:inline-flex">Trocar clínica</Button>}
                </>
              ) : <span className="truncate text-sm font-semibold">{currentPath === "/agencia" ? "Área da agência" : "Área de trabalho"}</span>}
            </div>
            <div className="shrink-0">
              {ready && email && <AccountMenu email={email} onSignOut={onSignOut} />}
              {signingOut && <p role="status" className="text-sm text-slate-600">Saindo…</p>}
            </div>
          </div>
        </header>
        {children}
      </div>
    </div>
  );
}
