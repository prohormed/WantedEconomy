package ru.wantedcraft.economy;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.UUID;

public class WantedEconomy extends JavaPlugin implements Listener, CommandExecutor {
    private Connection connection;
    private final HashMap<UUID, PlayerData> memoryData = new HashMap<>();

    @Override
    public void onEnable() {
        try {
            File dbFile = new File(getDataFolder(), "database.db");
            if (!dbFile.getParentFile().exists()) dbFile.getParentFile().mkdirs();
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement s = connection.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS eco (uuid TEXT PRIMARY KEY, balance REAL DEFAULT 100.0, wanted INTEGER DEFAULT 0)");
            }
        } catch (Exception e) { e.printStackTrace(); }

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("money").setExecutor(this);
        getCommand("pay").setExecutor(this);
        getCommand("wanted").setExecutor(this);

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) updateScoreboard(p);
        }, 20L, 20L);
    }

    @Override
    public void onDisable() {
        for (PlayerData d : memoryData.values()) savePlayer(d);
        try { if (connection != null && !connection.isClosed()) connection.close(); } catch (Exception e) {}
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            PlayerData d = loadPlayer(p.getUniqueId());
            Bukkit.getScheduler().runTask(this, () -> {
                memoryData.put(p.getUniqueId(), d);
                updateScoreboard(p);
            });
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        PlayerData d = memoryData.remove(e.getPlayer().getUniqueId());
        if (d != null) Bukkit.getScheduler().runTaskAsynchronously(this, () -> savePlayer(d));
    }

    private PlayerData loadPlayer(UUID u) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM eco WHERE uuid = ?")) {
            ps.setString(1, u.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return new PlayerData(u, rs.getDouble("balance"), rs.getInt("wanted"));
            try (PreparedStatement i = connection.prepareStatement("INSERT INTO eco (uuid) VALUES (?)")) {
                i.setString(1, u.toString()); i.executeUpdate();
            }
        } catch (Exception e) { e.printStackTrace(); }
        return new PlayerData(u, 100.0, 0);
    }

    private void savePlayer(PlayerData d) {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE eco SET balance = ?, wanted = ? WHERE uuid = ?")) {
            ps.setDouble(1, d.getBalance()); ps.setInt(2, d.getWantedLevel()); ps.setString(3, d.getUuid().toString());
            ps.executeUpdate();
        } catch (Exception e) { e.printStackTrace(); }
    }

    public void updateScoreboard(Player p) {
        PlayerData d = memoryData.get(p.getUniqueId());
        if (d == null) return;
        Scoreboard b = p.getScoreboard() == Bukkit.getScoreboardManager().getMainScoreboard() ? Bukkit.getScoreboardManager().getNewScoreboard() : p.getScoreboard();
        p.setScoreboard(b);
        Objective o = b.getObjective("wanted_board") == null ? b.registerNewObjective("wanted_board", Criteria.DUMMY, "§6§lWANTED CRAFT") : b.getObjective("wanted_board");
        o.setDisplaySlot(DisplaySlot.SIDEBAR);
        for (String entry : b.getEntries()) b.resetScores(entry);
        o.getScore("§7§m──────────────────").setScore(5);
        o.getScore("§f🪙 Баланс: §a" + String.format("%.1f", d.getBalance()) + "$").setScore(4);
        o.getScore("§f🚨 Розыск: " + d.getWantedStars()).setScore(3);
        o.getScore("§7§m──────────────────§r").setScore(2);
        o.getScore("§eplay.wantedcraft.ru").setScore(1);
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (c.getName().equalsIgnoreCase("money") && s instanceof Player) {
            PlayerData d = memoryData.get(((Player) s).getUniqueId());
            if (d != null) s.sendMessage("§e💳 §fБаланс: §a" + d.getBalance() + "$");
        }
        if (c.getName().equalsIgnoreCase("pay") && s instanceof Player && a.length >= 2) {
            Player p = (Player) s; Player t = Bukkit.getPlayer(a[0]);
            if (t != null && t.isOnline() && !t.equals(p)) {
                try {
                    double am = Double.parseDouble(a[1]);
                    PlayerData pData = memoryData.get(p.getUniqueId());
                    PlayerData tData = memoryData.get(t.getUniqueId());
                    if (am > 0 && pData != null && tData != null && pData.takeMoney(am)) {
                        tData.addMoney(am);
                        p.sendMessage("§e💸 §fПереведено §a" + am + "$ §fдля §b" + t.getName());
                        t.sendMessage("§e💰 §fПолучено §a" + am + "$ §fот §b" + p.getName());
                    }
                } catch (Exception ex) {}
            }
        }
        if (c.getName().equalsIgnoreCase("wanted") && s.hasPermission("wantedcraft.admin") && a.length >= 2) {
            Player t = Bukkit.getPlayer(a[0]);
            if (t != null) {
                try {
                    int lvl = Integer.parseInt(a[1]);
                    PlayerData tData = memoryData.get(t.getUniqueId());
                    if (tData != null) {
                        tData.setWantedLevel(lvl);
                        s.sendMessage("§e🛠️ §fРозыск игрока " + t.getName() + " изменен на " + lvl);
                    }
                } catch (Exception ex) {}
            }
        }
        return true;
    }

    public static class PlayerData {
        private final UUID u; private double b; private int w;
        public PlayerData(UUID u, double b, int w) { this.u = u; this.b = b; this.w = w; }
        public UUID getUuid() { return u; }
        public double getBalance() { return b; }
        public int getWantedLevel() { return w; }
        public void addMoney(double a) { this.b += a; }
        public boolean takeMoney(double a) { if (this.b >= a) { this.b -= a; return true; } return false; }
        public void setWantedLevel(int l) { this.w = Math.max(0, Math.min(5, l)); }
        public String getWantedStars() {
            if (w == 0) return "§aНет";
            StringBuilder s = new StringBuilder("§c");
            for (int i = 0; i < w; i++) s.append("⭐");
            return s.toString();
        }
    }
}
