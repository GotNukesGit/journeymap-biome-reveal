package com.pronghorn.biomereveal;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;

import java.util.Map;

/**
 * Minimal FML coremod. Its only job is to make this jar a recognized core
 * plugin so the {@code MixinConfigs} manifest attribute is picked up by the
 * GTNH mixin loader (UniMixins) — exactly how JourneyMap's own jar loads its
 * mixins. No ASM transformers of our own; all patching is done via Mixin.
 *
 * SortingIndex 1001 => loaded after FML's deobfuscation remapper, which is
 * required for mixins that reference deobfuscated Minecraft class names.
 */
@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.Name("BiomeRevealCore")
@IFMLLoadingPlugin.SortingIndex(1001)
public class BiomeRevealCore implements IFMLLoadingPlugin {

    @Override
    public String[] getASMTransformerClass() {
        return null;
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
        // nothing to inject
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
