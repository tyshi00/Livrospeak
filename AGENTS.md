# Livrospeak Repository Guide

## Vision

Livrospeak is a minimalist audiobook player designed for the Light Phone.

Its purpose is simple:

**Help people listen to stories.**

Livrospeak is not trying to recreate Libby.
It is not trying to become a podcast app, ebook reader, media player, or library management application.

Everything in this repository should support a calm, distraction-free listening experience.

---

# Product Philosophy

Every feature should make Livrospeak simpler.

Whenever multiple solutions are technically correct, prefer the one that:

- requires fewer taps
- introduces less UI
- uses fewer dependencies
- has fewer moving parts
- is easier to maintain
- feels more like the Light Phone

Never optimize for feature count.

Always optimize for calmness.

When in doubt, leave it out.

---

# User Experience

The application consists of three primary experiences:

- Books
- Player
- Settings

Books from every enabled source appear together in one unified library.

The user should not need to think about where a book came from.

Source-specific configuration belongs only in Settings.

The player should be identical regardless of source.

---

# Sources

Livrospeak currently supports or intends to support:

- Local audiobook folder (primary)
- Libby (optional)
- RSS feeds (optional)

Local audiobooks are the primary source.

Libby and RSS extend the library—they should never define the application's architecture.

---

# Light Design Language

Livrospeak should feel like a native Light Phone application.

It should never feel like a standard Android application wearing a Light Phone skin.

When implementing UI:

## Typography

- Prefer the existing Light SDK typography primitives.
- Use `LightText` with the appropriate `LightTextVariant`.
- Do not create custom typography scales.
- Avoid decorative typography.
- Create hierarchy through existing text variants rather than custom styles.

## Layout

- Use the Light grid system (`gridUnitsAsDp`) for spacing.
- Prefer generous whitespace.
- Maintain a consistent vertical rhythm.
- Keep layouts simple and easy to scan.
- Prefer scrolling over dense information.

## Components

- Prefer existing Light SDK components whenever possible.
- Extend existing Livrospeak or Light components before creating new ones.
- Use `lightClickable` where appropriate.
- Avoid unnecessary custom controls.

## Color

- Use the existing Light theme.
- Do not hardcode colors.
- Do not introduce accent colors.
- Avoid decorative borders, gradients, and shadows.

## Motion

- Motion should communicate state only.
- Avoid unnecessary animations.
- Fast interactions are preferred over elaborate transitions.

## Information Hierarchy

Display only information that helps the user choose a book or continue listening.

Avoid metadata that does not directly improve the listening experience.

Hierarchy should come from typography and whitespace rather than decoration.

## Whitespace

Whitespace is a design element.

Do not fill empty space unnecessarily.

A screen with fewer elements is often the better screen.

## Consistency

Identical actions should always look identical.

Maintain consistent spacing, typography, navigation, and interaction patterns throughout the application.

## Restraint

Every feature increases complexity.

Before implementing any feature, ask:

- Does this solve a real listening problem?
- Can this require fewer taps?
- Can it use less UI?
- Is it consistent with the Light Phone philosophy?

If the answer is uncertain, prefer not to implement the feature.

---

# Current Architecture

This repository is currently a single-module Kotlin Android application using Jetpack Compose.

The existing Libby integration is working and should be preserved while the rest of the application evolves around it.

Current major components:

- `LibbyLightApplication`
- `LibbyWebPlayer`
- `LibbyBridge`
- `PlayerDebugScreen`
- `LoanItem`
- `PlayerState`

The UI package already contains reusable Light-style Compose components.

Future work should migrate toward those components rather than creating parallel UI systems.

---

# Architectural Direction

The application should evolve toward source-independent domain models.

Introduce abstractions only when they support an implemented feature.

Avoid speculative architecture.

Preferred future concepts include:

- Audiobook
- AudiobookId
- AudiobookSource
- PlaybackController
- LibraryRepository

The UI should consume these abstractions rather than interacting directly with Libby, RSS, or filesystem code.

---

# Libby Integration

The current hidden Libby playback architecture is the project's greatest technical asset.

Preserve it.

Specifically:

- Maintain the application-scoped persistent WebView.
- Preserve document-start JavaScript injection.
- Preserve the WebMessage bridge.
- Preserve direct `BIF.objects.spool` playback control.
- Preserve DOM fallbacks where necessary.
- Do not replace Libby playback with direct media extraction.
- Do not expose the Libby interface during normal operation.

The Libby WebView should only become visible for:

- initial setup
- account restoration
- authentication
- diagnostics
- recovery

Libby should become one playback source rather than the application's architecture.

---

# Naming

User-facing text should refer to the application as **Livrospeak**.

Internal package names and legacy identifiers may remain until a dedicated migration.

Avoid unnecessary renaming of working code.

---

# Dependencies

Prefer the existing Android SDK and Light SDK.

Do not introduce third-party libraries unless there is a clear technical justification.

Smaller dependency graphs are preferred.

---

# Privacy

Never log or expose:

- passwords
- cookies
- authentication tokens
- Libby setup codes
- signed URLs
- authorization headers
- personally identifying library information

Sanitize URLs before logging.

Never dump browser storage or session contents.

---

# Change Discipline

Before modifying playback:

- inspect existing call sites
- preserve current behavior
- keep changes narrowly scoped

Do not rewrite working systems without explicit instruction.

Keep every change incremental.

Do not commit unless explicitly requested.

When possible:

- run `./gradlew assembleDebug`
- preserve a clean working tree
- verify Libby playback before handing work back

---

# Decision Rule

When several solutions are technically correct:

Choose the solution that is:

- simpler
- smaller
- calmer
- easier to understand
- easier to maintain
- more consistent with the Light Phone philosophy

The interface should disappear.

The user's attention should remain on the story, not on the application.
