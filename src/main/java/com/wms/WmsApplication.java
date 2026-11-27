package com.wms;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * WMS 智能仓储管理系统 启动类
 */
@EnableScheduling
@MapperScan("com.wms.mapper")
@SpringBootApplication
public class WmsApplication {

    public static void main(String[] args) {
        // 阶段 9.5：WebSocket 阻塞写超时 2s。慢客户端不读 socket 时，
        // Tomcat 的发送不会无限期阻塞在 TCP 写，而是超时抛异常，
        // 由 AgvWebSocketHandler 摘除会话并关闭连接。
        // 放在 run 之前设置，确保 Tomcat WebSocket 实现类初始化时能读到。
        if (System.getProperty("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT") == null) {
            System.setProperty("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT", "2000");
        }
        SpringApplication.run(WmsApplication.class, args);
    }
}
