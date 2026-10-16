package ru.domsanta;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Campfire;
import org.bukkit.block.data.type.Candle;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.block.data.type.PointedDripstone;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.block.data.type.Snow;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** Ищет место и строит мрачную новогоднюю площадку. Все изменённые блоки запоминаются и возвращаются обратно. */
public class StructureBuilder {

    public static final int R = 12;

    private record Saved(int x, int y, int z, BlockData data) {}

    private final World world;
    private final int cx, cz, baseY;
    private final Random rnd = new Random();
    private final Set<Long> seen = new HashSet<>();
    private final List<Saved> saved = new ArrayList<>();

    public StructureBuilder(World world, int cx, int cz, int baseY) {
        this.world = world;
        this.cx = cx;
        this.cz = cz;
        this.baseY = baseY;
    }

    // ================= поиск места =================

    public static StructureBuilder find(DomSantaPlugin plugin, World world) {
        FileConfiguration c = plugin.getConfig();
        int xmin = Math.min(c.getInt("location.x-min", -5000), c.getInt("location.x-max", 5000));
        int xmax = Math.max(c.getInt("location.x-min", -5000), c.getInt("location.x-max", 5000));
        int zmin = Math.min(c.getInt("location.z-min", -5000), c.getInt("location.z-max", 5000));
        int zmax = Math.max(c.getInt("location.z-min", -5000), c.getInt("location.z-max", 5000));
        int attempts = c.getInt("location.max-attempts", 25);
        int maxDiff = c.getInt("location.max-height-diff", 6);
        boolean flat = c.getBoolean("location.flatness-check", true);
        ThreadLocalRandom r = ThreadLocalRandom.current();

        for (int i = 0; i < attempts; i++) {
            int x = r.nextInt(xmin, xmax + 1);
            int z = r.nextInt(zmin, zmax + 1);
            if (!inBorder(world, x, z)) continue;
            int y = height(world, x, z);
            if (y < world.getMinHeight() + 16 || y > world.getMaxHeight() - 40) continue;
            if (world.getBlockAt(x, y, z).isLiquid()) continue;
            if (flat && !isFlat(world, x, y, z, maxDiff)) continue;
            if (hasTiles(world, x, z)) continue;
            return new StructureBuilder(world, x, z, y);
        }
        return null;
    }

    private static int height(World w, int x, int z) {
        return w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
    }

    private static boolean inBorder(World w, int x, int z) {
        int d = R + 25;
        return w.getWorldBorder().isInside(new Location(w, x + d, 64, z))
                && w.getWorldBorder().isInside(new Location(w, x - d, 64, z))
                && w.getWorldBorder().isInside(new Location(w, x, 64, z + d))
                && w.getWorldBorder().isInside(new Location(w, x, 64, z - d));
    }

    private static boolean isFlat(World w, int x, int y, int z, int maxDiff) {
        double[] radii = {R, R / 2.0};
        for (double rad : radii) {
            for (int a = 0; a < 8; a++) {
                double ang = Math.toRadians(a * 45.0);
                int x2 = x + (int) Math.round(Math.cos(ang) * rad);
                int z2 = z + (int) Math.round(Math.sin(ang) * rad);
                int yy = height(w, x2, z2);
                if (Math.abs(yy - y) > maxDiff) return false;
                if (w.getBlockAt(x2, yy, z2).isLiquid()) return false;
            }
        }
        return true;
    }

    private static boolean hasTiles(World w, int x, int z) {
        for (int chx = (x - R - 1) >> 4; chx <= (x + R + 1) >> 4; chx++) {
            for (int chz = (z - R - 1) >> 4; chz <= (z + R + 1) >> 4; chz++) {
                for (BlockState st : w.getChunkAt(chx, chz).getTileEntities()) {
                    if (Math.abs(st.getX() - x) <= R + 1 && Math.abs(st.getZ() - z) <= R + 1) return true;
                }
            }
        }
        return false;
    }

    // ================= геттеры =================

    public World getWorld() {
        return world;
    }

    /** Блок главного шалкера. */
    public Location getShulkerLocation() {
        return new Location(world, cx, baseY + 1, cz);
    }

    public Location getTeleportLocation() {
        Location l = new Location(world, cx + 0.5, baseY + 1, cz + 8.5);
        l.setYaw(180f);
        return l;
    }

    public boolean isInside(Location l, double radius) {
        if (l.getWorld() != world) return false;
        double dx = l.getX() - (cx + 0.5), dz = l.getZ() - (cz + 0.5);
        return dx * dx + dz * dz <= radius * radius;
    }

    // ================= постройка =================

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((y + 2048) & 0xFFF);
    }

    private void set(int x, int y, int z, BlockData data) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) return;
        Block b = world.getBlockAt(x, y, z);
        if (seen.add(key(x, y, z))) saved.add(new Saved(x, y, z, b.getBlockData()));
        b.setBlockData(data, false);
    }

    private void set(int x, int y, int z, Material m) {
        set(x, y, z, m.createBlockData());
    }

    private boolean airish(int x, int y, int z) {
        Material m = world.getBlockAt(x, y, z).getType();
        return m.isAir() || m == Material.SNOW;
    }

    private boolean reserved(int dx, int dz) {
        return Math.abs(dx) <= 1 && dz >= 6 && dz <= 9; // дорожка от двери и площадка телепорта
    }

    public void build() {
        int top = Math.min(world.getMaxHeight() - 1, baseY + 26);

        for (int dx = -R; dx <= R; dx++) {
            for (int dz = -R; dz <= R; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > R + 0.3) continue;
                int x = cx + dx, z = cz + dz;
                int surf = height(world, x, z);

                for (int y = top; y > baseY; y--) {
                    if (!world.getBlockAt(x, y, z).getType().isAir()) set(x, y, z, Material.AIR);
                }
                for (int y = Math.max(surf + 1, baseY - 14); y < baseY; y++) {
                    set(x, y, z, rnd.nextInt(4) == 0 ? Material.COBBLED_DEEPSLATE : Material.BLACKSTONE);
                }
                Material topMat = topBlock(dx, dz, d);
                set(x, baseY, z, topMat);

                boolean inHouse = Math.max(Math.abs(dx), Math.abs(dz)) <= 4;
                if (!inHouse && !reserved(dx, dz)) {
                    if (topMat == Material.SOUL_SOIL && rnd.nextInt(9) == 0 && d > 6) {
                        set(x, baseY + 1, z, Material.SOUL_FIRE);
                    } else if (rnd.nextInt(100) < 55) {
                        Snow sn = (Snow) Material.SNOW.createBlockData();
                        sn.setLayers(1 + rnd.nextInt(2));
                        set(x, baseY + 1, z, sn);
                    }
                }
            }
        }

        house();
        pillars();
        tree(7, 7, 8);
        tree(-7, 7, 8);
        tree(7, -7, 8);
        tree(-7, -7, 8);
        tree(0, -9, 6);
        tree(-2, 2, 3); // ёлка внутри дома
        snowman(8, 2, BlockFace.WEST);
        snowman(-8, -2, BlockFace.EAST);
        gifts();
        iceFence();
        pedestal();
    }

    private Material topBlock(int dx, int dz, double d) {
        int m = Math.max(Math.abs(dx), Math.abs(dz));
        if (m <= 1) return Material.POLISHED_BLACKSTONE_BRICKS;     // пьедестал под шалкером
        if (m <= 3) return Material.DARK_OAK_PLANKS;                 // пол дома
        if (m == 4) return Material.POLISHED_BLACKSTONE_BRICKS;      // фундамент
        if (d >= 8.6 && d <= 9.6) { // приглушённая «леденцовая» дорожка
            int seg = (int) Math.floor(Math.atan2(dz, dx) / (Math.PI / 12));
            return (seg & 1) == 0 ? Material.RED_TERRACOTTA : Material.WHITE_TERRACOTTA;
        }
        int r = rnd.nextInt(100);
        if (r < 38) return Material.BLACKSTONE;
        if (r < 62) return Material.POLISHED_BLACKSTONE;
        if (r < 78) return Material.COBBLED_DEEPSLATE;
        if (r < 90) return Material.SOUL_SOIL;
        if (r < 96) return Material.PACKED_ICE;
        return Material.BLUE_ICE;
    }

    // ================= Дом Санты =================

    private void house() {
        // стены: фундамент из чёрного кирпича, тёмный дуб, угловые брёвна
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (Math.abs(dx) != 4 && Math.abs(dz) != 4) continue;
                boolean corner = Math.abs(dx) == 4 && Math.abs(dz) == 4;
                for (int y = 1; y <= 5; y++) {
                    Material m = corner ? Material.DARK_OAK_LOG
                            : (y == 1 ? Material.POLISHED_BLACKSTONE_BRICKS : Material.DARK_OAK_PLANKS);
                    set(cx + dx, baseY + y, cz + dz, m);
                }
            }
        }
        // красные окна
        int[][] wins = {{-2, 4}, {2, 4}, {-3, -4}, {3, -4}, {4, -2}, {4, 2}, {-4, -2}, {-4, 2}};
        for (int[] w : wins) {
            set(cx + w[0], baseY + 2, cz + w[1], Material.RED_STAINED_GLASS);
            set(cx + w[0], baseY + 3, cz + w[1], Material.RED_STAINED_GLASS);
        }
        // дверной проём (юг, +z)
        set(cx, baseY + 1, cz + 4, Material.AIR);
        set(cx, baseY + 2, cz + 4, Material.AIR);
        for (int y = 1; y <= 3; y++) {
            set(cx - 1, baseY + y, cz + 4, Material.POLISHED_BLACKSTONE_BRICKS);
            set(cx + 1, baseY + y, cz + 4, Material.POLISHED_BLACKSTONE_BRICKS);
        }
        set(cx, baseY + 3, cz + 4, Material.POLISHED_BLACKSTONE_BRICKS);
        // фонари у двери
        for (int sx = -2; sx <= 2; sx += 4) {
            set(cx + sx, baseY + 1, cz + 5, Material.POLISHED_BLACKSTONE_BRICKS);
            set(cx + sx, baseY + 2, cz + 5, Material.SOUL_LANTERN);
        }

        // потолок и двускатная крыша
        for (int k = 0; k <= 4; k++) {
            int y = baseY + 6 + k;
            int sd = 5 - k;
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -(4 - k); dz <= 4 - k; dz++) {
                    set(cx + dx, y, cz + dz, Material.DARK_OAK_PLANKS);
                }
                for (int sign = 1; sign >= -1; sign -= 2) {
                    if (k >= 1 && rnd.nextInt(100) < 30) {
                        set(cx + dx, y, cz + sign * sd, Material.SNOW_BLOCK);
                    } else {
                        Stairs st = (Stairs) Material.DEEPSLATE_TILE_STAIRS.createBlockData();
                        st.setFacing(sign > 0 ? BlockFace.NORTH : BlockFace.SOUTH);
                        set(cx + dx, y, cz + sign * sd, st);
                    }
                }
            }
        }
        for (int dx = -4; dx <= 4; dx++) set(cx + dx, baseY + 10, cz, Material.SNOW_BLOCK); // снежный конёк

        // сосульки по свесам
        for (int dx = -4; dx <= 4; dx++) {
            for (int sign = 1; sign >= -1; sign -= 2) {
                if (rnd.nextInt(100) >= 35) continue;
                PointedDripstone pd = (PointedDripstone) Material.POINTED_DRIPSTONE.createBlockData();
                pd.setVerticalDirection(BlockFace.DOWN);
                pd.setThickness(PointedDripstone.Thickness.TIP);
                set(cx + dx, baseY + 5, cz + sign * 5, pd);
            }
        }

        // камин с синим душевым огнём у задней стены и труба
        for (int dx = -1; dx <= 1; dx++) {
            for (int y = 1; y <= 5; y++) set(cx + dx, baseY + y, cz - 4, Material.POLISHED_BLACKSTONE_BRICKS);
            for (int y = 1; y <= 3; y++) {
                if (dx == 0 && y <= 2) continue;
                set(cx + dx, baseY + y, cz - 3, Material.BLACKSTONE);
            }
        }
        set(cx, baseY + 1, cz - 3, Material.SOUL_SOIL);
        set(cx, baseY + 2, cz - 3, Material.SOUL_FIRE);
        for (int y = 6; y <= 11; y++) set(cx, baseY + y, cz - 4, Material.POLISHED_BLACKSTONE_BRICKS);
        Campfire cf = (Campfire) Material.SOUL_CAMPFIRE.createBlockData();
        cf.setLit(true);
        set(cx, baseY + 12, cz - 4, cf);

        // внутри: ковёр, подвесные фонари, паутина
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = 2; dz <= 3; dz++) set(cx + dx, baseY + 1, cz + dz, Material.RED_CARPET);
        }
        int[][] lamps = {{2, 2}, {2, -2}, {-2, -2}, {-2, 2}};
        for (int[] l : lamps) {
            if (!airish(cx + l[0], baseY + 5, cz + l[1])) continue;
            Lantern ln = (Lantern) Material.SOUL_LANTERN.createBlockData();
            ln.setHanging(true);
            set(cx + l[0], baseY + 5, cz + l[1], ln);
        }
        int[][] webs = {{3, 3}, {-3, 3}, {3, -3}, {-3, -3}};
        for (int[] w : webs) {
            if (airish(cx + w[0], baseY + 5, cz + w[1])) set(cx + w[0], baseY + 5, cz + w[1], Material.COBWEB);
        }
        // подарки в доме
        gift(-3, 3);
        gift(-3, 1);
        gift(-2, 3);
        gift(3, -3);
        gift(-3, -3);
    }

    private void snowman(int ox, int oz, BlockFace face) {
        int x = cx + ox, z = cz + oz;
        if (!airish(x, baseY + 1, z) || !airish(x, baseY + 2, z) || !airish(x, baseY + 3, z)) return;
        set(x, baseY + 1, z, Material.SNOW_BLOCK);
        set(x, baseY + 2, z, Material.SNOW_BLOCK);
        Directional head = (Directional) Material.JACK_O_LANTERN.createBlockData();
        head.setFacing(face);
        set(x, baseY + 3, z, head);
    }

    private void pillars() {
        int[][] ps = {{10, 0}, {-10, 0}, {0, 10}, {0, -10}};
        for (int[] p : ps) {
            int x = cx + p[0], z = cz + p[1];
            set(x, baseY + 1, z, Material.POLISHED_BLACKSTONE_BRICKS);
            for (int i = 2; i <= 5; i++) set(x, baseY + i, z, Material.POLISHED_BLACKSTONE_BRICK_WALL);
            set(x, baseY + 6, z, Material.POLISHED_BLACKSTONE_BRICKS);
            set(x, baseY + 7, z, Material.SOUL_LANTERN);
        }
    }

    private void tree(int ox, int oz, int h) {
        int tx = cx + ox, tz = cz + oz;
        for (int i = 1; i <= h; i++) set(tx, baseY + i, tz, Material.SPRUCE_LOG);
        for (int t = 3; t <= h + 1; t++) {
            int rad = (int) Math.round((h + 2 - t) * 0.38);
            if ((t & 1) == 0 && rad > 0) rad--;
            for (int dx = -rad; dx <= rad; dx++) {
                for (int dz = -rad; dz <= rad; dz++) {
                    if (dx == 0 && dz == 0 && t <= h) continue;
                    int dd = dx * dx + dz * dz;
                    if (dd > rad * rad + 1) continue;
                    int x = tx + dx, y = baseY + t, z = tz + dz;
                    if (!airish(x, y, z)) continue;
                    boolean outer = rad > 0 && dd >= rad * rad - 1;
                    int roll = rnd.nextInt(100);
                    if (outer && roll < 4) set(x, y, z, Material.RED_WOOL);
                    else if (outer && roll < 7) set(x, y, z, Material.SHROOMLIGHT);
                    else {
                        Leaves l = (Leaves) (rnd.nextInt(10) == 0 ? Material.DARK_OAK_LEAVES : Material.SPRUCE_LEAVES).createBlockData();
                        l.setPersistent(true);
                        set(x, y, z, l);
                    }
                }
            }
        }
        set(tx, baseY + h + 2, tz, Material.END_ROD);
    }

    private void gift(int dx, int dz) {
        Material[] bases = {Material.BLACK_WOOL, Material.RED_WOOL, Material.PURPLE_WOOL, Material.GRAY_WOOL};
        Material[] ribbons = {Material.RED_CARPET, Material.WHITE_CARPET, Material.LIGHT_BLUE_CARPET};
        int x = cx + dx, z = cz + dz;
        if (!airish(x, baseY + 1, z) || !airish(x, baseY + 2, z) || !airish(x, baseY + 3, z)) return;
        Material b = bases[rnd.nextInt(bases.length)];
        Material rib = ribbons[rnd.nextInt(ribbons.length)];
        set(x, baseY + 1, z, b);
        if (rnd.nextInt(10) < 3) {
            set(x, baseY + 2, z, b);
            set(x, baseY + 3, z, rib);
        } else {
            set(x, baseY + 2, z, rib);
        }
    }

    private void gifts() {
        int placed = 0, tries = 0;
        while (placed < 10 && tries++ < 200) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            double dist = 7.8 + rnd.nextDouble() * 1.6;
            int dx = (int) Math.round(Math.cos(ang) * dist), dz = (int) Math.round(Math.sin(ang) * dist);
            if (reserved(dx, dz)) continue;
            gift(dx, dz);
            placed++;
        }
    }

    private void iceFence() {
        for (int dx = -R; dx <= R; dx++) {
            for (int dz = -R; dz <= R; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d < R - 1.5 || d > R + 0.2) continue;
                if (rnd.nextInt(100) >= 30) continue;
                int h = 2 + rnd.nextInt(4);
                for (int i = 1; i <= h; i++) {
                    set(cx + dx, baseY + i, cz + dz, rnd.nextInt(10) < 3 ? Material.BLUE_ICE : Material.PACKED_ICE);
                }
            }
        }
    }

    private void pedestal() {
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                Candle c = (Candle) Material.RED_CANDLE.createBlockData();
                c.setCandles(3);
                c.setLit(true);
                set(cx + sx, baseY + 1, cz + sz, c);
            }
        }
        set(cx, baseY + 1, cz, Material.CYAN_SHULKER_BOX);
    }

    public void restore() {
        for (Saved s : saved) {
            world.getBlockAt(s.x(), s.y(), s.z()).setBlockData(s.data(), false);
        }
        saved.clear();
        seen.clear();
    }
}
