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
import java.util.Iterator;
import java.util.List;

public class ChestTrackerClient implements ClientModInitializer {

    private static KeyBinding toggleKey;
    private static boolean active = false;
    private static int scanTick = 0;
    // Liste sécurisée pour éviter les bugs entre l'affichage et le scan
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
                    synchronized (chestPositions) {
                        chestPositions.clear();
                    }
                }
            }

            if (!active) return;

            scanTick++;
            if (scanTick >= 100) { // Scan de zone toutes les 5 secondes (100 ticks)
                scanTick = 0;

                BlockPos playerPos = client.player.getBlockPos();
                List<BlockPos> tempPositions = new ArrayList<>();

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

                                // Vérification Lootr (uniquement au moment du scan global)
                                if (className.contains("lootr") && (className.contains("chest") || className.contains("barrel"))) {
                                    if (!isChestOpened(be, client.player.getUuid())) {
                                        isTarget = true;
                                    }
                                } 
                                // Vérification Minecraft classique
                                else if (be instanceof net.minecraft.block.entity.LootableContainerBlockEntity lootable) {
                                    if (lootable.getLootTable() != null) {
                                        isTarget = true;
                                    }
                                }

                                if (isTarget) {
                                    tempPositions.add(pos.toImmutable());
                                }
                            }
                        }
                    }
                }

                // Met à jour la liste principale proprement
                synchronized (chestPositions) {
                    chestPositions.clear();
                    chestPositions.addAll(tempPositions);
                }
            }
        });

        WorldRenderEvents.LAST.register(context -> {
            if (!active) return;

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

            synchronized (chestPositions) {
                if (chestPositions.isEmpty()) return;

                Iterator<BlockPos> iterator = chestPositions.iterator();
                while (iterator.hasNext()) {
                    BlockPos pos = iterator.next();
                    
                    // Calcul de la distance avec le joueur
                    double distSq = client.player.getSquaredDistance(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                    
                    // SI LE JOUEUR EST PROCHE (Moins de 6 blocs), on vérifie s'il l'a ouvert
                    if (distSq <= 36.0) { 
                        net.minecraft.block.entity.BlockEntity be = client.world.getBlockEntity(pos);
                        if (be == null) {
                            iterator.remove();
                            continue;
                        }

                        boolean opened = false;
                        String className = be.getClass().getName().toLowerCase();

                        if (className.contains("lootr") && (className.contains("chest") || className.contains("barrel"))) {
                            if (isChestOpened(be, client.player.getUuid())) {
                                opened = true;
                            }
                        } else if (be instanceof net.minecraft.block.entity.LootableContainerBlockEntity lootable) {
                            if (lootable.getLootTable() == null) {
                                opened = true;
                            }
                        }

                        // Si ouvert, on retire de la liste immédiatement et on n'affiche rien
                        if (opened) {
                            iterator.remove();
                            continue;
                        }
                    }

                    // Affichage de la balise (Très léger pour le PC si pas de calcul)
                    matrices.push();
                    matrices.translate(pos.getX() - cameraPos.x, pos.getY() - cameraPos.y, pos.getZ() - cameraPos.z);
                    WorldRenderer.drawBox(matrices, buffer, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0, r, g, b, a);
                    WorldRenderer.drawBox(matrices, buffer, 0.4, 1.0, 0.4, 0.6, 300.0, 0.6, r, g, b, a);
                    matrices.pop();
                }
            }
        });
    }

    // Analyse de la mémoire du coffre
    private static boolean isChestOpened(net.minecraft.block.entity.BlockEntity be, java.util.UUID playerUuid) {
        if (be == null || playerUuid == null) return false;
        String uuidStr = playerUuid.toString();
        try {
            Class<?> clazz = be.getClass();
            while (clazz != null && clazz != Object.class) {
                for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                    try {
                        field.setAccessible(true);
                        Object val = field.get(be);
                        if (val == null) continue;

                        if (val.toString().contains(uuidStr)) {
                            return true;
                        }
                    } catch (Exception ignored) {}
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Exception ignored) {}
        return false;
    }
}
