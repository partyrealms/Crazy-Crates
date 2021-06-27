package me.badbones69.crazycrates.api.objects;

import me.badbones69.crazycrates.Methods;
import me.badbones69.crazycrates.api.CrazyCrates;
import me.badbones69.crazycrates.api.enums.KeyType;
import me.badbones69.crazycrates.api.enums.Messages;
import me.badbones69.crazycrates.api.enums.QuadCrateParticles;
import me.badbones69.crazycrates.controllers.ParticleEffect;
import me.badbones69.crazycrates.multisupport.Version;
import me.badbones69.crazycrates.multisupport.nms.NMSSupport;
import org.bukkit.*;
import org.bukkit.Particle.DustOptions;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.material.Chest;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class QuadCrateSession {
    
    private static final CrazyCrates cc = CrazyCrates.getInstance();
    private static final NMSSupport nms = cc.getNMSSupport();
    private static final List<QuadCrateSession> crateSessions = new ArrayList<>();
    private static final List<Material> blacklistBlocks = nms.getQuadCrateBlacklistBlocks();
    
    private final QuadCrateSession instance;
    private final Crate crate;
    private final Player player;
    private final KeyType keyType;
    private final Location lastLocation;
    private final Location spawnLocation;
    private final Color particleColor;
    private final QuadCrateParticles particle;
    private final boolean checkHand;
    private final List<Location> chestLocations = new ArrayList<>();
    private final HashMap<Location, Boolean> chestsOpened = new HashMap<>();
    private final List<Entity> displayedRewards = new ArrayList<>();
    private CrateSchematic crateSchematic;
    private List<Location> schematicLocations = new ArrayList<>();
    private final List<BukkitRunnable> ongoingTasks = new ArrayList<>();
    private final HashMap<Location, BlockState> oldBlocks = new HashMap<>();
    private final HashMap<Location, BlockState> oldChestBlocks = new HashMap<>();
    
    public QuadCrateSession(Player player, Crate crate, KeyType keyType, Location spawnLocation, Location lastLocation, boolean checkHand) {
        this.instance = this;
        this.crate = crate;
        this.player = player;
        this.checkHand = checkHand;
        this.keyType = keyType;
        this.lastLocation = lastLocation;
        this.spawnLocation = spawnLocation.getBlock().getLocation();
        List<QuadCrateParticles> particles = Arrays.asList(QuadCrateParticles.values());
        this.particle = particles.get(new Random().nextInt(particles.size()));
        this.particleColor = getColors().get(new Random().nextInt(getColors().size()));
        crateSessions.add(instance);
    }
    
    public static void endAllCrates() {
        crateSessions.forEach(session -> session.endCrateForce(false));
        crateSessions.clear();
    }
    
    public static List<QuadCrateSession> getCrateSessions() {
        return crateSessions;
    }
    
    public static Boolean inSession(Player player) {
        for (QuadCrateSession session : crateSessions) {
            if (session.getPlayer() == player) {
                return true;
            }
        }
        return false;
    }
    
    public static QuadCrateSession getSession(Player player) {
        for (QuadCrateSession session : crateSessions) {
            if (session.getPlayer() == player) {
                return session;
            }
        }
        return null;
    }
    
    /**
     * @return True if the crate started successfully and false if it could not start.
     */
    public Boolean startCrate() {
        //Check if the spawnLocation is on a block
        if (spawnLocation.clone().subtract(0, 1, 0).getBlock().getType() == Material.AIR) {
            player.sendMessage(Messages.NOT_ON_BLOCK.getMessage());
            cc.removePlayerFromOpeningList(player);
            crateSessions.remove(instance);
            return false;
        }
        if (cc.getCrateSchematics().isEmpty()) {
            player.sendMessage(Messages.NO_SCHEMATICS_FOUND.getMessage());
            return false;
        }
        crateSchematic = cc.getCrateSchematics().get(new Random().nextInt(cc.getCrateSchematics().size()));
        schematicLocations = nms.getLocations(crateSchematic.getSchematicFile(), spawnLocation.clone());
        //Check if the locations are all able to be changed
        for (Location loc : schematicLocations) {
            if (blacklistBlocks.contains(loc.getBlock())) {
                player.sendMessage(Messages.NEEDS_MORE_ROOM.getMessage());
                cc.removePlayerFromOpeningList(player);
                crateSessions.remove(instance);
                return false;
            }
        }
        //Checking if players nearby are opening a quadcrate.
        List<Entity> shovePlayers = new ArrayList<>();
        for (Entity entity : player.getNearbyEntities(3, 3, 3)) {
            if (entity instanceof Player) {
                for (QuadCrateSession ongoingCrate : crateSessions) {
                    if (entity.getUniqueId() == ongoingCrate.getPlayer().getUniqueId()) {
                        player.sendMessage(Messages.TO_CLOSE_TO_ANOTHER_PLAYER.getMessage("%Player%", entity.getName()));
                        cc.removePlayerFromOpeningList(player);
                        crateSessions.remove(instance);
                        return false;
                    }
                }
                shovePlayers.add(entity);
            }
        }
        if (!cc.takeKeys(1, player, crate, keyType, checkHand)) {
            Methods.failedToTakeKey(player, crate);
            cc.removePlayerFromOpeningList(player);
            crateSessions.remove(instance);
            return false;
        }
        if (cc.getHologramController() != null) {
            cc.getHologramController().removeHologram(spawnLocation.getBlock());
        }
        player.teleport(spawnLocation.clone().add(.5, 0, .5));
        //Shove other players away from the player
        shovePlayers.forEach(entity -> entity.getLocation().toVector().subtract(spawnLocation.clone().toVector()).normalize().setY(1));
        //Add the chestLocations
        chestLocations.add(spawnLocation.clone().add(2, 0, 0));//East
        chestLocations.add(spawnLocation.clone().add(0, 0, 2));//South
        chestLocations.add(spawnLocation.clone().add(-2, 0, 0));//West
        chestLocations.add(spawnLocation.clone().add(0, 0, -2));//North
        chestLocations.forEach(location -> chestsOpened.put(location, false));
        //Save the oldBlock states
        for (Location loc : schematicLocations) {
            if (chestLocations.contains(loc)) {
                oldChestBlocks.put(loc.clone(), loc.getBlock().getState());
            } else {
                oldBlocks.put(loc.clone(), loc.getBlock().getState());
            }
        }
        nms.pasteSchematic(crateSchematic.getSchematicFile(), spawnLocation.clone());
        cc.addQuadCrateTask(player, new BukkitRunnable() {
            double radius = 0.0;//Radius of the particle spiral
            int crateNumber = 0; //The crate number that spawns next
            int tickTillSpawn = 0; //At tick 60 the crate will spawn and then reset the tick
            Location particleLocation = chestLocations.get(crateNumber).clone().add(.5, 3, .5);
            List<Location> spiralLocationsClockwise = getSpiralLocationsClockwise(particleLocation);
            List<Location> spiralLocationsCounterClockwise = getSpiralLocationsCounterClockwise(particleLocation);
            
            @Override
            public void run() {
                if (tickTillSpawn < 60) {
                    spawnParticles(particle, particleColor, spiralLocationsClockwise.get(tickTillSpawn), spiralLocationsCounterClockwise.get(tickTillSpawn));
                    tickTillSpawn++;
                } else {
                    player.playSound(player.getLocation(), cc.getSound("BLOCK_STONE_STEP", "STEP_STONE"), 1, 1);
                    Block chest = chestLocations.get(crateNumber).getBlock();
                    chest.setType(Material.CHEST);
                    rotateChest(chest, crateNumber);
                    if (crateNumber == 3) {//Last crate has spawned
                        cc.endQuadCrate(player);//Is canceled when method is called.
                    } else {
                        tickTillSpawn = 0;
                        crateNumber++;
                        radius = 0;
                        particleLocation = chestLocations.get(crateNumber).clone().add(.5, 3, .5);//Set the new particle location for the new crate
                        spiralLocationsClockwise = getSpiralLocationsClockwise(particleLocation);
                        spiralLocationsCounterClockwise = getSpiralLocationsCounterClockwise(particleLocation);
                    }
                }//154 - 33
            }
        }.runTaskTimer(cc.getPlugin(), 0, 1));
        cc.addCrateTask(player, new BukkitRunnable() {
            @Override
            public void run() {
                endCrateForce(true);
                player.sendMessage(Messages.OUT_OF_TIME.getMessage());
            }
        }.runTaskLater(cc.getPlugin(), cc.getQuadCrateTimer()));
        return true;
    }
    
    public void endCrate() {
        oldBlocks.keySet().forEach(location -> oldBlocks.get(location).update(true, false));
        new BukkitRunnable() {
            @Override
            public void run() {
                chestLocations.forEach(location -> oldChestBlocks.get(location).update(true, false));
                displayedRewards.forEach(Entity :: remove);
                player.teleport(lastLocation);
                if (cc.getHologramController() != null) {
                    cc.getHologramController().createHologram(spawnLocation.getBlock(), crate);
                }
                cc.endCrate(player);
                cc.removePlayerFromOpeningList(player);
                crateSessions.remove(instance);
            }
        }.runTaskLater(cc.getPlugin(), 3 * 20);
    }
    
    public void endCrateForce(boolean removeFromSessions) {
        oldBlocks.keySet().forEach(location -> oldBlocks.get(location).update(true, false));
        chestLocations.forEach(location -> oldChestBlocks.get(location).update(true, false));
        displayedRewards.forEach(Entity :: remove);
        player.teleport(lastLocation);
        cc.removePlayerFromOpeningList(player);
        if (removeFromSessions) {
            crateSessions.remove(instance);
        }
    }
    
    public Crate getCrate() {
        return crate;
    }
    
    public Player getPlayer() {
        return player;
    }
    
    public QuadCrateParticles getParticle() {
        return particle;
    }
    
    public Color getParticleColor() {
        return particleColor;
    }
    
    public Location getLastLocation() {
        return lastLocation;
    }
    
    public List<Location> getChestLocations() {
        return chestLocations;
    }
    
    public HashMap<Location, Boolean> getChestsOpened() {
        return chestsOpened;
    }
    
    public Boolean hasChestBeenOpened(Location location) {
        //If null it returns true just in case to make sure random blocks can't spawn prizes
        return chestsOpened.get(location) == null || chestsOpened.get(location);
    }
    
    public void setChestOpened(Location location, Boolean toggle) {
        chestsOpened.put(location, toggle);
    }
    
    public Boolean allChestsOpened() {
        for (Map.Entry<Location, Boolean> location : chestsOpened.entrySet()) {
            if (!location.getValue()) {
                return false;
            }
        }
        return true;
    }
    
    public List<Entity> getDisplayedRewards() {
        return displayedRewards;
    }
    
    public List<BukkitRunnable> getOngoingTasks() {
        return ongoingTasks;
    }
    
    public CrateSchematic getCrateSchematic() {
        return crateSchematic;
    }
    
    public List<Location> getSchematicLocations() {
        return schematicLocations;
    }
    
    public HashMap<Location, BlockState> getOldBlocks() {
        return oldBlocks;
    }
    
    public HashMap<Location, BlockState> getOldChestBlocks() {
        return oldChestBlocks;
    }
    
    private ArrayList<Location> getSpiralLocationsClockwise(Location center) {
        World world = center.getWorld();
        double downwardsDistnance = .05;
        double expandingRadius = .08;
        double y = center.getY();
        double radius = 0;
        int amount = 10;//Amount of particles in each circle
        int increaseRadius = 0;
        int nextLocation = 0;//The limit of how far the circle goes before reset.
        double increment = (2 * Math.PI) / amount;//Spacing
        ArrayList<Location> locations = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            double angle = nextLocation * increment;
            double x = center.getX() + (radius * Math.cos(angle));
            double z = center.getZ() + (radius * Math.sin(angle));
            locations.add(new Location(world, x, y, z));
            y -= downwardsDistnance;
            nextLocation++;
            increaseRadius++;
            if (increaseRadius == 6) {
                increaseRadius = 0;
                radius += expandingRadius;
            }
            if (nextLocation == 10) {
                nextLocation = 0;
            }
        }
        return locations;
    }
    
    private ArrayList<Location> getSpiralLocationsCounterClockwise(Location center) {
        World world = center.getWorld();
        double downwardsDistnance = .05;
        double expandingRadius = .08;
        double y = center.getY();
        double radius = 0;
        int amount = 10;//Amount of particles in each circle
        int increaseRadius = 0;
        int nextLocation = 0;//The limit of how far the circle goes before reset.
        double increment = (2 * Math.PI) / amount;//Spacing
        ArrayList<Location> locations = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            double angle = nextLocation * increment;
            double x = center.getX() - (radius * Math.cos(angle));
            double z = center.getZ() - (radius * Math.sin(angle));
            locations.add(new Location(world, x, y, z));
            y -= downwardsDistnance;
            nextLocation++;
            increaseRadius++;
            if (increaseRadius == 6) {
                increaseRadius = 0;
                radius += expandingRadius;
            }
            if (nextLocation == 10) {
                nextLocation = 0;
            }
        }
        return locations;
    }
    
    private List<Color> getColors() {
        return Arrays.asList(
        Color.AQUA, Color.BLACK, Color.BLUE, Color.FUCHSIA, Color.GRAY,
        Color.GREEN, Color.LIME, Color.MAROON, Color.NAVY, Color.OLIVE,
        Color.ORANGE, Color.PURPLE, Color.RED, Color.SILVER, Color.TEAL,
        Color.WHITE, Color.YELLOW);
    }
    
    private void spawnParticles(QuadCrateParticles quadCrateParticle, Color particleColor, Location location1, Location location2) {
        if (Version.getCurrentVersion().isNewer(Version.v1_17_R1)) {
            Particle particle;
            switch (quadCrateParticle) {
                case FLAME:
                    particle = Particle.FLAME;
                    break;
                case VILLAGER_HAPPY:
                    particle = Particle.VILLAGER_HAPPY;
                    break;
                case SPELL_WITCH:
                    particle = Particle.SPELL_WITCH;
                    break;
                default:
                    particle = Particle.REDSTONE;
            }
            if (Version.getCurrentVersion().isNewer(Version.v1_17_R1)) {
                if (particle == Particle.REDSTONE) {
                    location1.getWorld().spawnParticle(particle, location1, 0, new DustOptions(particleColor, 1));
                    location2.getWorld().spawnParticle(particle, location2, 0, new DustOptions(particleColor, 1));
                } else {
                    location1.getWorld().spawnParticle(particle, location1, 0);
                    location2.getWorld().spawnParticle(particle, location2, 0);
                }
            } else {
                location1.getWorld().spawnParticle(particle, location1, 0);
                location2.getWorld().spawnParticle(particle, location2, 0);
            }
        } else {
            ParticleEffect particleEffect;
            switch (quadCrateParticle) {
                case FLAME:
                    particleEffect = ParticleEffect.FLAME;
                    break;
                case VILLAGER_HAPPY:
                    particleEffect = ParticleEffect.VILLAGER_HAPPY;
                    break;
                case SPELL_WITCH:
                    particleEffect = ParticleEffect.SPELL_WITCH;
                    break;
                default:
                    particleEffect = ParticleEffect.REDSTONE;
            }
            particleEffect.display(0, 0, 0, 0, 1, location1, 100);
            particleEffect.display(0, 0, 0, 0, 1, location2, 100);
        }
    }
    
    private void rotateChest(Block chest, Integer direction) {
        BlockFace blockFace;
        switch (direction) {
            case 0://East
                blockFace = BlockFace.WEST;
                break;
            case 1://South
                blockFace = BlockFace.NORTH;
                break;
            case 2://West
                blockFace = BlockFace.EAST;
                break;
            case 3://North
                blockFace = BlockFace.SOUTH;
                break;
            default:
                blockFace = BlockFace.DOWN;
                break;
        }
        BlockState state = chest.getState();
        state.setData(new Chest(blockFace));
        state.update();
    }
    
}