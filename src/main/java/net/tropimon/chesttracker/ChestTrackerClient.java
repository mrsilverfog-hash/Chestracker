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
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChestTrackerClient implements ClientModInitializer {

    private static KeyBinding toggleKey;
    private static boolean active = false;
    private static int scanTick = 0;
    private static final int SCAN_INTERVAL = 100;
    
    // On stocke la position ET l'état du bloc pour comparer
    private static Map<BlockPos, BlockState> trackedBlocks = new HashMap<>();
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
                if (!active) trackedBlocks.clear();
            }
            if (!active) return;

            scanTick++;
            if (scanTick >= SCAN_INTERVAL) {
                scanTick = 0;
                trackedBlocks = scanWorld(client.world, client.player.getBlockPos());
            }
        });

        WorldRenderEvents.LAST.register(context -> {
            if (!active || trackedBlocks.isEmpty()) return;
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

            for (Map.Entry<BlockPos, BlockState> entry : trackedBlocks.entrySet()) {
                BlockPos pos = entry.getKey();
                BlockState currentState = client.world.getBlockState(pos);

                // COMPARISON : Si l'état actuel est différent de l'état scanné (ex: couleur changée), on ignore
                if (!currentState.equals(entry.getValue())) continue;

                String path = Registries.BLOCK.getId(currentState.getBlock()).getPath();
                float r = path.contains("lootr") ? 0.0f : (path.contains("safari_ball") ? 0.0f : 1.0f);
                float g = path.contains("lootr") ? 0.6f : (path.contains("safari_ball") ? 1.0f : 0.0f);
                float b = path.contains("lootr") ? 1.0f : (path.contains("safari_ball") ? 0.0f : 0.0f);

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

    private Map<BlockPos, BlockState> scanWorld(World world, BlockPos center) {
        Map<BlockPos, BlockState> result = new HashMap<>();
        BlockPos.iterate(center.add(-50, -50, -50), center.add(50, 50, 50)).forEach(pos -> {
            BlockState state = world.getBlockState(pos);
            String path = Registries.BLOCK.getId(state.getBlock()).getPath();
            if (path.contains("lootr") || path.contains("safari_ball") || path.contains("suspicious")) {
                result.put(pos.toImmutable(), state);
            }
        });
        return result;
    }

    private void drawMinecraftBeaconBeam(Tessellator t, Matrix4f m, float h, float r, float g, float b, float a) {
        BufferBuilder builder = t.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        float min = 0.2f, max = 0.8f;
        addFace(builder, m, min, max, 0, h, r, g, b, a, true);
        addFace(builder, m, min, max, 0, h, r, g, b, a, false);
        BufferRenderer.drawWithGlobalProgram(builder.end());
    }

    private void addFace(BufferBuilder b, Matrix4f m, float min, float max, float hMin, float hMax, float r, float g, float bl, float a, boolean h) {
        b.vertex(m, h ? min : min, hMin, h ? min : max).color(r, g, bl, a);
        b.vertex(m, h ? max : min, hMin, h ? max : max).color(r, g, bl, a);
        b.vertex(m, h ? max : min, hMax, h ? max : max).color(r, g, bl, a);
        b.vertex(m, h ? min : min, hMax, h ? min : max).color(r, g, bl, a);
    }
}
