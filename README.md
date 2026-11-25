# forge-agent-bridge

A headless [Forge](https://github.com/Card-Forge/forge) network client behind a local HTTP JSON API. It joins a Forge host's online lobby as an ordinary remote player, and a program drives the seat through HTTP: connect, ready up, draft, and play a match. [forge-mcp-server](https://github.com/olaservo/forge-mcp-server) uses it to give an AI agent a seat.

This project is independent. It is not affiliated with, endorsed by or sponsored by Card-Forge, Wizards of the Coast or Hasbro. Magic: The Gathering is a trademark of Wizards of the Coast LLC.

## How it fits together

The bridge is a small jar that runs beside Forge's own jar on the Java classpath. It uses Forge's classes and does not change them, so no custom Forge build is needed. It does not contain Forge.

One bridge is one seat. It listens on `127.0.0.1` only and has no authentication, so any program on the machine can drive the seat. A web page cannot: a request with an `Origin` header, or with a `Host` other than `127.0.0.1`, `localhost` or `[::1]`, gets 403.

## Requirements

- Java 17 or newer. To build from source, a JDK and Maven.
- A Forge desktop build: its `forge-gui-desktop-<version>-jar-with-dependencies.jar` and the folder that holds Forge's `res`.
- The bridge calls Forge's internal classes, which change between versions. Use a jar built against the Forge version you will run, and have the host run that version too.

## Download

The [releases page](https://github.com/olaservo/forge-agent-bridge/releases) has a jar for each Forge version it was built against. The file name ends with that version: `forge-agent-bridge-0.1.10-forge-2.0.16.jar` goes with the [Forge 2.0.16](https://github.com/Card-Forge/forge/releases/tag/forge-2.0.16) desktop release. Each release's notes say what was run against it.

For another Forge version, build from source.

## Build

```
mvn -B package -Dforge.jar=<absolute path to the Forge jar-with-dependencies>
```

This writes `target/forge-agent-bridge-0.1.10.jar`.

## Run

From the folder that holds Forge's `res` (in a source checkout, `forge-gui`), with the bridge jar first on the classpath. Use `;` between the jars on Windows and `:` elsewhere.

```
java -Djava.awt.headless=true -Dfile.encoding=UTF-8 -Dio.netty.tryReflectionSetAccessible=true -Xmx2048m -cp "<bridge jar>;<forge jar>" forge.agent.AgentMain --port 36744
```

It prints `AGENT_BRIDGE_READY port=36744` once the card database has loaded, about 30 to 40 seconds.

With `--exit-with-parent` the bridge exits when the process that started it ends, including when that process is killed. A program that spawns the bridge passes it so the bridge does not stay behind on its port. It watches its direct parent. When a shell starts `java`, that is the shell. With no parent left when it starts, it exits at once.

## Auto-pass

Forge has a per-player setting that passes priority for a player who has no available actions. The bridge sends it to the host for its seat, in one of three modes:

- `opponent-turns` (default): on during opponents' turns, off during the seat's own turn and from the opponent's end step. Off that early, the setting has reached the host before the seat's main phase. The host then asks in that end step, and the bridge presses OK itself when the host lists no card the seat could play (`actionable` is empty), so the seat is asked there only when it could act. The summary shows `waiting: true` for a prompt the bridge is answering.
- `always`: on for the whole game.
- `off`: never.

Start with `--auto-pass <mode>`, or switch during a game with `POST /game/yield {"autoPass": "<mode>"}`. The game summary reports `autoPass` (the mode) and `autoPassNow` (what the host was last told).

`always` is not safe. Forge decides "no available actions" by looking at the hand, the battlefield and flashback cards (`forge.ai.AvailableActions`). It does not look at other graveyard casts or abilities, and it does not count mana from a graveyard ability. In a live game on 2026-11-11 that skipped the seat's main phase while it could cast a card with disturb from its graveyard. On an opponent's turn the same gap can still hide an instant-speed play from the graveyard. Declaring attackers and blockers and answering choices are separate prompts and are still asked in every mode.

## Stops and yields

A Forge client tells the host where its player wants to stop, and the host passes priority for that player everywhere else while the stack is empty. The bridge sends the host its stops when a game opens, as the desktop client does:

- `default`: Forge's desktop defaults. On the seat's own turn it is asked in main 1, after blockers are declared, and in main 2. On an opponent's turn it is asked at beginning of combat, after attackers are declared, after blockers are declared, and in the end step. Auto-pass above removes those when the seat has nothing to play.
- `all`: every phase, as the bridge did up to 0.1.9. Start with `--stops all`.

The game summary reports `stops` as `default`, `all` or `custom`. A phase with something on the stack is always asked, and so are declaring attackers and blockers and answering choices.

A seat changes its stops with `POST /game/stops {"own": [...], "opponent": [...]}`, as a player does by clicking a phase label on the desktop. Each list is the whole set of steps for the seat's own turn or for an opponent's, by the names `phase` has in the game summary (`UPKEEP`, `DRAW`, `MAIN1`, `COMBAT_BEGIN`, `COMBAT_DECLARE_ATTACKERS`, `COMBAT_DECLARE_BLOCKERS`, `COMBAT_FIRST_STRIKE_DAMAGE`, `COMBAT_DAMAGE`, `COMBAT_END`, `MAIN2`, `END_OF_TURN`, `CLEANUP`). A list left out stays as it is. `GET /game/stops` and the answer to the POST give `stops`, `own` and `opponent`.

- In a game each change goes to the host at once. If the seat has a priority prompt up, with an empty stack, in a step that is no longer a stop, the host passes that prompt, as it does on the desktop.
- `own` must include `MAIN1`. Without it the seat is not asked in its first main phase and cannot play a land.
- The sets stay for later games in the same bridge process.
- `AgentStopsTest` removes two stops on the host's turn, keeps one, and adds the seat's own upkeep.

`POST /game/act` uses Forge's yields in three places:

- `ok` on the priority prompt, with the seat's own spell or ability on top of the stack, yields until the stack is empty. The host passes for the seat until then and stops when an opponent adds to the stack or attacks. `"once": true` passes one time.
- `endTurn` yields until the end of the turn, which is what the End Turn button does. It works whatever the Cancel button reads. With `ok` on another prompt, such as declaring attackers, `"endTurn": true` answers that prompt and yields the rest of the turn. With an action that casts a spell or activates an ability, `"endTurn": true` yields the rest of the turn once the seat's own item is on the stack, so the spell resolves and the turn ends in one call. An action the host refuses puts nothing on the stack and ends nothing.
- `"pass": true` on any action passes afterwards if the seat's own spell or ability is then on top of the stack, with the same yield.

A yield never passes over an opponent's response. When an opponent adds to the stack or attacks the seat, the yield ends and the host asks the seat before anything resolves. After `endTurn` on its own turn the bridge also switches auto-pass on, as after the End Turn button, so the host then asks only a seat with something it could play. `AgentInterruptTest` plays both against a host that counters the seat's spell.

The bridge sets the yield preferences it relies on after the stops: a yield ends on an opponent's spell or attack, and the host does not offer its own yield suggestions. The other yield preferences come from the Forge profile on the bridge's machine.

## Auto-join

Off by default. With `--auto-join <host>:<port>` the bridge checks the host's port every 5 seconds and sits down when a lobby appears there: it connects as `--auto-join-name` (default `Agent`), sends the deck given by `--auto-join-deck` and readies. The deck is a path to a `.dck` file, or a name looked up in Forge's `constructed` and `network-events` deck folders, which belong to the user's profile. A relative path is read from the folder that holds `res`, so a deck that ships with Forge works on any install, for example `res/quest/precons/Azorius Advance.dck`. Without a deck it takes the seat and stays unready, so the caller picks a deck and readies.

```
... forge.agent.AgentMain --port 36744 --auto-join 127.0.0.1:36743 --auto-join-name Claude --auto-join-deck "My Deck"
```

- It takes the first Open slot, whoever that slot was meant for. Leave it off when another player is joining.
- It tries once per lobby. After the seat ends, or the host refuses it because the lobby is full or a match is running, it waits until the host's port has closed before it tries again. A seat that leaves is not put back while the same lobby stays up.
- It readies only when it sits down. After a match the host unreadies every seat, and readying for the next match is the caller's choice.
- A seat taken through `POST /connect` or `POST /sit` is left alone.
- Each check while a lobby is up and the bridge is not seated opens and closes a connection, which the host logs as an unregistered peer.

## Hosting without a Forge client

`MatchHost` is a Forge host with no window and no seat of its own, in the same jar. Every slot is open, so the players are whoever joins: bridge seats, and people in a Forge desktop or mobile client who connect to this machine's address and the port. It starts a match once every slot is taken and each seat is ready with a deck, and when the players have left the match it waits for them to ready up again.

```
java -Djava.awt.headless=true -Dfile.encoding=UTF-8 -Dio.netty.tryReflectionSetAccessible=true -Xmx2048m -cp "<bridge jar>;<forge jar>" forge.agent.MatchHost --port 36743 --seats 2
```

It prints `MATCH_HOST_READY port=36743 games=1` once the card database has loaded. `--seats` is the number of players, 2 by default. `--limited` puts the lobby in Limited mode, for 40-card decks. `--games` is the games per match: 1 by default, or 3 or 5 for the best of that many. Without it the host sets 1 whatever the machine's Forge preference says, and Forge's own default there is 3. `--exit-with-parent` works as it does for the bridge.

Forge 2.0.16 takes the games per match from the preference `UI_MATCHES_PER_GAME` of the machine that hosts (`HostedMatch.getDefaultRules`). The host sets it for its own process and does not save the preferences file. After a game that does not decide the match, the next game starts when every seat has sent `continue`, as it does when each player of a Forge client presses Next Game.

- It listens on every network interface, as a Forge host does, and has no password. Anyone who can reach the port can take a seat.
- The first player to join takes slot 0, which a desktop host keeps for itself, and Forge marks that player's chat lines "(Host)".
- A deck that breaks the lobby's deck rules is refused with a chat line that names the seat and the problem, and the match waits for another deck. Forge's "enforce deck legality" preference turns this off, as it does for a desktop host.
- It hosts plain Constructed or Limited matches. Variants, a format restriction and draft events are not set up; `DraftHost` in the test sources hosts a draft.
- Run against bridge seats only. A Forge client as a player, and a player who disconnects during a game, have not been run.

## HTTP API

All routes answer JSON. Errors are `{"error": "..."}` with a 4xx or 5xx status.

- `GET /status`: everything about the seat. `GET /wait?since=<revision>&timeout=<seconds>` blocks until the revision passes `since`. While Forge's client retries a dropped connection, `connection.connected` stays true and `connection.reconnectState` is `RECONNECTING`. Once it stops, or the host closes the connection, `connected` is false, `closeReason` says why and `lobby` holds only `mySlot: -1`.
- `POST /connect {host, port, name}`, `POST /disconnect`
- `POST /sit {host, port, name, deck}`, `{..., deckFile}` or `{..., dck}`: connect, wait for the slot and, with a deck, send it and ready. `deck` is a name, `deckFile` a path to a `.dck` file, `dck` the file's text. Answers like `/status`, with the slot assigned. An unknown deck fails before a seat is taken.
- `POST /lobby/ready {ready}`, `POST /lobby/avatar {index}`, `POST /lobby/name {name}`, `POST /lobby/deck {name}` or `{dck}`. `name` renames the seated player, so a seat taken by auto-join under a placeholder name can be named later.
- `POST /chat {text}`. `chat` in the status holds the last 20 lines as `{from, text}`, with `from` null for a line from the host itself, and `chatCount` counts them all.
- `GET /draft/pack`, `POST /draft/pick {card}`, `GET /draft/pool`. `packNumber` and `pickNumber` count from 1. `secondsLeft` is sent while a pick is waiting and the host set a pick clock. The pool of a sealed event arrives the same way, with no packs before it.
- `POST /draft/deck {main, lands, landSet}`: builds the deck for the match from the pool, sends it to the host and saves it over the pool file, as the desktop's deck editor does. `main` lists pool card names, one entry per copy. `lands` maps basic land names to counts. `landSet` is the set code for the lands and defaults to the set most of the pool comes from. Cards left in the pool become the sideboard. `problem` is null when the deck is legal for Limited. `lobby.myDeck` in the status then shows the deck's name and sizes, because the host does not send a seat's deck back.
- `GET /game`, `GET /game/log?since=<n>`, `POST /game/act {action, id, color}`, `POST /game/decide {id, choices, text}` or `{id, amounts}`, `POST /game/yield {autoPass}`, `GET /game/stops`, `POST /game/stops {own, opponent}`, `GET /game/sideboard`
- `POST /game/act` also takes `once`, `endTurn` and `pass` (see Stops and yields), `"pay": "auto"`, which presses Auto when the action leads to the prompt that pays a cost, and `at`, a `promptCount`. When `at` is not the current count the action is not sent and the answer has `stale: true`.
- `messageCount` counts the game's `messages` and `logCount` the lines of the game log, so a caller can ask for what is new. Forge's client keeps one game log through the games of a match, so in a later game the log starts with the earlier games' lines. `logStart` is the number of lines it held when this game opened. `gamesOpened` goes up each time the host opens a game for the seat: a new match, the next game of a match, and a game sent again after a reconnect. A second match in the same lobby is game 1 of its match like the first, and `gamesOpened` tells them apart. In `GET /game` each player has `mana`, the mana floating in its pool by colour letter (`W`, `U`, `B`, `R`, `G`, `C`), and each stack item has `targets`, cards as `name#id`, players by name, and another stack item by the name of its source. `stack` is listed from the top down.

What the seat is asked comes in four forms in the game state:

- `buttons` and `prompt`: answer with the `ok` and `cancel` actions.
- `promptCount` goes up with each prompt the host sends, so a prompt with the same text as the last one is still seen as new. `waiting` is true from the end of one of the seat's inputs until the host's next prompt, while `prompt` reads "Waiting for ...". `hasPriority` is false while `waiting` is true, since the host's player view can say the seat holds priority for a moment after it passed. Once `gameOver` is true, `prompt` reads "Game over. Winner: <name>" and `waiting`, `priorityPrompt` and `hasPriority` are false. `priorityPrompt` is true while the prompt is the one that offers priority. The host sends prompts and button labels as text in its own language, and the bridge recognises that prompt and the End Turn button by comparing with Forge's English text, as Forge's own clients compare with their translation. Taken from Forge's code (`InputBase`, `VPrompt`); a host in another language has not been run.
- `actionable`: the host's list of cards that can act at this prompt: playable cards with priority, creatures that can attack or block while declaring, mana sources while paying. Each entry is `id` and `name`. It is Forge's highlight hint, so a card missing from it may still be usable.
- `selection`: the prompt accepts cards (`cards`, answered with `selectCard`) or players (`players`, answered with `selectPlayer`). A prompt that takes a spell or ability on the stack as a target, as a counterspell's does, lists those under `stack` with the `id` of the stack entry, answered with `selectStack`. Forge 2.0.16 asks a network client to pick these in its stack view, and a card click at such a prompt is refused. `chosen` lists what is picked so far, and the attacker being blocked while declaring blockers. Picking the card that reaches `max` answers the prompt without OK.
- `pendingDecision`: answer with `/game/decide`. While it is there, `prompt` is the decision's prompt and `waiting` and `priorityPrompt` are false. `choices` holds option indices. For kind `assign`, which splits combat damage or a divided amount, send one number per option in `amounts`, or the same as comma-separated `text`; they must add up to the decision's `amount`. A split that breaks a rule is replaced by the bridge's `default` and a line in `messages` says so. Like `/game/act`, it answers once the host has reacted and gone quiet.

The bridge forgets the game when its connection ends: on `/disconnect`, when the host goes away, and while Forge's client is reconnecting. `inGame` is then false, the prompt is empty and `messages` is cleared. When the client gets its seat back the host sends the game again.

`concede` first cancels an open prompt that selects cards or targets. Forge's target prompt asks again without end once the game is over, and the host's game thread died with a StackOverflowError when a seat conceded with one open.

`match` in the game state says where the match stands: `gamesPerMatch`, `game` (this game's number, from 1), `wins` (games won, by player name) and `over`. `over` is set as a game ends and is true when that game decided the match. All four are what the host sends a client: the game view's `NumGamesInMatch`, `NumPlayedGamesInMatch` and `MatchOver`, and the score text of `GameEventGameOutcome`, which the game log also shows. `wins` is null when that text does not name the players of the game.

Once `gameOver` is true the actions `quit`, `continue` and `newMatch` send Forge's `NextGameDecision`. `continue` starts the next game once every seat has sent it. When the match is over it is refused, as Forge's clients disable Next Game then. `quit` from any seat ends the match for everyone.

The three are refused while a game is running, with an error that says nothing was sent. Forge's host acts on them whenever they arrive (`HostedMatch.addNextGameDecision`): during a game `quit` ends the match for everyone with no result, and `continue` ends the game and starts another once every human player has sent it, which is at once when the other seats are AI. Forge's clients offer them only on the screen that ends a game. A seat that wants to leave a running game concedes first, or disconnects. `continue` and `newMatch` are sent once per finished game, and a second one is refused the same way.

A seat can sideboard between the games of a match. From the second game on, Forge's host asks each player for its main deck while it sets the game up, after every player has continued (`Match.prepareAllZones`, `ProtocolMethod.sideboard`). It sends the main deck and the sideboard as they stand and waits for the answer with no time limit, so the bridge answers at once and the seat gives its change beforehand, with `continue`:

- `match.sideboarding` is true after a game that did not end the match, when the seat has a sideboard or a main deck above the format's minimum. `GET /game/sideboard` then has `open: true`, `main` and `sideboard` as card names with counts, and the limits `minMain` and `maxSideboard`. The sideboard's entries also say what each card is.
- `POST /game/act {action: "continue", in, out}` moves the cards named in `in` from the sideboard to the main deck and those in `out` the other way, one entry per copy. The change is checked first: each card has to be where the seat says it is, the main deck keeps at least `minMain` cards (the format's minimum, or its size now if that is smaller) and the sideboard at most `maxSideboard` (15 in Constructed, no maximum in Limited). These are the limits Forge's host checks. A change that fails is an error and the seat has not continued.
- `continue` without `in` and `out` keeps the deck. After the seat has continued, a `continue` with other cards replaces its choice and one without withdraws it. Neither goes to the host again, which holds the seat's answer. A `continue` that would change nothing is refused.
- The host takes any card a client names in its answer, so the bridge answers only with the seat's own cards. If the decks the host sends do not allow the change after all, the deck stays as it is and a line in `messages` says so.
- The bridge takes the seat's deck at the start of a match from the deck it sent to the lobby, and after that from what the host sends with each sideboard prompt. A seat whose deck the bridge did not send has `sideboarding` false after the first game.

## Test

The tests host a draft and a match in-process and drive the bridge over HTTP. They need Forge's `res` folder as the working directory and they open Forge's network port, so do not run them while a Forge host is running on the same machine.

```
mvn -B test -DskipTests=false -Dforge.jar=<forge jar> -Dforge.home=<folder that holds res>
```

They also record the bridge's responses under `target/agent-fixtures`, which forge-mcp-server uses as test fixtures.

`DraftHost` in the test sources is a host with no window, for drafting against agent seats without the desktop client. It hosts a booster draft from one set, starts it when the expected seats are ready, fills the pod with Forge's AI drafters, and then starts the match with the deck its own AI seat drafted. It takes `--port`, `--block`, `--set`, `--pod`, `--timer`, `--seats` and `--no-match`. Run it from the folder that holds `res`, after `mvn test-compile`:

```
java -cp "target/test-classes;target/classes;<forge jar>" forge.agent.DraftHost --port 36743 --block "Magic 2011" --set M11 --pod 4 --timer 60
```

`AgentSoakTest` is off unless asked for. It plays games with random decks on both seats and compares the bridge's game state with the host's own at every prompt. It fails if they disagree, if the host waits on the seat while the bridge shows nothing to answer, or if a card offered in `selection` is refused. It writes `target/agent-soak/report.json`, which also lists the host inputs and decision kinds the games reached. A game takes two to four minutes.

```
mvn -B test -DskipTests=false -Dtest=AgentSoakTest -Dagent.soak.games=3 -Dforge.jar=<forge jar> -Dforge.home=<folder that holds res>
```

## License

GPL-3.0, the same licence as Forge, because the bridge is built on Forge's code. Copyright (c) 2026 Carbon-Based Content LLC. See [LICENSE](LICENSE).
