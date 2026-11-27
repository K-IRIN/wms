package com.wms.service.agv;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 纯函数寻路器（阶段 9）。
 *
 * <p>不依赖 Spring、数据库或任何全局状态：输入一份 {@link AgvGridView} 快照，
 * 输出"从起点到终点的格子序列（不含起点、含终点）"。
 * 行为与阶段 4 引擎内置 BFS 逐行等价，便于用随机网格做等价性回归。
 *
 * <p>语义：
 * <ol>
 *   <li>四邻接，不走斜线；</li>
 *   <li>先避开「封锁格 + 动态占用格」；无解时忽略动态占用再试一次（终点格永远允许进入）；</li>
 *   <li>搜索范围 = min(30, max(网格 x, 起点 x, 终点 x))，与旧实现一致。</li>
 * </ol>
 */
public final class AgvPathPlanner {

    /** 网格范围上限（防御异常坐标把搜索撑爆），与旧实现保持一致 */
    public static final int GRID_LIMIT = 30;

    private AgvPathPlanner() {
    }

    public static String cellKey(int x, int y) {
        return x + "," + y;
    }

    /** 判断格子是否与 (x1,y1) 四邻接 */
    public static boolean isAdjacent(int x1, int y1, int[] cell) {
        return cell != null && Math.abs(x1 - cell[0]) + Math.abs(y1 - cell[1]) == 1;
    }

    /** 先绕开动态占用，走不通再忽略占用试一次 */
    public static List<int[]> findPath(AgvGridView grid, int sx, int sy, int tx, int ty) {
        List<int[]> path = bfs(grid, sx, sy, tx, ty, true);
        if (path == null) {
            path = bfs(grid, sx, sy, tx, ty, false);
        }
        return path;
    }

    public static List<int[]> bfs(AgvGridView grid, int sx, int sy, int tx, int ty, boolean avoidOccupied) {
        if (sx == tx && sy == ty) {
            return new ArrayList<>();
        }
        int maxX = Math.min(GRID_LIMIT, Math.max(Math.max(grid.maxX(), tx), sx));
        int maxY = Math.min(GRID_LIMIT, Math.max(Math.max(grid.maxY(), ty), sy));
        String startKey = cellKey(sx, sy);
        String goalKey = cellKey(tx, ty);
        Map<String, int[]> parent = new HashMap<>();
        Set<String> seen = new HashSet<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{sx, sy});
        seen.add(startKey);
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            int[] cur = queue.poll();
            for (int[] d : dirs) {
                int nx = cur[0] + d[0];
                int ny = cur[1] + d[1];
                if (nx < 0 || ny < 0 || nx > maxX || ny > maxY) {
                    continue;
                }
                String key = cellKey(nx, ny);
                if (seen.contains(key)) {
                    continue;
                }
                // 封锁格永远不可通行
                if (grid.isBlocked(nx, ny)) {
                    continue;
                }
                // 动态占用：终点格允许进入，其余直接绕开
                if (avoidOccupied && !key.equals(goalKey) && grid.isOccupied(nx, ny)) {
                    continue;
                }
                parent.put(key, cur);
                if (key.equals(goalKey)) {
                    return buildPath(parent, sx, sy, tx, ty);
                }
                seen.add(key);
                queue.add(new int[]{nx, ny});
            }
        }
        return null;
    }

    /** 由 parent 链回溯出路径（不含起点，含终点） */
    public static List<int[]> buildPath(Map<String, int[]> parent, int sx, int sy, int tx, int ty) {
        List<int[]> cells = new ArrayList<>();
        int cx = tx;
        int cy = ty;
        while (!(cx == sx && cy == sy)) {
            cells.add(new int[]{cx, cy});
            int[] p = parent.get(cellKey(cx, cy));
            if (p == null) {
                return null;
            }
            cx = p[0];
            cy = p[1];
        }
        Collections.reverse(cells);
        return cells;
    }
}
