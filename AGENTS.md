# Codex / Parallel Agent Guide

This is an Android monorepo. The goal is independent native Android apps, starting with LayerForge, a locally running Android FFF slicer.

## Required rules

- **Never** claim that source-only work is production ready, APK-tested, or printer-safe without evidence.
- **Never** copy code, assets, UI screenshots, printer presets, or decompiled binaries from Creality Print. Implement documented behavior independently. Dependencies need explicit provenance and license review.
- **Never** commit the Creality Print `.exe`, credentials, device tokens, or signed release keys.
- Never remotely send or auto-start print jobs until protocol and firmware-specific validation tests are implemented.
- Keep engine C++17 portable; Android-specific code belongs in `jni_bridge.cpp` and Kotlin packages.
- Tests are mandatory for geometry work: run `cmake -S native-tests -B native-tests/build && cmake --build native-tests/build && ctest --test-dir native-tests/build --output-on-failure`.
- All PRs must state: implemented feature, test evidence, known limitations, and revised parity matrix row(s).

## Suggested non-overlapping agent ownership

- **Agent A – Native UI:** `apps/layerforge/src/main/java/` – Compose layout, 3D workbench, interaction, accessibility, projects, Android performance. Must not edit slicer geometry.
- **Agent B – Slicer:** `apps/layerforge/src/main/cpp/slicer.*`, `native-tests/` – robust mesh/paths, infill, support generation, G-code planner. Must not edit Compose UI.
- **Agent C – Device/Profiles:** propose new `apps/layerforge/src/main/java/.../device` and `.../profiles` modules – printer protocols, versioned vendor profiles, discovery, validated K1C macros. Must not embed tokens.
- **Agent D – QA/Integration:** `.github/workflows/`, acceptance/golden fixtures, performance testing, cross-module review. Integrates only after relevant owners finish and validates Android release builds.

## Immediate next tasks

1. Replace basic polygon offset with a robust geometry engine and adversarial fixtures for holes, thin walls and self-intersections.
2. Implement independent postprocessor with configurable start/end G-code macros, retractions, thermal control and firmware dialects.
3. Build installable APK in GitHub Actions and verify on Android phone (minimum 8.0, arm64 primary).
4. Add 3MF project import/export and persistent printer/filament presets.
5. Add model transform tools and proper 3D viewport picking/shading.
6. Perform supervised K1C validation **only** after the G-code output passes structural and macro checks.
