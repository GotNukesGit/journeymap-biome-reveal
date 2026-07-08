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

/**
 * Biome Reveal - companion for JourneyMap 5.2.19 FairPlay (GTNH 1.7.10).
 *
 * Records biomes as you explore and, via {@code MixinBlockInfoLayer}, shows the
 * biome name + BiomeDictionary tags on the fullscreen map for any chunk you've
 * discovered - not just the loaded chunks near you.
 *
 * Biomes are read through {@code World.getBiomeGenForCoords}, which is the
 * sanctioned path that also works with EndlessIDs / NotEnoughIDs (mods that
 * crash on raw {@code Chunk.getBiomeArray()} access). Ids are stored as ints so
 * extended biome ids (> 255) are preserved.
 *
 * Tags come live from Forge's BiomeDictionary at runtime (authoritative, no wiki).
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
        // trip to the Nether doesn't wipe the index.
        String wid = worldId();
        if (wid.equals(currentWorld)) return;
        currentWorld = wid;

        BiomeIndex.INSTANCE.open(wid);
        sweepTimer = 0;
        saveTimer = 0;
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.world != null && event.world.isRemote) {
            // Persist, but don't clear: a dimension change unloads the client world
            // too, and we want to keep the index across it.
            BiomeIndex.INSTANCE.flush();
        }
    }

    // ---- periodic recording -------------------------------------------
    // On the client, ChunkEvent.Load fires BEFORE biomes are filled, so we sweep
    // loaded chunks around the player instead. Biomes are read per-column via the
    // safe getBiomeGenForCoords path (EndlessIDs-compatible), never getBiomeArray.

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
            int[] cols = new int[256];
            for (int cx = pcx - SWEEP_RADIUS; cx <= pcx + SWEEP_RADIUS; cx++) {
                for (int cz = pcz - SWEEP_RADIUS; cz <= pcz + SWEEP_RADIUS; cz++) {
                    if (BiomeIndex.INSTANCE.isHandled(dim, cx, cz)) continue;
                    Chunk c = world.getChunkFromChunkCoords(cx, cz);
                    if (c == null || c instanceof EmptyChunk) continue;
                    try {
                        int bx0 = cx << 4;
                        int bz0 = cz << 4;
                        for (int lx = 0; lx < 16; lx++) {
                            for (int lz = 0; lz < 16; lz++) {
                                BiomeGenBase b = world.getBiomeGenForCoords(bx0 + lx, bz0 + lz);
                                cols[(lz << 4) | lx] = (b != null) ? b.biomeID : -1;
                            }
                        }
                        BiomeIndex.INSTANCE.recordRaw(dim, cx, cz, cols);
                    } catch (Throwable ignored) {
                        // skip a chunk that can't be read this pass
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

            int id = BiomeIndex.INSTANCE.biomeAt(dim, x, z);
            BiomeGenBase biome = (id >= 0) ? BiomeGenBase.getBiome(id) : null;

            if (biome == null) {
                // Live fallback ONLY for a currently-loaded chunk. Unloaded chunks
                // would hit the client's seedless WorldChunkManager and return
                // wrong biomes, so we never query those.
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

    // ---- helpers -------------------------------------------------------

    private static String worldId() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.isSingleplayer() && mc.getIntegratedServer() != null) {
            return "sp_" + mc.getIntegratedServer().getFolderName();
        }
        return "mp_world";
    }
}
