package com.pronghorn.biomereveal.mixin;

import com.pronghorn.biomereveal.BiomeReveal;
import journeymap.client.model.BlockCoordIntPair;
import journeymap.client.ui.fullscreen.layer.BlockInfoLayer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Rewrites the fullscreen-map hover label so the biome (name + BiomeDictionary
 * tags) shows for ANY discovered chunk, not just loaded ones.
 *
 * Vanilla JourneyMap {@code BlockInfoLayer.onMouseMove}:
 *   loaded chunk   -> "<coords> <biomeName>"   (no tags)
 *   unloaded chunk -> "<coords>"               (no biome at all)
 *
 * Both branches funnel through the same call:
 *   {@code BlockInfoLayer$BlockInfoStep.update(String, double, double)}
 * so a single @Redirect catches every case. We rebuild the label from the
 * hovered coord via our persistent index (+ live fallback for loaded chunks),
 * append the tags, then forward to the original update() through the invoker.
 *
 * Rebuilding from the coord (rather than appending to the incoming text) keeps
 * the "coord unchanged" branch idempotent — no double-appended tags.
 *
 * remap = false: onMouseMove and update are JourneyMap methods (not obfuscated)
 * and the descriptors reference deobfuscated Minecraft class names that are
 * identical at runtime, so no refmap remapping is needed for the targets.
 */
@Mixin(value = BlockInfoLayer.class, remap = false)
public abstract class MixinBlockInfoLayer {

    @Redirect(
        method = "onMouseMove",
        at = @At(
            value = "INVOKE",
            target = "Ljourneymap/client/ui/fullscreen/layer/BlockInfoLayer$BlockInfoStep;"
                   + "update(Ljava/lang/String;DD)V"
        )
    )
    private void biomereveal$rewriteLabel(Object step, String text, double x, double y,
                                          Minecraft mc, double mouseX, double mouseY,
                                          int gridWidth, int gridHeight,
                                          BlockCoordIntPair blockCoord) {
        String label = text;
        try {
            String enriched = BiomeReveal.enrich(mc, blockCoord.x, blockCoord.z, text);
            if (enriched != null) {
                label = enriched;
            }
        } catch (Throwable ignored) {
            // fall back to JourneyMap's original text
        }
        ((BlockInfoStepInvoker) step).biomereveal$update(label, x, y);
    }
}
