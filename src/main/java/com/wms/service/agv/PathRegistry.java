package com.wms.service.agv;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 路径队列注册表（阶段 9）。
 *
 * <p>引擎负责"走"，生命周期负责"清"。把三张表放到独立组件里，两边共享同一份状态，
 * 避免把调度状态拆成两半各存一份。
 */
@Component
public class PathRegistry {

    /** AGV-ID → 剩余路径格子（不含当前格） */
    private final Map<Long, List<int[]>> pathQueue = new ConcurrentHashMap<>();
    /** AGV-ID → 任务状态（阶段变化即重算路径） */
    private final Map<Long, Integer> pathPhase = new ConcurrentHashMap<>();
    /** AGV-ID → 目标格 "x,y" */
    private final Map<Long, String> pathTarget = new ConcurrentHashMap<>();

    public List<int[]> get(Long agvId) {
        return pathQueue.get(agvId);
    }

    public void put(Long agvId, List<int[]> path, int phase, String targetKey) {
        pathQueue.put(agvId, path);
        pathPhase.put(agvId, phase);
        pathTarget.put(agvId, targetKey);
    }

    public Integer phase(Long agvId) {
        return pathPhase.get(agvId);
    }

    public String target(Long agvId) {
        return pathTarget.get(agvId);
    }

    public void clear(Long agvId) {
        if (agvId == null) {
            return;
        }
        pathQueue.remove(agvId);
        pathPhase.remove(agvId);
        pathTarget.remove(agvId);
    }

    public void clearAll() {
        pathQueue.clear();
        pathPhase.clear();
        pathTarget.clear();
    }

    /** 只读视图：供既有路径巡检逻辑遍历（不要在外部改结构） */
    public Map<Long, List<int[]>> all() {
        return pathQueue;
    }
}
