# forge-agent-bridge

A headless [Forge](https://github.com/Card-Forge/forge) network client behind a local HTTP JSON API. It joins a Forge host's online lobby as an ordinary remote player, and a program drives the seat through HTTP: connect, ready up, draft, and play a match. [forge-mcp-server](https://github.com/olaservo/forge-mcp-server) uses it to give an AI agent a seat.

This project is independent. It is not affiliated with, endorsed by or sponsored by Card-Forge, Wizards of the Coast or Hasbro. Magic: The Gathering is a trademark of Wizards of the Coast LLC.

## How it fits together

The bridge is a small jar that runs beside Forge's own jar on the Java classpath. It uses Forge's classes and does not change them, so no custom Forge build is needed. It does not contain Forge.

One bridge is one seat. It listens on `127.0.0.1` only and has no authentication.

## Requirements

- A JDK, 17 or newer, and Maven to build.
- A Forge desktop build: its `forge-gui-desktop-<version>-jar-with-dependencies.jar` and the folder that holds Forge's `res`.
- The bridge calls Forge's internal classes, which change between versions. Build it against the Forge version you will run, and have the host run that version too.

Built against Forge `2.0.16-SNAPSHOT` (upstream commit `bdcce440b7c`) and run on 2026-11-11 against a desktop host of the same build: connect, lobby, avatar, chat and disconnect. The Forge jar used was built from a fork that also carries these classes, with this jar first on the classpath. Not yet tried against a Forge release download.

## Build

```
mvn -B package -Dforge.jar=<absolute path to the Forge jar-with-dependencies>
```

This writes `target/forge-agent-bridge-0.1.0.jar`.

## Run

From the folder that holds Forge's `res` (in a source checkout, `forge-gui`), with the bridge jar first on the classpath. Use `;` between the jars on Windows and `:` elsewhere.

```
java -Djava.awt.headless=true -Dfile.encoding=UTF-8 -Dio.netty.tryReflectionSetAccessible=true -Xmx2048m -cp "<bridge jar>;<forge jar>" forge.agent.AgentMain --port 36744
```

It prints `AGENT_BRIDGE_READY port=36744` once the card database has loaded, about 30 to 40 seconds.

## Auto-pass

By default the bridge turns on Forge's own "auto-pass when no actions are available" setting for its seat in every game, so the host passes priority for the seat when it has nothing it could play or activate. Declaring attackers and blockers and answering choices are still asked. Start with `--no-auto-pass` to leave it off, or switch it during a game with `POST /game/yield {"autoPassNoActions": false}`. The game state reports `autoPassNoActions` and `hasAvailableActions`.

## HTTP API

All routes answer JSON. Errors are `{"error": "..."}` with a 4xx or 5xx status.

- `GET /status`: everything about the seat. `GET /wait?since=<revision>&timeout=<seconds>` blocks until the revision passes `since`.
- `POST /connect {host, port, name}`, `POST /disconnect`
- `POST /lobby/ready {ready}`, `POST /lobby/avatar {index}`, `POST /lobby/deck {name}` or `{dck}`
- `POST /chat {text}`
- `GET /draft/pack`, `POST /draft/pick {card}`, `GET /draft/pool`
- `GET /game`, `GET /game/log?since=<n>`, `POST /game/act {action, id, color}`, `POST /game/decide {id, choices, text}`, `POST /game/yield {autoPassNoActions}`

## Test

The tests host a draft and a match in-process and drive the bridge over HTTP. They need Forge's `res` folder as the working directory and they open Forge's network port, so do not run them while a Forge host is running on the same machine.

```
mvn -B test -DskipTests=false -Dforge.jar=<forge jar> -Dforge.home=<folder that holds res>
```

They also record the bridge's responses under `target/agent-fixtures`, which forge-mcp-server uses as test fixtures.

## License

GPL-3.0, the same licence as Forge, because the bridge is built on Forge's code. Copyright (c) 2026 Carbon-Based Content LLC. See [LICENSE](LICENSE).
