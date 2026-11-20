package pl.chestbot;

import net.minecraft.block.BarrelBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.RaycastContext;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Glowna logika bota:
 * szukaj skrzynki -> idz -> otworz -> zabierz wszystko -> (pelny EQ -> /sell all [+ /sell confirm])
 * -> brak skrzynek lub koniec ochrony -> stoj nieruchomo, /spawn -> przycisk RTP na gabce -> dalej zbieraj.
 * Po drodze: auto-jedzenie, wychodzenie z wody/lawy, omijanie scian.
 */
public final class Bot {
    public enum State { IDLE, SEARCHING, WALKING, EXPLORING, OPENING, LOOTING, SELLING, SPAWNING,
        RTP_FIND, RTP_WALK, RTP_CLICK, RTP_WAIT }

    public static int tick = 0;
    public static int chestsLooted = 0;
    public static int sells = 0;
    public static int rtps = 0;
    public static String lastOverlay = "";

    private static boolean enabled = false;
    private static State state = State.IDLE;
    private static String status = "";

    // ochrona
    private static int lastProtectionTick = -100000;
    private static boolean protectionSeen = false;
    private static double protRemaining = -1;
    private static int startTick = 0;

    // odwiedzone skrzynki: pos -> tick wygasniecia
    private static final Map<Long, Integer> visited = new HashMap<>();

    private static ChestScanner.Target target;
    private static BlockPos attemptPos;
    private static int openFails, openRetries;
    private static List<BlockPos> path;
    private static int pathIdx;
    private static boolean keysHeld;
    private static Vec3d segStart = Vec3d.ZERO;
    private static Vec3d stuckPos = Vec3d.ZERO;
    private static int stuckCheck, stuckCount, nodeStart, collideTicks;
    private static int exploreCooldown, noChestSince = -1;

    private static int openStart, openClick, aimTicks;
    private static int lootStart, nextClick, lastClicked;
    private static boolean sawItems;
    private static final Set<Integer> skip = new HashSet<>();

    // sprzedaz
    private static int sellStage, sellTick, sellTries, sellSentTick, resendSellAt, confirmSentTick, confirmAt;
    private static boolean confirmPending, confirmSent;

    // /spawn
    private static int spawnStage, spawnStart, spawnSent, spawnDoneTick;
    private static Vec3d spawnPos = Vec3d.ZERO;

    // RTP (przycisk na gabce)
    private static BlockPos rtpPos;
    private static int rtpFindStart, rtpFindTries, rtpClicks, rtpClickTick;
    private static Vec3d rtpStartPos = Vec3d.ZERO;

    // woda / lawa
    private static boolean escaping;
    private static List<BlockPos> escPath;
    private static int escIdx, escCalc;

    // jedzenie
    private static int eatStage, eatStart, eatPrevSlot, eatCooldown, noFoodWarn = -100000;

    private static final Random RANDOM = new Random();
    private static final Pattern MMSS = Pattern.compile("(\\d+):(\\d{1,2})");
    private static final Pattern MINUTES = Pattern.compile("(\\d+)\\s*(?:m|min)\\b");
    private static final Pattern SECONDS = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(?:s|sek)");
    private static final Pattern ANYNUM = Pattern.compile("(\\d+)");

    private Bot() {}

    // ------------------------------------------------------------------ API

    public static boolean isEnabled() { return enabled; }

    public static void toggle() { if (enabled) stop("Wyłączony ręcznie"); else start(); }

    public static void start() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        if (!License.valid()) { chat("§cBrak aktywnej licencji: " + License.message()); return; }
        Config c = Config.get();
        enabled = true;
        state = State.SEARCHING;
        startTick = tick;
        target = null;
        path = null;
        chestsLooted = 0;
        sells = 0;
        rtps = 0;
        sellTries = 0;
        stuckCount = 0;
        eatStage = 0;
        escaping = false;
        noChestSince = -1;
        openFails = 0;
        openRetries = 0;
        Pathfinder.clearBlocked();
        protectionSeen = (tick - lastProtectionTick) <= c.protectionTimeoutSec * 20;
        setStatus("Start");
        chat("§aBot włączony.");
    }

    public static void stop(String reason) {
        MinecraftClient mc = MinecraftClient.getInstance();
        endEating(mc, mc.player);
        if (mc.player != null) holdStill(mc, mc.player);
        enabled = false;
        state = State.IDLE;
        path = null;
        target = null;
        escaping = false;
        if (mc.player != null && mc.player.currentScreenHandler != mc.player.playerScreenHandler
                && mc.currentScreen instanceof GenericContainerScreen) {
            mc.player.closeHandledScreen();
        }
        if (reason != null) chat("§cBot zatrzymany: §f" + reason);
    }

    public static void clearVisited() { visited.clear(); }

    public static void onDisconnect() {
        enabled = false;
        state = State.IDLE;
        keysHeld = false;
        eatStage = 0;
        escaping = false;
        visited.clear();
        Pathfinder.clearBlocked();
        ChestScanner.clear();
    }

    public static boolean isVisited(BlockPos pos) {
        Integer e = visited.get(pos.asLong());
        if (e == null) return false;
        if (tick >= e) { visited.remove(pos.asLong()); return false; }
        return true;
    }

    /** Wiadomosci systemowe: overlay = tekst nad hotbarem, reszta = czat. */
    public static void onGameMessage(Text msg, boolean overlay) {
        if (msg == null) return;
        if (overlay) { onOverlay(msg.getString()); return; }
        onChatLine(msg.getString());
    }

    /** Czat: potwierdzenie sprzedazy, limit komend, informacja o teleporcie na spawn. */
    public static void onChatLine(String raw) {
        if (!enabled || raw == null) return;
        Config c = Config.get();
        String s = raw.toLowerCase(Locale.ROOT);

        // "Teleportowano na spawn." - mozna isc dalej (wczesniej niz po stalym czasie)
        if (state == State.SPAWNING && spawnStage == 1) {
            String kw = c.spawnDoneKeyword == null ? "" : c.spawnDoneKeyword.trim().toLowerCase(Locale.ROOT);
            if (!kw.isEmpty() && s.contains(kw)) spawnDoneTick = tick;
            return;
        }
        if (state != State.SELLING) return;

        // limit komend: "Przez chwile po /sell nie mozesz wykonywac komend. Pozostalo: 1.5s"
        if (s.contains("nie możesz wykonywać komend") || s.contains("następnej komendy")) {
            double sec = parseCooldown(s);
            int at = tick + (int) Math.ceil(sec * 20) + 4;
            if (confirmSent && tick - confirmSentTick < 60) {
                confirmSent = false;
                confirmPending = true;
                confirmAt = at;
            } else if (confirmPending) {
                confirmAt = Math.max(confirmAt, at);
            } else if (tick - sellSentTick < 60) {
                resendSellAt = Math.max(resendSellAt, at);
            }
            return;
        }

        // prosba o potwierdzenie (np. "Wpisz /sell confirm, aby potwierdzic ...")
        if (c.autoConfirmSell && !confirmPending && !confirmSent) {
            String kw = c.sellConfirmKeyword == null ? "" : c.sellConfirmKeyword.trim().toLowerCase(Locale.ROOT);
            if (!kw.isEmpty() && s.contains(kw)) {
                confirmPending = true;
                confirmAt = Math.max(tick + 6, sellSentTick + (int) (c.sellConfirmDelaySec * 20));
            }
        }
    }

    private static double parseCooldown(String s) {
        Matcher m = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*s").matcher(s);
        double last = -1;
        while (m.find()) {
            try { last = Double.parseDouble(m.group(1).replace(',', '.')); } catch (Exception ignored) {}
        }
        return last >= 0 ? last : 2.0;
    }

    /** Wywolywane (z mixina) dla kazdego tekstu pokazywanego nad hotbarem. */
    public static void onOverlay(String s) {
        if (s == null || s.isBlank()) return;
        lastOverlay = s;
        Config c = Config.get();
        String kw = c.protectionKeyword == null ? "" : c.protectionKeyword.trim().toLowerCase(Locale.ROOT);
        if (kw.isEmpty() || s.toLowerCase(Locale.ROOT).contains(kw)) {
            lastProtectionTick = tick;
            protectionSeen = true;
            protRemaining = parseSeconds(s);
        }
    }

    /** Wyciaga z tekstu pozostaly czas: "00:08", "1m 5s", "8s", "8 sekund", "8". */
    static double parseSeconds(String text) {
        String s = text.toLowerCase(Locale.ROOT).replaceAll("§.", "");
        try {
            Matcher m = MMSS.matcher(s);
            if (m.find()) return Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
            double total = -1;
            Matcher mm = MINUTES.matcher(s);
            if (mm.find()) total = Integer.parseInt(mm.group(1)) * 60;
            Matcher ms = SECONDS.matcher(s);
            if (ms.find()) return Math.max(0, total) + Double.parseDouble(ms.group(1).replace(',', '.'));
            if (total >= 0) return total;
            Matcher any = ANYNUM.matcher(s);
            if (any.find()) return Integer.parseInt(any.group(1));
        } catch (Exception ignored) {}
        return -1;
    }

    public static double protectionLeft() {
        if (!protectionSeen || protRemaining < 0) return -1;
        return Math.max(0, protRemaining - (tick - lastProtectionTick) / 20.0);
    }

    public static String stateLabel() {
        if (!enabled) return "WYŁĄCZONY";
        if (escaping) return "wychodzi z wody/lawy";
        if (eatStage == 1) return "je";
        return switch (state) {
            case IDLE -> "bezczynny";
            case SEARCHING -> "szuka skrzynki";
            case WALKING -> "idzie do skrzynki";
            case EXPLORING -> "eksploruje";
            case OPENING -> "otwiera skrzynkę";
            case LOOTING -> "zabiera przedmioty";
            case SELLING -> "sprzedaje";
            case SPAWNING -> "stoi i czeka na /spawn";
            case RTP_FIND -> "szuka przycisku RTP";
            case RTP_WALK -> "idzie do przycisku RTP";
            case RTP_CLICK -> "klika przycisk RTP";
            case RTP_WAIT -> "czeka na teleport RTP";
        };
    }

    public static String statusText() { return status; }

    public static String protectionLabel() {
        if (!protectionSeen) return "czekam na tekst...";
        double left = protectionLeft();
        if (left >= 0) return String.format(Locale.ROOT, "zostało ~%.0fs (/spawn przy %.0fs)", left, Config.get().spawnAtSeconds);
        return "aktywna";
    }

    public static boolean protectionSeenNow() { return protectionSeen; }

    public static int freeSlots(ClientPlayerEntity p) {
        int n = 0;
        for (ItemStack s : p.getInventory().main) if (s.isEmpty()) n++;
        return n;
    }

    private static boolean isRtpState() {
        return state == State.RTP_FIND || state == State.RTP_WALK || state == State.RTP_CLICK || state == State.RTP_WAIT;
    }

    // ------------------------------------------------------------------ TICK

    public static void onTick(MinecraftClient mc) {
        tick++;
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) {
            if (enabled) { enabled = false; state = State.IDLE; keysHeld = false; eatStage = 0; escaping = false; }
            return;
        }
        Config c = Config.get();

        if (!License.valid()) {
            if (enabled) stop("Licencja: " + License.message());
            return;
        }

        int rad = 0;
        if (c.espEnabled) rad = Math.max(rad, c.espRange);
        if (enabled) rad = Math.max(rad, c.searchRadius);
        if (rad > 0) ChestScanner.update(mc, tick, rad);

        if (!enabled) return;
        if (p.isDead()) { stop("Gracz nie żyje"); return; }

        // ---- ochrona (tekst nad hotbarem) ----
        if (c.requireProtection && state != State.SPAWNING && !isRtpState()) {
            if (protectionSeen) {
                double left = protectionLeft();
                if (left >= 0 && left <= c.spawnAtSeconds) {
                    beginSpawn(mc, p, String.format(Locale.ROOT, "Zostało ~%.0fs ochrony", left));
                } else if (tick - lastProtectionTick > c.protectionTimeoutSec * 20) {
                    beginSpawn(mc, p, "Ochrona dobiegła końca");
                }
            } else if (tick - startTick > c.protectionStartGraceSec * 20) {
                beginSpawn(mc, p, "Nie wykryto ochrony nad hotbarem");
            }
            if (state == State.SPAWNING) { doSpawning(mc, p, c); return; }
        }

        // ---- brak skrzynek w poblizu -> spawn + RTP ----
        if (c.rtpLoop && noChestSince >= 0 && (state == State.SEARCHING || state == State.EXPLORING)
                && tick - noChestSince > c.noChestSec * 20) {
            beginSpawn(mc, p, "Brak skrzynek w pobliżu");
            doSpawning(mc, p, c);
            return;
        }

        // ---- woda / lawa: najpierw wyjdz ----
        boolean movingState = state == State.SEARCHING || state == State.WALKING || state == State.EXPLORING
                || state == State.OPENING || isRtpState();
        if ((movingState || (state == State.SPAWNING && p.isInLava())) && doEscape(mc, p)) return;

        // ---- auto-jedzenie ----
        if ((state == State.SEARCHING || state == State.WALKING || state == State.EXPLORING) && doEat(mc, p, c)) return;

        switch (state) {
            case SEARCHING -> doSearch(mc, p, c);
            case WALKING, EXPLORING, RTP_WALK -> doWalk(mc, p, c);
            case OPENING -> doOpen(mc, p, c);
            case LOOTING -> doLoot(mc, p, c);
            case SELLING -> doSell(mc, p, c);
            case SPAWNING -> doSpawning(mc, p, c);
            case RTP_FIND -> doRtpFind(mc, p, c);
            case RTP_CLICK -> doRtpClick(mc, p, c);
            case RTP_WAIT -> doRtpWait(mc, p, c);
            default -> {}
        }
    }

    // ------------------------------------------------------------------ WODA / LAWA

    private static boolean inFluid(ClientPlayerEntity p) {
        return p.isInLava() || p.isSubmergedInWater() || (p.isTouchingWater() && !p.isOnGround());
    }

    /** @return true gdy bot wlasnie wyplywa (wtedy reszta logiki jest pomijana). */
    private static boolean doEscape(MinecraftClient mc, ClientPlayerEntity p) {
        if (!inFluid(p)) {
            if (escaping) {
                escaping = false;
                escPath = null;
                mc.options.jumpKey.setPressed(false);
                mc.options.forwardKey.setPressed(false);
                keysHeld = false;
                if (state == State.WALKING || state == State.EXPLORING) state = State.SEARCHING;
                else if (state == State.RTP_WALK) state = State.RTP_FIND;
            }
            return false;
        }
        escaping = true;
        if (eatStage == 1) endEating(mc, p);

        if (escPath == null || escIdx >= escPath.size() || tick - escCalc >= 20) {
            escPath = Pathfinder.escapeFluid(mc.world, p.getBlockPos(), 3000);
            escIdx = 0;
            escCalc = tick;
        }
        mc.options.jumpKey.setPressed(true);          // plyn w gore / wyskakuj na brzeg
        keysHeld = true;
        if (escPath == null || escPath.isEmpty()) {
            mc.options.forwardKey.setPressed(true);   // nie znamy wyjscia - plyn przed siebie
            return true;
        }
        BlockPos n = escPath.get(escIdx);
        double dx = n.getX() + 0.5 - p.getX(), dz = n.getZ() + 0.5 - p.getZ();
        if (Math.abs(dx) < 0.45 && Math.abs(dz) < 0.45 && Math.abs(p.getY() - n.getY()) < 1.3
                && escIdx < escPath.size() - 1) {
            escIdx++;
            n = escPath.get(escIdx);
            dx = n.getX() + 0.5 - p.getX();
            dz = n.getZ() + 0.5 - p.getZ();
        }
        float want = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
        float diff = MathHelper.wrapDegrees(want - p.getYaw());
        p.setYaw(p.getYaw() + MathHelper.clamp(diff * 0.45f, -30f, 30f));
        mc.options.forwardKey.setPressed(Math.abs(diff) < 70f);
        return true;
    }

    // ------------------------------------------------------------------ JEDZENIE

    private static boolean goodFood(ItemStack s) {
        if (s.isEmpty() || !s.contains(DataComponentTypes.FOOD)) return false;
        Item i = s.getItem();
        return i != Items.ROTTEN_FLESH && i != Items.POISONOUS_POTATO && i != Items.PUFFERFISH
                && i != Items.SPIDER_EYE && i != Items.CHORUS_FRUIT && i != Items.GOLDEN_APPLE
                && i != Items.ENCHANTED_GOLDEN_APPLE && i != Items.SUSPICIOUS_STEW;
    }

    private static int findFoodSlot(ClientPlayerEntity p, Config c) {
        String pref = c.eatPreferred == null ? "" : c.eatPreferred.trim().toLowerCase(Locale.ROOT);
        int best = -1, bestScore = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.getInventory().main.get(i);
            if (!goodFood(s)) continue;
            var food = s.get(DataComponentTypes.FOOD);
            int score = food == null ? 0 : food.nutrition();
            String id = Registries.ITEM.getId(s.getItem()).getPath();
            if (!pref.isEmpty() && id.contains(pref)) score += 1000;
            if (score > bestScore) { bestScore = score; best = i; }
        }
        return best;
    }

    private static boolean doEat(MinecraftClient mc, ClientPlayerEntity p, Config c) {
        if (eatStage == 1) {
            holdStill(mc, p);
            mc.options.useKey.setPressed(true);
            boolean done = tick - eatStart > 4 && !p.isUsingItem();
            if (done || tick - eatStart > 70) {
                endEating(mc, p);
                eatCooldown = tick + (done ? 10 : 100);
            }
            return true;
        }
        if (!c.autoEat || tick < eatCooldown || mc.currentScreen != null) return false;
        if (p.getHungerManager().getFoodLevel() > c.eatBelow) return false;
        int slot = findFoodSlot(p, c);
        if (slot < 0) {
            if (tick - noFoodWarn > 1200) { chat("§eBrak jedzenia na pasku."); noFoodWarn = tick; }
            eatCooldown = tick + 100;
            return false;
        }
        eatPrevSlot = p.getInventory().selectedSlot;
        p.getInventory().selectedSlot = slot;
        holdStill(mc, p);
        mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
        mc.options.useKey.setPressed(true);
        eatStage = 1;
        eatStart = tick;
        setStatus("Jem");
        return true;
    }

    private static void endEating(MinecraftClient mc, ClientPlayerEntity p) {
        if (eatStage == 1) {
            mc.options.useKey.setPressed(false);
            if (p != null) p.getInventory().selectedSlot = eatPrevSlot;
            eatStage = 0;
        }
    }

    // ------------------------------------------------------------------ SZUKANIE I CHODZENIE

    private static void doSearch(MinecraftClient mc, ClientPlayerEntity p, Config c) {
        ClientWorld w = mc.world;
        if (p.currentScreenHandler != p.playerScreenHandler) {
            holdStill(mc, p);
            p.closeHandledScreen();
            return;
        }
        if (freeSlots(p) <= c.minFreeSlots) {
            holdStill(mc, p);
            if (c.autoSell) beginSell();
            else stop("Ekwipunek pełny");
            return;
        }

        // trzymamy sie jednego celu, zeby bot nie zmienial skrzynki w pol drogi
        ChestScanner.Target best = null;
        if (target != null && isTargetBlock(w, target.pos()) && !isVisited(target.pos())) best = target;
        if (best == null) {
            target = null;
            best = findNearest(mc, p, c);
        }
        if (best == null) {
            holdStill(mc, p);
            setStatus("Brak skrzynek w zasięgu");
            if (noChestSince < 0) noChestSince = tick;
            // jesli stoimy na spawnie i widac przycisk RTP - kliknij od razu
            if (c.rtpLoop && tick - noChestSince > 40 && tick % 20 == 0 && findRtpButton(mc, p, c) != null) {
                beginRtp();
                return;
            }
            if (c.explore) startExplore(mc, p);
            return;
        }
        noChestSince = -1;
        if (attemptPos == null || !attemptPos.equals(best.pos())) {   // nowa skrzynka - zeruj liczniki prob
            attemptPos = best.pos();
            openFails = 0;
            openRetries = 0;
        }
        target = best;
        if (canReach(p, best.pos(), c)) { beginOpen(); return; }

        Vec3d ctr = aimPoint(w, best.pos());
        BlockPos chestPos = best.pos();
        double reach = c.reach;
        boolean through = c.throughWalls;
        List<BlockPos> pth = Pathfinder.find(w, p.getBlockPos(), n -> {
            Vec3d eye = new Vec3d(n.getX() + 0.5, n.getY() + 1.62, n.getZ() + 0.5);
            if (eye.squaredDistanceTo(ctr) > reach * reach) return false;
            return through || visibleFrom(w, eye, chestPos, p);
        }, ctr, reach, 1.0, 6000);
        if (pth == null || pth.isEmpty()) {
            markVisited(w, best.pos(), 20 * 30);
            target = null;
            setStatus("Brak ścieżki do skrzynki - pomijam");
            return;
        }
        beginWalk(p, Pathfinder.smooth(w, p.getBlockPos(), pth), State.WALKING);
        setStatus("Idę do skrzynki (" + (int) Math.sqrt(p.getPos().squaredDistanceTo(ctr)) + " m)");
    }

    private static void startExplore(MinecraftClient mc, ClientPlayerEntity p) {
        if (tick < exploreCooldown) return;
        exploreCooldown = tick + 10;
        double ang = RANDOM.nextDouble() * Math.PI * 2;
        double dist = 14 + RANDOM.nextDouble() * 14;
        double tx = p.getX() + Math.cos(ang) * dist, tz = p.getZ() + Math.sin(ang) * dist;
        Vec3d tv = new Vec3d(tx, p.getY(), tz);
        List<BlockPos> pth = Pathfinder.find(mc.world, p.getBlockPos(), n -> {
            double dx = n.getX() + 0.5 - tx, dz = n.getZ() + 0.5 - tz;
            return dx * dx + dz * dz <= 6.25;
        }, tv, 2.0, 0.0, 3000);
        if (pth != null && !pth.isEmpty()) {
            beginWalk(p, Pathfinder.smooth(mc.world, p.getBlockPos(), pth), State.EXPLORING);
            setStatus("Eksploruję w poszukiwaniu skrzynek");
        }
    }

    private static void beginWalk(ClientPlayerEntity p, List<BlockPos> pth, State s) {
        path = pth;
        pathIdx = 0;
        state = s;
        segStart = p.getPos();
        stuckPos = p.getPos();
        stuckCheck = tick;
        nodeStart = tick;
        collideTicks = 0;
    }

    private static State afterWalkState() {
        return state == State.RTP_WALK ? State.RTP_FIND : State.SEARCHING;
    }

    private static void doWalk(MinecraftClient mc, ClientPlayerEntity p, Config c) {
        if (path == null || pathIdx >= path.size()) { state = afterWalkState(); return; }

        if (state == State.WALKING) {
            if (target == null || !isTargetBlock(mc.world, target.pos())) {
                target = null; state = State.SEARCHING; return;
            }
            if (canReach(p, target.pos(), c)) { holdStill(mc, p); beginOpen(); return; }
        } else if (state == State.RTP_WALK) {
            if (rtpPos == null || !(mc.world.getBlockState(rtpPos).getBlock() instanceof ButtonBlock)) {
                state = State.RTP_FIND; return;
            }
            if (canReachBlock(p, rtpPos, c)) {
                holdStill(mc, p);
                state = State.RTP_CLICK;
                openStart = tick;
                aimTicks = 0;
                setStatus("Klikam przycisk RTP");
                return;
            }
        } else if (tick % 20 == 0 && findNearest(mc, p, c) != null) {
            state = State.SEARCHING; return;
        }

        // zderzenie ze sciana: zapamietaj przeszkode i policz trase od nowa (zamiast skakac w sciane)
        if (p.horizontalCollision) collideTicks++; else collideTicks = 0;
        if (collideTicks >= 14) {
            BlockPos n = path.get(pathIdx);
            Direction d = Direction.getFacing(n.getX() + 0.5 - p.getX(), 0, n.getZ() + 0.5 - p.getZ());
            Pathfinder.block(p.getBlockPos().offset(d));
            collideTicks = 0;
            state = afterWalkState();
            return;
        }

        // zablokowanie: brak ruchu albo za dlugo do jednego punktu
        boolean stuck = false;
        if (tick - stuckCheck >= 30) {
            if (p.getPos().squaredDistanceTo(stuckPos) < 0.2) stuck = true;
            stuckPos = p.getPos();
            stuckCheck = tick;
        }
        if (tick - nodeStart > 100) stuck = true;
        if (stuck) {
            stuckCount++;
            if (stuckCount >= 3) {
                if (state == State.WALKING && target != null) markVisited(mc.world, target.pos(), 20 * 60);
                stuckCount = 0;
                target = null;
            }
            state = afterWalkState();
            return;
        }
        steer(mc, p, c, 0);
    }

    private static boolean canStepUp(ClientWorld w, ClientPlayerEntity p, double dx, double dz) {
        Direction d = Direction.getFacing(dx, 0, dz);
        BlockPos feet = p.getBlockPos();
        BlockPos ahead = feet.offset(d);
        VoxelShape sh = w.getBlockState(ahead).getCollisionShape(w, ahead);
        if (sh.isEmpty() || sh.getMax(Direction.Axis.Y) > 1.0) return false;   // brak przeszkody albo za wysoka
        return Pathfinder.passable(w, ahead.up()) && Pathfinder.passable(w, ahead.up(2)) && Pathfinder.passable(w, feet.up(2));
    }

    private static void steer(MinecraftClient mc, ClientPlayerEntity p, Config c, int depth) {
        BlockPos n = path.get(pathIdx);
        double cx = n.getX() + 0.5, cz = n.getZ() + 0.5;
        double dx = cx - p.getX(), dz = cz - p.getZ();
        double hd = Math.sqrt(dx * dx + dz * dz);
        boolean lastNode = pathIdx == path.size() - 1;
        boolean yOk = Math.abs(p.getY() - n.getY()) < 1.1;

        double sx = cx - segStart.x, sz = cz - segStart.z;
        double len2 = sx * sx + sz * sz;
        boolean passed = false;
        if (len2 > 0.25) {
            double t = ((p.getX() - segStart.x) * sx + (p.getZ() - segStart.z) * sz) / len2;
            passed = t >= 1.0 && hd < 1.3;
        }
        if (yOk && (hd < (lastNode ? 0.4 : 0.6) || (passed && !lastNode))) {
            segStart = new Vec3d(cx, n.getY(), cz);
            pathIdx++;
            nodeStart = tick;
            if (pathIdx >= path.size()) { state = afterWalkState(); return; }
            if (depth < 4) steer(mc, p, c, depth + 1);
            return;
        }

        float want = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
        float diff = MathHelper.wrapDegrees(want - p.getYaw());
        float step = MathHelper.clamp(diff * 0.35f, -22f, 22f) + (RANDOM.nextFloat() - 0.5f) * 0.4f;
        p.setYaw(p.getYaw() + step);
        float wantPitch = 6f + (float) Math.sin(tick * 0.06) * 1.5f;
        p.setPitch(MathHelper.lerp(0.08f, p.getPitch(), wantPitch));

        boolean forward = Math.abs(diff) < 55f;
        // skacz tylko gdy to ma sens: nastepny punkt wyzej albo przed nami stopien do wejscia (nie w wysoka sciane)
        boolean jump = p.isOnGround() && ((n.getY() > p.getY() + 0.1 && hd < 1.6)
                || (p.horizontalCollision && forward && canStepUp(mc.world, p, dx, dz)));
        mc.options.forwardKey.setPressed(forward);
        mc.options.jumpKey.setPressed(jump);
        keysHeld = true;

        boolean canSprint = c.sprint && forward && Math.abs(diff) < 25f
                && p.getHungerManager().getFoodLevel() > 6
                && !p.horizontalCollision && !p.isTouchingWater() && !p.isSneaking();
        if (canSprint) p.setSprinting(true);
        else if (!forward && p.isSprinting()) p.setSprinting(false);
    }

    // ------------------------------------------------------------------ OTWIERANIE SKRZYNEK

    private static void beginOpen() {
        state = State.OPENING;
        openStart = tick;
        openClick = -1;
        aimTicks = 0;
        stuckCount = 0;
        setStatus("Otwieram skrzynkę");
    }

    private static void doOpen(MinecraftClient mc, ClientPlayerEntity p, Config c) {
        if (target == null) { state = State.SEARCHING; return; }

        if (mc.currentScreen instanceof GenericContainerScreen && p.currentScreenHandler instanceof GenericContainerScreenHandler) {
            state = State.LOOTING;
            lootStart = tick;
            nextClick = tick + 3;
            lastClicked = -1;
            sawItems = false;
            skip.clear();
            setStatus("Zabieram przedmioty");
            return;
        }
        if (!canReach(p, target.pos(), c)) { state = State.SEARCHING; return; }

        holdStill(mc, p);
        int dt = tick - openStart;
        boolean aimed = aimAtPoint(p, aimPoint(mc.world, target.pos()));
        Vec3d v = p.getVelocity();
        boolean still = Math.hypot(v.x, v.z) < 0.04 || dt > 10;

        if (openClick < 0 && aimed && still) {
            clickBlock(mc, p, target.pos());
            openClick = tick;
        } else if (openClick >= 0 && tick - openClick == 25) {
            clickBlock(mc, p, target.pos());
        }
        if (dt > 70) {      // skrzynka sie nie otworzyla - sprobuj jeszcze raz, dopiero potem odpusc na chwile
            openFails++;
            if (openFails < 3) {
                beginOpen();
            } else {
                markVisited(mc.world, target.pos(), 20 * 40);
                target = null;
                openFails = 0;
                state = State.SEARCHING;
            }
        }
    }

    /** Obraca glowe plynnie w strone punktu; true gdy trafiono i rotacja jest stabilna (serwer zdazyl ja dostac). */
    private static boolean aimAtPoint(ClientPlayerEntity p, Vec3d ctr) {
        Vec3d eye = p.getEyePos();
        double dx = ctr.x - eye.x, dy = ctr.y - eye.y, dz = ctr.z - eye.z;
        double hd = Math.sqrt(dx * dx + dz * dz);
        float wantYaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
        float wantPitch = (float) -(Math.atan2(dy, hd) * 180.0 / Math.PI);
        float dYaw = MathHelper.wrapDegrees(wantYaw - p.getYaw());
        float dPitch = wantPitch - p.getPitch();
        if (Math.abs(dYaw) < 4f && Math.abs(dPitch) < 4f) {
            p.setYaw(p.getYaw() + dYaw);
            p.setPitch(wantPitch);
            aimTicks++;
        } else {
            p.setYaw(p.getYaw() + MathHelper.clamp(dYaw * 0.35f, -20f, 20f));
            p.setPitch(p.getPitch() + MathHelper.clamp(dPitch * 0.35f, -14f, 14f));
            aimTicks = 0;
        }
        return aimTicks >= 3;
    }

    /** Klik jak prawdziwy: punkt trafienia liczony promieniem wzdluz kierunku patrzenia (anticheaty to sprawdzaja). */
    private static void clickBlock(MinecraftClient mc, ClientPlayerEntity p, BlockPos pos) {
        ClientWorld w = mc.world;
        Vec3d eye = p.getEyePos();
        Vec3d end = eye.add(p.getRotationVec(1.0f).multiply(6.0));
        BlockHitResult hit = w.getBlockState(pos).getOutlineShape(w, pos).raycast(eye, end, pos);
        if (hit == null) {
            Vec3d ctr = aimPoint(w, pos);
            Direction face = Direction.getFacing(eye.x - ctr.x, eye.y - ctr.y, eye.z - ctr.z);
            hit = new BlockHitResult(ctr, face, pos, false);
        }
        mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, hit);
        p.swingHand(Hand.MAIN_HAND);
    }

    private static void doLoot(MinecraftClient mc, ClientPlayerEntity p, Config c) {
        if (!(mc.currentScreen instanceof GenericContainerScreen)
                || !(p.currentScreenHandler instanceof GenericContainerScreenHandler)) {
            // ekran zamkniety zanim cokolwiek zabralismy - otworz ponownie zamiast uznawac skrzynke za oproznioną
            if (!sawItems && openRetries < 2) { openRetries++; beginOpen(); return; }
            finishChest(mc, p, c, false, false);
            return;
        }
        GenericContainerScreenHandler h = (GenericContainerScreenHandler) p.currentScreenHandler;
        int size = h.getRows() * 9;

        if (!sawItems) {
            boolean any = false;
            for (int i = 0; i < size; i++) if (h.getSlot(i).hasStack()) { any = true; break; }
            if (any) {
                sawItems = true;
            } else if (tick - lootStart < Math.max(5, c.lootWaitTicks)) {
                return;                             // zawartosc moze jeszcze dojsc (lag / loot generowany z opoznieniem)
            } else {
                p.closeHandledScreen();             // naprawde pusta albo nie dosla - sprobuj raz jeszcze
                if (openRetries < 1) { openRetries++; beginOpen(); return; }
                if (target != null) markVisited(mc.world, target.pos(), c.chestCooldownSec * 20);
                target = null;
                openRetries = 0;
                state = State.SEARCHING;
                return;
            }
        }
        if (tick < nextClick) return;

        if (lastClicked >= 0 && h.getSlot(lastClicked).hasStack()) skip.add(lastClicked); // nie zmiescilo sie
        lastClicked = -1;

        int idx = -1;
        for (int i = 0; i < size; i++) {
            if (h.getSlot(i).hasStack() && !skip.contains(i)) { idx = i; break; }
        }
        if (idx < 0 || tick - lootStart > 20 * 30) {
            finishChest(mc, p, c, true, !skip.isEmpty());
            return;
        }
        mc.interactionManager.clickSlot(h.syncId, idx, 0, SlotActionType.QUICK_MOVE, p);
        lastClicked = idx;
        nextClick = tick + Math.max(1, c.lootDelay);
    }

    private static void finishChest(MinecraftClient mc, ClientPlayerEntity p, Config c, boolean close, boolean leftover) {
        if (close && p.currentScreenHandler != p.playerScreenHandler) p.closeHandledScreen();
        if (target != null) {
            boolean full = freeSlots(p) <= c.minFreeSlots;
            if (!leftover || !full) markVisited(mc.world, target.pos(), c.chestCooldownSec * 20);
            if (!leftover && sawItems) chestsLooted++;
        }
        target = null;
        openRetries = 0;
        openFails = 0;
        state = State.SEARCHING;
    }

    // ------------------------------------------------------------------ SPRZEDAZ

    private static void beginSell() {
        state = State.SELLING;
        sellStage = 0;
        sellTick = tick;
        sellTries = 0;
    }

    private static void doSell(MinecraftClient mc, ClientPlayerEntity p, Config c) {
        holdStill(mc, p);

        if (sellStage == 0) {
            if (p.currentScreenHandler != p.playerScreenHandler) p.closeHandledScreen();
            confirmPending = false;
            confirmSent = false;
            resendSellAt = 0;
            p.networkHandler.sendChatCommand(stripSlash(c.sellCommand));
            sellStage = 1;
            sellSentTick = tick;
            sellTick = tick;
            setStatus("Sprzedaję: /" + stripSlash(c.sellCommand));
            return;
        }
        if (resendSellAt > 0) {
            if (tick >= resendSellAt) { sellStage = 0; resendSellAt = 0; }
            return;
        }
        if (confirmPending) {
            if (tick >= confirmAt) {
                p.networkHandler.sendChatCommand(stripSlash(c.sellConfirmCommand));
                confirmPending = false;
                confirmSent = true;
                confirmSentTick = tick;
                sellTick = tick;
                setStatus("Potwierdzam: /" + stripSlash(c.sellConfirmCommand));
            } else {
                setStatus(String.format(Locale.ROOT, "Czekam na limit komend (%.1fs)...", (confirmAt - tick) / 20.0));
            }
            return;
        }
        if (tick - sellTick < c.sellWaitTicks) return;
        if (p.currentScreenHandler != p.playerScreenHandler) p.closeHandledScreen();

        if (freeSlots(p) > c.minFreeSlots) {
            sells++;
            sellTries = 0;
            state = State.SEARCHING;
        } else {
            sellTries++;
            if (sellTries >= 3) {
                stop("Sprzedaż nie zwolniła miejsca w ekwipunku (3 próby)");
            } else {
                resendSellAt = Math.max(tick, sellSentTick + 90);
            }
        }
    }

    // ------------------------------------------------------------------ /spawn

    private static void beginSpawn(MinecraftClient mc, ClientPlayerEntity p, String reason) {
        endEating(mc, p);
        holdStill(mc, p);
        if (p.currentScreenHandler != p.playerScreenHandler) p.closeHandledScreen();
        path = null;
        target = null;
        state = State.SPAWNING;
        spawnStage = 0;
        spawnStart = tick;
        spawnDoneTick = 0;
        noChestSince = -1;
        setStatus(reason);
        chat("§e" + reason + " §7-> staję w miejscu i wysyłam §f/" + stripSlash(Config.get().spawnCommand));
    }

    private static void doSpawning(MinecraftClient mc, ClientPlayerEntity p, Config c) {
        if (!p.isInLava()) holdStill(mc, p);   // zero ruchu - teleport sie anuluje przy poruszeniu
        if (p.currentScreenHandler != p.playerScreenHandler) p.closeHandledScreen();

        if (spawnStage == 0) {
            Vec3d v = p.getVelocity();
            boolean still = Math.hypot(v.x, v.z) < 0.03 && p.isOnGround();
            if (still || tick - spawnStart > 10) {
                p.networkHandler.sendChatCommand(stripSlash(c.spawnCommand));
                spawnStage = 1;
                spawnSent = tick;
                spawnPos = p.getPos();
            }
            return;
        }
        boolean teleported = p.getPos().squaredDistanceTo(spawnPos) > 100;
        boolean doneMsg = spawnDoneTick > 0 && tick - spawnDoneTick > 15;
        if (teleported || doneMsg || tick - spawnSent >= c.spawnWaitSec * 20) {
            if (c.rtpLoop) {
                chat("§aJestem na spawnie. §7Szukam przycisku RTP...");
                beginRtp();
            } else {
                enabled = false;
                state = State.IDLE;
                chat("§cBot zatrzymany (" + (teleported || doneMsg ? "teleport wykonany" : "koniec oczekiwania na /spawn")
                        + "). Zebrane skrzynki: §f" + chestsLooted + "§c, sprzedaże: §f" + sells);
            }
        }
    }

    // ------------------------------------------------------------------ RTP (przycisk na gabce)

    private static void beginRtp() {
        state = State.RTP_FIND;
        rtpFindStart = tick;
        rtpClicks = 0;
        rtpPos = null;
        setStatus("Szukam przycisku RTP");
    }

    private static BlockPos findRtpButton(MinecraftClient mc, ClientPlayerEntity p, Config c) {
        ClientWorld w = mc.world;
        BlockPos o = p.getBlockPos();
        int r = Math.max(4, c.rtpRadius);
        String sup = c.rtpSupport == null ? "" : c.rtpSupport.trim().toLowerCase(Locale.ROOT);
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -8; dy <= 8; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    m.set(o.getX() + dx, o.getY() + dy, o.getZ() + dz);
                    if (!(w.getBlockState(m).getBlock() instanceof ButtonBlock)) continue;
                    if (!sup.isEmpty() && !supportedBy(w, m, sup)) continue;
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d < bd) { bd = d; best = m.toImmutable(); }
                }
            }
        }
        return best;
    }

    private static boolean supportedBy(ClientWorld w, BlockPos pos, String sup) {
        for (Direction d : Direction.values()) {
            String id = Registries.BLOCK.getId(w.getBlockState(pos.offset(d)).getBlock()).getPath();
            if (id.contains(sup)) return true;
        }
        return false;
    }

    private static void doRtpFind(MinecraftClient mc, ClientPlayerEntity p, Config c) {
        holdStill(mc, p);
        if (tick - rtpFindStart > 20 * 20) {            // przez 20 s nie znaleziono / nie dotarlismy do przycisku
            rtpFindTries++;
            if (rtpFindTries >= 3) { stop("Nie znaleziono przycisku RTP (sprawdź zakładkę RTP)"); return; }
            beginSpawn(mc, p, "Nie znaleziono przycisku RTP");
            return;
        }
        if (tick % 10 != 0) return;
        BlockPos btn = findRtpButton(mc, p, c);
        if (btn == null) { setStatus("Szukam przycisku RTP..."); return; }
        rtpPos = btn;
        if (canReachBlock(p, btn, c)) {
            state = State.RTP_CLICK;
            openStart = tick;
            aimTicks = 0;
            setStatus("Klikam przycisk RTP");
            return;
        }
        ClientWorld w = mc.world;
        Vec3d ctr = aimPoint(w, btn);
        double reach = c.reach;
        boolean through = c.throughWalls;
        List<BlockPos> pth = Pathfinder.find(w, p.getBlockPos(), n -> {
            Vec3d eye = new Vec3d(n.getX() + 0.5, n.getY() + 1.62, n.getZ() + 0.5);
            if (eye.squaredDistanceTo(ctr) > reach * reach) return false;
            return through || visibleFrom(w, eye, btn, p);
        }, ctr, reach, 1.0, 6000);
        if (pth == null || pth.isEmpty()) { setStatus("Brak ścieżki do przycisku RTP"); return; }
        beginWalk(p, Pathfinder.smooth(w, p.getBlockPos(), pth), State.RTP_WALK);
        setStatus("Idę do przycisku RTP");
    }

    private static void doRtpClick(MinecraftClient mc, ClientPlayerEntity p, Config c) {
        if (rtpPos == null || !(mc.world.getBlockState(rtpPos).getBlock() instanceof ButtonBlock)) { state = State.RTP_FIND; return; }
        if (!canReachBlock(p, rtpPos, c)) { state = State.RTP_FIND; return; }
        holdStill(mc, p);
        boolean aimed = aimAtPoint(p, aimPoint(mc.world, rtpPos));
        Vec3d v = p.getVelocity();
        boolean still = Math.hypot(v.x, v.z) < 0.04 || tick - openStart > 10;
        if (aimed && still) {
            clickBlock(mc, p, rtpPos);
            rtpClicks++;
            rtpClickTick = tick;
            rtpStartPos = p.getPos();
            state = State.RTP_WAIT;
            setStatus("RTP: czekam na teleport (próba " + rtpClicks + ")");
        }
    }

    private static void doRtpWait(MinecraftClient mc, ClientPlayerEntity p, Config c) {
        holdStill(mc, p);   // teleport z odliczaniem anuluje sie przy ruchu
        if (p.getPos().squaredDistanceTo(rtpStartPos) > 625) { afterRtp(); return; }
        if (tick - rtpClickTick > c.rtpWaitSec * 20) {
            if (rtpClicks >= 3) {
                rtpClicks = 0;
                beginSpawn(mc, p, "RTP nie zadziałał");
                return;
            }
            state = State.RTP_CLICK;
            openStart = tick;
            aimTicks = 0;
        }
    }

    private static void afterRtp() {
        rtps++;
        visited.clear();
        Pathfinder.clearBlocked();
        target = null;
        path = null;
        state = State.SEARCHING;
        noChestSince = tick;
        startTick = tick;                 // ochrona liczy sie od nowa po teleporcie
        protectionSeen = false;
        lastProtectionTick = -100000;
        protRemaining = -1;
        rtpFindTries = 0;
        rtpClicks = 0;
        setStatus("RTP wykonany - szukam skrzynek");
        chat("§aRTP wykonany §7(#" + rtps + "). Szukam skrzynek...");
    }

    // ------------------------------------------------------------------ POMOCNICZE

    private static ChestScanner.Target findNearest(MinecraftClient mc, ClientPlayerEntity p, Config c) {
        ChestScanner.Target best = null;
        double bd = Double.MAX_VALUE;
        double r2 = (double) c.searchRadius * c.searchRadius;
        Vec3d pp = p.getPos();
        for (ChestScanner.Target t : ChestScanner.getTargets()) {
            if (!allowed(t.kind(), c)) continue;
            if (isVisited(t.pos())) continue;
            double d = pp.squaredDistanceTo(Vec3d.ofCenter(t.pos()));
            if (d > r2 || d >= bd) continue;
            BlockPos up = t.pos().up();
            if (mc.world.getBlockState(up).isSolidBlock(mc.world, up)) continue; // zablokowana od gory
            best = t;
            bd = d;
        }
        return best;
    }

    private static boolean allowed(ChestScanner.Kind k, Config c) {
        return switch (k) {
            case CHEST, TRAPPED -> c.useChests;
            case BARREL -> c.useBarrels;
            default -> false;
        };
    }

    private static boolean isTargetBlock(ClientWorld w, BlockPos pos) {
        var b = w.getBlockState(pos).getBlock();
        return b instanceof ChestBlock || b instanceof BarrelBlock;
    }

    /** Srodek ksztaltu bloku (lepszy cel niz srodek komorki - np. dla skrzyni czy malego przycisku). */
    private static Vec3d aimPoint(ClientWorld w, BlockPos pos) {
        VoxelShape sh = w.getBlockState(pos).getOutlineShape(w, pos);
        if (sh.isEmpty()) return Vec3d.ofCenter(pos);
        Box b = sh.getBoundingBox();
        return new Vec3d(pos.getX() + (b.minX + b.maxX) / 2, pos.getY() + (b.minY + b.maxY) / 2, pos.getZ() + (b.minZ + b.maxZ) / 2);
    }

    private static boolean canReach(ClientPlayerEntity p, BlockPos pos, Config c) {
        return canReachBlock(p, pos, c);
    }

    private static boolean canReachBlock(ClientPlayerEntity p, BlockPos pos, Config c) {
        ClientWorld w = MinecraftClient.getInstance().world;
        if (w == null) return false;
        if (p.getEyePos().squaredDistanceTo(aimPoint(w, pos)) > c.reach * c.reach) return false;
        if (c.throughWalls) return true;
        return visibleFrom(w, p.getEyePos(), pos, p);
    }

    /** Czy z punktu 'eye' widac blok (nic go nie zaslania). */
    private static boolean visibleFrom(ClientWorld w, Vec3d eye, BlockPos pos, ClientPlayerEntity p) {
        Vec3d ctr = aimPoint(w, pos);
        BlockHitResult hit = w.raycast(new RaycastContext(eye, ctr, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, p));
        if (hit.getType() == HitResult.Type.MISS) return true;
        BlockPos hp = hit.getBlockPos();
        if (hp.equals(pos)) return true;
        BlockState st = w.getBlockState(pos);
        if (st.getBlock() instanceof ChestBlock && st.contains(ChestBlock.CHEST_TYPE)
                && st.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE
                && hp.equals(pos.offset(ChestBlock.getFacing(st)))) {
            return true;
        }
        return false;
    }

    private static void markVisited(ClientWorld w, BlockPos pos, int ticks) {
        visited.put(pos.asLong(), tick + ticks);
        BlockState st = w.getBlockState(pos);
        if (st.getBlock() instanceof ChestBlock && st.contains(ChestBlock.CHEST_TYPE)
                && st.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
            visited.put(pos.offset(ChestBlock.getFacing(st)).asLong(), tick + ticks);
        }
    }

    private static void holdStill(MinecraftClient mc, ClientPlayerEntity p) {
        mc.options.forwardKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        if (p != null && p.isSprinting()) p.setSprinting(false);
        keysHeld = false;
    }

    private static String stripSlash(String s) {
        s = s == null ? "" : s.trim();
        while (s.startsWith("/")) s = s.substring(1);
        return s;
    }

    private static void setStatus(String s) { status = s; }

    public static void chat(String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.inGameHud != null) mc.inGameHud.getChatHud().addMessage(Text.literal("§8[§aChestBot§8] §r" + msg));
    }
}
