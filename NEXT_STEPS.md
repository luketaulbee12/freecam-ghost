# Next steps (mod)

The picture-to-build work and Build Studio moved to [build-studio](https://github.com/luketaulbee12/build-studio);
its progress notes are in that repo's `STATUS.md`. This file is only about the mod.

## Ideas
- Auto-builder: faster scaffolding cleanup; smarter ordering for tall builds (fewer pillars).
- Blueprints menu: thumbnails, rename/delete from the menu.
- Ghost blocks: mirror/rotate a blueprint before placing it.
- Designer (`/design`): more JSON styles; read styles shared from Build Studio.

## Testing loop
`./gradlew :neoforge:build`, then `./gradlew :neoforge:runClient` for a clean dev client
(log lines tagged `[auto-build]` in `run-client.log`).
