# Dependencies and implementation references

Versions are pinned; do not replace them with floating versions.

| Component | Initial selection | Purpose |
|---|---|---|
| Android Gradle Plugin | 9.5.0 | Android application build |
| Kotlin Gradle Plugin | 2.4.10 | Explicit Kotlin compiler, aligned with the Kotlin standard-library metadata resolved by the build |
| Gradle | 9.6.0 | Minimum version required by AGP 9.5 |
| JDK | 17+ (Codespaces image uses JDK 21) | Build runtime |
| Android SDK | API 36 / Build Tools 36.0.0 | Compile and package |
| AndroidX Media3 | 1.11.1 stable | ExoPlayer, MediaSession, player UI, DASH, HLS and SmoothStreaming playback |
| NewPipe Extractor | Upstream-pinned commit `e8f89145a830b4d6cae32412007f24ea8b36c73a` | Community-maintained catalogue/stream extraction adapter |
| OkHttp | 4.12.0 | Extractor HTTP transport |
| Kotlin coroutines | 1.10.2 | Off-main-thread extraction |
| Coil | 3.5.0 | Thumbnail and artwork loading |

AGP's built-in Kotlin compiler is disabled for this project and the explicit Kotlin Gradle Plugin is used. The first CI run exposed a mismatch between the compiler bundled with AGP and Kotlin 2.4 standard-library metadata. The external Kotlin plugin currently needs AGP's new DSL temporarily disabled (`android.newDsl=false`). Keep these compatibility switches documented and recheck them before upgrading AGP or Kotlin; do not remove them without a successful full CI run.

## Reusable projects to study

- [NewPipe](https://github.com/TeamNewPipe/NewPipe): open-source Android application and reference integration.
- [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor): extractor library used by NewPipe.
- [LibreTube](https://github.com/libre-tube/LibreTube): alternative Android frontend; useful for studying catalogue and playback boundaries.
- [Grayjay](https://grayjay.app/): multi-source/plugin-oriented application; study its provider abstraction and product patterns.

These are references, not instructions to copy their UI or assume their current implementations never break.

## Dependency and licensing checkpoint

The NewPipe Extractor project is licensed under **GPL-3.0-or-later**. This repository currently includes it as a build dependency for investigation and integration work. The repository has not selected or added an application-level licence. Before distributing Nexus, review the applicable licence obligations, source-distribution requirements, and compatibility with the rest of the application. Do not assume a dependency's licence is irrelevant because it is obtained from JitPack.

The NewPipe app's current dependency catalog pins the extractor to the commit above and includes a comment explaining that a commit pin can be more reliable than a tag when JitPack artifacts are unavailable. Re-check the upstream pin when upgrading.

The extractor uses unofficial service-facing interfaces. Upstream changes can break search or stream resolution without a change in Nexus; keep the integration isolated and test it against public content.

## Build automation

- [android-actions/setup-android](https://github.com/android-actions/setup-android): installs Android command-line tools and SDK packages in CI.
- [gradle/actions](https://github.com/gradle/actions): provides Gradle build caching for the checked-in Wrapper.
- GitHub Actions builds and tests the debug APK on pushes to `main` and pull requests targeting `main`.

## Not added yet

No cloud backend, account provider, analytics SDK, ads SDK, API secrets, local media database, or persistent queue are included. These would add operational costs, privacy obligations, or complexity before the core playback path is verified.
