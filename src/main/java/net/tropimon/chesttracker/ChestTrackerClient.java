package net.tropimon.chesttracker;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class ChestTrackerClient implements ClientModInitializer {

    private static KeyBinding toggleKey;
    private static boolean active = false;
    private static int scanTick = 0;
    private static final List<BlockPos> chestPositions = new ArrayList<>();

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "Activer/Désactiver Scanner",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_RIGHT_BRACKET, 
            "ChestTracker"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;

            while (toggleKey.wasPressed()) {
                active = !active;
                client.inGameHud.setTitleTicks(0, 20, 0);
                client.inGameHud.setTitle(Text.literal(active ? "§aScanner : ACTIVÉ" : "§cScanner : DÉSACTIVÉ"));
                if (!active) {
                    chestPositions.clear();
                }
            }

            if (!active) return;

            scanTick++;
            if (scanTick >= 80) { // Scan toutes les 4 secondes
                scanTick = 0;
                chestPositions.clear();

                BlockPos playerPos = client.player.getBlockPos();

                int chunkXStart = (playerPos.getX() - 64) >> 4;
                int chunkXEnd = (playerPos.getX() + 64) >> 4;
                int chunkZStart = (playerPos.getZ() - 64) >> 4;
                int chunkZEnd = (playerPos.getZ() + 64) >> 4;

                for (int cx = chunkXStart; cx <= chunkXEnd; cx++) {
                    for (int cz = chunkZStart; cz <= chunkZEnd; cz++) {
                        WorldChunk chunk = client.world.getChunk(cx, cz);
                        if (chunk == null) continue;

                        for (BlockPos pos : chunk.getBlockEntityPositions()) {
                            int relX = Math.abs(pos.getX() - playerPos.getX());
                            int relZ = Math.abs(pos.getZ() - playerPos.getZ());
                            int relY = playerPos.getY() - pos.getY();

                            if (relX <= 64 && relZ <= 64 && relY >= 0 && relY <= 64) {
                                net.minecraft.block.entity.BlockEntity be = chunk.getBlockEntity(pos);
                                if (be == null) continue;
                                
                                boolean isTarget = false;
                                String className = be.getClass().getName().toLowerCase();

                                // Détection Lootr : UNIQUEMENT les coffres et tonneaux non fouillés
                                if (className.contains("lootr") && (className.contains("chest") || className.contains("barrel"))) {
                                    if (!isChestOpened(be, client.player.getUuid())) {
                                        isTarget = true;
                                    }
                                } 
                                // Détection des blocs de butin classiques de Minecraft
                                else if (be instanceof net.minecraft.block.entity.LootableContainerBlockEntity lootable) {
                                    if (lootable.getLootTable() != null) {
                                        isTarget = true;
                                    } else if (lootable.hasCustomName() && lootable.getDisplayName() != null) {
                                        String name = lootable.getDisplayName().getString().toLowerCase();
                                        if (name.contains("butin")) {
                                            isTarget = true;
                                        }
                                    }
                                }

                                if (isTarget) {
                                    chestPositions.add(pos.toImmutable());
                                }
                            }
                        }
                    }
                }
            }
        });

        WorldRenderEvents.LAST.register(context -> {
            if (!active || chestPositions.isEmpty()) return;

            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null || client.player == null) return;

            MatrixStack matrices = context.matrixStack();
            VertexConsumerProvider consumers = context.consumers();
            Vec3d cameraPos = context.camera().getPos();
            
            VertexConsumer buffer = consumers.getBuffer(RenderLayer.getLines());

            float r = 0.0f;
            float g = 0.6f;
            float b = 1.0f;
            float a = 1.0f;

            for (BlockPos pos : chestPositions) {
                // Vérification en temps réel pour éteindre le rayon INSTANTANÉMENT à l'ouverture
                net.minecraft.block.entity.BlockEntity be = client.world.getBlockEntity(pos);
                if (be != null && isChestOpened(be, client.player.getUuid())) {
                    continue;
                }

                matrices.push();
                matrices.translate(pos.getX() - cameraPos.x, pos.getY() - cameraPos.y, pos.getZ() - cameraPos.z);

                // 1. Dessine la boîte autour du coffre
                WorldRenderer.drawBox(matrices, buffer, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0, r, g, b, a);
                
                // 2. Dessine le rayon vertical (balise) qui monte vers le ciel
                WorldRenderer.drawBox(matrices, buffer, 0.4, 1.0, 0.4, 0.6, 300.0, 0.6, r, g, b, a);

                matrices.pop();
            }
        });
    }

    // Analyse approfondie du coffre pour détecter si notre joueur l'a déjà ouvert
    private static boolean isChestOpened(net.minecraft.block.entity.BlockEntity be, java.util.UUID playerUuid) {
        if (be == null) return false;
        try {
            Class<?> clazz = be.getClass();
            while (clazz != null && clazz != Object.class) {
                for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                    try {
                        field.setAccessible(true);
                        Object val = field.get(be);
                        if (val == null) continue;

                        // Recherche dans les listes/ensembles d'UUID
                        if (val instanceof java.util.Collection<?> col) {
                            for (Object elem : col) {
                                if (elem != null && (elem.equals(playerUuid) || elem.toString().equalsIgnoreCase(playerUuid.toString()))) {
                                    return true;
                                }
                            }
                        } 
                        // Recherche dans les dictionnaires (Maps)
                        else if (val instanceof java.util.Map<?, ?> map) {
                            for (Object key : map.keySet()) {
                                if (key != null && (key.equals(playerUuid) || key.toString().equalsIgnoreCase(playerUuid.toString()))) {
                                    return true;
                                }
                            }
                        } 
                        // Recherche dans les tableaux simples
                        else if (val instanceof Object[] arr) {
                            for (Object elem : arr) {
                                if (elem != null && (elem.equals(playerUuid) || elem.toString().equalsIgnoreCase(playerUuid.toString()))) {
                                    return true;
                                }
                            }
                        } 
                        // Recherche d'un indicateur oui/non (ex: field "opened")
                        else if (val instanceof Boolean bool) {
                            String name = field.getName().toLowerCase();
                            if ((name.contains("open") || name.contains("loot")) && bool) {
                                return true;
                            }
                        }
                    } catch (Exception ignored) {}
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Exception ignored) {}
        return false;
    }
}
