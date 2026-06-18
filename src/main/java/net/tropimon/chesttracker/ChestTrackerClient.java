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
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class ChestTrackerClient implements ClientModInitializer {

    private static KeyBinding toggleKey;
    private static boolean active = false;
    private static int scanTick = 0;
    private static final int SCAN_INTERVAL = 100; // 5 secondes
    
    private static List<BlockPos> cachedBlocks = new ArrayList<>();
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
                if (!active) cachedBlocks.clear();
            }
            if (!active) return;

            scanTick++;
            if (scanTick >= SCAN_INTERVAL) {
                scanTick = 0;
                cachedBlocks = findAvailableBlocks(client.world, client.player.getBlockPos());
            }
        });

        WorldRenderEvents.LAST.register(context -> {
            if (!active || cachedBlocks.isEmpty()) return;
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null) return;

            Matrix4f viewMatrix = context.matrixStack().peek().getPositionMatrix();
            net.minecraft.util.math.Vec3d camPos = context.camera().getPos();

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
                
                float r, g, b;
                if (path.contains("lootr")) {
                    r = 0.0f; g = 0.6f; b = 1.0f; // Bleu
                } else if (path.contains("safari_ball")) {
                    r = 0.0f; g = 1.0f; b = 0.0f; // Vert
                } else {
                    r = 1.0f; g = 0.0f; b = 0.0f; // Rouge
                }

                Matrix4f matrix = new Matrix4f(viewMatrix);
                matrix.translate((float)(pos.getX() - camPos.x), (float)(pos.getY() + 1.0 - camPos.y), (float)(pos.getZ() - camPos.z));
                drawMinecraftBeaconBeam(tessellator, matrix, BEAM_HEIGHT, r, g, b, 0.7f);
            }

            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
        });
    }

    private List<BlockPos> findAvailableBlocks(World world, BlockPos center) {
        List<BlockPos> result = new ArrayList<>();
        BlockPos.iterate(center.add(-50, -50, -50), center.add(50, 50, 50)).forEach(pos -> {
            BlockState state = world.getBlockState(pos);
            Identifier id = Registries.BLOCK.getId(state.getBlock());
            String path = id.getPath();
            
            if (path.equals("suspicious_safari_gravel") || path.equals("suspicious_safari_sand") || 
                path.equals("safari_ball_loot") || path.contains("lootr")) {
                if (isAvailable(state)) {
                    result.add(pos.toImmutable());
                }
            }
        });
        return result;
    }

    @SuppressWarnings("unchecked")
    private boolean isAvailable(BlockState state) {
        Collection<Property<?>> properties = state.getProperties();
        for (Property<?> prop : properties) {
            if (prop.getName().equals("available") || prop.getName().equals("looted")) {
                Comparable<?> value = state.get((Property) prop);
                // Si la propriété est "available", on veut true. Si c'est "looted", on veut false.
                if (prop.getName().equals("available")) return Boolean.TRUE.equals(value);
                if (prop.getName().equals("looted")) return !Boolean.TRUE.equals(value);
            }
        }
        return true;
    }

    private void drawMinecraftBeaconBeam(Tessellator tessellator, Matrix4f matrix, float height, float r, float g, float b, float a) {
        BufferBuilder buffer = tessellator.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        float min = 0.2f, max = 0.8f;
        addFace(buffer, matrix, min, max, 0, height, r, g, b, a, true);
        addFace(buffer, matrix, min, max, 0, height, r, g, b, a, false);
        BufferRenderer.drawWithGlobalProgram(buffer.end());
    }

    private void addFace(BufferBuilder b, Matrix4f m, float min, float max, float hMin, float hMax, float r, float g, float bl, float a, boolean horizontal) {
        b.vertex(m, horizontal ? min : min, hMin, horizontal ? min : max).color(r, g, bl, a);
        b.vertex(m, horizontal ? max : min, hMin, horizontal ? max : max).color(r, g, bl, a);
        b.vertex(m, horizontal ? max : min, hMax, horizontal ? max : max).color(r, g, bl, a);
        b.vertex(m, horizontal ? min : min, hMax, horizontal ? min : max).color(r, g, bl, a);
    }
}
