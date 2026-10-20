# Repli Keyboard privacy

Repli has no servers. The only network destinations are OpenAI (for AI features, under your own account) and, if the build is configured and you leave it on, Firebase Crashlytics for anonymous crash reports. This note says exactly what leaves the phone, when, and what stays.

## On the phone, always

The English suggestion and autocorrect engine runs on the device with a bundled dictionary. The adaptive model learns words, short word sequences and the emoji you insert only when you commit text in eligible English fields — never in password, email address, URL, no-suggestions or incognito fields. Touch-aware ranking keeps key positions for the current word in memory only. The learned model is bounded, Keystore-encrypted, stored in the no-backup directory, and can be turned off or deleted under **Settings → Typing → Adaptive learning**.

Chat profiles (names and persona choices) live in app preferences. Persona guides and example replies, recent notification senders, learned writing styles, pending captures and **chat memory** use Keystore-encrypted, no-backup storage. Chat memory is the approved turns of each saved chat, at most 120 per chat; it is used to give later replies continuity and to describe your writing style. Removing a chat deletes its memory. Screen frames are memory-only and erased after review or generation.

The notification listener (opt-in) reads posted notifications from major chat apps, but only WhatsApp/Telegram one-to-one conversations feed reply context. The guided-capture overlay observes only the foreground package and never inspects content, gestures, types or sends. Voice guidance uses the on-device recognizer; audio is never stored or uploaded.

## Your ChatGPT account

When you tap **Continue with ChatGPT**, your browser opens OpenAI's sign-in page. Repli registers itself with OpenAI as a locally hosted app and receives an OAuth token for your account; Repli never sees your ChatGPT password. The token, a refresh token, the ID token and the registration ID are stored Keystore-encrypted on the phone and are sent only to `auth.openai.com` (to refresh or revoke) and `api.openai.com` (as the Authorization header of AI requests). Signing out revokes the refresh token and deletes them from the phone; you can also disconnect Repli in ChatGPT settings → Usage. Repli keeps your email address and display name from the ID token to show who is signed in.

If you use an OpenAI API key instead, it is stored the same way and sent only to `api.openai.com`.

## What is sent to OpenAI, and when

Every request goes directly to `api.openai.com` with `store: false` (OpenAI's instruction not to retain the request). Nothing is sent until you tap the action.

- **Reading a chat** (after you tap *Suggest replies* and allow capture): the cropped chat screenshot(s), up to four. OpenAI returns the visible messages, which you review before anything else happens.
- **Generate replies** (after you review and approve): the reviewed chat text, any direction you added, up to 24 earlier approved turns from this chat's on-device memory, up to five of your own earlier messages as style examples, a short style summary, the selected persona's name, guide and up to three examples, your reply intent, and your current local date/time with time zone (so greetings fit the hour). Contact names, app names, chat IDs and screenshots are **not** part of this request.
- **Rewrite**: the text in the message box (up to 600 characters), the chosen tone and your style cues. No chat context, no memory, no screenshots.
- **Choosing a model**: a request for the list of models your plan offers.

Requests are billed to your ChatGPT plan (with any cap you set in ChatGPT settings → Usage) or to your API key. Repli stores nothing about the content of these requests beyond the on-device chat memory described above, and keeps only token counts in its local Diagnostics.

## Crash reports

If the build is configured with Firebase and you leave the setting on, Crashlytics receives the stack trace, device model, OS version and app version when the keyboard crashes. Typed text, captured chats, suggestions, learned words and account identifiers are never attached, and nothing is sent from debug builds. Turn it off under **Settings → Repli → Crash reports**.

## Everything else

The keyboard inherits other FlorisBoard features, such as clipboard history and extension management, with their own settings and storage. No part of Repli sends typed text to a Repli server, because there is none.

Source code and issue reports: [Hoossayn/florisboard-repli](https://github.com/Hoossayn/florisboard-repli). Repli Keyboard is based on FlorisBoard; upstream information is at [florisboard.org](https://florisboard.org/).
