Security model: [SECURITY.md](./SECURITY.md)

Before creating or updating a PR, run `./gradlew autostyleCheck` and the relevant
tests and checks for the change. Checkstyle does not replace Autostyle. Fix any
formatting violations and rerun `autostyleCheck` successfully before pushing.
