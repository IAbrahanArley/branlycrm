# Landing interativa — evolução do briefing premium

## Escopo e origem

Evolução autorizada pelo briefing anexado nesta conversa, sobre a branch feat/landing-brand e worktree landing-brand. Mantém marca, menu ciano, fontes e stack existentes. Não muda o backend, as permissões nem o escopo funcional do MVP.

O briefing atual autoriza expressamente dados fictícios em demonstração e pede sombras, gradientes sutis e bento grid. Essas orientações substituem as restrições visuais da primeira proposta apenas na landing. Não há depoimentos, clientes reais, números de adoção ou resultados comerciais atribuídos ao Branly.

## Componentes

- Header: sticky ciano, âncoras, indicação da seção ativa e menu móvel.
- HeroSection: proposta de valor, CTAs locais e dashboard em perspectiva.
- DashboardPreview: cinco módulos demonstrativos; dados e métricas derivados do mesmo estado local.
- FeaturesBento: seis cards, incluindo agenda, relacionamento, oportunidades, indicadores e conceito de automação.
- PatientJourney: cinco momentos; observador de scroll atualiza a narrativa, com seleção manual acessível. Sticky somente no desktop/tablet para não sobrepor os textos no celular.
- RevenueSimulator: quatro premissas editáveis, cálculo puro separado e resultados animados.
- FinalCTA: leva à demonstração; não promete cadastro ou teste comercial indisponível.
- animations: observer de entradas, perspectiva limitada a ponteiro fino e contador com reduced motion.

A página estática permanece Server Component; apenas as ilhas interativas usam use client. Sem Motion/GSAP e sem novas dependências de aplicação. Prettier foi executado temporariamente para formatação, sem instalação no projeto. CSS premium reutiliza o CSS/tokens da primeira versão. Fonte continua auto-hospedada, sem chamada a provedor de fontes pelo navegador.

## Demonstração e honestidade

Marina A., Camila B., Luiza C. e Renata D. são exemplos fictícios, não pacientes ou clientes reais. As oportunidades usam valores em centavos e etapas locais. Mover pelo select ou por drag and drop recalcula a soma concluída e o gráfico. Busca tem estado vazio e Reiniciar restaura o exemplo. Nenhuma alteração é persistida.

Relacionamento mostra atividades ilustrativas; nenhuma avaliação é agendada. Automações são identificadas como conceito futuro e fora da entrega inicial. O switch apenas altera texto e estado no navegador, sem enviar mensagens ou criar tarefas reais.

A apresentação de um módulo no mockup não significa que ele esteja implementado no produto comercial. A disponibilidade é explicada na própria página e no FAQ.

## Simulador

Entradas: contatos mensais, conversão atual, ticket e conversão hipotética. A quarta entrada torna explícita a premissa que seria necessária para calcular uma diferença; o Branly não presume uma melhoria comprovada.

Receita bruta esperada = contatos × taxa/100 × ticket. Diferença = receita hipotética − receita atual. Valor zero e diferenças negativas são tratados. Ticket e receitas calculados em centavos; os números representam expectativa matemática e podem corresponder a frações de conversões. Custos, capacidade, impostos e cancelamentos não estão incluídos. Não há garantia de faturamento ou recomendação financeira.

## Checks executados

- Lint e TypeScript passaram.
- Build de produção Next.js no Docker passou.
- Quatro testes Node do cálculo passaram: base/diferença, cenário negativo, zeros e entradas inválidas/arredondamento.
- 25 verificações em navegador passaram: mover por select/drag, atualizar gráfico, busca/estado vazio, relacionamento, conceito de automação, reset, teclado, simulação positiva/zero/negativa, jornada, FAQ, âncoras, ausência de overflow em 320/390/768/1440, menu móvel e reduced motion.
- Screenshots reais em 1440/768/390 revisadas. Primeira inspeção identificou sobreposição móvel e textos pequenos; corrigidos.
- Inspeção usa allowances explícitas para gradientes, sombras, contornos e icon cards pedidos pelo usuário. A identificação de resultado acima do valor do simulador é mantida por ser rótulo de um cálculo, não elemento promocional. A falta de fotos/vídeo permanece deliberada; só existem marca e mockup conceitual, sem acervo fotográfico real fornecido.

## Limites e próximos passos

Versionada na branch feat/landing-brand; sem publicação ou merge nesta entrega. Prévia isolada em http://localhost:3001. Integração com cadastro/contratação só quando os fluxos reais existirem. Não há contato comercial inventado, cobrança ou formulário que simule envio. Core Web Vitals de produção não foram medidos; os testes locais não comprovam desempenho em todas as redes/dispositivos.

## Revisão final

A inspeção final de tablet e celular terminou com zero falhas e zero avisos, com as exceções estilísticas acima documentadas. Títulos em até duas linhas, sem sobreposição da jornada e sem textos públicos abaixo de 13 px. A UI demonstrativa preserva tamanhos compactos próprios de um painel; controles de toque foram ampliados no celular. As 25 verificações de navegador foram repetidas na imagem final e passaram.

Revisão visual interna: hierarquia consistente, marca preservada e mockup funcional no hero. A narrativa desktop é mais expressiva que a móvel, onde a prioridade é leitura sem cartões sobrepostos. Avaliação subjetiva: design 6,5; usabilidade 8,5; criatividade 7; conteúdo 8; ponderada 7,35/10. Sem acervo real, não há alegação de prova visual do produto comercial. Nenhuma nota representa avaliação de usuários.
