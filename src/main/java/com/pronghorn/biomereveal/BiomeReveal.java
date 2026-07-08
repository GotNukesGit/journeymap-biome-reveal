package com.pronghorn.biomereveal;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraftforge.common.BiomeDictionary;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;

import java.io.File;

/**
 * Biome Reveal — companion for JourneyMap 5.2.19 FairPlay (GTNH 1.7.10).
 *
 * Records biomes as you explore and, via {@code MixinBlockInfoLayer}, shows
 * the biome name + BiomeDictionary tags on the fullscreen map for ANY chunk
 * you've discovered — not just the loaded chunks near you.
 *
 * Tags come live from Forge's BiomeDictionary at runtime (the authoritative
 * source, no wiki). {@code describe(BiomeGenBase)} can be reused to dump the
 * full biome-to-tags table if you want to regenerate that list.
 */
@Mod(modid = BiomeReveal.MODID, name = BiomeReveal.NAME, version = BiomeReveal.VERSION,
     dependencies = "required-after:journeymap")
public class BiomeReveal {

    public static final String MODID = "biomereveal";
    public static final String NAME = "Biome Reveal";
    public static final String VERSION = "0.1.0";

    /** How wide to sweep around the player each pass (chunks). */
    private static final int SWEEP_RADIUS = 10;
    /** Sweep + save cadence in client ticks (~20/sec). */
    private static final int SWEEP_TICKS = 20;
    private static final int SAVE_TICKS = 20 * 30;

    private int sweepTimer = 0;
    private int saveTimer = 0;
    private String currentWorld = null;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);                 // WorldEvent.Load/Unload
        FMLCommonHandler.instance().bus().register(this);        // TickEvent.ClientTickEvent
    }

    // ---- world lifecycle ----------------------------------------------

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (event.world == null || !event.world.isRemote) return;

        // WorldEvent.Load fires again on every dimension change (the client world
        // is rebuilt). Only (re)open the store when the actual save changes, so a
        // trip to the Nether doesn't wipe the index or restart the backfill.
        String wid = worldId();
        if (wid.equals(currentWorld)) return;
        currentWorld = wid;

        BiomeIndex.INSTANCE.open(wid);
        sweepTimer = 0;
        saveTimer = 0;

        // Singleplayer only: backfill biomes from the save's region files so the
        // whole explored map works, not just chunks loaded after install.
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.isSingleplayer() && mc.getIntegratedServer() != null
                && !BiomeIndex.INSTANCE.isBackfilled()) {
            File worldDir = new File(new File(mc.mcDataDir, "saves"),
                                     mc.getIntegratedServer().getFolderName());
            if (worldDir.isDirectory()) {
                SaveBiomeScanner.backfillAsync(worldDir);
            }
        }
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.world != null && event.world.isRemote) {
            // Persist, but don't clear: a dimension change unloads the client world
            // too, and we want to keep the index across it. A different save will
            // reset things via open() on the next load.
            BiomeIndex.INSTANCE.flush();
        }
    }

    // ---- periodic recording -------------------------------------------
    // Note: on the client, ChunkEvent.Load fires BEFORE biomes are filled,
    // so we sweep loaded chunks around the player instead. A loaded (non-empty)
    // client chunk is always fully populated, so its biome array is real.

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient world = mc.theWorld;
        EntityClientPlayerMP player = mc.thePlayer;
        if (world == null || player == null) return;

        if (++sweepTimer >= SWEEP_TICKS) {
            sweepTimer = 0;
            int dim = world.provider.dimensionId;
            int pcx = player.chunkCoordX;
            int pcz = player.chunkCoordZ;
            for (int cx = pcx - SWEEP_RADIUS; cx <= pcx + SWEEP_RADIUS; cx++) {
                for (int cz = pcz - SWEEP_RADIUS; cz <= pcz + SWEEP_RADIUS; cz++) {
                    Chunk c = world.getChunkFromChunkCoords(cx, cz);
                    if (c != null && !(c instanceof EmptyChunk)) {
                        BiomeIndex.INSTANCE.record(dim, c);
                    }
                }
            }
        }

        if (++saveTimer >= SAVE_TICKS) {
            saveTimer = 0;
            BiomeIndex.INSTANCE.flush();
        }
    }

    // ---- lookup used by the mixin -------------------------------------

    /**
     * Build the full hover label for a block coord: "x, z  Biome [TAG, TAG]".
     * Returns {@code fallback} unchanged if the biome is neither in the store
     * nor in a currently-loaded chunk (i.e. genuinely undiscovered).
     */
    public static String enrich(Minecraft mc, int x, int z, String fallback) {
        try {
            World world = mc.theWorld;
            if (world == null) return fallback;
            int dim = world.provider.dimensionId;

            BiomeGenBase biome = biomeById(BiomeIndex.INSTANCE.biomeAt(dim, x, z));

            if (biome == null) {
                // Live fallback ONLY for a currently-loaded chunk. Unloaded
                // chunks would hit the client's seedless WorldChunkManager and
                // return wrong biomes, so we never query those.
                Chunk c = world.getChunkFromChunkCoords(x >> 4, z >> 4);
                if (c != null && !(c instanceof EmptyChunk)) {
                    biome = world.getBiomeGenForCoords(x, z);
                }
            }

            if (biome == null) return fallback;
            return x + ", " + z + "  " + describe(biome);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** Biome name + its BiomeDictionary tags, e.g. "Forest [FOREST, HILLS]". */
    public static String describe(BiomeGenBase biome) {
        StringBuilder sb = new StringBuilder(biome.biomeName);
        try {
            BiomeDictionary.Type[] types = BiomeDictionary.getTypesForBiome(biome);
            if (types != null && types.length > 0) {
                sb.append(" [");
                for (int i = 0; i < types.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(types[i].name());
                }
                sb.append(']');
            }
        } catch (Throwable ignored) {
            // BiomeDictionary lookup failed -> just show the name
        }
        return sb.toString();
    }

    private static BiomeGenBase biomeById(int id) {
        if (id < 0) return null;
        BiomeGenBase[] all = BiomeGenBase.getBiomeGenArray();
        return (id < all.length) ? all[id] : null;
    }

    // ---- helpers -------------------------------------------------------

    private static String worldId() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.isSingleplayer() && mc.getIntegratedServer() != null) {
            return "sp_" + mc.getIntegratedServer().getFolderName();
        }
        if (mc.getCurrentServerData() != null) {
            return "mp_" + mc.getCurrentServerData().serverIP;
        }
        return "world";
    }
}
