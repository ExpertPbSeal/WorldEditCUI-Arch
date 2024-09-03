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

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import org.enginehub.worldeditcui.WorldEditCUI;
import org.enginehub.worldeditcui.config.CUIConfiguration;
import org.enginehub.worldeditcui.event.listeners.CUIListenerChannel;
import org.enginehub.worldeditcui.event.listeners.CUIListenerWorldRender;
import org.enginehub.worldeditcui.gui.CUIConfigPanel;
import org.enginehub.worldeditcui.neoforge.mixins.LevelRendererAccessor;
import org.enginehub.worldeditcui.render.OptifinePipelineProvider;
import org.enginehub.worldeditcui.render.PipelineProvider;
import org.enginehub.worldeditcui.render.VanillaPipelineProvider;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.MixinEnvironment;

import java.util.List;

/**
 * Fabric mod entrypoint
 *
 * @author Mark Vainomaa
 */
@Mod(NeoForgeModWorldEditCUI.MOD_ID)
public final class NeoForgeModWorldEditCUI {
    private static final int DELAYED_HELO_TICKS = 10;

    public static final String MOD_ID = "worldeditcui";
    private static NeoForgeModWorldEditCUI instance;

    private static final String KEYBIND_CATEGORY_WECUI = "key.categories.worldeditcui";
    private final KeyMapping keyBindToggleUI = key("toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN);
    private final KeyMapping keyBindClearSel = key("clear", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN);
    private final KeyMapping keyBindChunkBorder = key("chunk", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN);

    private static final List<PipelineProvider> RENDER_PIPELINES = List.of(
            new OptifinePipelineProvider(),
            new VanillaPipelineProvider()
    );

    private WorldEditCUI controller;
    private CUIListenerWorldRender worldRenderListener;
    private CUIListenerChannel channelListener;

    private Level lastWorld;
    private LocalPlayer lastPlayer;

    private boolean visible = true;
    private int delayedHelo = 0;

    /**
     * Register a key binding
     *
     * @param name id, will be used as a localization key under {@code key.worldeditcui.<name>}
     * @param type type
     * @param code default value
     * @return new, registered keybinding in the mod category
     */
    private static KeyMapping key(final String name, final InputConstants.Type type, final int code) {
        return new KeyMapping("key." + MOD_ID + '.' + name, type, code, KEYBIND_CATEGORY_WECUI);
    }

    public NeoForgeModWorldEditCUI(IEventBus eventBus, ModContainer container) {
        if (Boolean.getBoolean("wecui.debug.mixinaudit")) {
            MixinEnvironment.getCurrentEnvironment().audit();
        }

        instance = this;

        if (FMLEnvironment.dist.isClient()) {
            eventBus.register(ModEventBusListener.class);
            eventBus.register(CUINetworking.class);
            NeoForge.EVENT_BUS.register(ForgeEventBusListener.class);
            container.registerExtensionPoint(IConfigScreenFactory.class, (mc, parent) ->
                new CUIConfigPanel(parent, instance.getController().getConfiguration()));
        }
    }

    private static class ModEventBusListener {

        @SubscribeEvent
        private static void onRegisterKeyMapping(RegisterKeyMappingsEvent event) {
            event.register(instance.keyBindChunkBorder);
            event.register(instance.keyBindClearSel);
            event.register(instance.keyBindToggleUI);
        }

        @SubscribeEvent
        private static void onClientLifecycleClientStarted(FMLClientSetupEvent event) {
            instance.onGameInitDone(Minecraft.getInstance());
        }
    }

    private static class ForgeEventBusListener {

        @SubscribeEvent
        private static void onClientTickEnd(ClientTickEvent.Post event) {
            instance.onTick(Minecraft.getInstance());
        }

        @SubscribeEvent
        private static void onClientPlayConnectionJoin(ClientPlayerNetworkEvent.LoggingIn event) {
            instance.onJoinGame(Minecraft.getInstance().getConnection());
        }

        @SubscribeEvent
        private static void onWorldRender(RenderLevelStageEvent event) {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
                if (((LevelRendererAccessor)event.getLevelRenderer()).getTransparencyChain() != null) {
                    try {
                        RenderSystem.getModelViewStack().pushMatrix();
                        RenderSystem.getModelViewStack().mul(event.getPoseStack().last().pose());
                        RenderSystem.applyModelViewMatrix();
                        event.getLevelRenderer().getTranslucentTarget().bindWrite(false);
                        instance.onPostRenderEntities(event.getPartialTick());
                    } finally {
                        Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
                        RenderSystem.getModelViewStack().popMatrix();
                    }
                }
            } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_WEATHER) { // TODO is this right?
                if (((LevelRendererAccessor)event.getLevelRenderer()).getTransparencyChain() == null) {
                    try {
                        RenderSystem.depthMask(true);
                        RenderSystem.getModelViewStack().pushMatrix();
                        RenderSystem.getModelViewStack().mul(event.getPoseStack().last().pose());
                        RenderSystem.applyModelViewMatrix();
                        instance.onPostRenderEntities(event.getPartialTick());
                        RenderSystem.depthMask(false);
                    } finally {
                        RenderSystem.getModelViewStack().popMatrix();
                        RenderSystem.applyModelViewMatrix();
                    }
                }
            }
        }
    }

    private void onTick(final Minecraft mc) {
        final CUIConfiguration config = this.controller.getConfiguration();
        final boolean inGame = mc.player != null;
        final boolean clock = mc.getTimer().getGameTimeDeltaPartialTick(true) > 0;

        if (inGame && mc.screen == null) {
            while (this.keyBindToggleUI.consumeClick()) {
                this.visible = !this.visible;
            }

            while (this.keyBindClearSel.consumeClick()) {
                if (mc.player != null) {
                    mc.player.connection.sendUnsignedCommand("/sel");
                }

                if (config.isClearAllOnKey()) {
                    this.controller.clearRegions();
                }
            }

            while (this.keyBindChunkBorder.consumeClick()) {
                this.controller.toggleChunkBorders();
            }
        }

        if (inGame && clock && this.controller != null) {
            if (mc.level != this.lastWorld || mc.player != this.lastPlayer) {
                this.lastWorld = mc.level;
                this.lastPlayer = mc.player;

                this.controller.getDebugger().debug("World change detected, sending new handshake");
                this.controller.clear();
                this.helo(mc.getConnection());
                this.delayedHelo = NeoForgeModWorldEditCUI.DELAYED_HELO_TICKS;
                if (mc.player != null && config.isPromiscuous()) {
                    mc.player.connection.sendUnsignedCommand("we cui"); // Tricks WE to send the current selection
                }
            }

            if (this.delayedHelo > 0) {
                this.delayedHelo--;
                if (this.delayedHelo == 0) {
                    this.helo(mc.getConnection());
                }
            }
        }
    }

    public void onPluginMessage(final String stringPayload) {
        try {
            Minecraft.getInstance().execute(() -> this.channelListener.onMessage(stringPayload));
        } catch (final Exception ex) {
            this.getController().getDebugger().info("Error decoding payload from server", ex);
        }
    }

    public void onGameInitDone(final Minecraft client) {
        this.controller = new WorldEditCUI();
        this.controller.initialise(client);
        this.worldRenderListener = new CUIListenerWorldRender(this.controller, client, RENDER_PIPELINES);
        this.channelListener = new CUIListenerChannel(this.controller);
    }

    public void onJoinGame(final ClientPacketListener handler) {
        this.visible = true;
        this.controller.getDebugger().debug("Joined game, sending initial handshake");
        this.helo(handler);
    }

    public void onPostRenderEntities(final DeltaTracker timer) {
        if (this.visible) {
            this.worldRenderListener.onRender(timer.getGameTimeDeltaPartialTick(true));
        }
    }

    private void helo(final ClientPacketListener handler) {
        final String message = "v|" + WorldEditCUI.PROTOCOL_VERSION;
        CUINetworking.send(handler, message);
    }

    public WorldEditCUI getController()
    {
        return this.controller;
    }

    public static NeoForgeModWorldEditCUI getInstance() {
        return instance;
    }
}
