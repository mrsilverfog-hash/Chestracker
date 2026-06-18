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
    private static final int SCAN_INTERVAL = 30; 
    
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
                    scanTick = 0;
                }
            }

            if (!active) return;

            scanTick++;
            if (scanTick >= SCAN_INTERVAL) {
                scanTick = 0;

                BlockPos playerPos = client.player.getBlockPos();
                List<BlockPos> tempChestPos = new ArrayList<>();
                List<BlockPos> tempSafariPos = new ArrayList<>();
                List<BlockPos> tempSafariBallPos = new ArrayList<>();

                int chunkXStart = (playerPos.getX() - 48) >> 4;
                int chunkXEnd = (playerPos.getX() + 48) >> 4;
                int chunkZStart = (playerPos.getZ() - 48) >> 4;
                int chunkZEnd = (playerPos.getZ() + 48) >> 4;

                for (int cx = chunkXStart; cx <= chunkXEnd; cx++) {
                    for (int cz = chunkZStart; cz <= chunkZEnd; cz++) {
                        WorldChunk chunk = client.world.getChunk(cx, cz);
                        if (chunk == null) continue;

                        net.minecraft.world.chunk.ChunkSection[] sections = chunk.getSectionArray();
                        int bottomY = chunk.getBottomY();
                        for (int sIdx = 0; sIdx < sections.length; sIdx++) {
                            net.minecraft.world.chunk.ChunkSection section = sections[sIdx];
                            if (section == null || section.isEmpty()) continue;
                            
                            int blockYBase = bottomY + (sIdx * 16);
                            if (blockYBase < playerPos.getY() - 40 || blockYBase > playerPos.getY() + 40) continue;
                            
                            for (int x = 0; x < 16; x++) {
                                for (int z = 0; z < 16; z++) {
                                    for (int y = 0; y < 16; y++) {
                                        net.minecraft.block.BlockState state = section.getBlockState(x, y, z);
                                        if (state.isAir()) continue;
                                        
                                        String id = net.minecraft.registry.Registries.BLOCK.getId(state.getBlock()).toString().toLowerCase();
                                        
                                        if (id.contains("safari")) {
                                            int absX = (cx << 4) + x;
                                            int absY = blockYBase + y;
                                            int absZ = (cz << 4) + z;
                                            BlockPos targetPos = new BlockPos(absX, absY, absZ);
                                            
                                            net.minecraft.block.entity.BlockEntity be = chunk.getBlockEntity(targetPos);
                                            
                                            if (id.contains("ball")) {
                                                if (!isSafariBallLooted(state, be)) {
                                                    tempSafariBallPos.add(targetPos.toImmutable());
                                                }
                                            } else if (id.contains("sand") || id.contains("gravel")) {
                                                if (!isBlockLooted(state)) {
                                                    tempSafariPos.add(targetPos.toImmutable());
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Recherche des coffres Lootr
                        for (BlockPos pos : chunk.getBlockEntityPositions()) {
                            net.minecraft.block.entity.BlockEntity be = chunk.getBlockEntity(pos);
                            if (be == null) continue;
                            String className = be.getClass().getName().toLowerCase();
                            if (className.contains("lootr") && (className.contains("chest") || className.contains("barrel"))) {
                                if (!isChestOpened(be, client.player.getUuid())) {
                                    tempChestPos.add(pos.toImmutable());
                                }
                            }
                        }
                    }
                }

                synchronized (chestPositions) { chestPositions.clear(); chestPositions.addAll(tempChestPos); }
                synchronized (safariPositions) { safariPositions.clear(); safariPositions.addAll(tempSafariPos); }
                synchronized (safariBallPositions) { safariBallPositions.clear(); safariBallPositions.addAll(tempSafariBallPos); }
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
                Iterator<BlockPos> iterator = safariBallPositions.iterator();
                while (iterator.hasNext()) {
                    BlockPos pos = iterator.next();
                    net.minecraft.block.BlockState state = client.world.getBlockState(pos);
                    net.minecraft.block.entity.BlockEntity be = client.world.getBlockEntity(pos);
                    
                    if (state == null || state.isAir() || isSafariBallLooted(state, be)) { 
                        iterator.remove(); 
                        continue; 
                    }
                    
                    matrices.push();
                    matrices.translate(pos.getX() - cameraPos.x, pos.getY() - cameraPos.y, pos.getZ() - cameraPos.z);
                    WorldRenderer.drawBox(matrices, buffer, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0, rG, gG, bG, aG);
                    WorldRenderer.drawBox(matrices, buffer, 0.4, 1.0, 0.4, 0.6, 300.0, 0.6, rG, gG, bG, aG);
                    matrices.pop();
                }
            }

            // --- 2. Balises ROUGES (Sable / Gravier Suspect Safari) ---
            float rR = 1.0f; float gR = 0.0f; float bR = 0.0f; float aR = 1.0f;
            synchronized (safariPositions) {
                Iterator<BlockPos> iterator = safariPositions.iterator();
                while (iterator.hasNext()) {
                    BlockPos pos = iterator.next();
                    net.minecraft.block.BlockState state = client.world.getBlockState(pos);
                    
                    if (state == null || state.isAir() || isBlockLooted(state)) { 
                        iterator.remove(); 
                        continue; 
                    }
                    
                    matrices.push();
                    matrices.translate(pos.getX() - cameraPos.x, pos.getY() - cameraPos.y, pos.getZ() - cameraPos.z);
                    WorldRenderer.drawBox(matrices, buffer, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0, rR, gR, bR, aR);
                    WorldRenderer.drawBox(matrices, buffer, 0.4, 1.0, 0.4, 0.6, 300.0, 0.6, rR, gR, bR, aR);
                    matrices.pop();
                }
            }

            // --- 3. Balises BLEUES (Coffres Lootr) ---
            float rB = 0.0f; float gB = 0.6f; float bB = 1.0f; float aB = 1.0f;
            synchronized (chestPositions) {
                Iterator<BlockPos> iterator = chestPositions.iterator();
                while (iterator.hasNext()) {
                    BlockPos pos = iterator.next();
                    net.minecraft.block.BlockState state = client.world.getBlockState(pos);
                    net.minecraft.block.entity.BlockEntity be = client.world.getBlockEntity(pos);
                    
                    if (state == null || state.isAir() || be == null || isChestOpened(be, client.player.getUuid())) { 
                        iterator.remove(); 
                        continue; 
                    }
                    
                    matrices.push();
                    matrices.translate(pos.getX() - cameraPos.x, pos.getY() - cameraPos.y, pos.getZ() - cameraPos.z);
                    WorldRenderer.drawBox(matrices, buffer, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0, rB, gB, bB, aB);
                    WorldRenderer.drawBox(matrices, buffer, 0.4, 1.0, 0.4, 0.6, 300.0, 0.6, rB, gB, bB, aB);
                    matrices.pop();
                }
            }
        });
    }

    // Vérification stricte pour le Sable et Gravier suspect
    private static boolean isBlockLooted(net.minecraft.block.BlockState state) {
        if (state == null || state.isAir()) return true;

        for (net.minecraft.state.property.Property<?> property : state.getProperties()) {
            if (property.getName().equalsIgnoreCase("looted")) {
                Object value = state.get(property);
                if (value != null && value.toString().equalsIgnoreCase("true")) {
                    return true; // Le bloc affiche explicitement "looted: true", donc pas de rayon.
                }
            }
        }
        return false;
    }

    // Vérification stricte pour la Safari Ball
    private static boolean isSafariBallLooted(net.minecraft.block.BlockState state, net.minecraft.block.entity.BlockEntity be) {
        if (state == null || state.isAir()) return true;

        // Si la Safari Ball utilise aussi la propriété looted dans son bloc
        for (net.minecraft.state.property.Property<?> property : state.getProperties()) {
            if (property.getName().equalsIgnoreCase("looted")) {
                Object value = state.get(property);
                if (value != null && value.toString().equalsIgnoreCase("true")) return true;
            }
        }

        // Si la BlockEntity a disparu ou est vide
        if (be == null) return true;

        if (be instanceof net.minecraft.block.entity.LootableContainerBlockEntity lootable) {
            if (lootable.getLootTable() == null && lootable.isEmpty()) return true;
        }
        if (be instanceof net.minecraft.inventory.Inventory inv) {
            if (inv.isEmpty()) return true;
        }

        return false;
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
