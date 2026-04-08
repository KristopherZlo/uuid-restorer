# UUID Restorer

Server-side Fabric mod for offline/LAN servers that restores premium UUIDs, skins, and player data by nickname.

## Compatibility

- Minecraft `1.21.8-1.21.11`
- Fabric Loader `0.17.3+`
- Java `21+`

## What it does

- restores Mojang UUID for known premium nicknames
- caches skin textures and reapplies them on login
- migrates `playerdata`, `stats`, and `advancements`
- provides conflict resolution commands for offline vs premium save data

## Commands

- `/uuidrestorer version`
- `/uuidrestorer status <nick>`
- `/uuidrestorer bind <nick>`
- `/uuidrestorer refresh <nick>`
- `/uuidrestorer migrate <nick>`
- `/uuidrestorer resolve <nick> <playerdata|stats|advancements|all> <offline|premium>`

## Important

Default `unsafe_semi_auto` mode trusts the nickname and does not prove premium ownership. Use it only if you accept that anyone with the same premium nickname can receive that account's UUID and cached textures on an offline server.
