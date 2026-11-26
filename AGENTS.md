# AGENTS.md — 本仓库的工作约定

本项目是毕业设计「多 AGV 协同调度系统（WMS）」：Spring Boot 3.3.7 + MyBatis-Plus + MySQL 8 后端（`src/main/java/com/wms`），Vue 3 + Vite + Element Plus 前端（`wms-web`），改进计划见 `docs/改进实施计划.md`。

## 黄金规则：每阶段「验收 → 打钩 → 推送」

**任何阶段完成后，必须按顺序完成下面 6 步，缺一步都不算完成：**

1. **构建**：`mvn -q -DskipTests package` 退出码 0；前端有改动时 `npm --prefix wms-web run build` 通过。
2. **验收**：启动后端，执行该阶段验收脚本（阶段 1：`python tools/verify_permissions.py`；阶段 2：`python tools/verify_stage2.py`；后续阶段见计划文档〈阶段验收与 GitHub 交付流程〉），**全部通过**并把输出写入 `docs/evidence/阶段N-*.md`。失败就修，不许跳过、不许手改结论。
3. **打钩**：在 `docs/改进实施计划.md` 把对应条目的 `- [ ]` 改为 `- [x]`，末尾追加 `✅ YYYY-MM-DD 验收：docs/evidence/阶段N-*.md`。
4. **提交**：一个阶段一个 commit，信息格式：
   ```
   feat(阶段N): <一句话结果>

   ## 改动
   - <文件/模块>：<改了什么、为什么>

   ## 验收
   - 命令/结果/证据文件
   ```
5. **推送**：`git push origin master:main`（`origin` = `ssh://git@ssh.github.com:443/K-IRIN/wms.git`，见〈环境〉；HTTPS 在本机取不到凭据）。
6. **核对并记录**：`git ls-remote origin main` 的 SHA 必须等于本地 `git rev-parse HEAD`；在计划文档〈阶段交付记录〉表追加一行。**推送失败必须如实告知用户，不得宣称已交付。**

> 没有验收证据的「完成」不算完成；没有推送到 GitHub 的「交付」不算交付。

## 环境

- JDK 21：`C:\Users\16134\.jdks\jbr-21.0.11`（`tools/env.ps1` 会设置；默认 `JAVA_HOME` 是 17，不切会报 `release version 21 not supported`）。
- MySQL 8：`root/123456`，库 `wms`。
- 构建：`mvn -q -DskipTests package` → 产物 `target/wms-server.jar`。
- 启动后端：`.\tools\run-backend.ps1`（日志 `logs/backend.out.log`，其中会打印图形验证码答案，供自动登录）。
- 启动前端：`npm --prefix wms-web run dev`（默认 5173，代理 `/api` → 8080）。
- 接口调试：`python tools/api.py login admin 123456` → `python tools/api.py get /agv/device/list`（token 存 `.tmp/token`）。
- 推送 GitHub：`origin` 已配成 `ssh://git@ssh.github.com:443/K-IRIN/wms.git`（HTTPS 在本机取不到凭据、443 SSH 稳定）；仓库内已配 `core.sshCommand` 指向 `.tmp/ssh2/id_ed25519`（`.tmp/` 被 gitignore，不入库）。直接 `git push origin master:main` 即可。

## 代码地图

- 调度引擎：`src/main/java/com/wms/service/AgvEngineService.java`（1 Hz 状态机；任务态 0–7、AGV 态 0–3、电量常量都在文件头）。
- AGV 接口：`controller/AgvTaskController.java`、`AgvDeviceController.java`、`AgvFaultController.java`。
- 权限：`annotation/RequireRole.java` + `aspect/RequireRoleAspect.java`，越权抛 `BizException(403)`；角色 `ADMIN/MANAGER/OPERATOR/GUEST/MAINTENANCE/DISPATCHER`。
- 前端菜单/路由：`wms-web/src/router/index.js`（动态路由，按菜单排序取首个可访问页）。
- 数据库脚本：`sql/wms.sql`；备份：`tools/db-backup/`。

## 注意

- 不改表结构（25 张表是答辩自洽红线），指标只加列、事件只写 `wms_agv_log`。
- 不引入 Spring Security；鉴权沿用 `@RequireRole` + AOP。
- `AgvEngineService.genTaskNo()` 用 `count+1`，并发下会撞号（阶段 5 修复）。
- 提交前检查 `git status`，`target/`、`node_modules/`、`dist/`、`logs/`、`.tmp/` 不应入库。
