package com.pronghorn.biomereveal;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.biome.WorldChunkManager;
import net.minecraft.world.storage.ISaveHandler;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * One-time singleplayer backfill for Biome Reveal.
 *
 * Reveals biomes for every chunk already saved to disk in the current save,
 * without re-visiting them. Two ideas make this both safe and correct:
 *
 *  1. WHICH chunks exist is read straight from each region file's 4 KiB
 *     location header (a chunk whose 4-byte entry is zero was never saved).
 *     No NBT is decoded and no chunk is generated - we only ever reference
 *     chunks that already exist on disk.
 *
 *  2. The biome for each column comes from the SERVER's seeded
 *     WorldChunkManager: worldServerForDimension(dim).getWorldChunkManager().
 *     On the server this manager is properly seeded, so it returns the real
 *     biome for any coordinate - unlike the client's seedless manager the
 *     README warns about. It also yields live BiomeGenBase objects, so
 *     extended (>255) NEID / EndlessIDs biome ids come through exactly as the
 *     live sweep stores them, with no on-disk biome-array parsing and no
 *     byte truncation.
 *
 * Runs on the SERVER thread (ServerTickEvent), a bounded batch per tick, so it
 * never stalls the game and never races IntCache (static + not thread-safe in
 * 1.7.10). A per-save ".backfilled" marker makes it run once ever, not on
 * every load.
 *
 * Register this from the client side only (see BiomeReveal.init). On a remote
 * multiplayer client MinecraftServer.getServer() is null, so it never runs.
 */
public final class BiomeBackfill {

    public static final BiomeBackfill INSTANCE = new BiomeBackfill();

    /** Chunks processed per server tick. Raise for a faster (hitchier) scan. */
    private static final int CHUNKS_PER_TICK = 256;

    private boolean started = false;
    private boolean done = false;
    private final ArrayDeque<long[]> queue = new ArrayDeque<long[]>(); // {dim, cx, cz}
    private File marker;

    private BiomeBackfill() {}

    public void register() {
        FMLCommonHandler.instance().bus().register(this);
    }

    /** Force a re-scan next load (e.g. from a /biomereveal rescan command). */
    public void reset() {
        if (marker != null) marker.delete();
        started = false;
        done = false;
        queue.clear();
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || done) return;

        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) return; // no integrated server yet (or remote MP)

        // Wait until the client side has opened the index (baseDir set) so
        // recordRaw has somewhere to write.
        if (!BiomeIndex.INSTANCE.isReady()) return;

        if (!started) {
            if (!begin(server)) return; // save not locatable yet; retry next tick
            started = true;
        }

        int budget = CHUNKS_PER_TICK;
        while (budget-- > 0 && !queue.isEmpty()) {
            long[] job = queue.poll();
            processChunk(server, (int) job[0], (int) job[1], (int) job[2]);
        }

        if (queue.isEmpty()) {
            BiomeIndex.INSTANCE.flush();
            try {
                if (marker != null) { marker.getParentFile().mkdirs(); marker.createNewFile(); }
            } catch (IOException ignored) {}
            done = true;
        }
    }

    // ---- planning ------------------------------------------------------

    /** Locate the save and enqueue every on-disk chunk across all dimensions. */
    private boolean begin(MinecraftServer server) {
        WorldServer overworld = server.worldServerForDimension(0);
        if (overworld == null) return false;

        this.marker = new File(BiomeIndex.INSTANCE.dir(), ".backfilled");
        if (marker.exists()) return true; // already done -> empty queue -> finishes at once

        ISaveHandler sh = overworld.getSaveHandler();
        File worldDir = sh.getWorldDirectory();
        if (worldDir == null || !worldDir.isDirectory()) return false;

        // Overworld: <worldDir>/region  (dim 0)
        enqueueRegionDir(0, new File(worldDir, "region"));

        // Other dims: <worldDir>/DIM<n>/region  (n parsed from the folder name)
        File[] subs = worldDir.listFiles();
        if (subs != null) {
            for (File sub : subs) {
                if (!sub.isDirectory()) continue;
                Integer dim = parseDimFolder(sub.getName());
                if (dim == null) continue;
                enqueueRegionDir(dim, new File(sub, "region"));
            }
        }
        return true;
    }

    private void enqueueRegionDir(int dim, File regionDir) {
        if (regionDir == null || !regionDir.isDirectory()) return;
        File[] files = regionDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            int[] rc = parseRegionName(f.getName());
            if (rc == null) continue;
            for (long ck : existingChunks(f, rc[0], rc[1])) {
                int cx = (int) (ck >> 32);
                int cz = (int) ck;
                if (BiomeIndex.INSTANCE.isHandled(dim, cx, cz)) continue; // sweep already got it
                queue.add(new long[]{ dim, cx, cz });
            }
        }
    }

    // ---- processing ----------------------------------------------------

    private void processChunk(MinecraftServer server, int dim, int cx, int cz) {
        try {
            WorldServer world = server.worldServerForDimension(dim); // lazy-loads the dim if needed
            if (world == null) return;
            WorldChunkManager wcm = world.getWorldChunkManager();

            int bx0 = cx << 4, bz0 = cz << 4;
            // One seeded lookup for the whole 16x16 chunk. getBiomeGenAt fills
            // row-major [x + z*width]; for a 16-wide chunk that is (z<<4)|x,
            // which is exactly the column layout recordRaw / biomeAt use.
            BiomeGenBase[] biomes = wcm.getBiomeGenAt(null, bx0, bz0, 16, 16, false);
            if (biomes == null || biomes.length < 256) return;

            int[] cols = new int[256];
            for (int i = 0; i < 256; i++) {
                cols[i] = (biomes[i] != null) ? biomes[i].biomeID : -1;
            }
            BiomeIndex.INSTANCE.recordRaw(dim, cx, cz, cols);
        } catch (Throwable ignored) {
            // skip a chunk that can't be read this pass
        }
    }

    // ---- region-file plumbing -----------------------------------------

    /** "DIM-1" -> -1, "DIM1" -> 1; null if not a dimension folder. */
    private static Integer parseDimFolder(String name) {
        if (name == null || !name.startsWith("DIM")) return null;
        try { return Integer.valueOf(Integer.parseInt(name.substring(3))); }
        catch (NumberFormatException e) { return null; }
    }

    /** Parse r.<rx>.<rz>.mca -> {rx, rz}, or null. */
    private static int[] parseRegionName(String name) {
        if (name == null || !name.endsWith(".mca") || !name.startsWith("r.")) return null;
        String[] p = name.split("\\.");
        if (p.length != 4) return null;
        try { return new int[]{ Integer.parseInt(p[1]), Integer.parseInt(p[2]) }; }
        catch (NumberFormatException e) { return null; }
    }

    /**
     * Chunk keys present in a region file, read from its 4 KiB location header.
     * Entry i (i = localX + localZ*32) is a 4-byte big-endian int; nonzero
     * means the chunk is stored. No decompression, no NBT.
     */
    private static List<Long> existingChunks(File mca, int rx, int rz) {
        List<Long> out = new ArrayList<Long>();
        if (mca.length() < 4096) return out;
        DataInputStream in = null;
        try {
            in = new DataInputStream(new FileInputStream(mca));
            for (int i = 0; i < 1024; i++) {
                int loc = in.readInt();
                if (loc == 0) continue;
                int cx = (rx << 5) + (i & 31);
                int cz = (rz << 5) + ((i >> 5) & 31);
                out.add(((long) cx << 32) | (cz & 0xFFFFFFFFL));
            }
        } catch (IOException ignored) {
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
        }
        return out;
    }
}
