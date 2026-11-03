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
import net.minecraft.world.InteractionHand;
import java.util.HashSet;
import java.util.Set;
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

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * Observes server-synchronised client data and optionally sends one vanilla
 * block-use interaction when the chosen display item appears. Server alone
 * decides rewards; the visible preview is not a guaranteed drop.
 */
public final class VaultAssistClient implements ClientModInitializer {
    private static final String PREFIX = "[宝库助手] ";
    private boolean enabled = true;
    private boolean autoEnabled = false; // safe default: explicit opt-in required
    private String normalTarget = "minecraft:emerald";
    private String ominousTarget = "minecraft:heavy_core";
    private final Set<String> attemptedVaults = new HashSet<>();
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
        loadSettings();
        // Check at both ends of the client tick. This is a low-risk fallback;
        // it does not claim packet-level or sub-tick notification.
        ClientTickEvents.START_CLIENT_TICK.register(mc -> tick(mc, "start"));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick(mc, "end"));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(literal("vaultassist")
                .executes(ctx -> status())
                .then(literal("on").executes(ctx -> { enabled = true; saveSettings(); say("已启用宝库监控"); return 1; }))
                .then(literal("off").executes(ctx -> { enabled = false; autoEnabled = false; saveSettings(); clearObservation(); say("已关闭监控和自动开锁"); return 1; }))
                .then(literal("auto")
                    .then(literal("on").executes(ctx -> { enabled = true; autoEnabled = true; saveSettings(); say("自动开锁已开启；请手持对应钥匙并对准未开过的宝库"); return 1; }))
                    .then(literal("off").executes(ctx -> { autoEnabled = false; saveSettings(); say("自动开锁已关闭"); return 1; })))
                .then(literal("target")
                    .then(literal("normal").then(argument("item", StringArgumentType.word()).executes(ctx -> setTarget(false, StringArgumentType.getString(ctx, "item")))))
                    .then(literal("ominous").then(argument("item", StringArgumentType.word()).executes(ctx -> setTarget(true, StringArgumentType.getString(ctx, "item"))))))
                .then(literal("resetattempts").executes(ctx -> { attemptedVaults.clear(); say("本次游戏的自动开锁尝试记录已清空"); return 1; }))
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

    private void tick(Minecraft mc, String phase) {
        if (mc.level == null || mc.player == null) { clearObservation(); return; }
        if (!enabled || mc.gui.screen() != null) return;
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
        String dim = mc.level.dimension().toString();
        boolean ominous = blockState.getValue(VaultBlock.OMINOUS);
        ItemStack visible = vault.getSharedData().getDisplayItem();
        String id = visible.isEmpty() ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(visible.getItem()).toString();
        String state = blockState.getValue(VaultBlock.STATE).toString();
        long now = System.nanoTime();
        boolean same = pos.equals(observedPos) && dim.equals(observedDimension);
        boolean changed = !same || !id.equals(observedItem);
        boolean newlyUnlocking = same && !state.equals(observedState)
                && state.toLowerCase(Locale.ROOT).contains("unlocking");
        if (changed) observedAtNano = now;
        // Record current snapshot BEFORE checking the target. No disk writes on the
        // critical path before sending the vanilla use-item interaction.
        observedPos = pos.immutable();
        observedDimension = dim;
        observedItem = id;
        observedState = state;
        observedOminous = ominous;
        String target = ominous ? ominousTarget : normalTarget;
        boolean targetVisible = id.equals(target);
        if (targetVisible && autoEnabled) {
            tryAutoOpen(mc, blockHit, pos, dim, ominous, id, state, phase);
        }
        if (changed) {
            log("DISPLAY", dim, pos, ominous, id, state, "phase=" + phase, "client_observation");
            if (targetVisible) {
                say("§a" + (ominous ? "不祥" : "普通") + "宝库展示目标 " + target + "！" +
                        (autoEnabled ? " §e自动模式已开启" : " §7(自动开锁未开启)"));
                log("TARGET_VISIBLE", dim, pos, ominous, id, state, "phase=" + phase + ";auto=" + autoEnabled, "preview_only");
            } else {
                say("§7" + (ominous ? "不祥" : "普通") + "宝库展示：" + id);
            }
        }
        if (newlyUnlocking) {
            String sessionId = Instant.now() + "_" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ();
            pending = new Pending(dim, pos.immutable(), ominous, id, observedAtNano, now, sessionId);
            long ageMs = Math.max(0, (now - observedAtNano) / 1_000_000);
            log("UNLOCK_OBSERVED", dim, pos, ominous, id, state,
                    "session=" + sessionId + ";display_age_ms=" + ageMs + ";phase=" + phase,
                    "not_proof_of_local_interaction");
            say("检测到宝库开锁。需要统计实际奖励时再用 /vaultassist hit 或 miss；无法确认用 skip");
        }
    }

    private void tryAutoOpen(Minecraft mc, BlockHitResult blockHit, BlockPos pos,
                             String dimension, boolean ominous, String id, String state, String phase) {
        if (mc.gameMode == null || mc.player == null || mc.level == null || mc.gui.screen() != null) return;
        if (mc.player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 25) return;
        if (!state.equalsIgnoreCase("ACTIVE") && !state.equalsIgnoreCase("EJECTING")) return;
        String key = dimension + ":" + pos.getX() + ":" + pos.getY() + ":" + pos.getZ();
        if (attemptedVaults.contains(key)) return;
        InteractionHand hand = null;
        if (mc.player.getMainHandItem().is(ominous ? Items.OMINOUS_TRIAL_KEY : Items.TRIAL_KEY))
            hand = InteractionHand.MAIN_HAND;
        else if (mc.player.getOffhandItem().is(ominous ? Items.OMINOUS_TRIAL_KEY : Items.TRIAL_KEY))
            hand = InteractionHand.OFF_HAND;
        if (hand == null) {
            if (System.nanoTime() - lastNoticeAt > 5_000_000_000L) {
                lastNoticeAt = System.nanoTime();
                say("§e目标已出现，但主手/副手没有" + (ominous ? "不祥" : "普通") + "试炼钥匙；未操作");
            }
            return;
        }
        attemptedVaults.add(key);
        long sendBeginNs = System.nanoTime();
        long visibleAgeNs = Math.max(0, sendBeginNs - observedAtNano);
        // IMPORTANT: send interaction before any CSV I/O or chat messages.
        try {
            mc.gameMode.useItemOn(mc.player, hand, blockHit);
            long returnNs = System.nanoTime();
            mc.player.swing(hand);
            log("AUTO_INTERACT_SENT", dimension, pos, ominous, id, state,
                    "hand=" + hand + ";phase=" + phase + ";display_age_ns=" + visibleAgeNs
                    + ";call_duration_ns=" + Math.max(0, returnNs - sendBeginNs),
                    "client_interaction_not_loot_confirmation");
            say("§a已发送" + (ominous ? "不祥" : "普通") + "宝库交互请求；目标=" + id
                    + "；观察到交互≈" + (visibleAgeNs / 1_000_000.0) + "ms（不是服务端真实延迟）");
        } catch (RuntimeException e) {
            log("AUTO_INTERACT_ERROR", dimension, pos, ominous, id, state, e.toString(), "client_error");
            say("§c开锁交互异常：" + e.getClass().getSimpleName());
        }
    }

    private int setTarget(boolean ominous, String item) {
        String id = item.contains(":") ? item.toLowerCase(Locale.ROOT) : "minecraft:" + item.toLowerCase(Locale.ROOT);
        boolean found = false;
        for (var entry : BuiltInRegistries.ITEM) {
            if (BuiltInRegistries.ITEM.getKey(entry).toString().equals(id)) { found = true; break; }
        }
        if (!found || id.equals("minecraft:air")) { say("物品 ID 不存在：" + id); return 0; }
        if (ominous) ominousTarget = id; else normalTarget = id;
        saveSettings();
        say((ominous ? "不祥" : "普通") + "宝库目标已改为 " + id);
        return 1;
    }

    private void loadSettings() {
        try {
            Path file = root.resolve("settings.properties");
            if (!Files.isRegularFile(file)) return;
            Properties p = new Properties();
            try (var in = Files.newInputStream(file)) { p.load(in); }
            enabled = Boolean.parseBoolean(p.getProperty("monitor_enabled", "true"));
            // Never automatically enable after restart: prevent accidental key consumption.
            autoEnabled = false;
            normalTarget = p.getProperty("normal_target", "minecraft:emerald");
            ominousTarget = p.getProperty("ominous_target", "minecraft:heavy_core");
        } catch (Exception e) { System.err.println("[VaultAssist] Cannot load settings: " + e); }
    }
    private void saveSettings() {
        try {
            Files.createDirectories(root);
            Properties p = new Properties();
            p.setProperty("monitor_enabled", Boolean.toString(enabled));
            p.setProperty("normal_target", normalTarget);
            p.setProperty("ominous_target", ominousTarget);
            try (var out = Files.newOutputStream(root.resolve("settings.properties"))) {
                p.store(out, "Vault Assist 26.2 settings; auto mode always starts OFF");
            }
        } catch (IOException e) { System.err.println("[VaultAssist] Cannot save settings: " + e); }
    }

    private void clearObservation() {
        observedPos = null;
        observedDimension = "";
        observedItem = "";
        observedState = "";
    }

    private int status() {
        say("监控=" + enabled + "；自动开锁=" + autoEnabled + "；普通目标=" + normalTarget + "；不祥目标=" + ominousTarget);
        say("手持匹配钥匙并对准宝库；同一宝库本次游戏只尝试一次。/vaultassist auto on|off");
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
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal(PREFIX + s));
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
