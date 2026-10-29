# Android Native Lab

A monorepo for independently written **native Android applications**. The GitHub repository is currently public; do not commit credentials or proprietary binaries.

## First app: LayerForge

**LayerForge** is an experimental, clean-room Android FFF slicer. It targets **feature parity with Creality Print 7.3** over multiple releases, but is **not** a Creality product, a Creality Print port, or a feature-complete slicer today. The Windows installer is not bundled, decompiled, copied, or redistributed. The reference is the public user-facing feature set and official documentation.

### What actually works in the current alpha source (0.5.0)

- Native Kotlin + Jetpack Compose Android shell (minimum Android 8.0, primarily modern arm64 devices).
- Android Storage Access Framework STL and basic Wavefront OBJ geometry import (binary/ASCII STL and triangulated OBJ polygons, capped at 60 MiB; OBJ material/color and assemblies are not supported).
- Rotatable, zoomable wireframe preview (sampled mesh, up to ~2,000 triangles shown), model scale, XYZ rotations and XY position that modify slicing G-code.
- Offline save/reopen of LayerForge-specific compressed .lfproj projects including model bytes, slicer parameters and model transformation; not an interoperable 3MF format.
- G-code material-length and volumetric estimates plus idealized motion-time estimate, excluding acceleration, heating and firmware macros. Editable generic build dimensions and travel speed.
- Editable persistent layer height, extrusion width, wall count, infill %, top/bottom layer count, extrusion speed, bed/nozzle temperatures, retraction length/threshold and fan percentage.
- **Offline device-local C++ slicing**, cross-sectional contour tracing, basic insets for perimeters, linear infill and solid top/bottom fill.
- G-code generation to local cache, interactive layer-by-layer XY toolpath preview, and explicit export via Android's save-file picker.
- Real C++ unit tests executable on Linux/macOS/Windows with CMake.

### Critical alpha limitations

The G-code is **not ready for unsupervised printing**. The offset and scanline geometry algorithms are intentionally basic and not safe for all topology; no robust self-intersection resolution, retraction, seam selection, fan profiles, pressure advance, supports, bridges, acceleration, multi-extrusion or firmware-specific start/end macros yet. The toolpath display is not a full 3D simulation. The built-in **generic** 220×220×250 mm profile is not an official K1C profile and has **not been validated on a real printer**. There is no printer connectivity yet. Never assume an untested file is safe simply because it exported.

## Second app: Digitaler Schulranzen

**Digitaler Schulranzen** is the tablet/S-Pen school application currently tracked at **V15.0.7**. Its source, Unraid sync backend, current UI reference screenshots, project idea, implemented state, architecture notes and open items are stored together under [`apps/digitaler-schulranzen/`](apps/digitaler-schulranzen/).

The project is an independent Android Gradle application using package `de.millione.schulranzen`. The documentation under [`apps/digitaler-schulranzen/docs/`](apps/digitaler-schulranzen/docs/) records the product idea, the features already implemented and the remaining work, including the current S-Pen writing/rendering and eraser quality status.

## Repository structure

```text
apps/layerforge/              Android Gradle application + native JNI slicing engine
native-tests/                 Host-side C++ slicer tests and CLI
.github/workflows/            Native tests and Android build CI
scripts/create-github-repo.ps1 Creates an owned private GitHub repository and pushes project
scripts/build-debug.ps1       Build an Android APK on Windows
scripts/run-native-tests.ps1  Host-side CMake tests on Windows
docs/FEATURE_PARITY.md        Feature comparison with Creality Print 7.3
docs/ARCHITECTURE.md          Technical structure and engineering standards
AGENTS.md                      Parallel Codex agent roles and integration rules
```

## Open and build the Android app

1. Install **Android Studio**, Android SDK 35, Android NDK and CMake (SDK Manager), and JDK 17.
2. Open this **repository root** in Android Studio; Gradle should sync.
3. Build Android app `:apps:layerforge:assembleDebug` and install on a physical arm64 device or x86_64 emulator.
4. Load a watertight ASCII or binary STL, adjust parameters, slice locally, export .gcode to a device file.

Alternatively in a command shell: `./gradlew :apps:layerforge:assembleDebug` (Windows: `gradlew.bat :apps:layerforge:assembleDebug`). CI does not require local installation.

### Native C++ verification (no Android SDK required)

```sh
cmake -S native-tests -B native-tests/build
cmake --build native-tests/build
ctest --test-dir native-tests/build --output-on-failure
```

Inspect test CLI: `native-tests/build/layerforge-cli example.stl output.gcode`.

## GitHub repository

Repository: [theubyte/Android-Native-Lab](https://github.com/theubyte/Android-Native-Lab). Clone with `git clone https://github.com/theubyte/Android-Native-Lab.git`. The initial creation helper is retained for reference and is not needed now that the repository exists.

## No source reuse / licensing

All current app and slicing engine source in this repository is original code authored for this project. The external product's public feature descriptions may be used to test expected behavior. Creality Print's code is AGPL-3.0, but **none of its source files or binary assets are included** in this package. Additional libraries require a separate documented license review. Copyright/trademark ownership of Creality-related names remains with their respective owners. No open-source license has been selected for our code yet. Public visibility does not grant a license to copy or redistribute it.

## Roadmap

See [feature parity matrix](docs/FEATURE_PARITY.md). All unchecked features remain to be implemented and verified; **do not infer completeness from buttons or UI labels**.
