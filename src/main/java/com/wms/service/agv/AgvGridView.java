package com.wms.service.agv;

import java.util.Set;

/**
 * 某一 tick 的网格快照（阶段 9）。
 *
 * <p>寻路只需要"地图多大、哪些格被车占着、哪些格被封着"，不需要再回查数据库。
 * 引擎每 tick 构建一次，传给 {@link AgvPathPlanner}，避免 BFS 内部反复
 * {@code deviceService.list()}。
 *
 * @param maxX     可用最大 x（含）
 * @param maxY     可用最大 y（含）
 * @param occupied 被 AGV 占用的格子（"x,y"）
 * @param blocked  被封锁的格子（"x,y"）
 */
public record AgvGridView(int maxX, int maxY, Set<String> occupied, Set<String> blocked) {

    public AgvGridView {
        occupied = occupied == null ? Set.of() : Set.copyOf(occupied);
        blocked = blocked == null ? Set.of() : Set.copyOf(blocked);
    }

    public static AgvGridView of(int maxX, int maxY, Set<String> occupied, Set<String> blocked) {
        return new AgvGridView(maxX, maxY, occupied, blocked);
    }

    public boolean inBounds(int x, int y) {
        return x >= 0 && y >= 0 && x <= maxX && y <= maxY;
    }

    public boolean isOccupied(int x, int y) {
        return occupied.contains(key(x, y));
    }

    public boolean isBlocked(int x, int y) {
        return blocked.contains(key(x, y));
    }

    public static String key(int x, int y) {
        return x + "," + y;
    }
}
