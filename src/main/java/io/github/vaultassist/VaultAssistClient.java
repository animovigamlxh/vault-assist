package io.github.vaultassist;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;
import java.util.Properties;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * All observations come from server-synchronised CLIENT state. The mod never issues
 * a use-item packet, switches items, or performs an automatic click.
 *
 * A detected UNLOCKING transition is an observation, NOT proof that the local player
 * was the one who opened the vault. The player must manually confirm the result.
 */
public final class VaultAssistClient implements ClientModInitializer {
    private static final String PREFIX = "[宝库助手] ";
    private boolean enabled = true;
    private BlockPos observedPos;
    private String observedDimension = "";
    private String observedItem = "";
    private String observedState = "";
    private boolean observedOminous;
    private long observedAtNano;
    private long lastNoticeAt;
    private Pending pending;
    private int totalConfirmed;
    private int totalHits;
    private int totalMisses;
    private int ticks;
    private Path root;

    private record Pending(String dimension, BlockPos pos, boolean ominous, String shownId,
                           long displayObservedNano, long unlockingObservedNano,
                           String sessionId) { }

    @Override
    public void onInitializeClient() {
        root = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
                .resolve("config").resolve("vaultassist");
        loadStats();
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(literal("vaultassist")
                .executes(ctx -> status())
                .then(literal("on").executes(ctx -> { enabled = true; say("已启用提示"); return 1; }))
                .then(literal("off").executes(ctx -> { enabled = false; clearObservation(); say("已关闭提示"); return 1; }))
                .then(literal("status").executes(ctx -> status()))
                .then(literal("stats").executes(ctx -> stats()))
                .then(literal("hit").executes(ctx -> confirm(true)))
                .then(literal("miss").executes(ctx -> confirm(false)))
                .then(literal("skip").executes(ctx -> skip()))
                .then(literal("note").then(argument("text", StringArgumentType.greedyString())
                    .executes(ctx -> { note(StringArgumentType.getString(ctx, "text")); return 1; })))
            )
        );
    }

    private void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null) { clearObservation(); return; }
        if (!enabled || mc.screen != null) return;
        ticks++;
        HitResult hit = mc.hitResult;
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            clearObservation();
            return;
        }
        BlockPos pos = blockHit.getBlockPos();
        BlockState blockState = mc.level.getBlockState(pos);
        if (!(blockState.getBlock() instanceof VaultBlock)
                || !(mc.level.getBlockEntity(pos) instanceof VaultBlockEntity vault)) {
            clearObservation();
            return;
        }
        String dim = mc.level.dimension().location().toString();
        boolean ominous = blockState.getValue(VaultBlock.OMINOUS);
        ItemStack visible = vault.getSharedData().getDisplayItem();
        String id = visible.isEmpty() ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(visible.getItem()).toString();
        String state = blockState.getValue(VaultBlock.STATE).toString();
        long now = System.nanoTime();
        boolean same = pos.equals(observedPos) && dim.equals(observedDimension);
        boolean changed = !same || !id.equals(observedItem);
        if (changed) {
            observedAtNano = now;
            log("DISPLAY", dim, pos, ominous, id, state, "", "client_observation");
        }
        // This event may be caused by another player. It is deliberately not counted as a win.
        if (same && !state.equals(observedState) && state.toLowerCase(Locale.ROOT).contains("unlocking")) {
            String sessionId = Instant.now().toString() + "_" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ();
            pending = new Pending(dim, pos.immutable(), ominous, observedItem, observedAtNano, now, sessionId);
            long lagMs = Math.max(0, (now - observedAtNano) / 1_000_000);
            log("UNLOCK_OBSERVED", dim, pos, ominous, observedItem, state,
                    "session=" + sessionId + ";display_age_ms=" + lagMs,
                    "not_proof_of_local_interaction");
            say("检测到宝库开锁！展示物品=" + observedItem + "，请检查实际奖励后输入 /vaultassist hit 或 miss；无法判断时用 skip");
        }
        observedPos = pos.immutable();
        observedDimension = dim;
        observedItem = id;
        observedState = state;
        observedOminous = ominous;
        if (visible.is(Items.HEAVY_CORE) && (changed || now - lastNoticeAt > 1_000_000_000L)) {
            mc.player.displayClientMessage(Component.literal("§a[宝库助手] 展示：沉重核心！ §e请手动使用钥匙 §7(" +
                    (ominous ? "不祥" : "普通") + "宝库)"), true);
            lastNoticeAt = now;
            if (changed) log("TARGET_VISIBLE", dim, pos, ominous, id, state, "", "hint_only");
        } else if (ticks % 20 == 0) {
            mc.player.displayClientMessage(Component.literal("§7[宝库助手] " + (ominous ? "不祥" : "普通") +
                    "宝库展示：" + id + " §8(只提示，不自动开锁)"), true);
        }
    }

    private void clearObservation() {
        observedPos = null;
        observedDimension = "";
        observedItem = "";
        observedState = "";
    }

    private int status() {
        say("模式=只提示/手动开锁；启用=" + enabled + "；目标=沉重核心；普通+不祥宝库");
        if (pending != null) say("待确认开锁：" + pending.sessionId + "，展示=" + pending.shownId);
        return 1;
    }

    private int stats() {
        say("人工确认总次数=" + totalConfirmed + "；命中=" + totalHits + "；未命中=" + totalMisses +
                "；确认命中率=" + (totalConfirmed == 0 ? "无数据" : String.format(Locale.ROOT,"%.1f%%", totalHits * 100.0 / totalConfirmed)) +
                "；待确认=" + (pending == null ? "否" : "是"));
        return 1;
    }

    private int confirm(boolean hit) {
        if (pending == null) { say("没有待确认的开锁记录。对准宝库直到检测到开锁，再人工确认。 "); return 0; }
        totalConfirmed++;
        if (hit) totalHits++; else totalMisses++;
        log(hit ? "MANUAL_HIT" : "MANUAL_MISS", pending.dimension, pending.pos,
                pending.ominous, pending.shownId, "",
                "session=" + pending.sessionId + ";unlock_detected_ns=" + pending.unlockingObservedNano,
                "user_confirmed;hit_means_heavy_core_in_actual_rewards");
        pending = null;
        saveStats();
        return stats();
    }

    private int skip() {
        if (pending != null) {
            log("SKIP", pending.dimension, pending.pos, pending.ominous, pending.shownId,
                    "", "session=" + pending.sessionId, "unverified");
            pending = null;
        }
        say("已跳过待确认结果，不计入命中率");
        return 1;
    }

    private void note(String message) {
        if (message.length() > 200) message = message.substring(0, 200);
        log("NOTE", observedDimension, observedPos, observedOminous, observedItem, observedState,
                message, "user_note");
        say("备注已写入 CSV");
    }

    private void say(String s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.literal(PREFIX + s), false);
    }

    private void loadStats() {
        try {
            Path file = root.resolve("stats.properties");
            if (!Files.isRegularFile(file)) return;
            Properties p = new Properties();
            try (var in = Files.newInputStream(file)) { p.load(in); }
            totalConfirmed = Integer.parseInt(p.getProperty("confirmed", "0"));
            totalHits = Integer.parseInt(p.getProperty("hits", "0"));
            totalMisses = Integer.parseInt(p.getProperty("misses", "0"));
        } catch (Exception e) { System.err.println("[VaultAssist] Cannot load stats: " + e); }
    }
    private void saveStats() {
        try {
            Files.createDirectories(root);
            Properties p = new Properties();
            p.setProperty("confirmed", Integer.toString(totalConfirmed));
            p.setProperty("hits", Integer.toString(totalHits));
            p.setProperty("misses", Integer.toString(totalMisses));
            try (var out = Files.newOutputStream(root.resolve("stats.properties"))) {
                p.store(out, "Human-verified results only; do not edit during game");
            }
        } catch (IOException e) { System.err.println("[VaultAssist] Cannot save stats: " + e); }
    }
    private void log(String event, String dimension, BlockPos pos, boolean ominous,
                     String item, String state, String detail, String confidence) {
        try {
            Files.createDirectories(root);
            Path file = root.resolve("events.csv");
            boolean header = !Files.isRegularFile(file);
            String line = String.join(",",
                    csv(Instant.now().toString()), Long.toString(System.currentTimeMillis()),
                    Long.toString(System.nanoTime()), csv(event), csv(dimension),
                    pos == null ? "" : Integer.toString(pos.getX()),
                    pos == null ? "" : Integer.toString(pos.getY()),
                    pos == null ? "" : Integer.toString(pos.getZ()),
                    csv(ominous ? "ominous" : "normal"), csv(item), csv(state),
                    csv(detail), csv(confidence)) + "\n";
            String title = "utc_time,epoch_ms,local_monotonic_ns,event,dimension,x,y,z,vault_type,display_item,state,detail,confidence\n";
            Files.writeString(file, (header ? title : "") + line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) { System.err.println("[VaultAssist] Cannot write CSV: " + e); }
    }
    private static String csv(String s) {
        if (s == null) s = "";
        return '"' + s.replace("\"", "\"\"").replace("\n", " ").replace("\r", " ") + '"';
    }
}
