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

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

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

    public record CuiPacket(String text) implements CustomPacketPayload {
        public static final Type<CuiPacket> TYPE = new Type<>(CHANNEL_WECUI);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .optional()
                .playBidirectional(
                        CuiPacket.TYPE,
                        CustomPacketPayload.codec(
                                (packet, buffer) -> buffer.writeCharSequence(packet.text(), StandardCharsets.UTF_8),
                                buffer -> new CuiPacket(buffer.readCharSequence(buffer.readableBytes(), StandardCharsets.UTF_8).toString())
                        ),
                        (payload, context) -> {
                                NeoForgeModWorldEditCUI.getInstance().onPluginMessage(payload.text());
                        }
                );
    }

    public static void send(final ClientPacketListener handler, final String text) {
        PacketDistributor.sendToServer(new CuiPacket(text));
    }
}
