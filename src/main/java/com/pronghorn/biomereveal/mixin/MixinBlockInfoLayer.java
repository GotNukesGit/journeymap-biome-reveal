package com.pronghorn.biomereveal.mixin;

import com.pronghorn.biomereveal.BiomeReveal;
import journeymap.client.model.BlockCoordIntPair;
import journeymap.client.ui.fullscreen.layer.BlockInfoLayer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Rewrites the fullscreen-map hover label so the biome (name + BiomeDictionary
 * tags) shows for ANY discovered chunk, not just loaded ones.
 *
 * Vanilla JourneyMap BlockInfoLayer.onMouseMove builds a label string and
 * passes it to BlockInfoLayer$BlockInfoStep.update(String, double, double).
 * We can't name that package-private inner class from here, so instead of
 * touching the step object we:
 *   1) capture the hovered coord at HEAD of onMouseMove (@Inject), then
 *   2) rewrite just the String argument of update(...) (@ModifyArg index 0).
 *
 * Rebuilding the label from the captured coord (rather than appending to the
 * incoming text) keeps the "coord unchanged" branch idempotent - no doubled tags.
 *
 * remap = false: onMouseMove and update are JourneyMap methods (not obfuscated).
 */
@Mixin(value = BlockInfoLayer.class, remap = false)
public abstract class MixinBlockInfoLayer {

    @Unique private Minecraft biomereveal$mc;
    @Unique private int biomereveal$x;
    @Unique private int biomereveal$z;
    @Unique private boolean biomereveal$have;

    @Inject(method = "onMouseMove", at = @At("HEAD"))
    private void biomereveal$capture(Minecraft mc, double mouseX, double mouseY,
                                     int gridWidth, int gridHeight, BlockCoordIntPair blockCoord,
                                     CallbackInfoReturnable<?> cir) {
        this.biomereveal$mc = mc;
        if (blockCoord != null) {
            this.biomereveal$x = blockCoord.x;
            this.biomereveal$z = blockCoord.z;
            this.biomereveal$have = true;
        } else {
            this.biomereveal$have = false;
        }
    }

    @ModifyArg(
        method = "onMouseMove",
        at = @At(
            value = "INVOKE",
            target = "Ljourneymap/client/ui/fullscreen/layer/BlockInfoLayer$BlockInfoStep;"
                   + "update(Ljava/lang/String;DD)V"
        ),
        index = 0
    )
    private String biomereveal$rewriteLabel(String original) {
        if (!this.biomereveal$have || this.biomereveal$mc == null) return original;
        try {
            String enriched = BiomeReveal.enrich(this.biomereveal$mc, this.biomereveal$x, this.biomereveal$z, original);
            return enriched != null ? enriched : original;
        } catch (Throwable t) {
            return original;
        }
    }
}
