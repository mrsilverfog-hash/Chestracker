package net.tropimon.chesttracker;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.render.*;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ChestTrackerClient implements ClientModInitializer {

    private enum BlockCategory { LOOTR, SAFARI_BALL, SUSPICIOUS }

    private record TrackedBlock(BlockPos pos, BlockCategory category) {}

    private record RenderEntry(double x, double y, double z, float r, float g, float b) {}

    private static KeyBinding toggleKey;
    private static boolean active = false;

    private static final int SCAN_RADIUS = 64;
    private static final int SCAN_BUDGET_PER_TICK = 30000;
    private static final int BEAM_HEIGHT = 256;

    private static final Map<Block, BlockCategory> targetBlocks = new HashMap<>();
    private static boolean targetBlocksInitialized = false;

    private static Iterator<BlockPos> scanIterator = null;
    private static List<TrackedBlock> pendingBlocks = new ArrayList<>();
    private static List<TrackedBlock> cachedBlocks = new ArrayList<>();
    private static Set<BlockPos> manualIgnoreList = new HashSet<>();

    @Override
    public void onInitializeClient() {
        initTargetBlocks();

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "Activer/Désactiver Scanner", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_BRACKET, "ChestTracker"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;
            while (toggleKey.wasPressed()) {
                active = !active;
                client.inGameHud.setTitle(Text.literal(active ? "§aScanner : ACTIVÉ" : "§cScanner : DÉSACTIVÉ"));
                if (!active) {
                    cachedBlocks.clear();
                    manualIgnoreList.clear();
                    scanIterator = null;
                    pendingBlocks.clear();
                }
            }
            if (!active) return;

            // Démarre une nouvelle passe de scan si la précédente est terminée
            if (scanIterator == null) {
                BlockPos center = client.player.getBlockPos();
                scanIterator = BlockPos.iterate(
                    center.add(-SCAN_RADIUS, -SCAN_RADIUS, -SCAN_RADIUS),
                    center.add(SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS)
                ).iterator();
                pendingBlocks = new ArrayList<>();
            }

            // Ne traite qu'une tranche de la zone à chaque tick (évite le pic de lag)
            int processed = 0;
            while (scanIterator.hasNext() && processed < SCAN_BUDGET_PER_TICK) {
                BlockPos pos = scanIterator.next();
                BlockState state = client.world.getBlockState(pos);
                BlockCategory category = targetBlocks.get(state.getBlock());
                if (category != null && isAvailable(state)) {
                    pendingBlocks.add(new TrackedBlock(pos.toImmutable(), category));
                }
                processed++;
            }

            // Passe terminée : on bascule le résultat et on relance une nouvelle passe au prochain tick
            if (!scanIterator.hasNext()) {
                cachedBlocks = pendingBlocks;
                scanIterator = null;
            }

            if (client.options.useKey.isPressed()) {
                HitResult hit = client.crosshairTarget;
                if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
                    BlockPos targetPos = ((BlockHitResult) hit).getBlockPos();
                    Block block = client.world.getBlockState(targetPos).getBlock();
                    if (targetBlocks.get(block) == BlockCategory.LOOTR) {
                        manualIgnoreList.add(targetPos);
                    }
                }
            }
        });

        WorldRenderEvents.LAST.register(context -> {
            if (!active || cachedBlocks.isEmpty()) return;
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null) return;

            Matrix4f viewMatrix = context.matrixStack().peek().getPositionMatrix();
            net.minecraft.util.math.Vec3d camPos = context.camera().getPos();
            VertexConsumer lineBuffer = context.consumers().getBuffer(RenderLayer.getLines());

            RenderSystem.setShader(GameRenderer::getPositionColorProgram);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.enableDepthTest();

            // Premier passage : on filtre, on dessine les contours (boîtes), et on prépare les lasers
            List<RenderEntry> toRender = new ArrayList<>();

            for (TrackedBlock tb : cachedBlocks) {
                if (tb.category() == BlockCategory.LOOTR && manualIgnoreList.contains(tb.pos())) continue;

                BlockState state = client.world.getBlockState(tb.pos());
                if (!isAvailable(state)) continue;

                float r, g, b;
                switch (tb.category()) {
                    case LOOTR -> { r = 0.0f; g = 0.6f; b = 1.0f; }
                    case SAFARI_BALL -> { r = 0.0f; g = 1.0f; b = 0.0f; }
                    default -> { r = 1.0f; g = 0.0f; b = 0.0f; }
                }

                BlockPos pos = tb.pos();
                context.matrixStack().push();
                context.matrixStack().translate(pos.getX() - camPos.x, pos.getY() - camPos.y, pos.getZ() - camPos.z);
                WorldRenderer.drawBox(context.matrixStack(), lineBuffer, 0, 0, 0, 1, 1, 1, r, g, b, 1.0f);
                context.matrixStack().pop();

                toRender.add(new RenderEntry(
                    pos.getX() - camPos.x,
                    pos.getY() + 1.0 - camPos.y,
                    pos.getZ() - camPos.z,
                    r, g, b
                ));
            }

            // Lasers : un seul dessin pour le cœur de tous les coffres, un seul pour l'aura de tous les coffres
            if (!toRender.isEmpty()) {
                Tessellator tessellator = Tessellator.getInstance();

                BufferBuilder innerBuilder = tessellator.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
                for (RenderEntry e : toRender) {
                    Matrix4f matrix = new Matrix4f(viewMatrix);
                    matrix.translate((float) e.x(), (float) e.y(), (float) e.z());
                    addFace3D(innerBuilder, matrix, 0.4f, 0.6f, 0, BEAM_HEIGHT, e.r(), e.g(), e.b(), 0.4f);
                }
                BufferRenderer.drawWithGlobalProgram(innerBuilder.end());

                BufferBuilder outerBuilder = tessellator.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
                for (RenderEntry e : toRender) {
                    Matrix4f matrix = new Matrix4f(viewMatrix);
                    matrix.translate((float) e.x(), (float) e.y(), (float) e.z());
                    addFace3D(outerBuilder, matrix, 0.3f, 0.7f, 0, BEAM_HEIGHT, e.r(), e.g(), e.b(), 0.15f);
                }
                BufferRenderer.drawWithGlobalProgram(outerBuilder.end());
            }

            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
        });
    }

    // Parcourt la liste des blocs UNE SEULE FOIS au démarrage pour savoir lesquels nous intéressent.
    // Les critères (mêmes textes, même logique "contains" pour lootr) sont identiques à avant.
    private static void initTargetBlocks() {
        if (targetBlocksInitialized) return;
        for (Block block : Registries.BLOCK) {
            String path = Registries.BLOCK.getId(block).getPath();
            if (path.equals("suspicious_safari_gravel") || path.equals("suspicious_safari_sand")) {
                targetBlocks.put(block, BlockCategory.SUSPICIOUS);
            } else if (path.equals("safari_ball_loot")) {
                targetBlocks.put(block, BlockCategory.SAFARI_BALL);
            } else if (path.contains("lootr")) {
                targetBlocks.put(block, BlockCategory.LOOTR);
            }
        }
        targetBlocksInitialized = true;
    }

    @SuppressWarnings("unchecked")
    private boolean isAvailable(BlockState state) {
        Collection<Property<?>> properties = state.getProperties();
        for (Property<?> prop : properties) {
            String name = prop.getName();
            if (name.equals("available") || name.equals("looted")) {
                Comparable<?> value = state.get((Property) prop);
                if (name.equals("available")) return Boolean.TRUE.equals(value);
                if (name.equals("looted")) return !Boolean.TRUE.equals(value);
            }
        }
        return true;
    }

    private void addFace3D(BufferBuilder b, Matrix4f m, float min, float max, float hMin, float hMax, float r, float g, float bl, float a) {
        b.vertex(m, min, hMin, min).color(r, g, bl, a);
        b.vertex(m, max, hMin, min).color(r, g, bl, a);
        b.vertex(m, max, hMax, min).color(r, g, bl, a);
        b.vertex(m, min, hMax, min).color(r, g, bl, a);
        b.vertex(m, min, hMin, max).color(r, g, bl, a);
        b.vertex(m, max, hMin, max).color(r, g, bl, a);
        b.vertex(m, max, hMax, max).color(r, g, bl, a);
        b.vertex(m, min, hMax, max).color(r, g, bl, a);
        b.vertex(m, max, hMin, min).color(r, g, bl, a);
        b.vertex(m, max, hMin, max).color(r, g, bl, a);
        b.vertex(m, max, hMax, max).color(r, g, bl, a);
        b.vertex(m, max, hMax, min).color(r, g, bl, a);
        b.vertex(m, min, hMin, min).color(r, g, bl, a);
        b.vertex(m, min, hMin, max).color(r, g, bl, a);
        b.vertex(m, min, hMax, max).color(r, g, bl, a);
        b.vertex(m, min, hMax, min).color(r, g, bl, a);
    }
}
