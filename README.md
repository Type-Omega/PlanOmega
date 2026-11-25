<div align="center">
  <img src="assets/logo/plan_omega_logo_4x.png" alt="Plan Omega Logo" width="420" />

  # Plan Omega

  **Client-side visual production planner, LP production balancer, and recipe engineering sandbox for Minecraft 1.20.1 (Forge) & GTCEu Modern.**

  [![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-62B47A?style=flat-square)](https://www.minecraft.net/)
  [![Forge](https://img.shields.io/badge/Forge-47.4.23-1E2D3D?style=flat-square)](https://files.minecraftforge.net/)
  [![Java](https://img.shields.io/badge/Java-17-ED8B00?style=flat-square)](https://adoptium.net/)
  [![License: MIT](https://img.shields.io/badge/License-MIT-blue?style=flat-square)](LICENSE)
  [![Release](https://img.shields.io/github/v/release/Type-Omega/PlanOmega?include_prereleases&style=flat-square)](https://github.com/Type-Omega/PlanOmega/releases)

</div>

---

## Overview

**Plan Omega** (`planomega`) is a 100% **client-side** production chain planning and balancing mod built for complex modded packs such as **GregTech Community Edition Modern (GTCEu)**, **GTNA**, **Applied Energistics 2**, **JEI**, **EMI**, and **KubeJS**.

Design your factory visually on an infinite pan-and-zoom node canvas, solve exact item/fluid/EU rates with an embedded **ojAlgo Linear Programming (LP)** solver, inspect realistic multiblock machine/coil/parallel Bills of Materials, audit bottlenecks, and export directly to Mermaid diagrams, AI analysis reports, or KubeJS server scripts.

---

## Key Features

### Infinite Node Canvas & Productivity
- **Pan & Zoom Graph Editor**: Smooth viewport navigation, grid snapping, sticky notes, and collapsible `GroupNode` sub-plans.
- **Auto-Expand Ports**: Right-click any disconnected input/output port to search and auto-wire a matching producer or consumer recipe directly beside the port.
- **Auto-Connect & Loop Closing**: One-click connection of compatible `OUTPUT` ↔ `INPUT` ports (exact `ResourceKey` and `#tag` matching), with an option to close recycled byproduct/catalyst loops.
- **Box-Select, Copy/Paste & Group Templates**: Drag-select multiple nodes (`Shift` to add), `Ctrl+C` / `Ctrl+V` subgraph cloning with fresh IDs and preserved internal edges, and reusable group templates saved in `config/planomega/templates/`.

### GTCEu Realism, LP Solver & Chain Auditor
- **Embedded LP Solver (`ojAlgo`)**: Computes exact throughput rates (`items/s`, `mB/s`, `EU/t`), machine counts, net power, and external input/output balances across complex cyclic chains.
- **Per-Node Coil & Parallel Hatch Configuration**: Configure GTCEu coil tiers (`Cupronickel` → `Tritanium`) for EBF/Pyrolyse/Cracker bonuses and real parallel counts (`1x`, `4x`, `16x`, `64x`, `256x`), persisted with backward-compatible `PlanCodec` schema v2.
- **Machine & Block BOM**: Summary drawer and exporters group required machines by type/tier/coil and estimate hatches, buses, energy hatches, coils, and parallel hatches for multiblocks.
- **Chain Auditor**: Flags missing/excess inputs, power spikes, **Hatch / Throughput Bottlenecks** on heavily overclocked/parallelized nodes, and **Unconnected Byproduct / Void Risks**.

### Mod Integrations & Exports (All Optional)
- **GTCEu Modern / GTNA**: Extracts machine recipes, voltage tiers, chanced outputs (`OR` / `AND` / `XOR` / `FIRST`), `notConsumable` catalysts/fluids, `ResearchCondition`, Fusion `eu_to_start` tiering, and Assembly Line recipes.
- **JEI & EMI**: Quick recipe lookup and integration hooks without requiring either mod at runtime.
- **Applied Energistics 2 (`ae2`)**: Crash-safe `Ae2Bridge` (isolated behind `ModList.get().isLoaded("ae2")`) to read stored items/fluids and registered autocrafting patterns from an open/nearby ME System.
- **KubeJS Direct Sync**: Export sandbox recipe diffs directly to `kubejs/server_scripts/planomega_sandbox.js` (with `.bak` backup and overwrite confirmation) and copy `/kubejs reload server_scripts`.
- **Mermaid & AI Analysis Exporters**: Export full production graphs, BOMs, and audit summaries to Markdown/Mermaid or AI-ready prompts.

### Localization
- Full 100% parity across **English (`en_us`)**, **Português Brasileiro (`pt_br`)**, and **简体中文 (`zh_cn`)**.

---

## Default Controls

| Action | Shortcut |
|---|---|
| Open Plan Omega Canvas | `P` (configurable in Controls) |
| Copy / Paste Subgraph | `Ctrl+C` / `Ctrl+V` |
| Box-Select | Left-Click Drag (`Shift` to append) |
| Port Context Menu (Auto-Expand / Disconnect) | Right-Click Port |
| Pan / Zoom Canvas | Middle-Click or Right-Drag / Mouse Wheel |

---

## Maven Dependency (Developers)

Plan Omega is published to the **Type-Omega Maven** repository:

```groovy
repositories {
    maven {
        name = 'Type-Omega'
        url = 'https://type-omega.github.io/maven/'
    }
}

dependencies {
    compileOnly "com.typeomega.planomega:planomega:0.1.0"
}
```

---

## Building & Testing

```bash
# Run unit tests, compile, and build the mod JAR
./gradlew clean test compileJava jar --no-daemon

# Launch Minecraft client in dev with optional GTCEu / JEI / EMI runtime mods
./gradlew runClient -PplanomegaDevMods=true --no-daemon
```

The compiled JAR is output to `build/libs/planomega-0.1.0.jar` (bundles `ojAlgo` via Jar-in-Jar; all mod integrations are optional).

---

## Inspiration & Credits

**Plan Omega** was directly inspired by **[PlanNH](https://github.com/GTNewHorizons/PlanNH)** (created by **Sbancuz** and contributors for Minecraft 1.7.10 / GregTech: New Horizons), which pioneered the in-game flowchart-based production planner workflow. Plan Omega brings that spirit to Minecraft 1.20.1 (Forge) with an embedded `ojAlgo` LP solver, GTCEu Modern coil/parallelism realism, AE2 integration, and KubeJS sandbox exporting.

---

## License

Licensed under the [MIT License](LICENSE). Created by **Raishxn** (**Type-Omega**).
