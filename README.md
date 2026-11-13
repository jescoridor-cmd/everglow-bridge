# EverGlowBridge

Paper plugin source targeting Paper API 1.21.11, Java 21. Maven build.

## Build
```bash
mvn clean package
```
Copy `target/everglow-bridge-1.0.0.jar` to the Paper server `plugins/` directory, start once, then edit `plugins/EverGlowBridge/config.yml` with the backend HTTPS URL and the same secret configured as `PLUGIN_API_TOKEN`. Restart.

## Optional integrations
- Vault: balance is read only when a Vault Economy provider is registered. Otherwise it reports `0` (this is an integration fallback and should be treated as incomplete data until Vault economy is installed).
- LuckPerms: primary group is read via LuckPerms API; otherwise rank is null.
- Playtime uses Bukkit `PLAY_ONE_MINUTE` statistic converted from ticks to seconds. Mining aggregates `MINE_BLOCK` statistics over block materials.

## Threading and limitations
Bukkit/player/server snapshots are collected on the server thread. HTTP is sent using Java `HttpClient.sendAsync`, with request/connect timeouts; no HTTP or SQL work blocks the main thread. Join/quit payloads are sent asynchronously after snapshotting. The periodic sync sends online players and current server status. Offline player historical stats are not imported automatically.

**Verify on your exact Paper build and installed plugin versions before production.** TPS uses Paper/Bukkit `getTPS()` first value (1-minute average). The implementation deliberately does not issue commands or mutate gameplay. API/DB integration is not considered live until deployment and an actual server test succeed.
