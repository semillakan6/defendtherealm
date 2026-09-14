package com.createdtr.defendtherealm;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = CreateDefendtheRealm.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = CreateDefendtheRealm.MODID, value = Dist.CLIENT)
public class CreateDefendtheRealmClient {
    public CreateDefendtheRealmClient(ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Do not forget to add translations for your config options to the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        NeoForge.EVENT_BUS.addListener(CreateDefendtheRealmClient::renderAssaultRoute);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // Some client setup code
        CreateDefendtheRealm.LOGGER.info("HELLO FROM CLIENT SETUP");
        CreateDefendtheRealm.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
    }

    static void renderAssaultRoute(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.options.advancedItemTooltips || minecraft.level == null) return;
        var debug = com.createdtr.defendtherealm.network.AssaultDebugState.latest();
        if (debug == null || !minecraft.level.dimension().location().toString().equals(debug.dimension())
                || Math.abs(minecraft.level.getGameTime() - debug.serverTime()) > 40) return;
        var pose = event.getPoseStack();
        var camera = event.getCamera().getPosition();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        var buffers = minecraft.renderBuffers().bufferSource();
        var lines = buffers.getBuffer(RenderType.lines());
        for (int i = 0; i < debug.route().length; i++) {
            BlockPos point = BlockPos.of(debug.route()[i]);
            float r = i == debug.cursor() ? 0.1F : 0.1F;
            float g = i == debug.cursor() ? 1.0F : 0.8F;
            float b = i == debug.cursor() ? 0.1F : 1.0F;
            LevelRenderer.renderLineBox(pose, lines, new AABB(point).inflate(0.08), r, g, b, 0.9F);
        }
        LevelRenderer.renderLineBox(pose, lines, AABB.ofSize(new net.minecraft.world.phys.Vec3(
                debug.targetX(), debug.targetY(), debug.targetZ()), 1.2, 1.2, 1.2), 1.0F, 0.2F, 1.0F, 1.0F);
        LevelRenderer.renderLineBox(pose, lines, AABB.ofSize(new net.minecraft.world.phys.Vec3(
                debug.shipX(), debug.shipY(), debug.shipZ()), 1.0, 1.0, 1.0), 1.0F, 1.0F, 1.0F, 1.0F);
        if (debug.hasOrbit()) LevelRenderer.renderLineBox(pose, lines,
                new AABB(BlockPos.of(debug.orbit())).inflate(0.2), 1.0F, 0.65F, 0.0F, 1.0F);
        if (debug.hasBlocker()) LevelRenderer.renderLineBox(pose, lines,
                new AABB(BlockPos.of(debug.blocker())).inflate(0.15), 1.0F, 0.0F, 0.0F, 1.0F);
        buffers.endBatch(RenderType.lines());
        pose.popPose();
    }
}
