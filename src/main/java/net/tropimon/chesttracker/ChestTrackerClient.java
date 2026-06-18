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
    
    // Listes pour chaque type de balise
    private static final List<BlockPos> chestPositions = new ArrayList<>();
    private static final List<BlockPos> safariPositions = new ArrayList<>();
    private static final List<BlockPos> safariBallPositions = new ArrayList<>();

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
                    synchronized (chestPositions) { chestPositions.clear(); }
                    synchronized (safariPositions) { safariPositions.clear(); }
                    synchronized (safariBallPositions) { safariBallPositions.clear(); }
                }
            }

            if (!active) return;

            scanTick++;
            if (scanTick >= 100) { // Scan toutes les 5 secondes
                scanTick = 0;

                BlockPos playerPos = client.player.getBlockPos();
                List<BlockPos> tempChestPos = new ArrayList<>();
                List<BlockPos> tempSafariPos = new ArrayList<>();
                List<BlockPos> tempSafariBallPos = new ArrayList<>();

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
                                
                                boolean isChestTarget = false;
                                boolean isSafariTarget = false;
                                boolean isSafariBallTarget = false;
                                String className = be.getClass().getName().toLowerCase();

                                // Vérification du nom du bloc (Safari et Safari Ball)
                                if (be instanceof net.minecraft.util.Nameable nameable) {
                                    Text dispNameText = nameable.getDisplayName();
                                    if (dispNameText != null) {
                                        String dispName = dispNameText.getString().toLowerCase();
                                        
                                        if (dispName.contains("safari ball loot")) {
                                            if (!isChestOpened(be, client.player.getUuid())) {
                                                isSafariBallTarget = true;
                                            }
                                        } else if (dispName.contains("safari") && (dispName.contains("sable suspect") || dispName.contains("gravier suspect"))) {
                                            if (!isChestOpened(be, client.player.getUuid())) {
                                                isSafariTarget = true;
                                            }
                                        }
                                    }
                                }

                                // Si ce n'est pas un bloc Safari, on regarde si c'est un coffre/tonneau
                                if (!isSafariTarget && !isSafariBallTarget) {
                                    if (className.contains("lootr") && (className.contains("chest") || className.contains("barrel"))) {
                                        if (!isChestOpened(be, client.player.getUuid())) {
                                            isChestTarget = true;
                                        }
                                    } 
                                    else if (be instanceof net.minecraft.block.entity.LootableContainerBlockEntity lootable) {
                                        if (lootable.getLootTable() != null) {
                                            isChestTarget = true;
                                        }
                                    }
                                }

                                if (isSafariBallTarget) tempSafariBallPos.add(pos.toImmutable());
                                if (isSafariTarget) tempSafariPos.add(pos.toImmutable());
                                if (isChestTarget) tempChestPos.add(pos.toImmutable());
                            }
                        }
                    }
                }

                synchronized (chestPositions) {
                    chestPositions.clear();
                    chestPositions.addAll(tempChestPos);
                }
                synchronized (safariPositions) {
                    safariPositions.clear();
                    safariPositions.addAll(tempSafariPos);
                }
                synchronized (safariBallPositions) {
                    safariBallPositions.clear();
                    safariBallPositions.addAll(tempSafariBallPos);
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

            // --- 1. Balises VERTES (Safari Ball Loot) ---
            float rG = 0.0f; float gG = 1.0f; float bG = 0.0f; float aG = 1.0f;
            synchronized (safariBallPositions) {
                if (!safariBallPositions.isEmpty()) {
                    Iterator<BlockPos> iterator = safariBallPositions.iterator();
                    while (iterator.hasNext()) {
                        BlockPos pos = iterator.next();
                        double distSq = client.player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                        
                        if (distSq <= 36.0) { 
                            net.minecraft.block.entity.BlockEntity be = client.world.getBlockEntity(pos);
                            if (be == null) { iterator.remove(); continue; }
                            
                            boolean opened = false;
                            if (be instanceof net.minecraft.util.Nameable nameable) {
                                Text dispNameText = nameable.getDisplayName();
                                if (dispNameText == null || !dispNameText.getString().toLowerCase().contains("safari ball loot")) {
                                    opened = true;
                                }
                            }
                            if (!opened && isChestOpened(be, client.player.getUuid())) { opened = true; }
                            if (opened) { iterator.remove(); continue; }
                        }
                        
                        matrices.push();
                        matrices.translate(pos.getX() - cameraPos.x, pos.getY() - cameraPos.y, pos.getZ() - cameraPos.z);
                        WorldRenderer.drawBox(matrices, buffer, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0, rG, gG, bG, aG);
                        WorldRenderer.drawBox(matrices, buffer, 0.4, 1.0, 0.4, 0.6, 300.0, 0.6, rG, gG, bG, aG);
                        matrices.pop();
                    }
                }
            }

            // --- 2. Balises ROUGES (Safari Sable/Gravier) ---
            float rR = 1.0f; float gR = 0.0f; float bR = 0.0f; float aR = 1.0f;
            synchronized (safariPositions) {
                if (!safariPositions.isEmpty()) {
                    Iterator<BlockPos> iterator = safariPositions.iterator();
                    while (iterator.hasNext()) {
                        BlockPos pos = iterator.next();
                        double distSq = client.player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                        
                        if (distSq <= 36.0) { 
                            net.minecraft.block.entity.BlockEntity be = client.world.getBlockEntity(pos);
                            if (be == null) { iterator.remove(); continue; }
                            
                            boolean opened = false;
                            if (be instanceof net.minecraft.util.Nameable nameable) {
                                Text dispNameText = nameable.getDisplayName();
                                if (dispNameText == null || !dispNameText.getString().toLowerCase().contains("safari")) {
                                    opened = true;
                                }
                            }
                            if (!opened && isChestOpened(be, client.player.getUuid())) { opened = true; }
                            if (opened) { iterator.remove(); continue; }
                        }
                        
                        matrices.push();
                        matrices.translate(pos.getX() - cameraPos.x, pos.getY() - cameraPos.y, pos.getZ() - cameraPos.z);
                        WorldRenderer.drawBox(matrices, buffer, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0, rR, gR, bR, aR);
                        WorldRenderer.drawBox(matrices, buffer, 0.4, 1.0, 0.4, 0.6, 300.0, 0.6, rR, gR, bR, aR);
                        matrices.pop();
                    }
                }
            }

            // --- 3. Balises BLEUES (Coffres/Tonneaux) ---
            float rB = 0.0f; float gB = 0.6f; float bB = 1.0f; float aB = 1.0f;
            synchronized (chestPositions) {
                if (!chestPositions.isEmpty()) {
                    Iterator<BlockPos> iterator = chestPositions.iterator();
                    while (iterator.hasNext()) {
                        BlockPos pos = iterator.next();
                        double distSq = client.player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                        
                        if (distSq <= 36.0) { 
                            net.minecraft.block.entity.BlockEntity be = client.world.getBlockEntity(pos);
                            if (be == null) { iterator.remove(); continue; }
                            
                            boolean opened = false;
                            String className = be.getClass().getName().toLowerCase();
                            if (className.contains("lootr") && (className.contains("chest") || className.contains("barrel"))) {
                                if (isChestOpened(be, client.player.getUuid())) opened = true;
                            } else if (be instanceof net.minecraft.block.entity.LootableContainerBlockEntity lootable) {
                                if (lootable.getLootTable() == null) opened = true;
                            }
                            if (opened) { iterator.remove(); continue; }
                        }
                        
                        matrices.push();
                        matrices.translate(pos.getX() - cameraPos.x, pos.getY() - cameraPos.y, pos.getZ() - cameraPos.z);
                        WorldRenderer.drawBox(matrices, buffer, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0, rB, gB, bB, aB);
                        WorldRenderer.drawBox(matrices, buffer, 0.4, 1.0, 0.4, 0.6, 300.0, 0.6, rB, gB, bB, aB);
                        matrices.pop();
                    }
                }
            }
        });
    }

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
                        if (val.toString().contains(uuidStr)) { return true; }
                    } catch (Exception ignored) {}
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Exception ignored) {}
        return false;
    }
}
