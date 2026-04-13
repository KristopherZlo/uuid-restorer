# UUID Restorer

UUID Restorer is a server-side Fabric mod for offline-mode dedicated servers and LAN/integrated worlds. It resolves a Mojang profile by nickname, swaps the joining player onto the premium UUID, restores signed skin data, and migrates save files from the offline UUID to the premium UUID.

## Compatibility

- Minecraft `1.21.8-1.21.11`
- Fabric Loader `0.17.3+`
- Fabric API `0.141.3+1.21.11`
- Java `21+`

## Where it runs

- Works on offline-mode dedicated servers
- Works in LAN/integrated worlds
- Passes through without changes on dedicated servers already running `online-mode=true`
- Does not require the mod on the client for normal gameplay

## What it does

- performs Mojang profile lookup asynchronously during login
- applies the premium UUID, canonical name, and signed textures when a profile is found
- stores nickname bindings in `config/uuid-restorer/bindings.json`
- caches skin properties and can refresh them for an already connected player after `/uuidrestorer bind`
- migrates `playerdata`, `playerdata.dat_old`, `stats`, and `advancements`
- rewrites embedded UUID values inside `playerdata` NBT before moving files
- creates timestamped backups under `<world>/uuid-restorer-backups/<player>/<timestamp>/`
- writes detailed diagnostics to `config/uuid-restorer/trace.log`

## Install

1. Build the mod with `./gradlew build` or use a release jar.
2. Put the jar into the server `mods/` folder.
3. Start the server once to generate `config/uuid-restorer/config.json`.
4. Keep the server in offline mode if you want UUID restoration to run on a dedicated server.

## Typical flow

1. Let the player join with a premium nickname, or pre-create a trusted binding with `/uuidrestorer bind <nick>`.
2. Inspect the stored state with `/uuidrestorer status <nick>`.
3. If offline and premium save data both exist, resolve the conflict with `/uuidrestorer resolve ...`.
4. Ask the player to relog if the current session still uses the offline UUID.

## Commands

Both `/uuidrestorer` and `/uuidrestore` are registered.

| Command | Purpose |
| --- | --- |
| `/uuidrestorer version` | Show mod version |
| `/uuidrestorer reload` | Reload config and stored bindings |
| `/uuidrestorer status <nick>` | Show binding, lookup, skin cache, and migration state |
| `/uuidrestorer bind <nick>` | Create a trusted premium binding, try migration, and prefer premium data on conflict |
| `/uuidrestorer refresh <nick>` | Re-resolve the stored profile and refresh cached textures |
| `/uuidrestorer unbind <nick>` | Remove the stored binding |
| `/uuidrestorer migrate <nick>` | Run safe migration for the stored binding |
| `/uuidrestorer resolve <nick> <playerdata\|stats\|advancements\|all> <offline\|premium>` | Resolve a conflict by choosing which side to keep |

## Login behavior

- If Mojang lookup succeeds, the player is switched to the premium UUID.
- If the nickname does not exist as a premium account, login falls back to the offline UUID and the binding is stored as offline-only.
- If live lookup fails, the login can still continue offline; trusted manual bindings may also be reused as a fallback.
- If both offline and premium save buckets exist and `denyOnConflict=true`, premium login is blocked until the conflict is resolved.
- If a trusted binding already matches the joining player's premium UUID, the mod can auto-prefer the premium side during conflict resolution.

## Config

Generated at `config/uuid-restorer/config.json`:

```json
{
  "bindingMode": "unsafe_semi_auto",
  "allowOfflineOnFirstLookupFailure": true,
  "migratePlayerdata": true,
  "migrateStats": true,
  "migrateAdvancements": true,
  "denyOnConflict": true,
  "touchServerLists": false
}
```

Key fields:

- `migratePlayerdata`, `migrateStats`, `migrateAdvancements`: control which save buckets are inspected and migrated.
- `denyOnConflict`: blocks premium login when both offline and premium data exist for the same player.
- `bindingMode`: legacy compatibility field. Current builds still perform live premium lookup in both accepted values; trusted manual bindings mainly matter for fallback trust and conflict auto-resolution.
- `allowOfflineOnFirstLookupFailure` and `touchServerLists`: retained in the generated config for compatibility, but they are not the main controls for the current login path.

## Safety notes

- Automatic restoration trusts the nickname. On an offline server, anyone joining with the same premium nickname can receive that premium UUID and cached textures.
- `/uuidrestorer bind <nick>` creates a trusted manual binding. This is the safest way to preserve a known-good mapping and reuse it when Mojang lookup is temporarily unavailable.
- Conflicts are intentionally conservative by default. If both offline and premium save files exist, resolve them explicitly instead of guessing.
- Broken `config.json` and `bindings.json` files are quarantined automatically as `*.broken-<timestamp>.json`.

## License

GPL-3.0-only
