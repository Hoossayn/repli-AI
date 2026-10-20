# AI policy

Repli Keyboard is a fork of FlorisBoard. FlorisBoard's own [AI policy](https://github.com/florisboard/florisboard/blob/main/AI_POLICY.md) prohibits AI-generated code in contributions to FlorisBoard. **That policy does not apply to this repository, and this repository does not claim to follow it.** Much of Repli's code, tests and documentation were written with AI assistance, reviewed and tested by the maintainers. Contributions back to FlorisBoard upstream must follow FlorisBoard's rules, not these.

## What we ask of contributors

1. You may use AI tools to write code, tests, documentation and messages for this repository.
2. You are accountable for what you submit. Read it, understand it, run it. A pull request you cannot explain will be closed.
3. Say so. Mention AI assistance in the pull request description (one line is enough). It helps reviewers know where to look harder.
4. Licensing is your responsibility: only submit work you can license under this project's [Apache License 2.0](LICENSE), and attribute sources where attribution is due.
5. Never paste user data, chat screenshots, tokens or keys into an AI tool while working on this project, and never commit any of them.

## What the app does with AI

The keyboard's AI features call OpenAI under the user's own ChatGPT plan or API key; there is no Repli server in between. What is sent, and when, is documented in the [privacy note](docs/repli-privacy.md). Changes that send more data, send it sooner, or send it anywhere else need to update that note in the same pull request.
