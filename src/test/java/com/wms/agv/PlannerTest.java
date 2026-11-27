package com.wms.agv;

import com.wms.service.agv.AgvGridView;
import com.wms.service.agv.AgvPathPlanner;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgvPathPlanner 纯函数单测（阶段 9）。
 *
 * <p>其中最后一条是"随机网格等价性"：测试内保留一份旧 BFS 参照实现（与阶段 4 引擎逐行一致），
 * 用 1000 组随机网格/起点/终点/封锁/占用对比两条路径，保证拆分不改变寻路结果。
 */
class PlannerTest {

    @Test
    void straightLine() {
        AgvGridView grid = AgvGridView.of(10, 10, Set.of(), Set.of());
        List<int[]> path = AgvPathPlanner.findPath(grid, 0, 0, 0, 3);
        assertNotNull(path);
        assertEquals(3, path.size());
        assertEquals(0, path.get(2)[0]);
        assertEquals(3, path.get(2)[1]);
        assertTrue(isValidChain(0, 0, path));
    }

    @Test
    void avoidsBlockedCells() {
        Set<String> blocked = Set.of("1,0", "1,1");
        AgvGridView grid = AgvGridView.of(5, 5, Set.of(), blocked);
        List<int[]> path = AgvPathPlanner.findPath(grid, 0, 0, 2, 0);
        assertNotNull(path);
        assertTrue(isValidChain(0, 0, path));
        assertFalse(path.stream().anyMatch(p -> blocked.contains(AgvPathPlanner.cellKey(p[0], p[1]))));
        assertEquals(2, path.get(path.size() - 1)[0]);
        assertEquals(0, path.get(path.size() - 1)[1]);
    }

    @Test
    void returnsNullWhenFullySealed() {
        // 用封锁格把起点围死：四个邻接格全部封锁，忽略占用也走不出去
        Set<String> blocked = Set.of("1,0", "0,1");
        AgvGridView grid = AgvGridView.of(3, 3, Set.of(), blocked);
        assertNull(AgvPathPlanner.findPath(grid, 0, 0, 2, 2));
    }

    @Test
    void targetOutsideGridBoundsIsStillReachable() {
        AgvGridView grid = AgvGridView.of(2, 2, Set.of(), Set.of());
        List<int[]> path = AgvPathPlanner.findPath(grid, 0, 0, 12, 12);
        assertNotNull(path);
        assertEquals(12, path.get(path.size() - 1)[0]);
        assertEquals(12, path.get(path.size() - 1)[1]);
        assertTrue(isValidChain(0, 0, path));
    }

    @Test
    void detoursAroundOccupiedVehicles() {
        Set<String> occupied = Set.of("0,1", "0,2");
        AgvGridView grid = AgvGridView.of(6, 6, occupied, Set.of());
        List<int[]> path = AgvPathPlanner.findPath(grid, 0, 0, 0, 3);
        assertNotNull(path);
        assertTrue(isValidChain(0, 0, path));
        assertFalse(path.stream().anyMatch(p -> occupied.contains(AgvPathPlanner.cellKey(p[0], p[1]))));
        assertEquals(3, path.get(path.size() - 1)[1]);
    }

    @Test
    void matchesLegacyBfsOnRandomGrids() {
        Random random = new Random(20261127L);
        for (int round = 0; round < 1000; round++) {
            int maxX = 1 + random.nextInt(12);
            int maxY = 1 + random.nextInt(12);
            Set<String> blocked = new HashSet<>();
            Set<String> occupied = new HashSet<>();
            int cells = (maxX + 1) * (maxY + 1);
            for (int i = 0; i < cells / 6; i++) {
                blocked.add(AgvPathPlanner.cellKey(random.nextInt(maxX + 1), random.nextInt(maxY + 1)));
            }
            for (int i = 0; i < cells / 8; i++) {
                occupied.add(AgvPathPlanner.cellKey(random.nextInt(maxX + 1), random.nextInt(maxY + 1)));
            }
            int sx = random.nextInt(maxX + 1);
            int sy = random.nextInt(maxY + 1);
            int tx = random.nextInt(maxX + 1);
            int ty = random.nextInt(maxY + 1);
            blocked.remove(AgvPathPlanner.cellKey(sx, sy));
            blocked.remove(AgvPathPlanner.cellKey(tx, ty));
            occupied.remove(AgvPathPlanner.cellKey(sx, sy));

            AgvGridView grid = AgvGridView.of(maxX, maxY, occupied, blocked);
            List<int[]> actual = AgvPathPlanner.findPath(grid, sx, sy, tx, ty);
            List<int[]> expected = legacyFindPath(maxX, maxY, blocked, occupied, sx, sy, tx, ty);

            if (expected == null) {
                assertNull(actual, "round " + round + " 参照无解，新实现却给了路径");
                continue;
            }
            assertNotNull(actual, "round " + round + " 参照有解，新实现返回 null");
            assertEquals(expected.size(), actual.size(), "round " + round + " 路径长度不同");
            for (int i = 0; i < expected.size(); i++) {
                assertEquals(expected.get(i)[0], actual.get(i)[0], "round " + round + " x@" + i);
                assertEquals(expected.get(i)[1], actual.get(i)[1], "round " + round + " y@" + i);
            }
        }
    }

    // ==================== 测试内参照实现（阶段 4 引擎 BFS 原样） ====================

    private static List<int[]> legacyFindPath(int maxX, int maxY, Set<String> blocked,
                                              Set<String> occupied, int sx, int sy, int tx, int ty) {
        List<int[]> path = legacyBfs(maxX, maxY, blocked, occupied, sx, sy, tx, ty, true);
        if (path == null) {
            path = legacyBfs(maxX, maxY, blocked, occupied, sx, sy, tx, ty, false);
        }
        return path;
    }

    private static List<int[]> legacyBfs(int maxX, int maxY, Set<String> blocked, Set<String> occupied,
                                         int sx, int sy, int tx, int ty, boolean avoidOccupied) {
        if (sx == tx && sy == ty) {
            return new ArrayList<>();
        }
        int limitX = Math.min(30, Math.max(Math.max(maxX, tx), sx));
        int limitY = Math.min(30, Math.max(Math.max(maxY, ty), sy));
        String goalKey = AgvPathPlanner.cellKey(tx, ty);
        Map<String, int[]> parent = new HashMap<>();
        Set<String> seen = new HashSet<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{sx, sy});
        seen.add(AgvPathPlanner.cellKey(sx, sy));
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            int[] cur = queue.poll();
            for (int[] d : dirs) {
                int nx = cur[0] + d[0];
                int ny = cur[1] + d[1];
                if (nx < 0 || ny < 0 || nx > limitX || ny > limitY) {
                    continue;
                }
                String key = AgvPathPlanner.cellKey(nx, ny);
                if (seen.contains(key) || blocked.contains(key)) {
                    continue;
                }
                if (avoidOccupied && !key.equals(goalKey) && occupied.contains(key)) {
                    continue;
                }
                parent.put(key, cur);
                if (key.equals(goalKey)) {
                    return legacyBuild(parent, sx, sy, tx, ty);
                }
                seen.add(key);
                queue.add(new int[]{nx, ny});
            }
        }
        return null;
    }

    private static List<int[]> legacyBuild(Map<String, int[]> parent, int sx, int sy, int tx, int ty) {
        List<int[]> cells = new ArrayList<>();
        int cx = tx;
        int cy = ty;
        while (!(cx == sx && cy == sy)) {
            cells.add(new int[]{cx, cy});
            int[] p = parent.get(AgvPathPlanner.cellKey(cx, cy));
            if (p == null) {
                return null;
            }
            cx = p[0];
            cy = p[1];
        }
        java.util.Collections.reverse(cells);
        return cells;
    }

    private static boolean isValidChain(int sx, int sy, List<int[]> path) {
        int cx = sx;
        int cy = sy;
        for (int[] cell : path) {
            if (Math.abs(cx - cell[0]) + Math.abs(cy - cell[1]) != 1) {
                return false;
            }
            cx = cell[0];
            cy = cell[1];
        }
        return true;
    }
}
