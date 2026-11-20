package pl.chestbot;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.function.Predicate;

/**
 * A* po siatce blokow: chodzenie (takze po skosie), wskakiwanie o 1 w gore, spadanie do 3 blokow.
 * Zwraca liste pozycji "stop" (blok w ktorym stoja nogi gracza).
 */
public final class Pathfinder {
    private static final Direction[] DIRS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private static final int[] DDX = {1, 1, -1, -1};
    private static final int[] DDZ = {1, -1, 1, -1};

    private static final class Node {
        final BlockPos pos; final Node parent; final double g, f;
        Node(BlockPos pos, Node parent, double g, double f) { this.pos = pos; this.parent = parent; this.g = g; this.f = f; }
    }

    /** Komorki, w ktore bot uderzyl i nie mogl przejsc (pos -> do kiedy ms). */
    private static final Map<Long, Long> BLOCKED = new java.util.concurrent.ConcurrentHashMap<>();

    private Pathfinder() {}

    public static void block(BlockPos pos) { BLOCKED.put(pos.asLong(), System.currentTimeMillis() + 120_000L); }

    public static void clearBlocked() { BLOCKED.clear(); }

    public static List<BlockPos> find(ClientWorld w, BlockPos start, Predicate<BlockPos> goal,
                                      Vec3d hTarget, double slack, double yWeight, int maxNodes) {
        Map<Long, Node> best = new HashMap<>();
        Set<Long> closed = new HashSet<>();
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));

        Node s = new Node(start, null, 0, h(start, hTarget, slack, yWeight));
        best.put(start.asLong(), s);
        open.add(s);
        int expanded = 0;

        while (!open.isEmpty() && expanded < maxNodes) {
            Node cur = open.poll();
            long key = cur.pos.asLong();
            if (best.get(key) != cur || closed.contains(key)) continue;
            closed.add(key);
            expanded++;

            if (goal.test(cur.pos)) return build(cur);

            for (int k = 0; k < 8; k++) {
                BlockPos n;
                double cost;
                if (k < 4) {
                    n = step(w, cur.pos, DIRS[k]);
                    if (n == null) continue;
                    int dy = n.getY() - cur.pos.getY();
                    cost = 1 + (dy > 0 ? 1.0 : dy < 0 ? -dy * 0.5 : 0);
                } else {
                    n = diag(w, cur.pos, DDX[k - 4], DDZ[k - 4]);
                    if (n == null) continue;
                    cost = 1.414;
                }
                if (Math.abs(n.getX() - start.getX()) > 90 || Math.abs(n.getZ() - start.getZ()) > 90) continue;
                long nk = n.asLong();
                if (closed.contains(nk)) continue;
                double g = cur.g + cost;
                Node old = best.get(nk);
                if (old != null && old.g <= g) continue;
                Node nn = new Node(n, cur, g, g + 1.4 * h(n, hTarget, slack, yWeight));
                best.put(nk, nn);
                open.add(nn);
            }
        }
        return null;
    }

    private static double h(BlockPos p, Vec3d t, double slack, double yWeight) {
        double dx = p.getX() + 0.5 - t.x;
        double dy = (p.getY() - t.y) * yWeight;
        double dz = p.getZ() + 0.5 - t.z;
        return Math.max(0, Math.sqrt(dx * dx + dy * dy + dz * dz) - slack);
    }

    private static List<BlockPos> build(Node end) {
        List<BlockPos> out = new ArrayList<>();
        for (Node n = end; n != null; n = n.parent) out.add(n.pos);
        Collections.reverse(out);
        if (!out.isEmpty()) out.remove(0);
        return out;
    }

    private static BlockPos step(ClientWorld w, BlockPos p, Direction d) {
        BlockPos n = p.offset(d);
        if (passable(w, n) && passable(w, n.up())) {
            if (ground(w, n.down())) return n;
            for (int k = 1; k <= 3; k++) {            // spadanie (max 3 bloki = bez obrazen)
                BlockPos t = n.down(k);
                if (!passable(w, t)) break;
                if (ground(w, t.down())) return t;
            }
            return null;
        }
        // wskakiwanie o 1 blok
        if (passable(w, p.up(2)) && passable(w, n.up()) && passable(w, n.up(2)) && ground(w, n)) {
            return n.up();
        }
        return null;
    }

    /** Ruch po skosie po plaskim terenie - tylko gdy oba sasiednie bloki tez sa wolne (zeby nie wchodzic w rogi). */
    private static BlockPos diag(ClientWorld w, BlockPos p, int dx, int dz) {
        BlockPos n = p.add(dx, 0, dz);
        BlockPos a = p.add(dx, 0, 0);
        BlockPos b = p.add(0, 0, dz);
        if (!passable(w, n) || !passable(w, n.up()) || !ground(w, n.down())) return null;
        if (!passable(w, a) || !passable(w, a.up()) || !passable(w, b) || !passable(w, b.up())) return null;
        if (!ground(w, a.down()) || !ground(w, b.down())) return null;
        return n;
    }

    static boolean loaded(ClientWorld w, BlockPos p) {
        if (p.getY() < w.getBottomY() || p.getY() >= w.getTopY()) return false;
        return w.getChunkManager().getWorldChunk(p.getX() >> 4, p.getZ() >> 4) != null;
    }

    static boolean passable(ClientWorld w, BlockPos p) {
        if (!loaded(w, p)) return false;
        if (!BLOCKED.isEmpty()) {
            Long until = BLOCKED.get(p.asLong());
            if (until != null) {
                if (System.currentTimeMillis() < until) return false;
                BLOCKED.remove(p.asLong());
            }
        }
        BlockState s = w.getBlockState(p);
        if (!s.getFluidState().isEmpty()) return false;
        if (!s.getCollisionShape(w, p).isEmpty()) return false;
        Block b = s.getBlock();
        return b != Blocks.FIRE && b != Blocks.SOUL_FIRE && b != Blocks.COBWEB
                && b != Blocks.SWEET_BERRY_BUSH && b != Blocks.POWDER_SNOW && b != Blocks.WITHER_ROSE;
    }

    static boolean ground(ClientWorld w, BlockPos p) {
        if (!loaded(w, p)) return false;
        BlockState s = w.getBlockState(p);
        if (s.getCollisionShape(w, p).isEmpty()) return false;
        if (s.getCollisionShape(w, p).getMax(Direction.Axis.Y) > 1.0) return false; // plotki itp.
        Block b = s.getBlock();
        return b != Blocks.MAGMA_BLOCK && b != Blocks.CACTUS && b != Blocks.CAMPFIRE
                && b != Blocks.SOUL_CAMPFIRE && b != Blocks.HONEY_BLOCK;
    }

    /**
     * Wygladza sciezke: zamiast schodkow bot idzie prosta linia (takze po skosie), o ile cala linia
     * jest bezpieczna dla gracza o szerokosci 0.6. Zmiany wysokosci zostaja jako osobne punkty.
     */
    public static List<BlockPos> smooth(ClientWorld w, BlockPos start, List<BlockPos> in) {
        if (in.size() < 2) return in;
        List<BlockPos> out = new ArrayList<>();
        BlockPos cur = start;
        int i = 0;
        while (i < in.size()) {
            int best = i;
            if (in.get(i).getY() == cur.getY()) {
                int limit = Math.min(in.size() - 1, i + 30);
                for (int j = i + 1; j <= limit; j++) {
                    if (in.get(j).getY() != cur.getY()) break;
                    if (lineClear(w, cur, in.get(j))) best = j;
                }
            }
            out.add(in.get(best));
            cur = in.get(best);
            i = best + 1;
        }
        return out;
    }

    private static boolean lineClear(ClientWorld w, BlockPos a, BlockPos b) {
        double ax = a.getX() + 0.5, az = a.getZ() + 0.5, bx = b.getX() + 0.5, bz = b.getZ() + 0.5;
        double len = Math.hypot(bx - ax, bz - az);
        int steps = Math.max(1, (int) Math.ceil(len / 0.25));
        int y = a.getY();
        for (int s = 0; s <= steps; s++) {
            double t = (double) s / steps;
            double x = ax + (bx - ax) * t, z = az + (bz - az) * t;
            for (int ox = -1; ox <= 1; ox += 2) {
                for (int oz = -1; oz <= 1; oz += 2) {
                    BlockPos feet = new BlockPos((int) Math.floor(x + ox * 0.4), y, (int) Math.floor(z + oz * 0.4));
                    if (!passable(w, feet) || !passable(w, feet.up()) || !ground(w, feet.down())) return false;
                }
            }
        }
        return true;
    }

    /**
     * Ucieczka z wody / lawy: BFS przez komorki plynow do najblizszego suchego miejsca, na ktorym mozna stanac.
     * Zwraca liste komorek (bez startowej) albo null gdy brak wyjscia w poblizu.
     */
    public static List<BlockPos> escapeFluid(ClientWorld w, BlockPos start, int maxNodes) {
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        Map<Long, BlockPos> parent = new HashMap<>();
        q.add(start);
        parent.put(start.asLong(), null);
        int n = 0;
        while (!q.isEmpty() && n < maxNodes) {
            BlockPos cur = q.poll();
            n++;
            if (!cur.equals(start) && passable(w, cur) && passable(w, cur.up()) && ground(w, cur.down())) {
                List<BlockPos> out = new ArrayList<>();
                for (BlockPos c = cur; c != null && !c.equals(start); c = parent.get(c.asLong())) out.add(c);
                Collections.reverse(out);
                return out;
            }
            for (Direction d : Direction.values()) {
                BlockPos nx = cur.offset(d);
                if (parent.containsKey(nx.asLong())) continue;
                if (Math.abs(nx.getX() - start.getX()) > 14 || Math.abs(nx.getZ() - start.getZ()) > 14
                        || Math.abs(nx.getY() - start.getY()) > 10) continue;
                if (!loaded(w, nx)) continue;
                if (!w.getBlockState(nx).getCollisionShape(w, nx).isEmpty()) continue;   // plyny maja pusta kolizje
                parent.put(nx.asLong(), cur);
                q.add(nx);
            }
        }
        return null;
    }
}
