package main.java.ColorMatch.Arena;

import cn.nukkit.Player;
import cn.nukkit.block.BlockAir;
import cn.nukkit.block.BlockWool;
import cn.nukkit.event.HandlerList;
import cn.nukkit.inventory.PlayerInventory;
import cn.nukkit.item.Item;
import cn.nukkit.item.ItemBlock;
import cn.nukkit.math.Vector3;
import cn.nukkit.potion.Effect;
import cn.nukkit.utils.Config;
import cn.nukkit.utils.TextFormat;
import lombok.Getter;
import main.java.ColorMatch.ColorMatch;
import cn.nukkit.event.Listener;
import main.java.ColorMatch.Utils.WoolColor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;

public class Arena extends ArenaManager implements Listener {

    public static final int PHASE_LOBBY = 0;
    public static final int PHASE_GAME = 1;

    @Getter
    protected int phase = PHASE_LOBBY;

    @Getter
    protected boolean enabled = false;

    @Getter
    protected HashMap<String, Player> players = new HashMap<>();

    @Getter
    protected HashMap<String, Player> spectators = new HashMap<>();

    protected ColorMatch plugin;
    protected Config config;
    protected ArenaListener listener;
    protected ArenaSchedule scheduler;

    @Getter
    protected String name = "";

    protected int currentColor = 0;

    public boolean starting = false;

    protected ArrayDeque<Player> winners = new ArrayDeque<>();

    public Arena(ColorMatch plugin, String name, Config cfg) {
        this.name = name;
        super.plugin = this;
        this.listener = new ArenaListener(this);
        this.scheduler = new ArenaSchedule(this);
        this.config = cfg;
        this.plugin = plugin;
    }

    public boolean enable() {
        if (!this.init(config)) {
            return false;
        }

        this.plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        scheduler.id = this.plugin.getServer().getScheduler().scheduleRepeatingTask(scheduler, 20).getTaskId();
        this.enabled = true;
        updateJoinSign();
        resetFloor();
        return true;
    }

    public boolean disable() {
        if (phase == PHASE_GAME) {
            endGame();
        }

        HandlerList.unregisterAll(listener);
        this.plugin.getServer().getScheduler().cancelTask(scheduler.getId());
        enabled = false;
        starting = false;
        updateJoinSign();
        return true;
    }

    public void start() {
        start(false);
    }

    public void start(boolean force) {
        starting = false;

        if (players.size() < plugin.conf.getMinPlayers() && !force) {
            return;
        }

        Effect effect = getGameEffect();

        players.values().forEach(p -> {
            p.teleport(getStartPos());
            p.sendMessage(ColorMatch.getPrefix() + TextFormat.AQUA + "Game started!");

            if (effect != null) {
                p.addEffect(effect.clone());
            }

            p.extinguish();
        });

        this.phase = PHASE_GAME;
        updateJoinSign();
        selectNewColor();
    }

    public void stop() {
        this.phase = PHASE_LOBBY;

        players.values().forEach(p -> removeFromArena(p, false));

        for (Player p : new ArrayList<>(spectators.values())) {
            removeSpectator(p);
        }

        updateJoinSign();
        resetFloor();
        winners.clear();
    }

    public void endGame() {
        if (this.players.size() == 1) {
            winners.add(this.players.values().toArray(new Player[1])[0]);
        }

        String message = "§6§l---------------------§r\n§5arena " + getName() + " has ended\n§4winners:\n§a1. %0\n§e2. %1\n§c3. %2\n§6§l---------------------";

        for (int i = 0; i < 3; i++) {
            String replace = "---";

            if (!winners.isEmpty()) {
                replace = winners.pop().getDisplayName();
            }

            message = message.replaceAll("%" + i, replace);
        }

        messageArenaPlayers(message);

        stop();
    }

    public void addToArena(Player p) {
        if (phase == PHASE_GAME) {
            this.addSpectator(p);
            return;
        }

        if (players.size() >= plugin.conf.getMaxPlayers() && !p.hasPermission("colormatch.joinfullarena")) {
            p.sendMessage(ColorMatch.getPrefix() + TextFormat.RED + "This game is full");
            return;
        }

        messageArenaPlayers(ColorMatch.getPrefix() + TextFormat.YELLOW + p.getDisplayName() + TextFormat.GRAY + " has joined (" + (TextFormat.BLUE + players.size() + 1) + TextFormat.YELLOW + "/" + TextFormat.BLUE + plugin.conf.getMaxPlayers());

        this.players.put(p.getName().toLowerCase(), p);
        updateJoinSign();

        resetPlayer(p);
        p.teleport(getStartPos());
        p.sendMessage(ColorMatch.getPrefix() + TextFormat.GRAY + "Joining to arena " + TextFormat.YELLOW + this.name + TextFormat.GRAY + "...");

        //p.setDisplayName(TextFormat.GRAY + "[" + TextFormat.GREEN + "GAME" + TextFormat.GRAY + "] " + TextFormat.WHITE + TextFormat.RESET + " " + p.getDisplayName());

        checkLobby();
    }

    public void onDeath(Player p) {
        resetPlayer(p);

        messageArenaPlayers(p.getDisplayName() + TextFormat.GRAY + " died!  " + TextFormat.AQUA + (players.size() - 1) + TextFormat.GRAY + " players left");
        players.remove(p.getName().toLowerCase());

        if (this.players.size() <= 2) {
            this.winners.add(p);
        }

        addSpectator(p);
        checkAlive();
    }

    public void removeFromArena(Player p) {
        removeFromArena(p, true);
    }

    public void removeFromArena(Player p, boolean message) {
        this.players.remove(p.getName().toLowerCase());
        updateJoinSign();
        resetPlayer(p);
        //p.setDisplayName(p.getDisplayName().replaceAll(TextFormat.GRAY + "[" + TextFormat.GREEN + "GAME" + TextFormat.GRAY + "] " + TextFormat.WHITE + TextFormat.RESET + " ", ""));

        if (message) {
            messageArenaPlayers(ColorMatch.getPrefix() + TextFormat.YELLOW + p.getDisplayName() + TextFormat.GRAY + " has left (" + (TextFormat.BLUE + players.size() + 1) + TextFormat.YELLOW + "/" + TextFormat.BLUE + plugin.conf.getMaxPlayers());

            if (p.isOnline()) {
                p.sendMessage(ColorMatch.getPrefix() + TextFormat.GRAY + "Leaving arena...");
            }
        }

        p.teleport(plugin.conf.getMainLobby());

        if (phase == PHASE_GAME) {
            if (this.players.size() <= 2) {
                this.winners.add(p);
            }

            checkAlive();
        }
    }

    public void addSpectator(Player p) {
        resetPlayer(p);
        p.teleport(getSpectatorPos());
        p.sendMessage(ColorMatch.getPrefix() + TextFormat.GRAY + "Joining as spectator...");
        //p.setDisplayName(TextFormat.GRAY + "[" + TextFormat.YELLOW + "SPECTATOR" + TextFormat.GRAY + "] " + TextFormat.WHITE + TextFormat.RESET + " " + p.getDisplayName());
        this.spectators.put(p.getName().toLowerCase(), p);
    }

    public void removeSpectator(Player p) {
        //p.setDisplayName(p.getDisplayName().replaceAll(TextFormat.GRAY + "[" + TextFormat.YELLOW + "SPECTATOR" + TextFormat.GRAY + "] " + TextFormat.WHITE + TextFormat.RESET + " ", ""));
        this.spectators.remove(p.getName().toLowerCase());
        p.teleport(plugin.conf.getMainLobby());
        resetPlayer(p);
    }

    public void selectNewColor() {
        this.currentColor = new Random().nextInt(15);

        Item item = new ItemBlock(new BlockWool(), currentColor);
        item.setDamage(currentColor);

        players.values().forEach((Player p) -> {
            PlayerInventory inv = p.getInventory();

            for (int i = 0; i < 9 && i < inv.getSize(); i++) {
                inv.setItem(i, item);
                inv.setHotbarSlotIndex(i, i);
            }

            inv.sendContents(p);

            inv.setItemInHand(item.clone());
            inv.sendHeldItem(p);

            /*String msg = WoolColor.getColorName(currentColor) + "\n\n\n\n";
            p.sendPopup(msg);*/
        });
    }

    public void resetFloor() {
        Random random = new Random();
        BlockWool block = new BlockWool();
        Vector3 v = new Vector3();

        int colorCount = 0;
        int blockCount = 0;

        int floorSize = (int) (((floor.maxX - floor.minX) / 3) * ((floor.maxZ - floor.minZ) / 3));

        int randomBlock = random.nextInt(floorSize);
        int maxColorCount = (int) floorSize / 32;

        for (int x = (int) getFloor().minX; x <= getFloor().maxX; x += 3) {
            for (int z = (int) getFloor().minZ; z <= getFloor().maxZ; z += 3) {
                int color = random.nextInt(15);

                int minX = x - 1;
                int minZ = z - 1;
                int maxX = x + 1;
                int maxZ = z + 1;

                if (color == this.currentColor) {
                    colorCount++;
                } else if (blockCount == randomBlock && colorCount < maxColorCount / 2) {
                    color = this.currentColor;
                }

                block.setDamage(color);

                for (int x_ = minX; x_ <= maxX; x_++) {
                    for (int z_ = minZ; z_ <= maxZ; z_++) {
                        level.setBlock(v.setComponents(x_, floorPos.getFloorY(), z_), block.clone(), true, false);
                    }
                }

                blockCount++;
            }
        }
    }

    public void removeFloor() {
        Vector3 v = new Vector3();
        BlockAir air = new BlockAir();

        /*for (int x = (int) getFloor().minX; x <= getFloor().maxX; x++) {
            for (int z = (int) getFloor().minZ; z <= getFloor().maxZ; z++) {
                int meta = level.getBlockDataAt(x, floorPos.getFloorY(), z);

                //int meta = level.getChunk(x >> 4, z >> 4, true).getBlockData(x & 15, floorPos.getFloorY() & 127, z & 15);

                if (meta != currentColor) {
                    level.setBlock(v.setComponents(x, floorPos.getFloorY(), z), new BlockAir(), true, false);
                }
            }
        }*/

        for (int x = (int) getFloor().minX; x <= getFloor().maxX; x += 3) {
            for (int z = (int) getFloor().minZ; z <= getFloor().maxZ; z += 3) {
                int minX = x - 1;
                int minZ = z - 1;
                int maxX = x + 1;
                int maxZ = z + 1;

                int meta = level.getBlockDataAt(x, floorPos.getFloorY(), z);

                //int meta = level.getChunk(x >> 4, z >> 4, true).getBlockData(x & 15, floorPos.getFloorY() & 127, z & 15);

                if (meta != currentColor) {
                    for (int x_ = minX; x_ <= maxX; x_++) {
                        for (int z_ = minZ; z_ <= maxZ; z_++) {
                            level.setBlock(v.setComponents(x_, floorPos.getFloorY(), z_), air.clone(), true, false);
                        }
                    }
                }
            }
        }
    }
}