# Conversation Rendering Guidelines

## Goals
* Provide a single, reusable dialogue renderer so every module emits identical NPC chat lines.
* Keep the API independent of Citizens/NPC tooling so quests, scripted cutscenes, and other systems can reuse it.
* Enforce formatting rules (colours, prefixes, numbering) automatically to avoid string drift between teams.
* Ensure every conversation entry point respects the shared `CooldownRegistry` so player/NPC timers are enforced consistently across runtime, Velocity, and menu flows.

## Standard Line Format
Each dialogue line renders as:
```
&e[NPC] <DisplayName> &7[step/total]: &f<message>
```

* `&e[NPC]` — constant yellow prefix.
* `<DisplayName>` — NPC name already colourised by its definition (e.g., `&6Rhea`).
* `&7[step/total]` — light grey 1-based step counter across the filtered dialogue lines.
* Message body — white (`&f`) by default, but allows inline MiniMessage/legacy codes for emphasis (`&o`, `&c`, etc.).

Example: `&e[NPC] &6Rhea &7[1/3]: &fWelcome to the lobby!`

## API Surface
Expose a lightweight builder in a shared module (e.g., `common-api/dialogue`):

```java
Dialogue conversation = Dialogue.builder()
    .id("tutorial.greeter")
    .cooldown(Duration.ofSeconds(5)) // maps to CooldownSpec before registry acquisition
    .lines(List.of(
        DialogueLine.of("&fWelcome to Fulcrum!", ctx -> !ctx.hasMetNpc()),
        DialogueLine.of("&fGrab a kit at the vendor to start.")
    ))
    .callbacks(DialogueCallbacks.builder()
        .onStart(ctx -> questService.flagOpened(ctx.playerId(), "tutorial.greeter"))
        .onComplete(ctx -> rewards.grant(ctx.playerId(), RewardTokens.TUTORIAL))
        .build())
    .build();
```

Key pieces:
* `DialogueLine` holds `message`, optional `predicate` (whether to include the line for this player), and optional `actions`.
* `DialogueCallbacks` (start/advance/complete) let quest logic mutate state without touching the renderer itself.
* `DialogueContext` exposes player UUID, audience handle, NPC id, quest state, etc. The renderer increments `step/total` after filtering lines by predicate.
* `Dialogue.builder().cooldown(Duration)` feeds into `CooldownSpec.rejecting(...)` so `DialogueService` can call `registry.acquire(key, spec)` automatically—duplicates while a player is still in range are rejected instead of silently restarting.
* `Dialogue.builder().cooldownGroup(String groupId)` (default: the dialogue id itself) controls which affinity/family the cooldown key belongs to. If NPC id and dialogue id match, do nothing; otherwise override so related dialogues share the same timer.
* `Dialogue.builder().timeout(Duration)` configures the inactivity timeout (default 30 s). When players stop advancing lines past this limit, the next interaction lazily cancels the stale session and starts over, ensuring AFK players don’t block the key forever.

## Rendering Flow
1. Caller invokes `DialogueService.startConversation(Player player, Dialogue dialogue)`.
2. `DialogueService` resolves the shared cooldown key (see below) and calls `CooldownRegistry.acquire`. Rejects short-circuit with the remaining duration so callers can display the same cooldown messaging as other systems.
3. Once accepted, `DialogueService` evaluates predicates, builds the filtered line list, and sends the first line using Adventure components (so MiniMessage + hex colours work). Legacy strings pass through `LegacyComponentSerializer`.
4. Players advance via custom UI (click, chat input, etc.); `DialogueService` emits subsequent lines until completion, calling callbacks at each phase.
5. When the conversation ends (either naturally, via distance cancellation, or because the inactivity timeout tripped), `DialogueService` immediately clears the cooldown key. Fresh conversations can start right away once the player moves out of range or re-engages after the timeout.

## Cooldown Integration
* **Canonical Key:** `CooldownKey.of("conversation", dialogue.id(), playerId, npcIdOrNull)` where `npcIdOrNull` is empty for ambient conversations. This key is registered in the union-find map so aliases like `/play` or menu-driven prompts can link to the same timer.
* **Spec:** Default `CooldownSpec.rejecting(Duration.ofSeconds(5))` unless a dialogue overrides it; use `.cooldown(Duration)` or `.cooldownSpec(...)` if you need extend-on-acquire semantics.
* **Timeout:** `.timeout(Duration.ofSeconds(30))` (or custom) drives the inactivity timer. If the player doesn’t advance within this window, the next interaction cancels the old session (`DialogueCancelReason.TIMEOUT`), clears the cooldown key, and restarts from the first line.
* **Usage:** Every entry point (NPC interactions, runtime `/play` command, Velocity prompts, menu buttons) must call `registry.acquire(key, spec)` before invoking `DialogueService`. If rejected, surface a unified message via `GenericResponse.ERROR_COOLDOWN`.
* **Linking Examples:**  
  - NPC Interaction → Conversation: link `("npc","interaction",playerId,npcId)` to the conversation key so either flow respects the same expiry.  
  - Weapon Ability → Ultimate Dialogue: when combat actions should delay ultimate briefings, extend both keys inside the combat handler using the shared registry.

### Grouping / Family Keys
* Each conversation resolves a **group id** that determines how cooldowns fan out to related systems. By default we assume `groupId = dialogue.id()` which also matches the NPC id for the majority of scripts.
* Override the grouping via `Dialogue.builder().cooldownGroup("tutorial.family")` (or a lambda if we need context-aware ids). This lets multiple NPCs or UI entry points map to the same canonical key without manually calling `registry.link`.
* When syncing to command families (`/play <family>`), use the same group id string so the registry naturally enforces the union (e.g., `groupId = familyName`), keeping NPC prompts and command invocations in lock-step.

## Integration Notes
* NPC behaviors call into `DialogueService` from `onInteract` or `passive` without knowing about Citizens.
* Distance/visibility gates should call `DialogueService.cancel(playerId, DialogueCancelReason.DISTANCE)` as soon as the player leaves the bubble so the cooldown entry clears immediately rather than waiting for natural expiry.
* Quest scripts or cutscenes can also call the same service when no NPC is present; they simply pass a pseudo NPC id/display name for the prefix.
* Because the renderer lives outside `runtime/npc`, we can reuse it inside Velocity chat flows or other services without dragging in the entire NPC toolkit.
* To ensure usage “across the board,” modules that previously maintained ad-hoc cooldown maps (NPC orchestrators, Velocity `/play`, menu buttons) now depend on the shared registry service supplied by `common-api`. Wiring this dependency into each module’s injector guarantees all dialogue-capable features follow the same cooldown semantics automatically.
