# Prompt Inicial — Plan Omega

```text
Quero criar o mod **Plan Omega** (`mod_id: planomega`, pacote `com.typeomega.planomega`, licença MIT) em `/home/raishxn/MineProjects/PlanOmega`, um production planner visual em grafo/fluxograma in-game (client-side only) inspirado no conceito do GTNewHorizons/PlanNH, projetado do zero para o ecossistema **TypeOmega** (Minecraft 1.20.1, Forge 47.4.23, Parchment 2023.09.03).

As logos e ícones já estão salvos em `assets/logo/` (`plan_omega_logo_4x.png`, `plan_omega_icon_256.png`, etc.).

## Propósito Duplo do Mod

1. **Para os jogadores (in-game planner):**
   - Canvas 2D infinito com zoom, pan, seleção múltipla, grupos colapsáveis, sticky notes e fios conectando entradas/saídas (itens, fluidos e energia/EU).
   - Integração com **EMI** (`1.1.13`) e/ou **JEI** (`15.20.0.115`) para buscar e adicionar receitas diretamente para o canvas a partir de qualquer item/fluido (produtores e consumidores), com suporte completo a **GregTech CEu Modern (`7.5.3`)** (receitas de máquina, `EUt`, tier/voltagem, overclock, circuito programado, temperatura de bobina, cleanroom, chance outputs, catalisadores e fluidos), além de recipes vanilla/custom/KubeJS/GTNA/TypeOmega.
   - **Balanceador de Produção (Linear Programming):** usar `ojAlgo` (ou solver LP equivalente em Java puro) para equilibrar cadeias complexas — incluindo loops fechados/reciclagem — permitindo fixar metas de saída ou entrada e calculando automaticamente número de máquinas, overclock, EU/t total, demanda líquida de matérias-primas e excesso de subprodutos.
   - Painel de resumo (Summary), salvamento local em arquivo, compartilhamento via clipboard e exportação **Mermaid** (compatível com GuideME / MkDocs).

2. **Para o autor + IA (engenharia de receitas e química real no modpack):**
   - **Exportação estruturada para IA (JSON / Markdown-Mermaid enriquecido):** exportar qualquer grafo/cadeia selecionada contendo IDs exatos das receitas (`gtceu:...`, `kubejs:...`, `typeomega:...`), insumos/produtos com quantidades e taxas calculadas, `EUt`, duração, circuito programado, temperatura, catalisadores e subprodutos não consumidos — pronto para análise por IA.
   - **Diagnóstico automático da cadeia (Audit / Friction Flags):** identificar e destacar no canvas e no JSON exportado:
     - Subprodutos órfãos/descartados (oportunidade de reciclagem ou cadeia química secundária);
     - Gargalos severos de tempo, EU/t ou excesso de máquinas;
     - Passos intermediários redundantes (candidatos a simplificação) ou saltos artificiais/genéricos (candidatos a expansão usando **química real** com balanço de massa e rotas industriais plausíveis).
   - **Modo Sandbox / "What-If" (Rascunho de Receitas):** permitir criar e editar nós hipotéticos diretamente no canvas (alterar reagentes, estequiometria, catalisadores, tempo, EUt ou inserir novas etapas químicas propostas pela IA), rodar o balanceador sobre a rota simulada e exportar o **diff (`atual` vs `proposto`)** para gerar scripts KubeJS ou código Java no TypeOmega.

## Stack & Referências no Workspace

- **Alvo:** Minecraft `1.20.1`, Forge `47.4.23`, Java 17, Gradle (pode usar como referência de setup `~/MineProjects/GTIA/core/gradle.properties` e `build.gradle`).
- **Mods do pack:** `gtceu` (`7.5.3`), `ldlib` (`1.0.52.a`), `emi` (`1.1.13`), `jei` (`15.20.0.115`), `ae2` (`15.4.10`), `gtna` (`0.6.2`), `typeomega`, `guideme`.
- **UI:** usar renderização nativa Forge/Screen leve e responsiva (ou LDLib onde fizer sentido), mantendo o mod **100% client-side** (sem exigir instalação no servidor).
- **Idiomas:** suportar `en_us`, `pt_br` e `zh_cn` desde o início.

## Como começar (Fase 1 — MVP Funcional)

Antes de sair codando tudo, analise a arquitetura ideal e me proponha o plano em fases:
1. **Fase 1 (Skeleton + Modelo de Dados + Canvas Base):** projeto Gradle 1.20.1 compilando, `mods.toml` usando `assets/logo/`, keybind abrindo tela com canvas 2D infinito (pan/zoom/grid), modelo imutável/serializável de `PlanGraph` (`RecipeNode`, `VirtualRecipeNode`, `StickyNote`, `GroupNode`, `Edge`).
2. **Fase 2 (Extração de Receitas EMI/JEI + GTCEu):** adaptador de receitas lendo RecipeManager + GTCEu (`GTRecipe`, `EUt`, circuitos, fluidos, chances) + busca e inserção rápida no canvas.
3. **Fase 3 (Solver `ojAlgo` + Overclock + Summary):** balanceamento LP de fluxos e loops, painel lateral de totais líquidos e gargalos.
4. **Fase 4 (Modo Autor + Export IA + Sandbox "What-If"):** criação/edição de nós hipotéticos no canvas, auditoria automática de subprodutos/complexidade e exportação JSON/Mermaid/Diff para trabalhar junto com a IA no balanceamento e química real do TypeOmega.

Comece criando a estrutura inicial do projeto Gradle e o plano detalhado antes de implementar a Fase 1.
```
