package com.wms.service.agv;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.entity.WmsAgvDevice;
import com.wms.entity.WmsAgvTask;
import com.wms.websocket.AgvWebSocketHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGV 实时推送发布器（阶段 9）。
 *
 * <p>把 WebSocket 发送从调度线程上摘下来：tick 只做「序列化 + 入队」（微秒级），
 * 真正的网络写由单独一条 daemon 线程完成。这样即使某个客户端不读 socket，
 * 也只会把队列顶满，不会把 1 Hz 的调度卡住。
 *
 * <p>队列有界（{@value #CAPACITY}），满了丢最旧一帧并计数——实时位置数据"旧帧"没有保留价值，
 * 丢旧保新比阻塞或无限堆积更合理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgvPublisher {

    /** 队列容量：1000 帧 ≈ 8 台车 × 每 tick 2 帧 × 60 秒的余量 */
    public static final int CAPACITY = 1000;

    private final AgvWebSocketHandler wsHandler;
    private final ObjectMapper objectMapper;

    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(CAPACITY);
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong maxQueue = new AtomicLong();

    private volatile boolean running = true;
    private Thread worker;

    @PostConstruct
    public void start() {
        worker = new Thread(this::loop, "agv-ws-sender");
        worker.setDaemon(true);
        worker.start();
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    private void loop() {
        while (running) {
            String json;
            try {
                json = queue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (json == null) {
                continue;
            }
            try {
                wsHandler.sendRaw(json);
                sent.incrementAndGet();
            } catch (Exception e) {
                // 发送线程绝不能因为单个会话异常而退出
                log.debug("AGV WS 发送失败，已跳过：{}", e.getMessage());
            }
        }
    }

    /** 序列化并入队；序列化失败只丢这一帧 */
    private void enqueue(String type, Object data) {
        String json;
        try {
            json = objectMapper.writeValueAsString(Map.of("type", type, "data", data));
        } catch (Exception e) {
            log.warn("AGV WS 消息序列化失败，已跳过：{}", e.getMessage());
            return;
        }
        if (!queue.offer(json)) {
            // 丢最旧，保最新
            queue.poll();
            dropped.incrementAndGet();
            queue.offer(json);
        }
        maxQueue.accumulateAndGet(queue.size(), Math::max);
    }

    public void pushAgv(WmsAgvDevice agv) {
        enqueue("agv", agv);
    }

    public void pushTask(WmsAgvTask task) {
        enqueue("task", task);
    }

    public void pushAlert(String level, String msg) {
        enqueue("alert", Map.of(
                "level", level,
                "msg", msg,
                "time", LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))));
    }

    /** 等待状态变化（地图上的"等待中"气泡） */
    public void pushWaiting(Long agvId, boolean waiting, Long since, String reason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agvId", agvId);
        data.put("waiting", waiting);
        if (waiting) {
            data.put("since", since);
            data.put("reason", reason);
        }
        enqueue("waiting", data);
    }

    public long dropped() {
        return dropped.get();
    }

    public long sent() {
        return sent.get();
    }

    public int queueSize() {
        return queue.size();
    }

    public long maxQueueSize() {
        return maxQueue.get();
    }

    public Map<String, Object> stats() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("capacity", CAPACITY);
        map.put("queueSize", queue.size());
        map.put("maxQueueSize", maxQueue.get());
        map.put("sent", sent.get());
        map.put("dropped", dropped.get());
        map.put("onlineSessions", wsHandler.onlineCount());
        return map;
    }
}
