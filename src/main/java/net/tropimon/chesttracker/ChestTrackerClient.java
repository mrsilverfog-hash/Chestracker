package net.tropimon.chesttracker;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.*;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;
import org.lwjgl.glfw.GLFW;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class ChestTrackerClient implements ClientModInitializer {

    private static KeyBinding toggleKey;
    private static boolean active = false;
    private static int scanTick = 0;
    // 100 ticks = 5 secondes (Minecraft tourne à 20 ticks par seconde)
    private static final int SCAN_INTERVAL = 100; 
    
    private static final List<BlockPos> chestPositions = new ArrayList<>();
    private static final List<BlockPos> safariPositions = new ArrayList<>();
    private static final List<BlockPos> safariBallPositions = new ArrayList<>();

    // Paramètres des rayons importés de vos mods
    private static final int BEAM_HEIGHT = 256;
    private static final float BEAM_INNER_RADIUS = 0.12f;
    private static final float BEAM_OUTER_RADIUS = 0.25f;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "Activer/Désactiver Scanner",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_LEFT_BRACKET, // Touche ^ sur un clavier français
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

                // Recherche à 50 blocs de distance horizontalement
                int chunkXStart = (playerPos.getX() - 50) >> 4;
                int chunkXEnd = (playerPos.getX() + 50) >> 4;
                int chunkZStart = (playerPos.getZ() - 50) >> 4;
                int chunkZEnd = (playerPos.getZ() + 50) >> 4;

                for (int cx = chunkXStart; cx <= chunkXEnd; cx++) {
                    for (int cz = chunkZStart; cz <= chunkZEnd; cz++) {
                        WorldChunk chunk = client.world.getChunk(cx, cz);
                        if (chunk == null) continue;

                        for (BlockPos pos : chunk.getBlockEntityPositions()) {
                            // On ignore les blocs au-dessus ou en dessous (le bloc doit être à la même hauteur Y que le joueur)
                            if (pos.getY() != playerPos.getY()) continue;

                            net.minecraft.block.entity.BlockEntity be = chunk.getBlockEntity(pos);
                            if (be == null) continue;

                            net.minecraft.block.BlockState state = chunk.getBlockState(pos);
                            if (state == null || state.isAir()) continue;

                            String id = net.minecraft.registry.Registries.BLOCK.getId(state.getBlock()).toString().toLowerCase();
                            String className = be.getClass().getName().toLowerCase();

                            // 1. Coffres Lootr
                            if (className.contains("lootr") && (className.contains("chest") || className.contains("barrel"))) {
                                if (!isChestOpened(be, client.player.getUuid())) {
                                    tempChestPos.add(pos.toImmutable());
                                }
                            }
                            // 2. Blocs Safari
                            else if (id.contains("safari")) {
                                if (id.contains("ball")) {
                                    if (!isSafariLooted(state, be, id)) {
                                        tempSafariBallPos.add(pos.toImmutable());
                                    }
                                } else if (id.contains("sand") || id.contains("gravel")) {
                                    if (!isSafariLooted(state, be, id)) {
                                        tempSafariPos.add(pos.toImmutable());
                                    }
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

            // --- 1. DESSIN DES BOÎTES AUTOUR DES BLOCS ---

            // Boîtes VERTES (Safari Ball)
            float rG = 0.0f; float gG = 1.0f; float bG = 0.0f; float aG = 1.0f;
            synchronized (safariBallPositions) {
                Iterator<BlockPos> iterator = safariBallPositions.iterator();
                while (iterator.hasNext()) {
                    BlockPos pos = iterator.next();
                    net.minecraft.block.BlockState state = client.world.getBlockState(pos);
                    if (state == null || state.isAir()) { iterator.remove(); continue; }
                    
                    matrices.push();
                    matrices.translate(pos.getX() - cameraPos.x, pos.getY() - cameraPos.y, pos.getZ() - cameraPos.z);
                    WorldRenderer.drawBox(matrices, buffer, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0, rG, gG, bG, aG);
                    matrices.pop();
                }
            }

            // Boîtes ROUGES (Sable / Gravier Suspect)
            float rR = 1.0f; float gR = 0.0f; float bR = 0.0f; float aR = 1.0f;
            synchronized (safariPositions) {
                Iterator<BlockPos> iterator = safariPositions.iterator();
                while (iterator.hasNext()) {
                    BlockPos pos = iterator.next();
                    net.minecraft.block.BlockState state = client.world.getBlockState(pos);
                    if (state == null || state.isAir()) { iterator.remove(); continue; }
                    
                    matrices.push();
                    matrices.translate(pos.getX() - cameraPos.x, pos.getY() - cameraPos.y, pos.getZ() - cameraPos.z);
                    WorldRenderer.drawBox(matrices, buffer, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0, rR, gR, bR, aR);
                    matrices.pop();
                }
            }

            // Boîtes BLEUES (Coffres Lootr)
            float rB = 0.0f; float gB = 0.6f; float bB = 1.0f; float aB = 1.0f;
            synchronized (chestPositions) {
                Iterator<BlockPos> iterator = chestPositions.iterator();
                while (iterator.hasNext()) {
                    BlockPos pos = iterator.next();
                    net.minecraft.block.BlockState state = client.world.getBlockState(pos);
                    if (state == null || state.isAir()) { iterator.remove(); continue; }
                    
                    matrices.push();
                    matrices.translate(pos.getX() - cameraPos.x, pos.getY() - cameraPos.y, pos.getZ() - cameraPos.z);
                    WorldRenderer.drawBox(matrices, buffer, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0, rB, gB, bB, aB);
                    WorldRenderer.drawBox(matrices, buffer, 0.4, 1.0, 0.4, 0.6, 300.0, 0.6, rB, gB, bB, aB);
                    matrices.pop();
                }
            }

            // --- 2. RENDU DES FAISCEAUX AVANCÉS ET TOURNANTS ---
            long currentTick = client.world.getTime();
            float tickDelta = context.tickCounter().getTickDelta(true);
            float angle = ((currentTick % 360) + tickDelta) * 2.0f;

            Matrix4f viewMatrix = context.matrixStack().peek().getPositionMatrix();

            RenderSystem.setShader(GameRenderer::getPositionColorProgram);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.enableDepthTest();

            Tessellator tessellator = Tessellator.getInstance();

            // FAISCEAUX VERTS (Safari Ball)
            synchronized (safariBallPositions) {
                for (BlockPos pos : safariBallPositions) {
                    Matrix4f modelMatrix = new Matrix4f(viewMatrix);
                    modelMatrix.translate(
                        (float)(pos.getX() + 0.5 - cameraPos.x),
                        (float)(pos.getY() + 1.0 - cameraPos.y),
                        (float)(pos.getZ() + 0.5 - cameraPos.z)
                    );
                    drawBeamLayer(tessellator, modelMatrix, BEAM_INNER_RADIUS, BEAM_HEIGHT, angle,
                        0.10f, 1.0f, 0.20f, 0.92f, 8);
                    drawBeamLayer(tessellator, modelMatrix, BEAM_OUTER_RADIUS, BEAM_HEIGHT, angle * 0.7f,
                        0.10f * 0.7f, 1.0f * 0.7f, 0.20f * 0.7f, 0.65f, 8);
                }
            }

            // FAISCEAUX ROUGES (Sable / Gravier)
            synchronized (safariPositions) {
                for (BlockPos pos : safariPositions) {
                    Matrix4f modelMatrix = new Matrix4f(viewMatrix);
                    modelMatrix.translate(
                        (float)(pos.getX() + 0.5 - cameraPos.x),
                        (float)(pos.getY() + 1.0 - cameraPos.y),
                        (float)(pos.getZ() + 0.5 - cameraPos.z)
                    );
                    drawBeamLayer(tessellator, modelMatrix, BEAM_INNER_RADIUS, BEAM_HEIGHT, angle,
                        1.0f, 0.10f, 0.08f, 0.92f, 8);
                    drawBeamLayer(tessellator, modelMatrix, BEAM_OUTER_RADIUS, BEAM_HEIGHT, angle * 0.7f,
                        1.0f, 0.10f * 0.5f, 0.08f * 0.5f, 0.65f, 8);
                }
            }

            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
        });
    }

    private static void drawBeamLayer(Tessellator tessellator, Matrix4f matrix,
                                       float radius, float height, float rotAngle,
                                       float r, float g, float b, float a, int sides) {
        BufferBuilder buffer = tessellator.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        double angleOffset = Math.toRadians(rotAngle);
        for (int i = 0; i < sides; i++) {
            double angle1 = angleOffset + (2 * Math.PI * i / sides);
            double angle2 = angleOffset + (2 * Math.PI * (i + 1) / sides);
            float x1 = (float)(Math.cos(angle1) * radius);
            float z1 = (float)(Math.sin(angle1) * radius);
            float x2 = (float)(Math.cos(angle2) * radius);
            float z2 = (float)(Math.sin(angle2) * radius);
            buffer.vertex(matrix, x1, 0,      z1).color(r, g, b, a);
            buffer.vertex(matrix, x2, 0,      z2).color(r, g, b, a);
            buffer.vertex(matrix, x2, height, z2).color(r, g, b, 0f);
            buffer.vertex(matrix, x1, height, z1).color(r, g, b, 0f);
        }
        BufferRenderer.drawWithGlobalProgram(buffer.end());
    }

    private static boolean isSafariLooted(net.minecraft.block.BlockState state, net.minecraft.block.entity.BlockEntity be, String id) {
        if (state == null || state.isAir()) return true;

        if (id.contains("sand") || id.contains("gravel")) {
            return id.contains("_e") || id.contains("empty") || id.contains("looted");
        }

        if (id.contains("_e") || id.contains("empty") || id.contains("looted")) {
            return true;
        }

        for (net.minecraft.state.property.Property<?> property : state.getProperties()) {
            String propName = property.getName().toLowerCase();
            Object value = state.get(property);
            if (value == null) continue;
            String valStr = value.toString().toLowerCase();
            
            if (propName.equals("looted") && valStr.equals("true")) return true;
            if (propName.contains("empty") || propName.contains("cleared") || propName.contains("taken")) {
                if (valStr.equals("true")) return true;
            }
            if (propName.equals("dusted") || propName.equals("brushed") || propName.equals("stage")) {
                if (valStr.equals("3")) return true;
            }
        }

        if (be != null) {
            if (be instanceof net.minecraft.block.entity.LootableContainerBlockEntity lootable) {
                if (lootable.getLootTable() == null && lootable.isEmpty()) return true;
            }
            if (be instanceof net.minecraft.inventory.Inventory inv) {
                if (inv.isEmpty()) return true;
            }

            try {
                Class<?> clazz = be.getClass();
                while (clazz != Object.class) {
                    for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                        field.setAccessible(true);
                        String name = field.getName().toLowerCase();
                        Object val = field.get(be);
                        if (val == null) continue;
                        
                        if (name.contains("looted") || name.contains("empty") || name.contains("brushed") || name.contains("hasloot")) {
                            if (val instanceof Boolean) {
                                boolean boolVal = (Boolean) val;
                                if (name.contains("hasloot")) return !boolVal;
                                return boolVal;
                            }
                            if (val instanceof Integer && (Integer) val >= 3) return true;
                        }
                    }
                    clazz = clazz.getSuperclass();
                }
            } catch (Exception ignored) {}
        }

        return false;
    }

    private static boolean isChestOpened(net.minecraft.block.entity.BlockEntity be, java.util.UUID playerUuid) {
        if (be == null || playerUuid == null) return false;
        String uuidStr = playerUuid.toString();
        try {
            Class<?> clazz = be.getClass();
            while (clazz != Object.class) {
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
