package com.pronghorn.biomereveal;

import com.gtnewhorizon.gtnhmixins.IEarlyMixinLoader;
import cpw.mods.fml.relauncher.IFMLLoadingPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FML coremod + GTNH early-mixin loader.
 *
 * On GTNH the plain {@code MixinConfigs} manifest attribute is NOT honored by
 * the mixin loader (UniMixins/GTNHMixins); mods must register their mixin config
 * through {@link IEarlyMixinLoader} instead. That's what wires
 * {@code mixins.biomereveal.json} (and thus MixinBlockInfoLayer) into the game.
 *
 * SortingIndex 1001 => loaded after FML's deobfuscation remapper.
 */
@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.Name("BiomeRevealCore")
@IFMLLoadingPlugin.SortingIndex(1001)
public class BiomeRevealCore implements IFMLLoadingPlugin, IEarlyMixinLoader {

    // ---- IFMLLoadingPlugin (no ASM transformers of our own) ----

    @Override
    public String[] getASMTransformerClass() { return null; }

    @Override
    public String getModContainerClass() { return null; }

    @Override
    public String getSetupClass() { return null; }

    @Override
    public void injectData(Map<String, Object> data) { }

    @Override
    public String getAccessTransformerClass() { return null; }

    // ---- IEarlyMixinLoader (registers our mixin config with GTNH's loader) ----

    @Override
    public String getMixinConfig() {
        return "mixins.biomereveal.json";
    }

    @Override
    public List<String> getMixins(Set<String> loadedCoreMods) {
        List<String> mixins = new ArrayList<String>();
        mixins.add("MixinBlockInfoLayer");
        mixins.add("BlockInfoStepInvoker");
        return mixins;
    }
}
