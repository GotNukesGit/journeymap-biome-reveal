package com.pronghorn.biomereveal;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.chunk.storage.RegionFile;

import java.io.DataInputStream;
import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-time singleplayer backfill: reads the biome array stored for every
 * generated chunk in the world's save files and feeds it into {@link BiomeIndex}.
 *
 * A singleplayer save keeps, in its Anvil region files, a byte[256] "Biomes"
 * array per chunk — the same biome ids we need. Reading those means the map
 * shows biomes for everywhere you've already explored, not just chunks loaded
 * after the mod was installed.
 *
 * This is inherently local-only: on a remote multiplayer server the region
 * files live on the server, so there is nothing on the client to backfill from.
 *
 * Runs on a low-priority daemon thread so it never stutters the game.
 */
final class SaveBiomeScanner {

    private static final Pattern REGION = Pattern.compile("r\\.-?\\d+\\.-?\\d+\\.mca");
    private static final Pattern DIM = Pattern.compile("DIM(-?\\d+)");

    private SaveBiomeScanner() {}

    static void backfillAsync(final File worldDir) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    scan(worldDir);
                    BiomeIndex.INSTANCE.flush();
                    BiomeIndex.INSTANCE.markBackfilled();
                } catch (Throwable ignored) {
                    // Backfill is best-effort; live recording still works.
                }
            }
        }, "BiomeReveal-Backfill");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void scan(File worldDir) {
        // Overworld region files live directly under <world>/region
        process(0, new File(worldDir, "region"));

        // Other dimensions live under <world>/DIM<n>/region
        File[] children = worldDir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (!child.isDirectory()) continue;
                Matcher m = DIM.matcher(child.getName());
                if (m.matches()) {
                    int dim = Integer.parseInt(m.group(1));
                    if (dim == 0) continue; // already handled above
                    process(dim, new File(child, "region"));
                }
            }
        }
    }

    private static void process(int dim, File regionDir) {
        if (regionDir == null || !regionDir.isDirectory()) return;
        File[] files = regionDir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (!REGION.matcher(file.getName()).matches()) continue;

            RegionFile region = null;
            try {
                region = new RegionFile(file);
                for (int lx = 0; lx < 32; lx++) {
                    for (int lz = 0; lz < 32; lz++) {
                        DataInputStream in = null;
                        try {
                            in = region.getChunkDataInputStream(lx, lz);
                            if (in == null) continue; // no chunk saved at this slot

                            NBTTagCompound tag = CompressedStreamTools.read(in);
                            NBTTagCompound level = tag.getCompoundTag("Level");
                            byte[] biomes = level.getByteArray("Biomes");
                            if (biomes != null && biomes.length >= 256) {
                                int cx = level.getInteger("xPos");
                                int cz = level.getInteger("zPos");
                                BiomeIndex.INSTANCE.recordRaw(dim, cx, cz, biomes);
                            }
                        } catch (Throwable perChunk) {
                            // skip a corrupt/partial chunk
                        } finally {
                            if (in != null) try { in.close(); } catch (Throwable ignored) {}
                        }
                    }
                }
            } catch (Throwable perRegion) {
                // skip an unreadable region file
            } finally {
                if (region != null) try { region.close(); } catch (Throwable ignored) {}
            }
        }
        // Persist progress after each dimension so a crash mid-scan isn't wasted.
        BiomeIndex.INSTANCE.flush();
    }
}
