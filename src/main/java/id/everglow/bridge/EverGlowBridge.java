package id.everglow.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.milkbowl.vault.economy.Economy;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class EverGlowBridge extends JavaPlugin implements Listener {
    private final Gson gson = new Gson();
    private HttpClient http;
    private String baseUrl, token;
    private int timeoutSeconds;
    private Economy economy;
    private LuckPerms luckPerms;
    private BukkitTask periodicTask;

    @Override public void onEnable() {
        saveDefaultConfig();
        baseUrl = getConfig().getString("api.base-url", "").replaceAll("/$", "");
        token = getConfig().getString("api.token", "");
        timeoutSeconds = Math.max(2, getConfig().getInt("sync.request-timeout-seconds", 8));
        if (baseUrl.isBlank() || token.length() < 32 || baseUrl.contains("YOUR-API")) {
            getLogger().severe("Configure api.base-url and a 32+ character api.token in config.yml. Bridge disabled.");
            Bukkit.getPluginManager().disablePlugin(this); return;
        }
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build();
        RegisteredServiceProvider<Economy> econ = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (econ != null) economy = econ.getProvider();
        try { luckPerms = LuckPermsProvider.get(); } catch (IllegalStateException ignored) { getLogger().warning("LuckPerms not found; rank field will be null."); }
        Bukkit.getPluginManager().registerEvents(this, this);
        long ticks = Math.max(20L, getConfig().getLong("sync.interval-seconds", 60) * 20L);
        periodicTask = Bukkit.getScheduler().runTaskTimer(this, this::collectAndSendSync, 40L, ticks);
        getLogger().info("EverGlowBridge enabled. Vault=" + (economy != null) + ", LuckPerms=" + (luckPerms != null));
    }

    @Override public void onDisable() { if (periodicTask != null) periodicTask.cancel(); }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        // Snapshot Bukkit state on the server thread; only network work runs asynchronously.
        JsonObject payload = playerSnapshot(p, true);
        payload.addProperty("event_type", "join");
        postAsync("/api/plugin/player-join", payload);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        JsonObject payload = playerSnapshot(p, false);
        payload.addProperty("event_type", "quit");
        postAsync("/api/plugin/player-quit", payload);
    }

    private void collectAndSendSync() {
        List<JsonObject> players = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) players.add(playerSnapshot(p, true));
        JsonObject status = new JsonObject();
        status.addProperty("online", true);
        status.addProperty("players_online", Bukkit.getOnlinePlayers().size());
        status.addProperty("max_players", Bukkit.getMaxPlayers());
        double[] tps = Bukkit.getTPS();
        status.addProperty("tps", tps.length > 0 ? Math.max(0, Math.min(100, tps[0])) : 0);
        status.addProperty("motd", Bukkit.getMotd());
        JsonObject root = new JsonObject(); root.add("players", gson.toJsonTree(players)); root.add("server_status", status);
        postAsync("/api/plugin/sync", root);
    }

    private JsonObject playerSnapshot(Player p, boolean online) {
        JsonObject j = new JsonObject(); UUID id = p.getUniqueId();
        j.addProperty("uuid", id.toString()); j.addProperty("username", p.getName());
        j.addProperty("rank", resolveRank(id));
        j.addProperty("balance", economy == null ? 0.0 : Math.max(0.0, economy.getBalance(p)));
        j.addProperty("playtime", Math.max(0L, p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L));
        j.addProperty("kills", Math.max(0, p.getStatistic(Statistic.PLAYER_KILLS)));
        j.addProperty("deaths", Math.max(0, p.getStatistic(Statistic.DEATHS)));
        j.addProperty("blocks_mined", countMined(p));
        j.addProperty("online", online);
        j.addProperty("first_join", Instant.ofEpochMilli(p.getFirstPlayed()).toString());
        j.addProperty("last_seen", Instant.now().toString());
        return j;
    }

    private String resolveRank(UUID id) {
        if (luckPerms == null) return null;
        try {
            var user = luckPerms.getUserManager().getUser(id);
            return user == null ? null : user.getPrimaryGroup();
        } catch (RuntimeException ex) { getLogger().warning("Could not read LuckPerms group for " + id); return null; }
    }

    private long countMined(Player p) {
        long total = 0;
        for (Material material : Material.values()) {
            if (!material.isBlock() || !material.isItem()) continue;
            try { total += Math.max(0, p.getStatistic(Statistic.MINE_BLOCK, material)); }
            catch (IllegalArgumentException ignored) { /* Statistic/material pair not supported by this server build. */ }
        }
        return total;
    }

    private void postAsync(String path, JsonObject payload) {
        String body = gson.toJson(payload);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(timeoutSeconds)).header("Content-Type", "application/json")
                .header("Accept", "application/json").header("X-API-Token", token)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> { if (response.statusCode() < 200 || response.statusCode() >= 300)
                    getLogger().warning("API " + path + " returned HTTP " + response.statusCode()); })
                .exceptionally(error -> { getLogger().warning("API request failed for " + path + ": " + error.getMessage()); return null; });
    }
}
