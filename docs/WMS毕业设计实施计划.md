# 基于 SpringBoot + Vue 的智能仓储管理系统（WMS）毕业设计实施计划

> **使用说明**：每完成一步，把对应行的 `- [ ]` 改成 `- [x]`（在 GitHub 上会渲染成打勾的复选框）。
> 建议把本文件放到项目根目录 `E:\毕业设计\WMS\docs\计划.md`，随代码一起提交到 GitHub，进度一目了然。

- **项目路径**：`E:\毕业设计\WMS`
- **技术栈**：Spring Boot 3.x + MyBatis-Plus + MySQL 8 ｜ Vue 3 + Vite + Element Plus + ECharts
- **当前状态**：已有空 Maven 骨架（pom.xml 未引入 SpringBoot），已 `git init`，未关联 GitHub 远程仓库
- **功能范围**（依据需求文档 9 大模块）：用户权限、基础资料、采购入库、销售出库、库存管理、条码二维码、库存盘点、单据日志、数据统计可视化

---

## 总体阶段概览

| 阶段 | 内容 | 建议周期 |
|------|------|----------|
| 0 | 开发环境准备 | 2 天 |
| 1 | 需求分析与系统设计 | 1 周 |
| 2 | 项目骨架搭建 + Git/GitHub 版本管控 | 3 天 |
| 3 | 数据库建表与代码生成 | 3 天 |
| 4 | 模块一：用户登录与权限管理 | 1 周 |
| 5 | 模块二：基础资料信息管理 | 1 周 |
| 6 | 模块三：采购入库管理 | 1 周 |
| 7 | 模块四：销售出库管理 | 1 周 |
| 8 | 模块五：仓库库存管理 | 1 周 |
| 9 | 模块六：物料条码二维码管理 | 4 天 |
| 10 | 模块七：库存盘点与盈亏管理 | 4 天 |
| 11 | 模块八：单据记录与日志管理 | 3 天 |
| 12 | 模块九：数据统计与可视化分析（核心） | 1 周 |
| 13 | 系统联调、测试与修 bug | 1 周 |
| 14 | 毕业论文撰写 | 3~4 周（可与开发并行） |
| 15 | 部署演示 + 答辩准备 | 1 周 |

---

## 阶段 0：开发环境准备

- [x] 0.1 确认 JDK：`java -version`，建议 JDK 17 或 21（LTS 长期支持版，Spring Boot 3.x 最低要求 17）✅ 已确认 JDK 25.0.2 LTS（`C:\Program Files\Java\latest\jdk-25`），已配置 JAVA_HOME
- [x] 0.2 确认 Maven 3.8+：`mvn -v`（IDEA 自带 Maven 也可以）✅ 已确认 Maven 3.9.14（`D:\apache-maven-3.9.14`），在 JDK 25 下运行正常
- [x] 0.3 安装 Node.js 18+：`node -v`、`npm -v`（建议顺带安装 pnpm：`npm i -g pnpm`）✅ 已确认 Node v25.8.1 + npm 11.11.0（`D:\Node.js`），已装 pnpm
- [x] 0.4 安装 MySQL 8.0，记住 root 密码；安装数据库可视化工具（Navicat / DataGrip / MySQL Workbench 任选）✅ MySQL 8.0 已安装（`E:\MySQL\MySQL Server 8.0`），MySQL80 服务运行中
- [x] 0.5 安装 Git：`git --version`；配置身份： ✅ Git 2.54，已配置 K-IRIN / 1613436732@qq.com
      `git config --global user.name "你的名字"` 和 `git config --global user.email "你的GitHub邮箱"`
- [x] 0.6 注册 GitHub 账号（没有的话），并配置 SSH key 或记住账号密码/Token（见文末教程第 5 节）⏳ 账号已有（K-IRIN）；SSH 密钥已生成，**还差把公钥粘贴到 GitHub 网页**（见下方指引）
- [x] 0.7 开发工具：IntelliJ IDEA（后端）+ VS Code（前端，装 Vue-Official 插件）✅ IDEA 2024.3.1（`D:\java\IntelliJ IDEA 2024.3.1`）+ VS Code（`E:\Microsoft VS Code`）均已安装
- [ ] 0.8 接口调试工具：Apifox 或 Postman（推荐 Apifox，可自动生成接口文档）

## 阶段 1：需求分析与系统设计（论文的"需求分析/系统设计"章节素材）

- [ ] 1.1 按需求文档整理 9 大模块功能清单，画出**系统功能结构图**（ProcessOn / draw.io 在线画，免费）
- [ ] 1.2 确定 4 种角色（管理员、仓管员、普通操作员、访客），画出**用例图**
- [ ] 1.3 设计数据库 **ER 图**（实体：用户、角色、菜单、仓库、库区、库位、物料、物料分类、供应商、客户、入库单、出库单、库存、库存流水、盘点单、移库单、操作日志等）
- [x] 1.4 编写**数据库表设计文档**：每张表的字段名、类型、含义、约束（约 20 张表，清单见阶段 3）✅ 已生成 `docs/数据库设计.md`（22 张表，由 tools/codegen.py 自动生成，论文可直接用）
- [ ] 1.5 设计 **RESTful 接口清单**：每个接口的 URL、请求方式（GET/POST/PUT/DELETE）、入参、出参（可用 Apifox 管理）
- [x] 1.6 确认最终技术选型与版本，写进论文"相关技术介绍"一节：✅ 已定版：Spring Boot 3.3.7 / JDK 21（编译目标）/ MyBatis-Plus 3.5.9 / MySQL 8 / JJWT 0.12.5 / Knife4j 4.5.0 / EasyExcel 4.0.3 / ZXing 3.5.3 ｜ Vue 3 / Vite / Element Plus / Pinia / Vue Router / Axios / ECharts
      Spring Boot 3.x / MyBatis-Plus 3.5.x / MySQL 8 / JWT / Knife4j(Swagger) / EasyExcel / ZXing(二维码) ｜ Vue 3 / Vite / Element Plus / Pinia / Vue Router / Axios / ECharts

## 阶段 2：项目骨架搭建 + Git/GitHub 版本管控

### 2A 后端骨架（在现有 `E:\毕业设计\WMS` 基础上改造）

- [x] 2.1 改造 `pom.xml`：引入 `spring-boot-starter-parent` 父工程及依赖（web、validation、mybatis-plus-boot-starter、mysql-connector-j、lombok、hutool、jjwt、knife4j、easyexcel、zxing）✅ 已完成，额外补充 AOP 依赖与 Lombok 注解处理器配置（兼容 JDK 21 编译）
- [x] 2.2 编写启动类 `WmsApplication`（删除示例 `Main.java`），确认项目能启动、访问无报错 ✅ 已验证：启动 2.2 秒，`/api/test/ping` 返回 `{"code":200,"data":"pong"}`（注意：数据库接好前启动类暂时 exclude 了 DataSourceAutoConfiguration）
- [x] 2.3 编写 `application.yml`：配置端口（如 8080）、数据库连接、MyBatis-Plus、JWT 密钥、文件上传大小 ✅ 端口 8080 + 上下文 `/api`；数据源配置已写好但暂时注释（阶段3 取消注释）
- [x] 2.4 搭建统一基础设施：统一返回体 `Result<T>`、全局异常处理器、跨域配置（CORS）、Knife4j 接口文档配置 ✅ 另有 BizException、MybatisPlusConfig 分页插件
- [x] 2.5 规划后端包结构：`controller / service / mapper / entity / dto / vo / config / common / utils / interceptor` ✅ 基础包 com.wms

### 2B 前端骨架（新建 `wms-web` 目录，与后端同级）

- [x] 2.6 初始化前端：`npm create vite@latest wms-web -- --template vue`，选择 Vue 3 ✅ 已创建 wms-web
- [x] 2.7 安装依赖：`element-plus`、`axios`、`pinia`、`vue-router`、`echarts`、`@element-plus/icons-vue` ✅ 已安装（npm 已切 npmmirror 镜像提速）
- [x] 2.8 规划前端目录：`api/（接口封装） router/ store/ views/（按模块分文件夹） layout/ components/ utils/` ✅
- [x] 2.9 封装 axios（请求拦截器自动带 Token、响应拦截器统一处理错误码）、配置 vite 代理解决跨域 ✅ `utils/request.js` + vite 代理 `/api → 8080`
- [x] 2.10 搭建整体布局 Layout：左侧菜单栏 + 顶部用户栏 + 主内容区（路由出口）✅ 含 9 大模块菜单占位 + 数据看板占位页，`npm run build` 构建通过

### 2C Git 版本管控与 GitHub 上传（详细命令见文末教程）

- [x] 2.11 完善 `.gitignore`：忽略 `target/`、`node_modules/`、`.idea/`、`*.iml`、`dist/`、日志文件 ✅
- [x] 2.12 首次本地提交：`git add .` → `git commit -m "chore: 项目初始化，搭建前后端骨架"` ✅ 提交号 40c0736
- [x] 2.13 在 GitHub 网页上新建**私有仓库** `wms`（不要勾选自动生成 README，避免冲突）✅ 已建：git@github.com:K-IRIN/wms.git
- [x] 2.14 关联远程仓库并推送：`git remote add origin <仓库地址>` → `git branch -M main` → `git push -u origin main` ✅ 已推送 main（注：22 端口被网络封禁，已在 ~/.ssh/config 配置走 443 端口的 ssh.github.com）
- [x] 2.15 建立开发分支：`git checkout -b dev`，推送到远程：`git push -u origin dev` ✅ dev 分支已推送，当前工作分支为 dev
      **之后所有功能都在 dev 或 feature 分支上开发，完成并测试后合并回 main**
- [ ] 2.16 养成习惯：每完成一个小功能就 `commit` 一次；每天收工前 `push` 一次

## 阶段 3：数据库建表与代码生成

- [x] 3.1 创建数据库 `wms`（字符集 utf8mb4）✅
- [x] 3.2 编写并执行建表 SQL（建议的表清单，可按设计调整）：✅ 实际建成 22 张表，脚本在 `sql/wms.sql`
  - 权限相关：`sys_user`、`sys_role`、`sys_menu`、`sys_user_role`、`sys_role_menu`
  - 基础资料：`wms_warehouse`（仓库）、`wms_area`（库区）、`wms_location`（库位）、`wms_material_category`（物料分类）、`wms_material`（物料SKU）、`wms_supplier`（供应商）、`wms_customer`（客户）
  - 业务单据：`wms_inbound_order` + `wms_inbound_item`（入库单及明细）、`wms_outbound_order` + `wms_outbound_item`（出库单及明细）、`wms_check_order` + `wms_check_item`（盘点单及明细）、`wms_move_order`（移库单）
  - 库存与日志：`wms_inventory`（库存）、`wms_inventory_log`（库存流水）、`sys_operation_log`（操作日志）
- [x] 3.3 插入初始化数据：admin 账号、4 个角色、菜单数据、1 个示例仓库/库区/库位、若干测试物料 ✅ admin/123456（BCrypt 加密）、17 个菜单、示例仓库库区库位、4 个测试物料、示例供应商客户
- [x] 3.4 用 MyBatis-Plus 代码生成器（或手写）生成所有表的 `entity / mapper / service / serviceImpl` ✅ 自研生成器 `tools/codegen.py`，22 张表 × 4 类文件共 88 个 Java 文件
- [x] 3.5 提交代码：`git commit -m "feat: 数据库建表与实体类生成"` 并 push ✅ 数据源已接通（root/123456），启动验证通过

## 阶段 4：模块一 —— 用户登录与权限管理

- [x] 4.1 后端：用户注册接口（格式校验、账号唯一性校验、BCrypt 密码加密、默认分配角色）✅ `/auth/register`，默认分配 OPERATOR 角色，已联调通过
- [x] 4.2 后端：登录接口 + 图片验证码（hutool 生成）+ JWT 签发；连续 5 次密码错误锁定账号 10 分钟；记录登录 IP 和时间 ✅ 验证码 5 分钟有效、一次性；锁定与登录记录均入库
- [x] 4.3 后端：登录拦截器/JWT 过滤器，解析 Token 获取当前用户 ✅ `JwtInterceptor` + `UserContext`(ThreadLocal)，未登录返回 401 JSON
- [x] 4.4 后端：RBAC 权限——根据角色查询菜单树与按钮权限，登录后返回给前端 ✅ `/auth/userinfo` 返回用户+角色+菜单树（ADMIN 全量，其余按 role_menu）
- [x] 4.5 后端：个人信息管理（改头像/昵称/电话/密码，改密码需校验旧密码）✅ `/profile` 接口，头像存 `uploads/avatar/`，改密码写操作日志
- [x] 4.6 后端：账号状态管理（管理员启用/禁用/重置密码/注销，支持批量）✅ `/system/user` 分页/增删改/批量启停/重置密码，仅 ADMIN 可用
- [x] 4.7 前端：登录页（账号密码 + 验证码）、注册页 ✅ 验证码点击刷新，登录页附演示账号提示
- [x] 4.8 前端：路由守卫（未登录跳登录页；按角色菜单动态生成路由）✅ `import.meta.glob` 按菜单 path 动态注册路由，无匹配走 placeholder
- [x] 4.9 前端：个人中心页、用户管理页（管理员）✅ 个人中心（资料/头像/改密码）+ 用户管理（搜索/分页/增删改/批量启停/重置密码）
- [x] 4.10 联调本模块全部功能 → `git commit -m "feat: 用户登录与权限管理模块"` → push ✅ 登录/注册/401拦截/错误密码/用户管理接口全部 curl 验证通过，提交 `308aa27` 已推送 dev

## 阶段 5：模块二 —— 基础资料信息管理

- [x] 5.1 后端：仓库 CRUD（名称/地址/类型/负责人/状态，多仓库并存）✅ `/base/warehouse` 分页/增删改/启停，编号唯一校验，有库区时禁止删除
- [x] 5.2 后端：库区库位层级管理（仓库→库区→库位 树形结构，库位含编号/容量/状态/可存放类型）✅ `/base/location/tree` 三级树 + 库区/库位增删改，同层编号唯一
- [x] 5.3 后端：物料 SKU 管理（编号/名称/规格/单位/采购价/销售价/分类/保质期，分类树形展示）✅ `/base/material` 分页支持按分类子树过滤 + 分类树增删改
- [x] 5.4 后端：供应商管理、客户管理（含合作状态/等级，禁用而非删除）✅ 供应商"终止/恢复合作"、客户"停用/启用"，均不做物理删除
- [x] 5.5 后端：EasyExcel 批量导入（物料/供应商/客户）与一键导出 ✅ 导入自动跳过编号为空/重复行并返回统计；导出中文表头，已实测
- [x] 5.6 前端：仓库管理页、库区库位树形管理页 ✅ 库位树节点带状态标签和行内操作按钮；数据库补录"库区库位"菜单并同步进 `sql/wms.sql`
- [x] 5.7 前端：物料管理页（含分类树侧栏）、供应商页、客户页（均为表格 + 搜索 + 新增/编辑弹窗）✅ 分类树支持点选过滤物料
- [x] 5.8 前端：导入导出按钮（导入用 el-upload 上传 Excel，导出直接下载文件流）✅ `downloadBlob` 解析响应头文件名触发下载
- [x] 5.9 联调 → `git commit -m "feat: 基础资料信息管理模块"` → push ✅ 全部接口 curl 实测通过（含导入去重、导出内容校验），提交 `2e92c7d` 已推送 dev

## 阶段 6：模块三 —— 采购入库管理

- [ ] 6.1 后端：创建入库单（选供应商/仓库/日期，批量添加物料明细，自动算总金额；单据号按规则自动生成，如 `RK20260719001`）
- [ ] 6.2 后端：质检审核（合格→待上架；不合格→退货/补货标记）
- [ ] 6.3 后端：扫码入库接口（扫物料二维码自动带出物料信息）
- [ ] 6.4 后端：上架分配（查询空闲库位智能推荐 + 手动指定；确认上架后累加库位库存）
- [ ] 6.5 后端：入库单审核（通过→正式生效更新库存并写库存流水；驳回→可修改重提）
- [ ] 6.6 后端：历史入库记录多条件查询（时间/供应商/物料/状态）+ 明细 + 打印导出
- [ ] 6.7 前端：入库单列表页、新建入库单页（明细可增删行）、审核页、上架操作页、详情页
- [ ] 6.8 联调 → `git commit -m "feat: 采购入库管理模块"` → push

## 阶段 7：模块四 —— 销售出库管理

- [ ] 7.1 后端：创建出库单（选客户/仓库/物料/数量，自动算总金额）
- [ ] 7.2 后端：库存余量校验（提交时校验，不足则返回明确提示，禁止超卖、禁止负库存——用数据库乐观锁或事务保证）
- [ ] 7.3 后端：拣货任务生成（出库单确认后自动生成，含物料、库位、数量）
- [ ] 7.4 后端：扫码核验出库（扫描物料二维码比对出库单明细）
- [ ] 7.5 后端：出库复核（拣货完成后二次复核，通过后扣减库位库存并写流水）
- [ ] 7.6 后端：历史出库记录查询、预览、打印、导出
- [ ] 7.7 前端：出库单列表页、新建出库单页、拣货任务页、复核页、详情页
- [ ] 7.8 联调 → `git commit -m "feat: 销售出库管理模块"` → push

## 阶段 8：模块五 —— 仓库库存管理

- [ ] 8.1 后端：实时库存查询（总库存 / 各仓库 / 各库位 三个维度，编号名称模糊搜索）
- [ ] 8.2 后端：库存变动明细追溯（每笔入/出/移/盘点流水，含数量、时间、操作人、关联单据）
- [ ] 8.3 后端：移库管理（仓内/跨仓移库，选源库位+目标库位，确认后双向更新库存）
- [ ] 8.4 后端：库存上下限预警（自定义上下限，超限标红；首页/列表展示预警清单）
- [ ] 8.5 后端：临期物料预警（按保质期自动计算，输出临期清单，如 30 天内到期）
- [ ] 8.6 后端：库存冻结/解冻（冻结库存不可出库，出库校验时排除）
- [ ] 8.7 前端：库存查询页（多维 Tab）、流水明细页、移库操作页、预警中心页（库存预警 + 临期预警）
- [ ] 8.8 联调 → `git commit -m "feat: 仓库库存管理模块"` → push

## 阶段 9：模块六 —— 物料条码二维码管理

- [ ] 9.1 后端：ZXing 生成物料二维码（内容含编号/名称/规格/单价等，返回图片）
- [ ] 9.2 后端：库位二维码生成（扫码可查询该库位存放物料与数量）
- [ ] 9.3 后端：批量生成二维码 + 打包下载（批量打印标签用）
- [ ] 9.4 后端：物料资料修改后一键刷新二维码内容
- [ ] 9.5 前端：二维码管理页（单个生成/批量生成/打印预览）
- [ ] 9.6 前端：网页扫码功能（调用摄像头识别二维码，可用 `html5-qrcode` 库；电脑摄像头即可演示，手机浏览器也行）
- [ ] 9.7 联调（与入库扫码、出库核验流程打通）→ `git commit -m "feat: 条码二维码管理模块"` → push

## 阶段 10：模块七 —— 库存盘点与盈亏管理

- [ ] 10.1 后端：创建盘点单（按仓库/库区/物料分类生成任务，带出账面库存）
- [ ] 10.2 后端：实物盘点录入（录实际数量，自动计算盘盈/盘亏）
- [ ] 10.3 后端：差异审核（必填盈亏原因 → 管理员审核 → 通过后自动修正库存并写流水）
- [ ] 10.4 后端：盘点记录存档查询（月度/季度/年度分类，可导出）
- [ ] 10.5 前端：盘点单列表页、盘点录入页（账面 vs 实际对照录入）、差异审核页
- [ ] 10.6 联调 → `git commit -m "feat: 库存盘点与盈亏管理模块"` → push

## 阶段 11：模块八 —— 单据记录与日志管理

- [ ] 11.1 后端：单据统一管理中心（入库/出库/盘点/移库单集中列表，状态：待审核/已审核/已驳回/已完成）
- [ ] 11.2 后端：操作日志——用 Spring AOP 自定义注解 `@OperationLog`，自动记录登录/增/删/改/审核/导出（操作人、时间、内容、IP）
- [ ] 11.3 后端：单据作废（必填原因，作废后只读不可改）
- [ ] 11.4 后端：日志多条件筛选查询（时间/人员/类型）
- [ ] 11.5 前端：单据中心页、操作日志页、作废操作入口
- [ ] 11.6 联调 → `git commit -m "feat: 单据与日志管理模块"` → push

## 阶段 12：模块九 —— 数据统计与可视化分析（答辩核心亮点）

- [ ] 12.1 后端：出入库总量统计接口（按日/周/月/年，入库量、出库量、剩余库存）
- [ ] 12.2 后端：物料品类占比分析接口（库存占比、出入库占比）
- [ ] 12.3 后端：盘点分析接口（各仓库盘点次数、盈亏次数与总量、准确率）
- [ ] 12.4 后端：库存周转分析接口（出入库频次、积压数量，识别滞销/高频物料）
- [ ] 12.5 后端：员工绩效统计接口（每人入库量/出库量/盘点次数/单据处理量）
- [ ] 12.6 后端：预警统计接口（周期内短缺/积压/临期数量，风险报表）
- [ ] 12.7 后端：报表导出（Excel 导出；图表可前端截图保存）
- [ ] 12.8 前端：数据大屏/仪表盘首页（ECharts：折线图、柱状图、饼图、排行表格 + 统计卡片）
- [ ] 12.9 联调 → `git commit -m "feat: 数据统计与可视化分析模块"` → push

## 阶段 13：系统联调、测试与修复

- [ ] 13.1 全流程走查：注册 → 建基础资料 → 入库（质检/上架/审核）→ 出库（拣货/复核）→ 移库 → 盘点 → 报表，每个角色各走一遍
- [ ] 13.2 边界测试：库存不足出库、并发审核、禁用账号登录、空数据图表显示等
- [ ] 13.3 编写论文"系统测试"章节的测试用例表（用例、步骤、预期、实际结果）
- [ ] 13.4 修复所有发现的 bug，每修一个 commit 一次（`fix: xxx`）
- [ ] 13.5 代码整理：删掉无用代码和注释掉的死代码，统一命名
- [ ] 13.6 合并 dev → main，打版本标签：`git tag v1.0` 并 push

## 阶段 14：毕业论文撰写（建议与开发并行推进）

- [ ] 14.1 拟定论文提纲并找导师确认
- [ ] 14.2 第 1 章 绪论（背景、意义、国内外现状、论文结构）
- [ ] 14.3 第 2 章 相关技术介绍（SpringBoot、Vue、MyBatis-Plus、MySQL、ECharts、JWT 等）
- [ ] 14.4 第 3 章 系统需求分析（功能需求——用阶段 1 的功能结构图和用例图；非功能需求）
- [ ] 14.5 第 4 章 系统设计（架构设计、数据库设计——用 ER 图和表设计文档、接口设计）
- [ ] 14.6 第 5 章 系统实现（按 9 大模块写，每个模块配运行截图 + 核心代码说明）
- [ ] 14.7 第 6 章 系统测试（用阶段 13 的测试用例表）
- [ ] 14.8 第 7 章 总结与展望；摘要、Abstract、参考文献、致谢
- [ ] 14.9 按学校模板排版，查重，降重修改，定稿

## 阶段 15：部署演示与答辩准备

- [ ] 15.1 整理一键启动方式：后端 `mvn package` 打 jar 包，前端 `npm run build` 打包；写好 `README.md`（项目介绍、技术栈、启动步骤、演示账号密码）
- [ ] 15.2 准备演示数据（足够丰富的物料、出入库记录，让图表好看）
- [ ] 15.3 制作答辩 PPT（背景意义、技术选型、功能演示、系统设计、总结）
- [ ] 15.4 录制演示视频备用（防止现场环境出问题）
- [ ] 15.5 准备答辩常见问答（为什么用 JWT？RBAC 怎么实现的？怎么防止超卖？库存并发怎么处理？）
- [ ] 15.6 最终代码 push 到 GitHub，打 `v1.0-final` 标签

---

# 附：Git 与 GitHub 上传 / 版本管控教程

## 1. 第一次配置 Git（只做一次）

```bash
git config --global user.name "你的GitHub用户名"
git config --global user.email "你的GitHub注册邮箱"
```

## 2. 在 GitHub 上创建仓库（网页操作）

1. 登录 GitHub → 右上角 `+` → `New repository`
2. 仓库名填 `wms`，选 **Private**（毕业设计建议私有，答辩后可转公开）
3. **不要勾选** "Add a README file"（避免与本地冲突）
4. 点 Create，复制页面给出的仓库地址，形如 `https://github.com/你的用户名/wms.git`

## 3. 本地项目首次上传（在 `E:\毕业设计\WMS` 目录下执行）

```bash
# 你目录里已经 git init 过了，这几步跳过 init
git add .                                  # 暂存所有文件
git commit -m "chore: 项目初始化"           # 本地提交
git branch -M main                         # 主分支命名为 main
git remote add origin https://github.com/你的用户名/wms.git   # 关联远程仓库
git push -u origin main                    # 首次推送（会弹窗让你登录 GitHub 授权）
```

推送成功后刷新 GitHub 网页就能看到代码了。

## 4. 日常开发流程（最重要的习惯）

```bash
git status                    # 查看改了哪些文件
git add .                     # 暂存改动
git commit -m "feat: 完成入库单创建接口"   # 提交，写清楚干了什么
git push                      # 推送到 GitHub（每天至少一次，防止电脑出问题丢代码）
```

**开发新功能前，先开分支：**

```bash
git checkout -b feature/inbound     # 从当前分支新建功能分支并切换过去
# ...写代码、commit...
git checkout dev                    # 功能完成后切回 dev
git merge feature/inbound           # 合并功能分支
git push
```

分支建议：`main`（稳定版，答辩演示用）→ `dev`（日常开发）→ `feature/xxx`（每个新功能一个分支）。

## 5. 免密推送（二选一）

- **SSH（推荐，一次配置永久免密）**：
  `ssh-keygen -t ed25519 -C "你的邮箱"` 一路回车 → 打开 `C:\Users\16134\.ssh\id_ed25519.pub` 复制全部内容 → GitHub 网页 `Settings → SSH and GPG keys → New SSH key` 粘贴保存 → 远程地址改用 `git@github.com:你的用户名/wms.git`
- **HTTPS**：第一次 push 时按弹窗登录授权即可，Windows 凭据管理器会记住

## 6. Commit 提交信息规范（答辩时 Git 历史也是加分项）

| 前缀 | 用途 | 示例 |
|------|------|------|
| `feat:` | 新功能 | `feat: 完成库存预警接口` |
| `fix:` | 修 bug | `fix: 修复出库库存不足未拦截的问题` |
| `docs:` | 文档 | `docs: 更新README启动说明` |
| `chore:` | 杂项/配置 | `chore: 项目初始化` |
| `refactor:` | 重构 | `refactor: 抽取库存扣减公共方法` |

## 7. 常见问题

- **push 被拒（rejected）**：远程有你本地没有的提交，先 `git pull --rebase` 再 push
- **误删/改坏了代码**：`git checkout -- 文件名` 恢复单个文件；`git log --oneline` 找到历史版本可回退
- **node_modules 被传上去了**：在 `.gitignore` 加 `node_modules/`，然后 `git rm -r --cached node_modules` 再 commit

---

*计划制定时间：2026-07-19。实际执行时可根据学校时间节点（开题、中期、答辩）调整各阶段周期。*
