# Dependencies and implementation references

Versions are pinned; do not replace them with floating versions.

| Component | Initial selection | Purpose |
|---|---|---|
| Android Gradle Plugin | 9.5.0 | Android application build |
| Gradle | 9.6.0 | Minimum version required by AGP 9.5 |
| JDK | 17+ (Codespaces image uses JDK 21) | Build runtime |
| Android SDK | API 36 / Build Tools 36.0.0 | Compile and package |
| AndroidX Media3 | 1.11.1 stable | ExoPlayer, MediaSession, player UI |
| NewPipe Extractor | Upstream-pinned commit `e8f89145a830b4d6cae32412007f24ea8b36c73a` | Community-maintained catalogue/stream extraction adapter |

## Reusable projects to study

- [NewPipe](https://github.com/TeamNewPipe/NewPipe): complete open-source Android application and reference integration.
- [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor): extractor library used by NewPipe.
- [LibreTube](https://github.com/libre-tube/LibreTube): alternative Android frontend; useful for studying catalogue and playback boundaries.
- [Grayjay](https://grayjay.app/): multi-source/plugin-oriented application; study its provider abstraction and product patterns.

These are references, not instructions to copy their UI or assume their current implementations never break.

## Dependency and licensing checkpoint

The NewPipe Extractor project is licensed under **GPL-3.0-or-later**. This repository currently includes it as a build dependency for investigation and integration work. Before distributing Nexus, review the applicable licence obligations and choose a compatible release/licensing plan. Do not assume a dependency's licence is irrelevant because it is obtained from JitPack.

The NewPipe app's current dependency catalog pins the extractor to the commit above and includes a comment explaining that a commit pin can be more reliable than a tag when JitPack artifacts are unavailable. Re-check the upstream pin when upgrading.

## Build automation

- [android-actions/setup-android](https://github.com/android-actions/setup-android): installs Android command-line tools and SDK packages in CI.
- [gradle/actions](https://github.com/gradle/actions): installs a pinned Gradle distribution and caches build dependencies.
- GitHub Actions builds a debug APK on pushes and pull requests.

## Not added yet

No cloud backend, account provider, analytics SDK, ads SDK, or API secrets are included. These would add operational costs, privacy obligations, and unnecessary complexity before the core playback path is verified.
