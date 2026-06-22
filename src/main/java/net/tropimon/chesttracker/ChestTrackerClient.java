package net.tropimon.chesttracker;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
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
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ChestTrackerClient implements ClientModInitializer {

    private static KeyBinding toggleKey;
    private static boolean active = false;
    private static int scanTick = 0;
    private static final int SCAN_INTERVAL = 100;
    
    private static List<BlockPos> cachedBlocks = new ArrayList<>();
    private static Set<BlockPos> manualIgnoreList = new HashSet<>();
    private static final int BEAM_HEIGHT = 256;

    @Override
    public void onInitializeClient() {
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
                }
            }
            if (!active) return;

            scanTick++;
            if (scanTick >= SCAN_INTERVAL) {
                scanTick = 0;
                cachedBlocks = findAvailableBlocks(client.world, client.player.getBlockPos());
            }

            if (client.options.useKey.isPressed()) {
                HitResult hit = client.crosshairTarget;
                if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
                    BlockPos targetPos = ((BlockHitResult) hit).getBlockPos();
                    String path = Registries.BLOCK.getId(client.world.getBlockState(targetPos).getBlock()).getPath();
                    if (path.contains("lootr")) {
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

            Tessellator tessellator = Tessellator.getInstance();

            for (BlockPos pos : cachedBlocks) {
                BlockState state = client.world.getBlockState(pos);
                String path = Registries.BLOCK.getId(state.getBlock()).getPath();

                if (path.contains("lootr") && manualIgnoreList.contains(pos)) continue;
                if (!isAvailable(state)) continue;

                float r, g, b;
                if (path.contains("lootr")) { r = 0.0f; g = 0.6f; b = 1.0f; }
                else if (path.contains("safari_ball")) { r = 0.0f; g = 1.0f; b = 0.0f; }
                else { r = 1.0f; g = 0.0f; b = 0.0f; }

                context.matrixStack().push();
                context.matrixStack().translate(pos.getX() - camPos.x, pos.getY() - camPos.y, pos.getZ() - camPos.z);
                WorldRenderer.drawBox(context.matrixStack(), lineBuffer, 0, 0, 0, 1, 1, 1, r, g, b, 1.0f);
                context.matrixStack().pop();

                Matrix4f matrix = new Matrix4f(viewMatrix);
                matrix.translate((float)(pos.getX() - camPos.x), (float)(pos.getY() + 1.0 - camPos.y), (float)(pos.getZ() - camPos.z));
                drawMinecraftBeaconBeam(tessellator, matrix, BEAM_HEIGHT, r, g, b, 0.5f);
            }

            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
        });
    }

    private List<BlockPos> findAvailableBlocks(World world, BlockPos center) {
        List<BlockPos> result = new ArrayList<>();
        BlockPos.iterate(center.add(-64, -64, -64), center.add(64, 64, 64)).forEach(pos -> {
            BlockState state = world.getBlockState(pos);
            String path = Registries.BLOCK.getId(state.getBlock()).getPath();
            
            if (path.equals("suspicious_safari_gravel") || path.equals("suspicious_safari_sand") || 
                path.equals("safari_ball_loot") || path.contains("lootr")) {
                if (isAvailable(state)) result.add(pos.toImmutable());
            }
        });
        return result;
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

    private void drawMinecraftBeaconBeam(Tessellator tessellator, Matrix4f matrix, float height, float r, float g, float b, float a) {
        // Cœur intérieur coloré
        BufferBuilder builderInner = tessellator.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        addFace3D(builderInner, matrix, 0.4f, 0.6f, 0, height, r, g, b, 0.4f);
        BufferRenderer.drawWithGlobalProgram(builderInner.end());

        // Aura extérieure colorée
        BufferBuilder builderOuter = tessellator.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        addFace3D(builderOuter, matrix, 0.3f, 0.7f, 0, height, r, g, b, 0.15f);
        BufferRenderer.drawWithGlobalProgram(builderOuter.end());
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
