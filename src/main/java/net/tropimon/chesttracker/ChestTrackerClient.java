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
import net.minecraft.client.render.block.entity.BeaconBlockEntityRenderer;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class ChestTrackerClient implements ClientModInitializer {

    private static KeyBinding toggleKey;
    private static boolean active = false;
    private static int scanTick = 0;
    private static final List<BlockPos> chestPositions = new ArrayList<>();
    private static final Identifier BEAM_TEXTURE = Identifier.of("textures/entity/beacon_beam.png");

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
            if (scanTick >= 20) {
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
                                if (be instanceof net.minecraft.block.entity.ChestBlockEntity || 
                                    be instanceof net.minecraft.block.entity.EnderChestBlockEntity) {
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
            if (client.world == null) return;

            float[] blueColor = new float[]{0.0f, 0.4f, 1.0f};
            long worldTime = client.world.getTime();
            float tickDelta = client.getRenderTickCounter().getTickDelta(true);

            MatrixStack matrices = context.matrixStack();
            VertexConsumerProvider consumers = context.consumers();
            Vec3d cameraPos = context.camera().getPos();

            for (BlockPos pos : chestPositions) {
                matrices.push();
                matrices.translate(pos.getX() - cameraPos.x, pos.getY() - cameraPos.y, pos.getZ() - cameraPos.z);

                BeaconBlockEntityRenderer.renderBeam(
                    matrices,
                    consumers,
                    BEAM_TEXTURE,
                    tickDelta,
                    worldTime,
                    1,
                    256,
                    blueColor
                );

                matrices.pop();
            }
        });
    }
}
