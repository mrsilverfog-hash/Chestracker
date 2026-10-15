package net.tropimon.chesttracker;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.render.*;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.state.property.Property;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.lwjgl.glfw.GLFW;
import org.joml.Matrix4f;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ChestTrackerClient implements ClientModInitializer {

    private enum BlockCategory { LOOTR, SAFARI_BALL, SUSPICIOUS }

    /**
     * darkFrom = hauteur (au-dessus du bloc trouvé) du premier bloc non-air traversé par le rayon.
     * Le rayon est normal en dessous, foncé à partir de là. BEAM_HEIGHT = rien traversé.
     */
    private record TrackedBlock(BlockPos pos, BlockCategory category, float darkFrom) {}

    private record RenderEntry(double x, double y, double z, float r, float g, float b, float darkFrom) {}

    // Partie du rayon au-dessus d'un bloc traversé : même teinte, très assombrie
    private static final float COVERED_DARKEN = 0.3f;

    private static KeyBinding toggleKey;
    private static KeyBinding debugKey;
    private static boolean active = false;

    // Scan par sections de chunk (16x16x16) : une section est sautée d'un coup si sa palette
    // ne contient aucun bloc recherché, ce qui permet une grande portée et des passes rapides.
    // Portée horizontale en chunks (12 = 192 blocs), limitée de fait par la distance de rendu.
    private static final int SCAN_RADIUS_CHUNKS = 12;
    private static final int SECTION_BUDGET_PER_TICK = 2000;
    private static final int BLOCK_BUDGET_PER_TICK = 40000;
    private static final int BEAM_HEIGHT = 256;



    private static final Map<Block, BlockCategory> targetBlocks = new HashMap<>();
    private static boolean targetBlocksInitialized = false;

    // Cache de la méthode "hasClientOpened(UUID)" de Lootr, trouvée par réflexion (une fois par type de bloc)
    private static final Map<Class<?>, Method> lootrHasClientOpenedMethods = new HashMap<>();

    private static World lastWorld = null;

    private static boolean scanning = false;
    private static int[] scanChunkXs = new int[0];
    private static int[] scanChunkZs = new int[0];
    private static int scanChunkIdx = 0;
    private static int scanSectionIdx = 0;
    private static List<TrackedBlock> pendingBlocks = new ArrayList<>();
    private static List<TrackedBlock> cachedBlocks = new ArrayList<>();

    // ===================== AUTOLOOT (actif en même temps que le scanner) =====================
    private static final String LOOTR_NAMESPACE = "lootr";
    // Attente MAXIMALE après l'ouverture : on démarre dès que le contenu du coffre est arrivé,
    // et on abandonne au bout de ce délai si le coffre est toujours vide
    private static final int MAX_SCAN_DELAY_TICKS = 5;
    // Nombre d'objets traités au maximum par tick (chacun = 2 clics : prendre + poser)
    private static final int GRABS_PER_TICK = 6;

    private static boolean currentContainerIsLootr = false;
    private static boolean alreadyQueuedForThisOpen = false;
    private static boolean screenJustOpened = false;
    private static int ticksWaited = 0;
    private static final Deque<Integer> pendingGrabSlots = new ArrayDeque<>();

    @Override
    public void onInitializeClient() {
        initTargetBlocks();

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "Activer/Désactiver Scanner + Autoloot", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_BRACKET, "ChestTracker"
        ));

        debugKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "Debug Bloc Ciblé", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET, "ChestTracker"
        ));

        // Autoloot : on retient si le bloc qu'on vient d'ouvrir est un coffre/tonneau Lootr
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            Identifier blockId = Registries.BLOCK.getId(
                world.getBlockState(hitResult.getBlockPos()).getBlock()
            );
            currentContainerIsLootr = blockId.getNamespace().equals(LOOTR_NAMESPACE)
                && (blockId.getPath().equals("lootr_chest") || blockId.getPath().equals("lootr_barrel"));
            return ActionResult.PASS;
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;

            // Changement de monde/dimension : on repart de zéro
            if (client.world != lastWorld) {
                lastWorld = client.world;
                cachedBlocks = new ArrayList<>();
                scanning = false;
                pendingBlocks = new ArrayList<>();
            }

            while (toggleKey.wasPressed()) {
                active = !active;
                client.inGameHud.setTitle(Text.literal(active
                    ? "§aScanner + Autoloot : ACTIVÉ"
                    : "§cScanner + Autoloot : DÉSACTIVÉ"));
                if (!active) {
                    cachedBlocks.clear();
                    scanning = false;
                    pendingBlocks.clear();
                        pendingGrabSlots.clear();
                }
            }

            while (debugKey.wasPressed()) {
                HitResult hit = client.crosshairTarget;
                if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
                    BlockPos targetPos = ((BlockHitResult) hit).getBlockPos();
                    printBlockDebugInfo(client, targetPos);
                }
            }

            // L'autoloot tourne à chaque tick (il ne fait quelque chose que si "active" est vrai)
            autolootTick(client);

            if (!active) return;

            scanTick(client);
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
                boolean available = (tb.category() == BlockCategory.LOOTR)
                    ? !isOpenedByThisPlayer(client, tb.pos())
                    : isAvailable(client.world.getBlockState(tb.pos()));
                if (!available) continue;

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
                    r, g, b,
                    tb.darkFrom()
                ));
            }

            // Lasers : un seul dessin pour le cœur de tous les blocs, un seul pour l'aura de tous les blocs
            if (!toRender.isEmpty()) {
                Tessellator tessellator = Tessellator.getInstance();

                BufferBuilder innerBuilder = tessellator.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
                for (RenderEntry e : toRender) {
                    Matrix4f matrix = new Matrix4f(viewMatrix);
                    matrix.translate((float) e.x(), (float) e.y(), (float) e.z());
                    addBeam(innerBuilder, matrix, 0.4f, 0.6f, e, 0.4f, 0.85f);
                }
                BufferRenderer.drawWithGlobalProgram(innerBuilder.end());

                BufferBuilder outerBuilder = tessellator.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
                for (RenderEntry e : toRender) {
                    Matrix4f matrix = new Matrix4f(viewMatrix);
                    matrix.translate((float) e.x(), (float) e.y(), (float) e.z());
                    addBeam(outerBuilder, matrix, 0.3f, 0.7f, e, 0.15f, 0.4f);
                }
                BufferRenderer.drawWithGlobalProgram(outerBuilder.end());
            }

            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
        });
    }

    // Parcourt la liste des blocs UNE SEULE FOIS au démarrage pour savoir lesquels nous intéressent.
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

    // Demande directement à Lootr : "est-ce que ce joueur précis a déjà ouvert ce coffre/tonneau ?"
    // (sans avoir besoin d'ajouter Lootr comme dépendance de compilation : on cherche la méthode au moment de l'exécution)
    private static boolean isOpenedByThisPlayer(MinecraftClient client, BlockPos pos) {
        if (client.player == null || client.world == null) return false;

        BlockEntity entity = client.world.getBlockEntity(pos);
        if (entity == null) return false;

        Class<?> clazz = entity.getClass();
        Method method = lootrHasClientOpenedMethods.computeIfAbsent(clazz, c -> {
            try {
                return c.getMethod("hasClientOpened", UUID.class);
            } catch (NoSuchMethodException e) {
                return null;
            }
        });

        if (method == null) {
            // Méthode introuvable (version différente de Lootr, ou pas un bloc Lootr) : on suppose "pas encore ouvert"
            return false;
        }

        try {
            return (boolean) method.invoke(entity, client.player.getUuid());
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    // ===================== SCAN PAR SECTIONS =====================

    private static boolean isTarget(BlockState state) {
        return targetBlocks.containsKey(state.getBlock());
    }

    /** Prépare une passe : liste des chunks dans le rayon, du plus proche au plus lointain. */
    private static void startScanPass(MinecraftClient client) {
        int pcx = client.player.getBlockX() >> 4;
        int pcz = client.player.getBlockZ() >> 4;
        int r = SCAN_RADIUS_CHUNKS;
        List<int[]> chunks = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) continue;
                chunks.add(new int[]{pcx + dx, pcz + dz, dx * dx + dz * dz});
            }
        }
        chunks.sort((a, b) -> Integer.compare(a[2], b[2]));
        scanChunkXs = new int[chunks.size()];
        scanChunkZs = new int[chunks.size()];
        for (int i = 0; i < chunks.size(); i++) {
            scanChunkXs[i] = chunks.get(i)[0];
            scanChunkZs[i] = chunks.get(i)[1];
        }
        scanChunkIdx = 0;
        scanSectionIdx = 0;
        pendingBlocks = new ArrayList<>();
        scanning = true;
    }

    private static void scanTick(MinecraftClient client) {
        if (!scanning) startScanPass(client);

        int sections = 0;
        int blocks = 0;
        while (scanning && sections < SECTION_BUDGET_PER_TICK && blocks < BLOCK_BUDGET_PER_TICK) {
            if (scanChunkIdx >= scanChunkXs.length) {
                // Passe terminée : on publie le résultat, une nouvelle passe démarre au prochain tick
                cachedBlocks = pendingBlocks;
                scanning = false;
                break;
            }

            int cx = scanChunkXs[scanChunkIdx];
            int cz = scanChunkZs[scanChunkIdx];
            WorldChunk chunk = client.world.getChunkManager().getWorldChunk(cx, cz);
            if (chunk == null) {
                scanChunkIdx++;
                scanSectionIdx = 0;
                continue;
            }

            ChunkSection[] secs = chunk.getSectionArray();
            if (scanSectionIdx >= secs.length) {
                scanChunkIdx++;
                scanSectionIdx = 0;
                continue;
            }

            int si = scanSectionIdx++;
            sections++;
            ChunkSection sec = secs[si];
            if (sec == null || sec.isEmpty()) continue;
            // Palette de la section : si aucun bloc recherché, on saute les 4096 blocs d'un coup
            if (!sec.getBlockStateContainer().hasAny(ChestTrackerClient::isTarget)) continue;

            int baseX = cx << 4;
            int baseY = chunk.sectionIndexToCoord(si) << 4;
            int baseZ = cz << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = sec.getBlockState(x, y, z);
                        BlockCategory category = targetBlocks.get(state.getBlock());
                        if (category == null) continue;
                        BlockPos pos = new BlockPos(baseX + x, baseY + y, baseZ + z);
                        boolean available;
                        if (category == BlockCategory.LOOTR) {
                            available = !isOpenedByThisPlayer(client, pos);
                        } else {
                            available = isAvailable(state);
                        }
                        if (available) pendingBlocks.add(new TrackedBlock(pos, category, firstObstacleHeight(client.world, pos)));
                    }
                }
            }
            blocks += 4096;
        }
    }

    private static boolean isAvailable(BlockState state) {
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

    // ===================== AUTOLOOT =====================

    private static void autolootTick(MinecraftClient client) {
        if (!(client.currentScreen instanceof HandledScreen<?>)) {
            alreadyQueuedForThisOpen = false;
            screenJustOpened = false;
            ticksWaited = 0;
            pendingGrabSlots.clear();
            return;
        }

        if (!active) {
            pendingGrabSlots.clear();
            return;
        }

        if (!alreadyQueuedForThisOpen) {
            if (!screenJustOpened) {
                screenJustOpened = true;
                ticksWaited = 0;
            } else {
                ticksWaited++;
                if (currentContainerIsLootr) {
                    // Dès que le contenu est synchronisé (au moins un objet visible), on y va tout de suite
                    if (containerHasItems(client.player.currentScreenHandler) || ticksWaited >= MAX_SCAN_DELAY_TICKS) {
                        queueMatchingItems(client, client.player.currentScreenHandler);
                        alreadyQueuedForThisOpen = true;
                    }
                }
            }
        }

        if (!(client.player.currentScreenHandler instanceof GenericContainerScreenHandler containerHandler)) {
            return;
        }

        processGrabQueue(client, containerHandler);
    }

    private static boolean containerHasItems(ScreenHandler handler) {
        if (!(handler instanceof GenericContainerScreenHandler containerHandler)) return false;
        int size = containerHandler.getInventory().size();
        for (int i = 0; i < size; i++) {
            if (!containerHandler.getSlot(i).getStack().isEmpty()) return true;
        }
        return false;
    }

    // Objets récupérés même si le joueur n'en possède pas encore :
    //  - méga-gemmes de Mega Showdown (ex : mega_showdown:beedrillite, charizardite_x)
    //  - cristaux Z de Mega Showdown (ex : mega_showdown:grassium_z)
    //  - tableaux de Tropifurnitures (ex : tropifurnitures:painting_artwork1, painting2x2blank)
    private static final java.util.regex.Pattern MEGA_STONE = java.util.regex.Pattern.compile("^[a-z_]+ite(_[xyz])?$");

    private static boolean isAlwaysLooted(ItemStack stack) {
        Identifier id = Registries.ITEM.getId(stack.getItem());
        String ns = id.getNamespace();
        String path = id.getPath();
        if (ns.equals("mega_showdown")) {
            if (path.endsWith("_z")) return true;                 // cristal Z
            if (MEGA_STONE.matcher(path).matches()) return true;  // méga-gemme
        }
        if (ns.equals("tropifurnitures") && path.startsWith("painting")) return true; // tableau
        return false;
    }

    private static void queueMatchingItems(MinecraftClient client, ScreenHandler handler) {
        if (!(handler instanceof GenericContainerScreenHandler containerHandler)) {
            return;
        }

        int containerSize = containerHandler.getInventory().size();
        var playerInventory = client.player.getInventory();

        for (int i = 0; i < containerSize; i++) {
            ItemStack containerStack = containerHandler.getSlot(i).getStack();
            if (containerStack.isEmpty()) {
                continue;
            }

            boolean alreadyOwned = false;
            for (ItemStack invStack : playerInventory.main) {
                if (!invStack.isEmpty() && invStack.getItem() == containerStack.getItem()) {
                    alreadyOwned = true;
                    break;
                }
            }

            if (alreadyOwned || isAlwaysLooted(containerStack)) {
                pendingGrabSlots.add(i);
            }
        }

        if (!pendingGrabSlots.isEmpty()) {
            client.player.sendMessage(
                Text.literal("§aAutoloot : " + pendingGrabSlots.size() + " objet(s) récupéré(s)"), true);
        }
    }

    private static void processGrabQueue(MinecraftClient client, GenericContainerScreenHandler containerHandler) {
        int done = 0;
        while (!pendingGrabSlots.isEmpty() && done < GRABS_PER_TICK) {
            int slotIndex = pendingGrabSlots.poll();
            transferViaPickup(client, containerHandler, slotIndex);
            done++;
        }
    }

    // Transfère un objet du coffre vers l'inventaire en 2 clics simples (prendre, puis poser),
    // plutôt qu'un shift-clic (QUICK_MOVE) qui pose problème sur certains slots.
    private static void transferViaPickup(MinecraftClient client, GenericContainerScreenHandler containerHandler, int containerSlotIndex) {
        ItemStack stack = containerHandler.getSlot(containerSlotIndex).getStack().copy();
        if (stack.isEmpty()) {
            return;
        }

        // Étape 1 : on prend l'objet du coffre (il va sur le curseur)
        client.interactionManager.clickSlot(
            containerHandler.syncId, containerSlotIndex, 0, SlotActionType.PICKUP, client.player
        );

        int containerSize = containerHandler.getInventory().size();
        int totalSlots = containerHandler.slots.size();

        // Étape 2a : on cherche d'abord une pile déjà existante du même objet, avec de la place
        Integer targetSlot = null;
        for (int i = containerSize; i < totalSlots; i++) {
            ItemStack invStack = containerHandler.getSlot(i).getStack();
            if (!invStack.isEmpty() && invStack.getItem() == stack.getItem()
                && invStack.getCount() < invStack.getMaxCount()) {
                targetSlot = i;
                break;
            }
        }

        // Étape 2b : sinon, un slot vide
        if (targetSlot == null) {
            for (int i = containerSize; i < totalSlots; i++) {
                if (containerHandler.getSlot(i).getStack().isEmpty()) {
                    targetSlot = i;
                    break;
                }
            }
        }

        if (targetSlot != null) {
            // On pose l'objet dans l'inventaire
            client.interactionManager.clickSlot(
                containerHandler.syncId, targetSlot, 0, SlotActionType.PICKUP, client.player
            );
        } else {
            // Pas de place : on remet l'objet là où on l'a pris
            client.interactionManager.clickSlot(
                containerHandler.syncId, containerSlotIndex, 0, SlotActionType.PICKUP, client.player
            );
        }
    }

    // ===================== DEBUG =====================

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void printBlockDebugInfo(MinecraftClient client, BlockPos pos) {
        if (client.player == null || client.world == null) return;

        BlockState state = client.world.getBlockState(pos);
        String id = Registries.BLOCK.getId(state.getBlock()).toString();

        client.player.sendMessage(Text.literal("§6--- Debug bloc (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ") ---"), false);
        client.player.sendMessage(Text.literal("§7ID : §f" + id), false);

        Collection<Property<?>> properties = state.getProperties();
        if (properties.isEmpty()) {
            client.player.sendMessage(Text.literal("§7Propriétés : aucune"), false);
        } else {
            for (Property<?> prop : properties) {
                Object value = state.get((Property) prop);
                client.player.sendMessage(Text.literal("§7" + prop.getName() + " = §f" + value), false);
            }
        }

        BlockEntity entity = client.world.getBlockEntity(pos);
        if (entity == null) {
            client.player.sendMessage(Text.literal("§7BlockEntity : aucune"), false);
        } else {
            client.player.sendMessage(Text.literal("§7BlockEntity : §f" + entity.getClass().getName()), false);
        }

        if (targetBlocks.get(state.getBlock()) == BlockCategory.LOOTR) {
        }
    }

    /** Hauteur du premier bloc non-air (quel qu'il soit) au-dessus du bloc trouvé, ou BEAM_HEIGHT s'il n'y en a pas. */
    private static float firstObstacleHeight(World world, BlockPos pos) {
        int top = Math.min(BEAM_HEIGHT, world.getTopY() - (pos.getY() + 1));
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int h = 0; h < top; h++) {
            m.set(pos.getX(), pos.getY() + 1 + h, pos.getZ());
            if (!world.getBlockState(m).isAir()) return h;
        }
        return BEAM_HEIGHT;
    }

    /** Rayon normal jusqu'au premier bloc traversé, puis foncé (et plus opaque) jusqu'en haut. */
    private static void addBeam(BufferBuilder b, Matrix4f m, float min, float max, RenderEntry e, float alpha, float darkAlpha) {
        float split = Math.max(0, Math.min(BEAM_HEIGHT, e.darkFrom()));
        if (split > 0) {
            addFace3D(b, m, min, max, 0, split, e.r(), e.g(), e.b(), alpha);
        }
        if (split < BEAM_HEIGHT) {
            addFace3D(b, m, min, max, split, BEAM_HEIGHT,
                e.r() * COVERED_DARKEN, e.g() * COVERED_DARKEN, e.b() * COVERED_DARKEN, darkAlpha);
        }
    }

    private static void addFace3D(BufferBuilder b, Matrix4f m, float min, float max, float hMin, float hMax, float r, float g, float bl, float a) {
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
