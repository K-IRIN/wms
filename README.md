# WMS 智能仓储管理系统

> 基于 Spring Boot 3 + Vue 3 的前后端分离智能仓储管理系统（本科毕业设计）
> 覆盖仓储业务全流程九大模块，内置数据可视化看板；二期将扩展 WCS-AGV 设备调度模拟（v2.0）

## 系统简介

本系统面向中小型仓库的日常管理场景，实现从**基础资料 → 采购入库 → 销售出库 → 库存管理 → 条码 → 盘点 → 单据日志 → 数据统计**的完整业务闭环。后端提供 RESTful API，前端为现代化单页应用（SPA），通过 JWT 完成登录鉴权，基于 RBAC 模型做角色权限控制。

## 功能模块

| 模块 | 说明 |
|---|---|
| 用户与权限管理 | 用户/角色/菜单三级 RBAC，JWT 登录鉴权，图形验证码，操作日志切面 |
| 基础资料管理 | 物料（分类树）、供应商、客户、仓库/库区/库位三级结构 |
| 采购入库管理 | 入库单创建 → 审核 → 上架 → 库存生效全流程 |
| 销售出库管理 | 出库单创建 → 审核 → 拣货 → 出库，库存不足拦截 |
| 仓库库存管理 | 实时库存查询、库存移动、库存流水追溯 |
| 条码管理 | 物料条码/二维码生成与打印（ZXing），扫码查询（html5-qrcode） |
| 库存盘点 | 盘点单 → 实盘录入 → 盈亏自动核算 → 盘盈盘亏单 |
| 单据与日志 | 出入库单据中心、操作日志查询 |
| 数据统计看板 | 库存总览、出入库趋势、物料分类占比等 ECharts 可视化 |

## 技术栈

**后端**（`wms-server`，Spring Boot 3.3.7 / Java 21）

- Spring Boot Web / Validation / AOP
- MyBatis-Plus 3（ORM + 分页）
- MySQL 8（22 张表）
- JJWT（登录令牌）、Spring AOP（操作日志）
- Hutool、EasyExcel（导入导出）、ZXing（条码/二维码）
- knife4j（在线 API 文档）、Lombok

**前端**（`wms-web`，Vue 3.5 / Vite 8）

- Element Plus 2 + @element-plus/icons-vue
- Pinia（状态管理）、Vue Router
- ECharts 6（数据可视化）、axios
- html5-qrcode（扫码）

## 项目结构

```
WMS/
├── src/main/java/com/wms/   # 后端源码（controller/service/mapper/entity/...）
├── src/main/resources/
│   └── application.yml      # 后端配置（端口、数据库连接）
├── wms-web/                 # 前端源码（Vite + Vue3）
│   └── src/views/           # 各业务页面
├── sql/
│   └── wms.sql              # 数据库初始化脚本（建库 + 22 张表 + 基础数据）
├── docs/
│   └── WMS毕业设计实施计划.md # 完整开发计划与进度（含 Git 教程）
├── 启动后端.bat              # Windows 一键启动后端
└── 启动前端.bat              # Windows 一键启动前端
```

## 快速开始

### 环境要求

- JDK 21
- Node.js 18+（开发使用 v25）
- MySQL 8.0+
- Maven 3.6+（仅手动启动后端时需要）

### 1. 初始化数据库

用 MySQL 客户端执行 `sql/wms.sql`，自动创建 `wms` 库、全部表和演示数据。

如数据库账号密码不是 `root / 123456`，请修改 `src/main/resources/application.yml` 中的 `spring.datasource` 配置。

### 2. 启动系统（二选一）

**方式 A：Windows 一键启动（推荐）**

1. 双击 `启动后端.bat`，等待出现 `Started` 字样（约 15 秒）
2. 双击 `启动前端.bat`，等待出现 `Local: http://localhost:5173`
3. 浏览器访问 http://localhost:5173

**方式 B：手动命令启动**

```bash
# 后端（项目根目录）
mvn -DskipTests package
java -jar target/wms-server.jar

# 前端（wms-web 目录）
npm install        # 首次运行需要
npm run dev
```

### 3. 登录

| 账号 | 密码 | 角色 |
|---|---|---|
| admin | 123456 | 系统管理员 |

> ⚠️ 演示密码请在部署后及时修改（个人中心可改）。

### 4. 在线 API 文档

后端启动后访问：http://localhost:8080/api/doc.html （knife4j）

## 版本历史

| 版本 | 说明 |
|---|---|
| v1.0 | 九大业务模块全部完成，系统联调测试通过 |
| v1.1 | 现代科技风界面改版；物料列表新增库存总量/存放库位列；时间格式统一等实用性优化 |

## 二期规划（v2.0：WCS-AGV 设备调度模拟）

在 v1.x 基础上只做加法，用**软件定时器 + 状态机**模拟真实 AGV 硬件，无需任何真实设备：

- **E1** AGV 设备档案与数据模型（设备表/任务表/运行日志表、库位地图坐标、设备运维员角色）
- **E2** 调度引擎 + WebSocket 实时推送（任务自动生成/分配/优先级插队、路径与电量模拟、故障注入与自动重分配）
- **E3** 仓库地图可视化与监控大屏（AGV 实时移动动画、告警提醒、运行统计图表）

详细计划见 `docs/WMS毕业设计实施计划.md` 的"二期扩展"章节。

## 分支说明

- `main`：稳定发布分支（带版本标签 v1.0 / v1.1）
- `dev`：日常开发分支，功能稳定后合并回 main

## 相关文档

- 完整开发计划与 Git/GitHub 教程：`docs/WMS毕业设计实施计划.md`

---

仅供学习交流使用。
