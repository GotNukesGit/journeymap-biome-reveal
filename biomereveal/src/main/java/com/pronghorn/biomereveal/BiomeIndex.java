package com.pronghorn.biomereveal;

import net.minecraft.client.Minecraft;
import net.minecraft.world.chunk.Chunk;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Persistent per-column biome IDs for every chunk you've explored.
 *
 * JourneyMap keeps none of this: its ChunkMD cache is expireAfterAccess(30s),
 * in-memory only, and the map tiles it writes to disk are rendered PNGs, not
 * biome IDs. So Biome Reveal records biomes itself and answers biomeAt(x,z)
 * for anywhere you've had loaded.
 *
 * Storage: byte[256] per chunk (one biome id per column). Chunks are grouped
 * into 32x32-chunk regions, one gzip file per region under
 *   <.minecraft>/biomereveal/<worldId>/DIM<dim>/r.<rx>.<rz>.bz
 *
 * On the CLIENT a received chunk is always fully populated once it exists (it
 * is created together with fillChunk), so every stored column is a real id;
 * "unknown" is expressed only by a chunk being absent from the store (-1).
 */
public final class BiomeIndex {

    public static final BiomeIndex INSTANCE = new BiomeIndex();

    private static final int COLS = 256;          // 16 * 16 columns per chunk
    private static final int RSHIFT = 5;          // 32 chunks per region edge

    /** dim -> regionKey -> chunkKey -> byte[256] */
    private final Map<Integer, Map<Long, Map<Long, byte[]>>> mem = new HashMap<Integer, Map<Long, Map<Long, byte[]>>>();
    /** dim -> set of dirty regionKeys */
    private final Map<Integer, Set<Long>> dirty = new HashMap<Integer, Set<Long>>();
    /** dim -> chunkKeys already handled this session (skip re-recording) */
    private final Map<Integer, Set<Long>> handled = new HashMap<Integer, Set<Long>>();

    private File baseDir;

    private BiomeIndex() {}

    // ---- lifecycle -----------------------------------------------------

    /** Call on client world load. worldId should be stable per save/server. */
    public synchronized void open(String worldId) {
        this.baseDir = new File(Minecraft.getMinecraft().mcDataDir, "biomereveal/" + sanitize(worldId));
        this.baseDir.mkdirs();
        this.mem.clear();
        this.dirty.clear();
        this.handled.clear();
    }

    public synchronized void close() {
        flush();
        this.mem.clear();
        this.dirty.clear();
        this.handled.clear();
        this.baseDir = null;
    }

    // ---- recording -----------------------------------------------------

    /** Record a fully-populated (loaded) chunk. Idempotent and cheap to spam. */
    public synchronized void record(int dim, Chunk chunk) {
        if (chunk == null) return;
        byte[] src = chunk.getBiomeArray();
        if (src == null) return;
        store(dim, chunk.xPosition, chunk.zPosition, src);
    }

    /**
     * Record a raw biome column array read straight from a save's region file.
     * Used by the singleplayer backfill so previously-explored chunks work too.
     */
    public synchronized void recordRaw(int dim, int cx, int cz, byte[] cols) {
        if (cols == null) return;
        store(dim, cx, cz, cols);
    }

    private void store(int dim, int cx, int cz, byte[] src) {
        if (src.length < COLS) return;
        long ck = key(cx, cz);
        Set<Long> done = handled.get(dim);
        if (done == null) { done = new HashSet<Long>(); handled.put(dim, done); }
        if (done.contains(ck)) return;                     // already stored this session

        byte[] cols = new byte[COLS];
        System.arraycopy(src, 0, cols, 0, COLS);

        Map<Long, byte[]> region = loadRegion(dim, cx >> RSHIFT, cz >> RSHIFT);
        byte[] prev = region.get(ck);
        if (prev == null || !Arrays.equals(prev, cols)) {
            region.put(ck, cols);
            markDirty(dim, key(cx >> RSHIFT, cz >> RSHIFT));
        }
        done.add(ck);
    }

    /** True once the one-time save-file backfill has completed for this world. */
    public synchronized boolean isBackfilled() {
        return baseDir != null && new File(baseDir, ".backfilled").exists();
    }

    /** Mark the save-file backfill complete so it doesn't run again. */
    public synchronized void markBackfilled() {
        if (baseDir == null) return;
        try { new File(baseDir, ".backfilled").createNewFile(); } catch (IOException ignored) {}
    }

    // ---- query ---------------------------------------------------------

    /** Biome id at block (x,z) in dim, or -1 if that chunk isn't in the store. */
    public synchronized int biomeAt(int dim, int blockX, int blockZ) {
        int cx = blockX >> 4;
        int cz = blockZ >> 4;
        Map<Long, byte[]> region = loadRegion(dim, cx >> RSHIFT, cz >> RSHIFT);
        byte[] cols = region.get(key(cx, cz));
        if (cols == null) return -1;
        int col = ((blockZ & 0xF) << 4) | (blockX & 0xF);
        return cols[col] & 0xFF;
    }

    // ---- persistence ---------------------------------------------------

    public synchronized void flush() {
        if (baseDir == null) return;
        for (Map.Entry<Integer, Set<Long>> e : dirty.entrySet()) {
            int dim = e.getKey();
            for (Long rk : e.getValue()) saveRegion(dim, rk);
            e.getValue().clear();
        }
    }

    private Map<Long, byte[]> loadRegion(int dim, int rx, int rz) {
        Map<Long, Map<Long, byte[]>> byRegion = mem.get(dim);
        if (byRegion == null) { byRegion = new HashMap<Long, Map<Long, byte[]>>(); mem.put(dim, byRegion); }
        long rk = key(rx, rz);
        Map<Long, byte[]> region = byRegion.get(rk);
        if (region != null) return region;

        region = new HashMap<Long, byte[]>();
        File f = regionFile(dim, rx, rz);
        if (f.exists()) {
            DataInputStream in = null;
            try {
                in = new DataInputStream(new GZIPInputStream(new BufferedInputStream(new FileInputStream(f))));
                int n = in.readInt();
                for (int i = 0; i < n; i++) {
                    long ck = in.readLong();
                    byte[] cols = new byte[COLS];
                    in.readFully(cols);
                    region.put(ck, cols);
                }
            } catch (IOException ignored) {
                // corrupt/partial file -> start that region fresh
            } finally {
                closeQuietly(in);
            }
        }
        byRegion.put(rk, region);
        return region;
    }

    private void saveRegion(int dim, long rk) {
        Map<Long, Map<Long, byte[]>> byRegion = mem.get(dim);
        if (byRegion == null) return;
        Map<Long, byte[]> region = byRegion.get(rk);
        if (region == null || region.isEmpty()) return;

        int rx = (int) (rk >> 32);
        int rz = (int) rk;
        File f = regionFile(dim, rx, rz);
        f.getParentFile().mkdirs();
        DataOutputStream out = null;
        try {
            out = new DataOutputStream(new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(f))));
            out.writeInt(region.size());
            for (Map.Entry<Long, byte[]> c : region.entrySet()) {
                out.writeLong(c.getKey());
                out.write(c.getValue());
            }
        } catch (IOException ignored) {
        } finally {
            closeQuietly(out);
        }
    }

    // ---- helpers -------------------------------------------------------

    private File regionFile(int dim, int rx, int rz) {
        return new File(baseDir, "DIM" + dim + "/r." + rx + "." + rz + ".bz");
    }

    private void markDirty(int dim, long rk) {
        Set<Long> set = dirty.get(dim);
        if (set == null) { set = new HashSet<Long>(); dirty.put(dim, set); }
        set.add(rk);
    }

    private static long key(int a, int b) {
        return ((long) a << 32) | (b & 0xFFFFFFFFL);
    }

    private static String sanitize(String s) {
        return s == null ? "world" : s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) try { c.close(); } catch (IOException ignored) {}
    }
}
