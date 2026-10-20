# Contributing to Repli Keyboard

Thanks for your interest. Repli is a FlorisBoard fork with its own reply, rewrite and suggestion features; the keyboard itself is FlorisBoard's. Please keep contributions in English and follow the [code of conduct](CODE_OF_CONDUCT.md) and the [AI policy](AI_POLICY.md).

## Where things live

- `app/src/main/kotlin/dev/patrickgold/florisboard/repli/` — Repli: capture, review, replies, rewrite, personas, chats, diagnostics.
- `repli/openai/` — the device-direct OpenAI client (prompts, response parsing, failure mapping) and, under `auth/`, Sign in with ChatGPT. These files have no Android imports apart from the storage and screen adapters, so they run under plain JVM tests.
- `ime/nlp/latin/repli/` — the on-device suggestion and autocorrect engine (lexicon, n-gram prior, adaptive model, key-proximity correction).
- `app/settings/repli/` — Repli's screens; `app/settings/account/` — onboarding.
- Everything else is FlorisBoard. Prefer fixing keyboard-level bugs upstream unless they come from Repli's changes.

## Building and testing

Android SDK 37 and JDK 17. `./gradlew :app:assembleDebug` builds; `./gradlew :app:testDebugUnitTest` runs the unit tests, which include the OpenAI client, the OAuth flow and the suggestion engine. No configuration files or keys are needed. Crash reporting is optional (see the README).

Test AI features on a device with your own ChatGPT plan or API key. Anything that changes what is sent to OpenAI must update [docs/repli-privacy.md](docs/repli-privacy.md) in the same pull request.

## Pull requests

- One change per pull request, with a short description of what and why.
- Add or update a unit test when the change is testable without a device; say what you checked on a device when it isn't.
- Never include tokens, API keys, screenshots of real chats, or logs containing them. `_logs/` and `_transfer/` are git-ignored for a reason.
- Mention AI assistance in one line, per the AI policy.

## Reporting bugs

Open an issue with the device, Android version, Repli version (Settings → About), the steps, and — for reply or capture problems — the **Diagnostics** card from Settings → Repli and the relevant `RepliOpenAI:` / `RepliChatGpt:` lines from logcat. Those lines contain only closed failure codes, never content.

## Translations and keyboard layouts

Repli currently ships English QWERTY only. Layout and translation work for the keyboard itself belongs upstream in FlorisBoard; Repli pulls it in by merging upstream.
