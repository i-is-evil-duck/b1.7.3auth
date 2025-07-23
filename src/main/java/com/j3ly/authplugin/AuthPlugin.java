package com.j3ly.authplugin;

import java.io.*;
import java.util.*;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.Event.Priority;
import org.bukkit.event.player.*;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public class AuthPlugin extends JavaPlugin {

    private final Map<String, String[]> playerData = new HashMap<>(); // name -> [password, ip, time]
    private final Set<String> authedPlayers = new HashSet<>();
    private final Set<String> pendingAuth = new HashSet<>();
    private final Map<String, Location> originalLocations = new HashMap<>();

    private long timeoutSeconds = 120;
    private long ipCacheMillis = 12 * 60 * 60 * 1000; // 12 hrs default

    private final Location authSpawn = new Location(null, 0, 100, 0); // world will be set on teleport

    private File configFile;

    @Override
    public void onEnable() {
        configFile = new File(getDataFolder(), "auth.yml");
        if (!getDataFolder().exists()) getDataFolder().mkdir();
        loadConfig();

        PluginManager pm = getServer().getPluginManager();

        pm.registerEvent(Event.Type.PLAYER_JOIN, new PlayerListener() {
            @Override
            public void onPlayerJoin(PlayerJoinEvent event) {
                Player player = event.getPlayer();
                String name = player.getName().toLowerCase();
                String ip = player.getAddress().getAddress().getHostAddress();
                Location joinLoc = player.getLocation();

                if (!(joinLoc.getBlockX() == 0 && joinLoc.getBlockY() == 100 && joinLoc.getBlockZ() == 0)) {
                    originalLocations.put(name, joinLoc);
                }

                String[] data = playerData.get(name);
                long now = System.currentTimeMillis();

                if (data != null && data.length == 3) {
                    String savedIp = data[1];
                    long time = 0;
                    try { time = Long.parseLong(data[2]); } catch (Exception ignored) {}

                    if (savedIp.equals(ip) && (now - time) < ipCacheMillis) {
                        authedPlayers.add(name);
                        player.sendMessage("\u00a7a[Auth] Auto-logged in.");
                        return;
                    }
                }

                Location teleportLoc = authSpawn.clone();
                teleportLoc.setWorld(player.getWorld());
                player.teleport(teleportLoc);
                pendingAuth.add(name);
                player.sendMessage("\u00a7c[Auth] Please /login <password> or /register <password>");
            }
        }, Priority.Normal, this);

        pm.registerEvent(Event.Type.PLAYER_MOVE, new PlayerListener() {
            @Override
            public void onPlayerMove(PlayerMoveEvent event) {
                Player player = event.getPlayer();
                String name = player.getName().toLowerCase();
                if (!authedPlayers.contains(name) && pendingAuth.contains(name)) {
                    if (!event.getFrom().getBlock().equals(event.getTo().getBlock())) {
                        event.setTo(event.getFrom());
                        player.sendMessage("\u00a7c[Auth] Please /login <password> or /register <password>");
                    }
                }
            }
        }, Priority.Normal, this);
    }

    @Override
    public void onDisable() {
        saveConfig();
        System.out.println("[AuthPlugin] Disabled and data saved.");
    }

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) return false;
        Player player = (Player) sender;
        String name = player.getName().toLowerCase();
        String ip = player.getAddress().getAddress().getHostAddress();

        if (cmd.getName().equalsIgnoreCase("register")) {
            if (args.length != 1) {
                player.sendMessage("\u00a7cUsage: /register <password>");
                return true;
            }
            if (playerData.containsKey(name)) {
                player.sendMessage("\u00a7cAlready registered. Use /login.");
                return true;
            }

            String password = args[0];
            String time = String.valueOf(System.currentTimeMillis());
            playerData.put(name, new String[]{password, ip, time});
            authedPlayers.add(name);
            pendingAuth.remove(name);
            teleportToOriginalLocation(player);
            saveConfig();

            player.sendMessage("\u00a7a[Auth] Registered and logged in!");
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("login")) {
            if (args.length != 1) {
                player.sendMessage("\u00a7cUsage: /login <password>");
                return true;
            }
            String[] data = playerData.get(name);
            if (data == null) {
                player.sendMessage("\u00a7cYou are not registered.");
                return true;
            }
            String correctPassword = data[0];
            if (!correctPassword.equals(args[0])) {
                player.sendMessage("\u00a7cWrong password.");
                return true;
            }
            data[1] = ip;
            data[2] = String.valueOf(System.currentTimeMillis());
            authedPlayers.add(name);
            pendingAuth.remove(name);
            teleportToOriginalLocation(player);
            saveConfig();

            player.sendMessage("\u00a7a[Auth] Logged in!");
            return true;
        }

        return false;
    }

    private void teleportToOriginalLocation(Player player) {
        Location loc = originalLocations.get(player.getName().toLowerCase());
        if (loc != null) player.teleport(loc);
    }

    private void loadConfig() {
        if (!configFile.exists()) return;

        try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("login time out:")) {
                    timeoutSeconds = Long.parseLong(line.replace("login time out:", "").replace("sec", "").trim());
                } else if (line.startsWith("ip cache time:")) {
                    String raw = line.replace("ip cache time:", "").replace("hrs", "").trim();
                    ipCacheMillis = Long.parseLong(raw) * 60 * 60 * 1000;
                } else if (line.contains(" : ")) {
                    String[] parts = line.split(" : ");
                    if (parts.length >= 3) {
                        String pname = parts[0].trim();
                        String ppass = parts[1].replaceAll("^\"|\"$", "").trim();
                        String pip = parts[2].trim();
                        String time = parts.length > 3 ? parts[3].trim() : String.valueOf(System.currentTimeMillis());
                        playerData.put(pname.toLowerCase(), new String[]{ppass, pip, time});
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[AuthPlugin] Error reading config: " + e.getMessage());
        }
    }

    private void saveConfig() {
        try {
            FileWriter writer = new FileWriter(configFile, false);
            writer.write("login time out:" + timeoutSeconds + "sec\n");
            writer.write("ip cache time:" + (ipCacheMillis / (60 * 60 * 1000)) + "hrs\n\n");
            writer.write("registered players\n");

            for (Map.Entry<String, String[]> entry : playerData.entrySet()) {
                String name = entry.getKey();
                String[] data = entry.getValue();
                writer.write(name + " : \"" + data[0] + "\" : " + data[1] + " : " + data[2] + "\n");
            }

            writer.flush();
            writer.close();
        } catch (Exception e) {
            System.out.println("[AuthPlugin] Failed to save config: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
