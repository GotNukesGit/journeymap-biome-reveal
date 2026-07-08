package com.pronghorn.biomereveal.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes the package-private {@code update(String, double, double)} on
 * JourneyMap's private inner class {@code BlockInfoLayer$BlockInfoStep}.
 *
 * Applying this @Mixin makes that inner class implement this interface, so the
 * step instance handed to our @Redirect can be cast to it and invoked without
 * reflection or an access transformer. Non-Minecraft target => remap = false.
 */
@Mixin(targets = "journeymap.client.ui.fullscreen.layer.BlockInfoLayer$BlockInfoStep", remap = false)
public interface BlockInfoStepInvoker {

    @Invoker("update")
    void biomereveal$update(String text, double x, double y);
}
