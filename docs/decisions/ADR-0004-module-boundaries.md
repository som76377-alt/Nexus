# ADR-0004: Keep one Gradle module during foundation

- Status: Accepted for current stage
- Date: 2026-11-06

## Decision

Keep the existing `:app` Gradle module. Establish clean Kotlin package boundaries and interfaces first.

## Why

The project is still compact. Immediate multi-module conversion would add Gradle wiring, build time and dependency complexity before the new domain/data boundaries have proven stable.

## Revisit when

A second feature needs a reusable module or independent build lifecycle, and measured build/ownership costs justify the split.
