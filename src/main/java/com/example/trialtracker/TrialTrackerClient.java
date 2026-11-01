package com.example.trialtracker;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.TrialSpawnerState;
import net.minecraft.block.enums.VaultState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.ShaderProgramKeys;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TrialTrackerClient implements ClientModInitializer {
    private static final int RADIUS = 200;

    // Позиция счётчика (hudY < 0 = по умолчанию, слева снизу)
    public int hudX = 6;
    public int hudY = -1;

    private final Map<BlockPos, Boolean> spawners = new HashMap<>();
    private final Set<BlockPos> vaults = new HashSet<>();
    private final Set<BlockPos> opened = new HashSet<>();
    // Статистика (хранится в файле отдельно для каждого мира/сервера)
    private final Set<BlockPos> clearedSpawners = new HashSet<>();
    private final Set<BlockPos> chambers = new HashSet<>();      // "якоря" камер, в которых ты был
    private final Set<BlockPos> doneChambers = new HashSet<>();  // камеры, о зачистке которых уже сообщили
    private String worldKey = "unknown";

    public KeyBinding toggleKey;
    private KeyBinding editKey;
    // Что подсвечивать (выбирается в меню по бинду)
    public boolean showSpawners = false;
    public boolean showVaults = false;
    public boolean showTracer = true;
    public boolean showDistance = true;

    private int tick = 0;
    private int spawnersLeft, spawnersTotal, vaultsLeft, vaultsTotal;
    private int prevLeft = 0;
    private boolean inChamber;

    private Path cfg(String name) { return FabricLoader.getInstance().getConfigDir().resolve(name); }

    @Override
    public void onInitializeClient() {
        loadConfig();

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.trialtracker.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, "key.category.trialtracker"));
        editKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.trialtracker.hud_edit", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, "key.category.trialtracker"));

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        WorldRenderEvents.LAST.register(this::renderHighlight);

        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
            clearAll();
            var si = mc.getCurrentServerEntry();
            if (si != null) worldKey = si.address;
            else if (mc.getServer() != null) worldKey = "sp:" + mc.getServer().getSaveProperties().getLevelName();
            else worldKey = "unknown";
            loadOpened();
            loadStats();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> { clearAll(); inChamber = false; });

        // Открыл хранилище правильным ключом -> запоминаем навсегда
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClient) {
                BlockPos pos = hit.getBlockPos();
                BlockState st = world.getBlockState(pos);
                if (st.isOf(Blocks.VAULT) && st.get(Properties.VAULT_STATE) == VaultState.ACTIVE) {
                    var stack = player.getStackInHand(hand);
                    boolean ominous = st.get(Properties.OMINOUS);
                    boolean rightKey = ominous ? stack.isOf(Items.OMINOUS_TRIAL_KEY) : stack.isOf(Items.TRIAL_KEY);
                    if (rightKey) markOpened(pos);
                }
            }
            return ActionResult.PASS;
        });

        HudRenderCallback.EVENT.register((ctx, tc) -> {
            var mc = MinecraftClient.getInstance();
            if (!inChamber || mc.currentScreen instanceof HudEditScreen) return;
            int y = hudY < 0 ? ctx.getScaledWindowHeight() - 66 : hudY;
            drawHud(ctx, hudX, y, spawnersLeft, spawnersTotal, vaultsLeft, vaultsTotal);
        });
    }

    private void clearAll() {
        spawners.clear(); vaults.clear(); opened.clear();
        clearedSpawners.clear(); chambers.clear(); doneChambers.clear();
        prevLeft = 0;
    }

    public void drawHud(DrawContext ctx, int x, int y, int sl, int st, int vl, int vt) {
        var tr = MinecraftClient.getInstance().textRenderer;
        ctx.drawItem(new ItemStack(Items.TRIAL_SPAWNER), x, y);
        ctx.drawTextWithShadow(tr, sl + " / " + st, x + 22, y + 4, sl == 0 ? 0xFF55FF55 : 0xFFFFFFFF);
        ctx.drawItem(new ItemStack(Items.VAULT), x, y + 20);
        ctx.drawTextWithShadow(tr, vl + " / " + vt, x + 22, y + 24, vl == 0 ? 0xFF55FF55 : 0xFFFFFFFF);
        ctx.drawTextWithShadow(tr, Text.translatable("trialtracker.stats",
                clearedSpawners.size(), opened.size(), chambers.size()), x, y + 44, 0xFFFFD27F);
    }

    // ---------- конфиг и файлы ----------
    private void loadConfig() {
        try {
            Path f = cfg("trialtracker.properties");
            if (!Files.exists(f)) return;
            java.util.Properties p = new java.util.Properties();
            try (Reader r = Files.newBufferedReader(f)) { p.load(r); }
            hudX = Integer.parseInt(p.getProperty("hudX", "6"));
            hudY = Integer.parseInt(p.getProperty("hudY", "-1"));
            showSpawners = Boolean.parseBoolean(p.getProperty("showSpawners", "false"));
            showVaults = Boolean.parseBoolean(p.getProperty("showVaults", "false"));
            showTracer = Boolean.parseBoolean(p.getProperty("showTracer", "true"));
            showDistance = Boolean.parseBoolean(p.getProperty("showDistance", "true"));
        } catch (Exception ignored) { }
    }

    public void saveConfig() {
        try {
            java.util.Properties p = new java.util.Properties();
            p.setProperty("hudX", String.valueOf(hudX));
            p.setProperty("hudY", String.valueOf(hudY));
            p.setProperty("showSpawners", String.valueOf(showSpawners));
            p.setProperty("showVaults", String.valueOf(showVaults));
            p.setProperty("showTracer", String.valueOf(showTracer));
            p.setProperty("showDistance", String.valueOf(showDistance));
            try (Writer w = Files.newBufferedWriter(cfg("trialtracker.properties"))) { p.store(w, "Trial Tracker"); }
        } catch (IOException ignored) { }
    }

    private void loadOpened() {
        try {
            Path f = cfg("trialtracker_opened.txt");
            if (!Files.exists(f)) return;
            for (String line : Files.readAllLines(f)) {
                String[] a = line.split("\\|");
                if (a.length == 4 && a[0].equals(worldKey)) {
                    opened.add(new BlockPos(Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3])));
                }
            }
        } catch (Exception ignored) { }
    }

    private void markOpened(BlockPos pos) {
        BlockPos b = pos.toImmutable();
        if (!opened.add(b)) return;
        append("trialtracker_opened.txt", worldKey + "|" + b.getX() + "|" + b.getY() + "|" + b.getZ());
    }

    // Статистика: тип S = зачищенный спавнер, C = камера, в которой был, D = камера зачищена
    private void loadStats() {
        try {
            Path f = cfg("trialtracker_stats.txt");
            if (!Files.exists(f)) return;
            for (String line : Files.readAllLines(f)) {
                String[] a = line.split("\\|");
                if (a.length == 5 && a[0].equals(worldKey)) {
                    BlockPos b = new BlockPos(Integer.parseInt(a[2]), Integer.parseInt(a[3]), Integer.parseInt(a[4]));
                    switch (a[1]) {
                        case "S" -> clearedSpawners.add(b);
                        case "C" -> chambers.add(b);
                        case "D" -> doneChambers.add(b);
                    }
                }
            }
        } catch (Exception ignored) { }
    }

    private void addStat(Set<BlockPos> set, String type, BlockPos pos) {
        BlockPos b = pos.toImmutable();
        if (!set.add(b)) return;
        append("trialtracker_stats.txt", worldKey + "|" + type + "|" + b.getX() + "|" + b.getY() + "|" + b.getZ());
    }

    private void append(String file, String line) {
        try {
            Files.writeString(cfg(file), line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
    }

    // ---------- сканирование ----------
    private void onTick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null) return;
        while (toggleKey.wasPressed()) {
            if (mc.currentScreen == null) mc.setScreen(new HighlightScreen(this));
        }
        while (editKey.wasPressed()) {
            if (mc.currentScreen == null) mc.setScreen(new HudEditScreen(this));
        }
        if (++tick % 20 != 0) return;
        World world = mc.world;
        BlockPos p = mc.player.getBlockPos();

        int chunkRadius = Math.min(mc.options.getClampedViewDistance(), (RADIUS + 15) / 16);
        ChunkPos center = new ChunkPos(p);
        for (int cx = center.x - chunkRadius; cx <= center.x + chunkRadius; cx++) {
            for (int cz = center.z - chunkRadius; cz <= center.z + chunkRadius; cz++) {
                WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz);
                if (chunk == null) continue;
                for (BlockPos pos : chunk.getBlockEntityPositions()) {
                    BlockState st = chunk.getBlockState(pos);
                    if (st.isOf(Blocks.TRIAL_SPAWNER)) {
                        boolean left = st.get(Properties.TRIAL_SPAWNER_STATE) != TrialSpawnerState.COOLDOWN;
                        BlockPos key = pos.toImmutable();
                        Boolean was = spawners.put(key, left);
                        // Был активен, а теперь на перезарядке, и ты рядом -> засчитываем зачистку
                        if (was != null && was && !left && hdist(key, p) <= 48 && Math.abs(key.getY() - p.getY()) <= 30) {
                            addStat(clearedSpawners, "S", key);
                        }
                    } else if (st.isOf(Blocks.VAULT)) {
                        vaults.add(pos.toImmutable());
                        VaultState vs = st.get(Properties.VAULT_STATE);
                        if ((vs == VaultState.UNLOCKING || vs == VaultState.EJECTING) && pos.getSquaredDistance(p) <= 36) {
                            markOpened(pos);
                        }
                    }
                }
            }
        }

        spawners.keySet().removeIf(b -> world.isChunkLoaded(b) && !world.getBlockState(b).isOf(Blocks.TRIAL_SPAWNER));
        vaults.removeIf(b -> world.isChunkLoaded(b) && !world.getBlockState(b).isOf(Blocks.VAULT));

        spawnersTotal = spawnersLeft = vaultsTotal = vaultsLeft = 0;
        for (var e : spawners.entrySet()) {
            if (near(e.getKey(), p)) { spawnersTotal++; if (e.getValue()) spawnersLeft++; }
        }
        for (BlockPos v : vaults) {
            if (near(v, p)) { vaultsTotal++; if (!opened.contains(v)) vaultsLeft++; }
        }
        inChamber = spawnersTotal + vaultsTotal > 0;

        // Находимся ли мы внутри камеры (рядом с любым известным блоком камеры)
        BlockPos nearest = null;
        double best = Double.MAX_VALUE;
        for (BlockPos b : spawners.keySet()) { double d = b.getSquaredDistance(p); if (d < best) { best = d; nearest = b; } }
        for (BlockPos b : vaults) { double d = b.getSquaredDistance(p); if (d < best) { best = d; nearest = b; } }
        boolean inside = nearest != null && hdist(nearest, p) <= 32 && Math.abs(nearest.getY() - p.getY()) <= 25;

        if (inside) {
            BlockPos anchor = findAnchor(nearest);
            if (anchor == null) { addStat(chambers, "C", nearest); anchor = nearest.toImmutable(); }

            int leftNow = spawnersLeft + vaultsLeft;
            if (prevLeft > 0 && leftNow == 0 && !doneChambers.contains(anchor)) {
                addStat(doneChambers, "D", anchor);
                mc.player.sendMessage(Text.translatable("trialtracker.cleared"), true);
                mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f));
            }
        }
        prevLeft = spawnersLeft + vaultsLeft;
    }

    // Ищем уже известную камеру рядом с блоком (одна камера = блоки в пределах ~100 блоков)
    private BlockPos findAnchor(BlockPos b) {
        for (BlockPos a : chambers) {
            if (hdist(a, b) <= 100 && Math.abs(a.getY() - b.getY()) <= 60) return a;
        }
        return null;
    }

    private static double hdist(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ---------- подсветка ----------
    private void renderHighlight(WorldRenderContext ctx) {
        if (!showSpawners && !showVaults) return;
        MatrixStack m = ctx.matrixStack();
        if (m == null) return;

        List<BlockPos> targets = new ArrayList<>();
        if (showSpawners) for (var e : spawners.entrySet()) if (e.getValue()) targets.add(e.getKey());
        if (showVaults) for (BlockPos v : vaults) if (!opened.contains(v)) targets.add(v);
        if (targets.isEmpty()) return;

        var camera = ctx.camera();
        Vec3d cam = camera.getPos();
        Matrix4f mat = m.peek().getPositionMatrix();
        float time = (System.currentTimeMillis() % 3000L) / 3000f;

        // Ближайшая цель
        BlockPos nearest = targets.get(0);
        double bestD = Double.MAX_VALUE;
        for (BlockPos b : targets) {
            double d = center(b).squaredDistanceTo(cam);
            if (d < bestD) { bestD = d; nearest = b; }
        }

        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        // 1) Заливка граней
        RenderSystem.setShader(ShaderProgramKeys.POSITION_COLOR);
        BufferBuilder fb = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        for (BlockPos b : targets) {
            float[] c = rainbow(b, time);
            float x0 = (float) (b.getX() - cam.x), y0 = (float) (b.getY() - cam.y), z0 = (float) (b.getZ() - cam.z);
            fill(mat, fb, x0, y0, z0, x0 + 1, y0 + 1, z0 + 1, c[0], c[1], c[2], 0.28f);
        }
        BufferRenderer.drawWithGlobalProgram(fb.end());

        // 2) Рёбра + линия к ближайшей цели
        RenderSystem.lineWidth(4.0f);
        RenderSystem.setShader(ShaderProgramKeys.RENDERTYPE_LINES);
        BufferBuilder lb = Tessellator.getInstance().begin(VertexFormat.DrawMode.LINES, VertexFormats.LINES);
        for (BlockPos b : targets) {
            float[] c = rainbow(b, time);
            double e = 0.002;
            VertexRendering.drawBox(m, lb,
                    b.getX() - cam.x - e, b.getY() - cam.y - e, b.getZ() - cam.z - e,
                    b.getX() + 1 - cam.x + e, b.getY() + 1 - cam.y + e, b.getZ() + 1 - cam.z + e,
                    c[0], c[1], c[2], 1.0f);
        }
        if (showTracer) {
            // Начало линии: чуть впереди и ниже камеры (выглядит как идущая от низа экрана)
            Vector3f fwd = camera.getHorizontalPlane();
            Vector3f up = camera.getVerticalPlane();
            float sx = fwd.x * 0.9f - up.x * 0.35f, sy = fwd.y * 0.9f - up.y * 0.35f, sz = fwd.z * 0.9f - up.z * 0.35f;
            Vec3d c = center(nearest);
            float ex = (float) (c.x - cam.x), ey = (float) (c.y - cam.y), ez = (float) (c.z - cam.z);
            float nx = ex - sx, ny = ey - sy, nz = ez - sz;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 0.0001f) {
                nx /= len; ny /= len; nz /= len;
                float[] col = rainbow(nearest, time);
                lb.vertex(mat, sx, sy, sz).color(col[0], col[1], col[2], 1.0f).normal(m.peek(), nx, ny, nz);
                lb.vertex(mat, ex, ey, ez).color(col[0], col[1], col[2], 1.0f).normal(m.peek(), nx, ny, nz);
            }
        }
        BufferRenderer.drawWithGlobalProgram(lb.end());

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();

        // 3) Подписи с расстоянием (видны сквозь стены)
        VertexConsumerProvider consumers = ctx.consumers();
        if (showDistance && consumers != null) {
            TextRenderer tr = MinecraftClient.getInstance().textRenderer;
            for (BlockPos b : targets) {
                Vec3d c = center(b);
                double dist = Math.sqrt(c.squaredDistanceTo(cam));
                String text = (int) dist + " m";
                m.push();
                m.translate(c.x - cam.x, b.getY() + 1.45 - cam.y, c.z - cam.z);
                m.multiply(camera.getRotation());
                float s = Math.min(0.025f * Math.max(1f, (float) dist / 12f), 0.15f);
                m.scale(s, -s, s);
                float w = tr.getWidth(text);
                tr.draw(text, -w / 2f, 0, 0xFFFFFFFF, false, m.peek().getPositionMatrix(), consumers,
                        TextRenderer.TextLayerType.SEE_THROUGH, 0x66000000, 0xF000F0);
                m.pop();
            }
            if (consumers instanceof VertexConsumerProvider.Immediate imm) imm.draw();
        }
    }

    private static Vec3d center(BlockPos b) {
        return new Vec3d(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
    }

    private static float[] rainbow(BlockPos b, float time) {
        float hue = (time + (Math.floorMod(b.getX() * 31 + b.getZ() * 17 + b.getY(), 100) / 100f)) % 1.0f;
        int rgb = java.awt.Color.HSBtoRGB(hue, 1.0f, 1.0f);
        return new float[]{((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f};
    }

    private static void v(Matrix4f m, VertexConsumer vc, float x, float y, float z, float r, float g, float b, float a) {
        vc.vertex(m, x, y, z).color(r, g, b, a);
    }

    private static void quad(Matrix4f m, VertexConsumer vc, float[] p, float r, float g, float b, float a) {
        for (int i = 0; i < 12; i += 3) v(m, vc, p[i], p[i + 1], p[i + 2], r, g, b, a);
    }

    private static void fill(Matrix4f m, VertexConsumer vc, float x0, float y0, float z0,
                             float x1, float y1, float z1, float r, float g, float b, float a) {
        quad(m, vc, new float[]{x0,y0,z0, x1,y0,z0, x1,y0,z1, x0,y0,z1}, r, g, b, a);
        quad(m, vc, new float[]{x0,y1,z0, x1,y1,z0, x1,y1,z1, x0,y1,z1}, r, g, b, a);
        quad(m, vc, new float[]{x0,y0,z0, x1,y0,z0, x1,y1,z0, x0,y1,z0}, r, g, b, a);
        quad(m, vc, new float[]{x0,y0,z1, x1,y0,z1, x1,y1,z1, x0,y1,z1}, r, g, b, a);
        quad(m, vc, new float[]{x0,y0,z0, x0,y0,z1, x0,y1,z1, x0,y1,z0}, r, g, b, a);
        quad(m, vc, new float[]{x1,y0,z0, x1,y0,z1, x1,y1,z1, x1,y1,z0}, r, g, b, a);
    }

    private static boolean near(BlockPos a, BlockPos b) {
        int dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ(), dy = a.getY() - b.getY();
        return dx * dx + dz * dz <= RADIUS * RADIUS && Math.abs(dy) <= 40;
    }
}
