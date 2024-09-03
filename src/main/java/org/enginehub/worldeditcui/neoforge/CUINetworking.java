/*
 * Copyright (c) 2011-2024 WorldEditCUI team and contributors
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.enginehub.worldeditcui.neoforge;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.PayloadRegistration;
import org.enginehub.worldeditcui.neoforge.mixins.NetworkRegistryAccessor;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * Networking wrappers to integrate nicely with MultiConnect.
 *
 * <p>These methods generally first call </p>
 */
final class CUINetworking {

    static final String CHANNEL_LEGACY = "WECUI"; // pre-1.13 channel name
    static final ResourceLocation CHANNEL_WECUI = ResourceLocation.fromNamespaceAndPath("worldedit", "cui");

    private CUINetworking() {
    }

    public record ClientCuiPacket(String text) implements CustomPacketPayload {
        public static final Type<ClientCuiPacket> TYPE = new Type<>(CHANNEL_WECUI);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }


    private static Class<?> weCuiPacketClass;
    private static Method weCuiPacketTextMethod;
    private static Constructor<?> weCuiPacketConstructor;
    static {
        try {
            weCuiPacketClass = Class.forName("com.sk89q.worldedit.neoforge.net.handler.WECUIPacketHandler$CuiPacket");
            weCuiPacketTextMethod = weCuiPacketClass.getMethod("text");
            weCuiPacketConstructor = weCuiPacketClass.getConstructor(String.class);
        } catch (Exception ignored) {

        }
    }

    @SuppressWarnings("UnstableApiUsage, unchecked, rawtypes")
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrations = NetworkRegistryAccessor.getPAYLOAD_REGISTRATIONS();
        if (registrations.get(ConnectionProtocol.PLAY).containsKey(CHANNEL_WECUI)) {
            PayloadRegistration<?> existingHandler = registrations.get(ConnectionProtocol.PLAY).get(CHANNEL_WECUI);
            PayloadRegistration<?> newHandler = new PayloadRegistration(existingHandler.type(), existingHandler.codec(), (payload, context) -> {
                if (context.player() instanceof ServerPlayer) {
                    // Server-side packet, let WE handle it
                    ((IPayloadHandler)existingHandler.handler()).handle(payload, context);
                    return;
                }
                try {
                    NeoForgeModWorldEditCUI.getInstance().onPluginMessage((String) weCuiPacketTextMethod.invoke(payload));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, existingHandler.protocols(), existingHandler.flow(), existingHandler.version(), existingHandler.optional());
            registrations.get(ConnectionProtocol.PLAY).put(CHANNEL_WECUI, newHandler);
        } else {
            event.registrar("1")
                    .optional()
                    .playBidirectional(
                            ClientCuiPacket.TYPE,
                            CustomPacketPayload.codec(
                                    (packet, buffer) -> buffer.writeCharSequence(packet.text(), StandardCharsets.UTF_8),
                                    buffer -> new ClientCuiPacket(buffer.readCharSequence(buffer.readableBytes(), StandardCharsets.UTF_8).toString())
                            ),
                            (payload, context) -> {

                            }
                    );
        }
    }

    public static void send(final ClientPacketListener handler, final String text) {
        if (weCuiPacketClass != null) {
            try {
                Object packet = weCuiPacketConstructor.newInstance(text);
                PacketDistributor.sendToServer((CustomPacketPayload) packet);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        } else {
            PacketDistributor.sendToServer(new ClientCuiPacket(text));
        }
    }
}
