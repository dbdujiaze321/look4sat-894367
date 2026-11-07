# Contributing

This is a fork of Look4Sat. Changes that are generic improvements to the app
belong upstream first; send them to
[rt-bishop/Look4Sat](https://github.com/rt-bishop/Look4Sat) unless they only make
sense here.

## Before you start

Open an issue describing what is wrong and how you noticed. For a bug, the most
useful thing you can attach is the element set (two TLE lines) and the station
coordinates that produced the bad number, because almost every prediction bug
reduces to "this input produces this output".

## Build

JDK 21 and Android SDK platform 37 are required. The Gradle wrapper handles the
rest.

```bash
./gradlew :app:assembleDebug
./gradlew :core:domain:test :core:data:test
./gradlew :app:assembleRelease
```

`./gradlew detekt` runs the static analysis configuration in `config/`.

## Code layout

```
app/                application module, manifest, R8 rules
core/domain/        pure JVM: orbital mechanics, models, formatting, policies
core/data/          Room, network sources, repositories, injection
core/presentation/  shared Compose components, theme, strings, navigation keys
feature/*           one screen group per module (passes, satellites, radar, ...)
build-logic/        convention plugins and the version catalog wiring
```

The rule that keeps this testable: **nothing in `core/domain` may import
Android**. Orbital maths, merge rules, CSV and ICS writers, and the staleness
model live there, which is why they are covered by JVM tests instead of device
tests.

## Tests

`core/domain` and `core:data` run on the JVM; no device or emulator is needed.

When you fix a bug in the orbital kernel or in a parser, add the failing case as
a test first. Two properties are easy to break and hard to notice:

* results must not depend on query order (there is a test for it);
* a parser that "works on my file" may drop everything after a blank line or a
  quoted comma. Both have happened here.

Values that come from a real device or a real element set are better than
synthetic fixtures, but never commit a real station position. Use the neutral
fixtures in the tests.

## Style

* Kotlin official formatting, four-space indent, 120 column limit.
* Comments explain *why*, especially where the obvious implementation is wrong.
  A comment that restates the code is worse than no comment.
* No abbreviation-by-removal in public names; `PassMetrics`, not `PM`.
* User-facing strings go in `core/presentation/src/main/res/values/strings.xml`
  and `values-zh/strings.xml`. Both, always.

## Commits

One logical change per commit. The subject line is imperative and under 72
characters; the body explains the failure mode being fixed, not the diff.

## Licence

Contributions are accepted under GPL-3.0-or-later, the licence of the project.
By opening a pull request you confirm you wrote the code or that it is
compatible with that licence.
