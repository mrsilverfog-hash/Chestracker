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
    private static final int SCAN_INTERVAL = 100; // 5 secondes pour rafraîchir la liste globale
    
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
                // VÉRIFICATION INSTANTANÉE : Si le bloc n'est plus "disponible", on saute ce tour de rendu
                BlockState state = client.world.getBlockState(pos);
                if (!isAvailable(state)) continue; 

                String path = Registries.BLOCK.getId(state.getBlock()).getPath();
                float r, g, b;
                if (path.contains("lootr")) { r = 0.0f; g = 0.6f; b = 1.0f; } 
                else if (path.contains("safari_ball")) { r = 0.0f; g = 1.0f; b = 0.0f; } 
                else { r = 1.0f; g = 0.0f; b = 0.0f; }

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

    private void drawMinecraftBeaconBeam(Tessellator t, Matrix4f m, float h, float r, float g, float b, float a) {
        BufferBuilder b = t.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        float min = 0.2f, max = 0.8f;
        addFace(b, m, min, max, 0, h, r, g, b, a, true);
        addFace(b, m, min, max, 0, h, r, g, b, a, false);
        BufferRenderer.drawWithGlobalProgram(b.end());
    }

    private void addFace(BufferBuilder b, Matrix4f m, float min, float max, float hMin, float hMax, float r, float g, float bl, float a, boolean h) {
        b.vertex(m, h ? min : min, hMin, h ? min : max).color(r, g, bl, a);
        b.vertex(m, h ? max : min, hMin, h ? max : max).color(r, g, bl, a);
        b.vertex(m, h ? max : min, hMax, h ? max : max).color(r, g, bl, a);
        b.vertex(m, h ? min : min, hMax, h ? min : max).color(r, g, bl, a);
    }
}
