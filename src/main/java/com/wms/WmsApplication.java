package com.wms;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * WMS 智能仓储管理系统 启动类
 *
 * TODO 阶段3：wms 数据库建好、application.yml 取消数据源注释后，
 *      把下面的 exclude = DataSourceAutoConfiguration.class 删掉
 */
@MapperScan("com.wms.mapper")
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class WmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(WmsApplication.class, args);
    }
}
