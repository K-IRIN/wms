# WMS 智能仓储管理系统

> 基于 Spring Boot 3 + Vue 3 的前后端分离智能仓储管理系统（本科毕业设计）
> 覆盖仓储业务全流程九大模块，并内置 **WCS-AGV 多车协同调度模拟**（v2.0）

## 系统简介

本系统面向中小型仓库的日常管理场景，实现从**基础资料 → 采购入库 → 销售出库 → 库存管理 → 条码 → 盘点 → 单据日志 → 数据统计**的完整业务闭环。后端提供 RESTful API，前端为现代化单页应用（SPA），通过 JWT 完成登录鉴权，基于 RBAC 模型做角色权限控制。

二期在此基础上只做加法：用**软件定时器 + 状态机**模拟真实 WCS 的 AGV 硬件调度，无需任何真实设备即可演示多车协同、路径冲突消解与电量管理。

## 功能模块

| 模块 | 说明 |
|---|---|
| 用户与权限管理 | 用户/角色/菜单三级 RBAC，JWT 登录鉴权，图形验证码，操作日志切面，`@RequireRole` 注解式后端按钮级授权 |
| 基础资料管理 | 物料（分类树）、供应商、客户、仓库/库区/库位三级结构，库位带地图坐标 |
| 采购入库管理 | 入库单创建 → 审核 → 上架 → 库存生效全流程 |
| 销售出库管理 | 出库单创建 → 审核 → 拣货 → 出库，库存不足拦截 |
| 仓库库存管理 | 实时库存查询、库存移动、库存流水追溯 |
| 条码管理 | 物料条码/二维码生成与打印（ZXing），扫码查询（html5-qrcode） |
| 库存盘点 | 盘点单 → 实盘录入 → 盈亏自动核算 → 盘盈盘亏单 |
| 单据与日志 | 出入库单据中心、操作日志查询 |
| 数据统计看板 | 库存总览、出入库趋势、物料分类占比、设备利用率等 ECharts 可视化 + Excel 导出 |
| **AGV 设备档案** | 设备编号/载重/速度/待命区、启停、充电、三状态（空闲/运行中/充电中/故障） |
| **AGV 仓库地图** | SVG 实时地图：库位色块、AGV 逐格移动动画、任务路径虚线、等待让行呼吸光圈 |
| **AGV 搬运任务** | 三类任务、两级优先级、分页查询、取消、状态流转时间线 |
| **AGV 待分配队列** | 队列排序（紧急优先）、等待时长、可用车辆数、紧急插队 |
| **AGV 运行监控** | 设备实时卡片、告警列表、四类异常注入（故障/堵塞/取货失败/低电）、冲突快照 |
| **多车冲突消解** | BFS 显式路径队列、双格预留占用表、等待图死锁检测与链式让行、格子级阻塞绕行 |

## 技术栈

**后端**（`wms-server`，Spring Boot 3.3.7 / Java 21）

- Spring Boot Web / Validation / AOP
- MyBatis-Plus 3（ORM + 分页）
- MySQL 8（25 张表）
- JJWT（登录令牌）、Spring AOP（操作日志 + 角色鉴权切面）
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
│   └── service/AgvEngineService.java   # AGV 调度引擎（1 Hz 状态机 + BFS 路径 + 冲突消解）
├── src/main/resources/
│   └── application.yml      # 后端配置（端口、数据库连接、AGV 开关与分配策略）
├── wms-web/                 # 前端源码（Vite + Vue3）
│   └── src/views/agv/       # AGV 模块：device / map / monitor / task / queue
├── sql/
│   ├── wms.sql              # 数据库全量初始化脚本（建库 + 25 张表 + v1.1 种子数据）
│   └── upgrade_v1.1.sql     # v1.0 → v1.1 增量升级脚本（已有库用这个）
├── tools/
│   ├── env.ps1              # 一键切 JDK21 与 MySQL 路径
│   ├── run-backend.ps1      # 启动后端（日志写入 logs/backend.out.log）
│   ├── api.py               # 接口调试助手（自动完成图形验证码登录）
│   ├── verify_stage*.py     # 各阶段验收脚本
│   └── run_experiments.py   # 论文第 5 章对比实验执行器
├── docs/
│   ├── 改进实施计划.md       # 毕业设计后续改进执行版计划（含打钩与交付记录）
│   ├── 系统测试用例.md       # 系统测试用例表（含 AGV 用例 18 条）
│   └── evidence/            # 各阶段验收证据（截图、脚本输出、实验数据）
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

用 MySQL 客户端执行 `sql/wms.sql`，自动创建 `wms` 库、25 张表和演示数据（含双通道仓库布局、8 台 AGV 与六个角色的菜单授权矩阵）。

```bash
mysql -uroot -p --default-character-set=utf8mb4 < sql/wms.sql
```

如果是从 **v1.0 旧库升级**，改用增量脚本（不删数据）：

```bash
mysql -uroot -p --default-character-set=utf8mb4 wms < sql/upgrade_v1.1.sql
```

如数据库账号密码不是 `root / 123456`，请修改 `src/main/resources/application.yml` 中的 `spring.datasource` 配置。

### 2. 启动系统（二选一）

**方式 A：Windows 一键启动（推荐）**

1. 双击 `启动后端.bat`，等待出现 `Started` 字样（约 15 秒）
2. 双击 `启动前端.bat`，等待出现 `Local: http://localhost:5173`
3. 浏览器访问 http://localhost:5173

**方式 B：手动命令启动**

```bash
# 后端（项目根目录）
. .\tools\env.ps1          # 切换到 JDK 21（默认 JAVA_HOME 指向 17，会编译失败）
mvn -DskipTests package
java -jar target/wms-server.jar

# 前端（wms-web 目录）
npm install        # 首次运行需要
npm run dev
```

### 3. 登录

| 账号 | 密码 | 角色 | 可见范围 |
|---|---|---|---|
| admin | 123456 | 系统管理员 | 全部菜单、用户与权限、基础资料 |
| dispatcher | 123456 | 调度员 | 搬运任务、待分配队列、仓库地图、运行监控 |
| maintainer | 123456 | 设备运维员 | AGV 设备档案、地图、监控、异常注入 |
| manager | 123456 | 仓管员 | 基础资料、单据、库存、盘点 |
| operator | 123456 | 普通操作员 | 出入库、库存、条码 |
| guest | 123456 | 访客 | 数据看板、仓库地图、运行监控（只读） |

> ⚠️ 演示密码请在部署后及时修改（个人中心可改）。
> 登录后首页按**当前角色实际拥有的菜单**自动跳转：调度员进仓库地图，访客进数据看板，操作员进采购入库。

### 4. AGV 模块说明

AGV 部分是一个**软件模拟的 WCS 调度内核**（`AgvEngineService`，1 Hz 状态机），不需要任何真实硬件：

- **仓库布局**：8×8 网格。`x=0` 与 `x=7` 为双纵向主通道，`y=0` 月台行、`y=1` 待命区、`y=3/6` 作业横通道，中间 4 排共 24 个货架库位。
- **任务生命周期**：待分配 → 前往取货 → 取货中 → 前往卸货 → 卸货中 → 返回待命区 → 已完成（8 态，转换表集中校验，非法流转被拒）。
- **路径与冲突**：按任务 BFS 算一条显式路径队列跨 tick 续走；每 tick 重建格子占用表，进入下一格前校验目标格与再下一格（双格预留），同 tick 不会两车进同一格；等待图成环时按「紧急优先 → 距离近优先 → id 小优先」选让行方，空闲车用链式推挤让开通道。
- **电量**：每移动一格耗电 1%；空闲低电自动回充；低电注入或在途电量跌破阈值会把在途任务退回待分配队列（优先级保留）并由其他车辆改派。
- **实时推送**：WebSocket `/api/ws/agv` 广播 `agv` / `task` / `alert` / `waiting` 四类消息，地图与监控页据此自动刷新。
- **实验台**：`POST /api/agv/task/reset` 一键清空并设定车辆数与分配策略（就近 / 就近+负载惩罚），`POST /api/agv/task/batch` 批量建单，用于论文第 5 章的对比实验。

### 5. 在线 API 文档

后端启动后访问：http://localhost:8080/api/doc.html （knife4j）

### 6. 验收与实验脚本

```bash
python tools/verify_permissions.py   # 阶段1：六角色权限矩阵
python tools/verify_stage2.py        # 阶段2：任务闭环
python tools/verify_stage4.py        # 阶段4：多车冲突消解
python tools/verify_stage6.py        # 阶段6：AGV 用例 18 条
python tools/run_experiments.py      # 阶段5：对比实验（6 组，产出 CSV）
```

脚本需要后端已启动，且日志里能看到图形验证码答案（`tools/run-backend.ps1` 会写 `logs/backend.out.log`）。

## 版本历史

| 版本 | 说明 |
|---|---|
| v1.0 | 九大业务模块全部完成，系统联调测试通过 |
| v1.1 | 现代科技风界面改版；物料列表新增库存总量/存放库位列；时间格式统一等实用性优化 |
| v2.0 | WCS-AGV 多车协同调度：BFS 路径队列、双格预留占用表、死锁检测与链式让行、格子级阻塞绕行、8 态转换表校验、低电自动回充并释放任务、设备利用率等五项统计、实验台与对比实验脚本 |

## 后续改进计划

毕业设计后续改进（v1.1 实施计划）的分阶段执行记录、验收证据与交付提交号见：

- `docs/改进实施计划.md`（含〈阶段交付记录〉表）
- `docs/evidence/`（各阶段验收证据、对比实验 CSV、测试用例实际结果）

详细历史计划见 `docs/WMS毕业设计实施计划.md`。

## 分支说明

- `main`：稳定发布分支（带版本标签 v1.0 / v1.1）
- `dev`：日常开发分支，功能稳定后合并回 main

## 相关文档

- 后续改进执行版计划与交付记录：`docs/改进实施计划.md`
- 系统测试用例表（含 AGV 用例 18 条的实际结果）：`docs/系统测试用例.md`
- 各阶段验收证据与实验数据：`docs/evidence/`
- 数据库设计说明：`docs/数据库设计.md`
- 完整开发计划与 Git/GitHub 教程：`docs/WMS毕业设计实施计划.md`

---

仅供学习交流使用。
