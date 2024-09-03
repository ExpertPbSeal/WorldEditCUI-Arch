package org.enginehub.worldeditcui.neoforge.mixins;

import net.minecraft.network.ConnectionProtocol;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistration;
import org.jetbrains.annotations.Contract;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(NetworkRegistry.class)
public interface NetworkRegistryAccessor {

    @Accessor
    @Contract("-> _")
    static Map<ConnectionProtocol, Map<ResourceLocation, PayloadRegistration<?>>> getPAYLOAD_REGISTRATIONS() {
        throw new AssertionError();
    }
}
