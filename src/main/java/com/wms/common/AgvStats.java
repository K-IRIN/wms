package com.wms.common;

import com.wms.entity.WmsAgvTask;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * AGV 统计口径（单一实现，看板与导出共用，避免两处算法漂移）
 */
public final class AgvStats {

    private AgvStats() {
    }

    /**
     * 设备利用率(%)：近 24 小时内「车辆实际执行任务的时长之和」/（启用车辆数 × 统计窗口）。
     * <p>统计窗口 = 24h 内最早一条任务的开始时间 → 现在，不足 1 小时按 1 小时算。</p>
     * <p>忙碌时段只算真正占用车辆的区间：已完成/已取消按 start→finish，执行中（1~5）按 start→now；
     * 待分配（0）与无开始时间的任务不计入；跨窗口部分裁掉。</p>
     */
    public static double utilization(List<WmsAgvTask> tasks, long enabledAgv, LocalDateTime now) {
        if (enabledAgv <= 0) {
            return 0;
        }
        LocalDateTime windowStart = now.minusHours(24);
        LocalDateTime earliest = tasks.stream()
                .map(WmsAgvTask::getStartTime)
                .filter(Objects::nonNull)
                .filter(t -> t.isAfter(windowStart))
                .min(LocalDateTime::compareTo)
                .orElse(null);
        long windowSec = earliest == null ? 3600
                : Math.max(3600, Duration.between(earliest, now).getSeconds());

        double busySec = tasks.stream().mapToDouble(t -> {
            if (t.getStartTime() == null || t.getStartTime().isAfter(now)) {
                return 0;
            }
            LocalDateTime end;
            if (t.getFinishTime() != null) {
                end = t.getFinishTime();
            } else if (t.getStatus() != null && t.getStatus() >= 1 && t.getStatus() <= 5) {
                end = now;
            } else {
                return 0;
            }
            LocalDateTime s = t.getStartTime().isBefore(windowStart) ? windowStart : t.getStartTime();
            if (!end.isAfter(s)) {
                return 0;
            }
            return Duration.between(s, end).getSeconds();
        }).sum();

        double rate = busySec / (enabledAgv * (double) windowSec) * 100;
        return Math.round(Math.min(100, Math.max(0, rate)) * 10) / 10.0;
    }
}
