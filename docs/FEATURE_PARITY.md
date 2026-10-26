# Creality Print 7.3 parity roadmap (2026-10-26)

Reference: official Creality Print 7.3 release notes and Creality interface/quick-start documentation. This is a **functional benchmark**, not reverse engineering or copying proprietary UI/source. The term "parity" means every listed user-facing feature must be implemented, exercised with real tests and recorded as passed.

| Area | Capability | LayerForge alpha | Next acceptance criterion |
|---|---|---|---|
| Platforms | Standalone Android app | **Source implemented**, Android build not yet executed | Build+install on real arm64 Android device |
| Files | Binary/ASCII STL | **Implemented**, native host tests only | Golden corpus, malformed-file fuzz tests |
| Files | 3MF / CXPRJ projects | Not implemented | Save/reopen full project without loss |
| Files | OBJ+color; STEP/STP CAD | Not implemented | Reliable import with units/color/material |
| Workbench | Interactive 3D viewport | Sampled wireframe implemented | Depth shading, model picking, gesture/multiplate |
| Workbench | Move/rotate/scale/mirror/clone/arrange | Not implemented | Correct transform persisted in projects |
| Workbench | Cut/split/multiple plates/text/emboss | Not implemented | UI and mesh edit tests |
| Profiles | Editable speed, infill, shell, temperatures | Basic parameters implemented | Full searchable profiles, import/export, validation |
| Profiles | Official model/printer/nozzle/filament presets | Not implemented | Versioned K1C and other verified presets |
| Engine | Cross section, basic walls, linear infill | **Implemented**, simple watertight models | Automated G-code geometry comparisons |
| Engine | Robust polygon clipping, thin walls, Arachne-like variable widths | Not implemented | Complex geometry stress corpus and quality measures |
| Engine | Top/bottom, bridges, cooling, support/towers | Basic top/bottom only | Manufacturable bridging/support structures |
| Engine | Adaptive layers, ironing, seams, fuzzy skin | Not implemented | Tests for visual/functional print outcomes |
| Engine | Organic/tree/paint-on supports | Not implemented | Toolpath review on overhang test prints |
| Engine | Gyroid, honeycomb, cubic, TPMS/adaptive infill | Not implemented | Pattern geometry suite |
| Engine | ZAA anti-aliasing, slope-aware top contours (7.3) | Not implemented | Reference shape and path validation |
| Engine | Adaptive TPMS infill (7.3) | Not implemented | Gradient-density slice benchmarks |
| Multi-material | Color painting, color OBJ mapping, CFS slot assignment | Not implemented | End-to-end color assignment/preview |
| Multi-material | Gradient/full color mixing (7.3) | Not implemented | Mix ratios and hardware validation |
| Multi-material | Mixed nozzle diameters, multi-nozzle K3 (7.3) | Not implemented | Tool change and diameter geometry tests |
| Multi-material | Purge reuse for internal infill (7.3) | Not implemented | Waste/contamination constraints tested |
| Preview | Full layer toolpath/speed/flow/time/material preview | **Basic per-layer 2D extrusion/travel visualization implemented** | Full 3D, speed/flow/time view and semantic comparisons |
| Device | LAN/USB/WAN print delivery; Klipper/Moonraker | Not implemented | Authenticated printer handshake and dry-run |
| Device | Status, live camera, manual control and cancel | Not implemented | Integrations with real/test printers |
| Cloud | Account, Creality Cloud model/print workflow | Not implemented | Vendor-compatible documented APIs/permission |
| Calibration | Temperature, flow, retraction, pressure advance, VFA | Not implemented | Profile-specific calibration G-code |
| AI | Q&A, slicing recommendations and risk detection | Not implemented | Consent, measurable accuracy, opt-in services |
| Packaging | Local APK builds, CI, update strategy | Initial CI config only | Reproducible signed releases and update process |

## Mandatory engineering standards

1. Feature only gets **implemented** status when real input/output code exists, not when a button exists.
2. Feature only gets **verified** status when applicable native tests, Android instrumentation and relevant printer validation pass.
3. Explicit clean-room development: build against documented behavior; never include the Windows installer in version control.
4. Add fixtures for thin walls, holes, nested polygons, non-manifold meshes, sloped surfaces, multi-material objects and G-code dialect differences.
5. No fabricated cross-platform compatibility, "AI quality", or printing safety claims.

## Milestone sequence

- **M0 – Local Core:** STL inspection, simple geometry sectioning, model wireframe, basic G-code export. (Source exists, host test passes.)
- **M1 – Reliable Geometry:** robust graph repair, boolean clipping, offsets, overhang paths, bridge/skin/variable layer, toolpath preview, performance benchmarks.
- **M2 – Professional Slicer:** profile database, advanced infill, seam/ironing/supports, 3MF/OBJ/STEP and multi-plate editor.
- **M3 – Real Printers:** validated K1C/K-series presets, Moonraker/LAN transport, machine discovery, print monitor, camera and cancellation.
- **M4 – Multi-material 7.3:** CFS/full-color/gradient/multi-nozzle, smarter purge, adaptive TPMS and ZAA.
- **M5 – Completeness:** full reference acceptance suite, localization, accessibility, stability, signed releases and updated documentation.
