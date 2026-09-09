package com.createdtr.defendtherealm.data;

import com.createdtr.defendtherealm.CreateDefendtheRealm;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.data.structures.SnbtToNbt;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.data.event.GatherDataEvent;

@EventBusSubscriber(modid = CreateDefendtheRealm.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class PrototypeData {
    @SubscribeEvent public static void gather(GatherDataEvent event) {
        event.getGenerator().addProvider(event.includeServer(), new SnbtToNbt(event.getGenerator().getPackOutput(),
                List.of(Path.of("../src/main/structures"))));
    }
}
