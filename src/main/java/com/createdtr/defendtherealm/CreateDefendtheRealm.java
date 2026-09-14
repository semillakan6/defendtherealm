package com.createdtr.defendtherealm;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(CreateDefendtheRealm.MODID)
public class CreateDefendtheRealm {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "createdefendtherealm";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();
    // Create a Deferred Register to hold Blocks which will all be registered under the "createdefendtherealm" namespace
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    // Create a Deferred Register to hold Items which will all be registered under the "createdefendtherealm" namespace
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredBlock<com.createdtr.defendtherealm.hq.DevHqBlock> DEV_HQ = BLOCKS.register("dev_hq",
            () -> new com.createdtr.defendtherealm.hq.DevHqBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).strength(0.5F, 0.5F)));
    public static final DeferredItem<BlockItem> DEV_HQ_ITEM = ITEMS.registerSimpleBlockItem("dev_hq", DEV_HQ);
    public static final DeferredBlock<com.createdtr.defendtherealm.hq.DevTargetBlock> DEV_DEFENSE = BLOCKS.register("dev_defense_target",
            () -> new com.createdtr.defendtherealm.hq.DevTargetBlock(com.createdtr.defendtherealm.hq.DevTargetSavedData.Kind.DEFENSE,
                    BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE).strength(1.5F, 3.0F)));
    public static final DeferredBlock<com.createdtr.defendtherealm.hq.DevTargetBlock> DEV_INFRASTRUCTURE = BLOCKS.register("dev_infrastructure_target",
            () -> new com.createdtr.defendtherealm.hq.DevTargetBlock(com.createdtr.defendtherealm.hq.DevTargetSavedData.Kind.INFRASTRUCTURE,
                    BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_YELLOW).strength(1.0F, 2.0F)));
    public static final DeferredItem<BlockItem> DEV_DEFENSE_ITEM = ITEMS.registerSimpleBlockItem("dev_defense_target", DEV_DEFENSE);
    public static final DeferredItem<BlockItem> DEV_INFRASTRUCTURE_ITEM = ITEMS.registerSimpleBlockItem("dev_infrastructure_target", DEV_INFRASTRUCTURE);
    // Create a Deferred Register to hold CreativeModeTabs which will all be registered under the "createdefendtherealm" namespace
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> DTR_TAB = CREATIVE_MODE_TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.createdefendtherealm")) //The language key for the title of your CreativeModeTab
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> DEV_HQ_ITEM.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                output.accept(DEV_HQ_ITEM.get());
                output.accept(DEV_DEFENSE_ITEM.get());
                output.accept(DEV_INFRASTRUCTURE_ITEM.get());
            }).build());

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public CreateDefendtheRealm(IEventBus modEventBus, ModContainer modContainer) {
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(com.createdtr.defendtherealm.network.DtrNetwork::register);

        // Register the Deferred Register to the mod event bus so blocks get registered
        BLOCKS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so items get registered
        ITEMS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so tabs get registered
        CREATIVE_MODE_TABS.register(modEventBus);

        // Register ourselves for server and other game events we are interested in.
        // Note that this is necessary if and only if we want *this* class (CreateDefendtheRealm) to respond directly to events.
        // Do not add this line if there are no @SubscribeEvent-annotated functions in this class, like onServerStarting() below.
        NeoForge.EVENT_BUS.register(this);

        // Register the item to a creative tab
        modEventBus.addListener(this::addCreative);

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Create: Defend the Realm common integration initialized");
    }

    // Add the example block item to the building blocks tab
    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        // The development HQ is intentionally exposed only through the DTR tab.
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // Do something when the server starts
        LOGGER.info("Create: Defend the Realm server integration ready");
    }
}
