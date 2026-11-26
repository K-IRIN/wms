-- ============================================================================
-- WMS 毕业设计后续改进 v1.1 —— 增量升级脚本（阶段 1：权限与数据自洽）
-- ----------------------------------------------------------------------------
-- 适用对象：已在运行 v1.0（2026-07-28 基线）的 wms 库
-- 执行方式：mysql -uroot -p wms < sql/upgrade_v1.1.sql
--
-- 本脚本只做四件事，不新增表（守住"25 张表不变"的自洽红线）：
--   1. 库位坐标回填 + 双通道仓库布局（≥24 个库位）
--   2. 新增"调度员"角色 + 补齐 AGV 菜单的 perms + 新增"搬运任务/待分配队列"菜单
--   3. 补齐六个角色的菜单授权矩阵（解决"非管理员登录即白屏"）
--   4. 演示账号（6 个角色各一个）
--
-- 仓库网格约定（供论文"地图与库位"一节引用）：
--   x = 列（0..7）  y = 行（0..7）
--   x ∈ {0, 7}    → 纵向主通道（双通道），贯通 y=0..7
--   y ∈ {0, 1, 3, 6} → 横向通道：y=0 月台行、y=1 待命区、y=3/y=6 作业横通道
--   其余格        → 货架行 y ∈ {2,4,5,7}，x ∈ {1..6}，共 24 个存储库位
--   每个货架格都与某条横通道相邻，因此任意库位都有可达路径（不穿货架）
-- ============================================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------------------
-- 1. 库位坐标回填与双通道布局
-- ----------------------------------------------------------------------------

-- 1.1 旧库位先按编号规则回填坐标（A02-01-01 → x=1, y=1），用 REGEXP 兜底只处理合法编号
UPDATE wms_location
SET pos_y = CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(location_code, '-', 2), '-', -1) AS UNSIGNED),
    pos_x = CAST(SUBSTRING_INDEX(location_code, '-', -1) AS UNSIGNED)
WHERE location_code REGEXP '^[A-Za-z]+-[0-9]+-[0-9]+$'
  AND (pos_x IS NULL OR pos_y IS NULL);

-- 1.2 清掉演示用的旧存储库位（保留月台），重建双通道布局
--     注意：不能只按 id 过滤——旧库位 id 可能从 1 开始，而 id=1 也可能是存储库位
DELETE FROM wms_location
WHERE location_code NOT IN ('A01-DOCK', 'A01-DOCK-OUT');

-- 1.3 重新写入 24 个存储库位 + 2 个月台（东侧月台用于出库方向演示）
--     编号规则：A02-{行号y}-{列号x}，与 BasLocationController.parsePosFromCode 一致
INSERT INTO wms_location (area_id, location_code, capacity, goods_type, status, remark, pos_x, pos_y)
VALUES
  -- 货架行 y=2（紧邻待命区横通道 y=1）
  (2, 'A02-02-01', 1000, '通用',     0, '货架行A-01', 1, 2),
  (2, 'A02-02-02', 1000, '通用',     0, '货架行A-02', 2, 2),
  (2, 'A02-02-03',  800, '电子元件', 0, '货架行A-03', 3, 2),
  (2, 'A02-02-04',  800, '电子元件', 0, '货架行A-04', 4, 2),
  (2, 'A02-02-05',  500, '原料',     0, '货架行A-05', 5, 2),
  (2, 'A02-02-06',  500, '原料',     0, '货架行A-06', 6, 2),
  -- 货架行 y=4（紧邻作业横通道 y=3）
  (2, 'A02-04-01', 1000, '通用',     0, '货架行B-01', 1, 4),
  (2, 'A02-04-02', 1000, '通用',     0, '货架行B-02', 2, 4),
  (2, 'A02-04-03',  800, '电子元件', 0, '货架行B-03', 3, 4),
  (2, 'A02-04-04',  800, '电子元件', 0, '货架行B-04', 4, 4),
  (2, 'A02-04-05',  500, '原料',     0, '货架行B-05', 5, 4),
  (2, 'A02-04-06',  500, '原料',     0, '货架行B-06', 6, 4),
  -- 货架行 y=5（紧邻作业横通道 y=6）
  (2, 'A02-05-01',  600, '半成品',   0, '货架行C-01', 1, 5),
  (2, 'A02-05-02',  600, '半成品',   0, '货架行C-02', 2, 5),
  (2, 'A02-05-03',  600, '半成品',   0, '货架行C-03', 3, 5),
  (2, 'A02-05-04',  400, '成品',     0, '货架行C-04', 4, 5),
  (2, 'A02-05-05',  400, '成品',     0, '货架行C-05', 5, 5),
  (2, 'A02-05-06',  400, '成品',     0, '货架行C-06', 6, 5),
  -- 货架行 y=7（紧邻作业横通道 y=6）
  (2, 'A02-07-01',  300, '成品',     0, '货架行D-01', 1, 7),
  (2, 'A02-07-02',  300, '成品',     0, '货架行D-02', 2, 7),
  (2, 'A02-07-03',  300, '成品',     0, '货架行D-03', 3, 7),
  (2, 'A02-07-04',  300, '成品',     0, '货架行D-04', 4, 7),
  (2, 'A02-07-05',  300, '成品',     0, '货架行D-05', 5, 7),
  (2, 'A02-07-06',  300, '成品',     0, '货架行D-06', 6, 7)
ON DUPLICATE KEY UPDATE capacity = VALUES(capacity), goods_type = VALUES(goods_type),
                        status = VALUES(status), remark = VALUES(remark),
                        pos_x = VALUES(pos_x), pos_y = VALUES(pos_y);

-- 1.4 两个虚拟月台：西侧收货月台 (0,0)、东侧发货月台 (7,0)
UPDATE wms_location SET pos_x = 0, pos_y = 0, goods_type = 'AGV虚拟月台',
                        remark = 'AGV 装卸货月台（西侧，收货）'
WHERE location_code = 'A01-DOCK';

INSERT INTO wms_location (area_id, location_code, capacity, goods_type, status, remark, pos_x, pos_y)
VALUES (1, 'A01-DOCK-OUT', NULL, 'AGV虚拟月台', 0, 'AGV 装卸货月台（东侧，发货）', 7, 0)
ON DUPLICATE KEY UPDATE pos_x = 7, pos_y = 0, goods_type = 'AGV虚拟月台',
                        remark = 'AGV 装卸货月台（东侧，发货）';

-- ----------------------------------------------------------------------------
-- 2. 角色与菜单
-- ----------------------------------------------------------------------------

-- 2.1 新增"调度员"角色（开题报告九大模块之模块 5：调度分配）
INSERT INTO sys_role (id, role_code, role_name, description)
VALUES (6, 'DISPATCHER', '调度员', '负责 AGV 搬运任务建单、待分配队列调度、地图与监控查看')
ON DUPLICATE KEY UPDATE role_name = VALUES(role_name), description = VALUES(description);

-- 2.2 补齐 AGV 菜单的 perms（原先 19~22 四条 INSERT 未带 perms 列，按钮级权限无从谈起）
UPDATE sys_menu SET perms = 'agv:device:list' WHERE id = 20;
UPDATE sys_menu SET perms = 'agv:map:view'    WHERE id = 21;
UPDATE sys_menu SET perms = 'agv:monitor:view' WHERE id = 22;

-- 2.3 新增两个 AGV 子菜单：搬运任务 / 待分配队列
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, icon, perms, sort)
VALUES (23, 19, '搬运任务',   'C', '/agv/task',  'List',       'agv:task:list',  4),
       (24, 19, '待分配队列', 'C', '/agv/queue', 'Sort',       'agv:queue:list', 5)
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name), path = VALUES(path),
                        perms = VALUES(perms), sort = VALUES(sort), status = 1;

-- ----------------------------------------------------------------------------
-- 3. 六角色菜单授权矩阵
--    菜单ID 参考：1 数据看板 / 2 基础资料 / 3 仓库 / 4 物料 / 5 供应商 / 6 客户
--                 7 采购入库 / 8 销售出库 / 9 库存 / 10 条码 / 11 盘点 / 12 单据
--                 13 操作日志 / 14 系统管理 / 15 用户 / 16 角色 / 17 菜单
--                 18 库区库位 / 19 设备监控(目录) / 20 设备档案 / 21 仓库地图
--                 22 运行监控 / 23 搬运任务 / 24 待分配队列
-- ----------------------------------------------------------------------------

-- 3.0 先清掉非管理员角色的旧授权，保证矩阵可重入
DELETE FROM sys_role_menu WHERE role_id <> 1;

-- 3.1 管理员：全部菜单（沿用"SELECT 1, id FROM sys_menu"，此处只补新增的 23/24）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) SELECT 1, id FROM sys_menu;

-- 3.2 仓管员 MANAGER：基础资料 + 全部单据 + 库存 + 日志 + 地图/监控（只读视角）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
  (2, 1),(2, 2),(2, 3),(2, 18),(2, 4),(2, 5),(2, 6),
  (2, 7),(2, 8),(2, 9),(2, 10),(2, 11),(2, 12),(2, 13),
  (2, 19),(2, 21),(2, 22);

-- 3.3 普通操作员 OPERATOR：仅出入库 + 库存 + 条码
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
  (3, 7),(3, 8),(3, 9),(3, 10);

-- 3.4 访客 GUEST：数据看板 + 仓库地图 + 运行监控（只读）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
  (4, 1),(4, 19),(4, 21),(4, 22);

-- 3.5 设备运维员 MAINTENANCE：设备档案 + 地图 + 监控 + 数据看板
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
  (5, 1),(5, 19),(5, 20),(5, 21),(5, 22);

-- 3.6 调度员 DISPATCHER：搬运任务 + 待分配队列 + 地图 + 监控
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
  (6, 19),(6, 21),(6, 22),(6, 23),(6, 24);

-- ----------------------------------------------------------------------------
-- 4. 演示账号（6 个角色各一个，口令统一 123456）
-- ----------------------------------------------------------------------------
INSERT INTO sys_user (id, username, password, nickname, real_name, dept, phone, status)
VALUES
  (2, 'manager',    '$2b$12$MOW0do4DqzK3Q0pftJr0qubHaLMDMV46rXuq9Y2SYYO6gNHMw//h.', '仓管员',     '王仓管', '仓储部', '13800000002', 1),
  (3, 'operator',   '$2b$12$MOW0do4DqzK3Q0pftJr0qubHaLMDMV46rXuq9Y2SYYO6gNHMw//h.', '普通操作员', '李操作', '仓储部', '13800000003', 1),
  (4, 'guest',      '$2b$12$MOW0do4DqzK3Q0pftJr0qubHaLMDMV46rXuq9Y2SYYO6gNHMw//h.', '访客',       '陈访客', '外部',   '13800000004', 1),
  (5, 'maintainer', '$2b$12$MOW0do4DqzK3Q0pftJr0qubHaLMDMV46rXuq9Y2SYYO6gNHMw//h.', '设备运维员', '刘运维', '设备部', '13800000005', 1),
  (6, 'dispatcher', '$2b$12$MOW0do4DqzK3Q0pftJr0qubHaLMDMV46rXuq9Y2SYYO6gNHMw//h.', '调度员',     '孙调度', '调度中心', '13800000006', 1)
ON DUPLICATE KEY UPDATE nickname = VALUES(nickname), real_name = VALUES(real_name),
                        dept = VALUES(dept), phone = VALUES(phone), status = 1;

DELETE FROM sys_user_role WHERE user_id > 1;
INSERT INTO sys_user_role (user_id, role_id) VALUES
  (2, 2),(3, 3),(4, 4),(5, 5),(6, 6);

-- ----------------------------------------------------------------------------
-- 5. AGV 设备：5 台，停放在双通道待命位，坐标与网格对齐
-- ----------------------------------------------------------------------------
UPDATE wms_agv_device SET
    agv_name = '搬运一号', max_load = 500.00, speed = 1.00, battery = 100, status = 0,
    enabled = 1, pos_x = 0, pos_y = 1, home_x = 0, home_y = 1, remark = '西通道待命位'
WHERE agv_code = 'AGV-01';

UPDATE wms_agv_device SET
    agv_name = '搬运二号', max_load = 500.00, speed = 1.00, battery = 90,  status = 0,
    enabled = 1, pos_x = 0, pos_y = 2, home_x = 0, home_y = 2, remark = '西通道待命位'
WHERE agv_code = 'AGV-02';

UPDATE wms_agv_device SET
    agv_name = '搬运三号', max_load = 800.00, speed = 0.80, battery = 85,  status = 0,
    enabled = 1, pos_x = 7, pos_y = 1, home_x = 7, home_y = 1, remark = '东通道待命位·低速车'
WHERE agv_code = 'AGV-03';

INSERT INTO wms_agv_device (agv_code, agv_name, max_load, speed, battery, status, enabled, pos_x, pos_y, home_x, home_y, remark)
VALUES
  ('AGV-04', '搬运四号', 800.00, 1.00, 100, 0, 1, 7, 2, 7, 2, '东通道待命位'),
  ('AGV-05', '搬运五号', 600.00, 1.00, 95,  0, 1, 0, 6, 0, 6, '西通道待命位'),
  ('AGV-06', '搬运六号', 600.00, 1.00, 100, 0, 1, 0, 4, 0, 4, '西通道待命位'),
  ('AGV-07', '搬运七号', 600.00, 1.00, 100, 0, 1, 7, 3, 7, 3, '东通道待命位'),
  ('AGV-08', '搬运八号', 600.00, 0.80, 100, 0, 1, 7, 6, 7, 6, '东通道待命位·低速车')
ON DUPLICATE KEY UPDATE agv_name = VALUES(agv_name), max_load = VALUES(max_load), speed = VALUES(speed),
    battery = VALUES(battery), status = 0, enabled = 1,
    pos_x = VALUES(pos_x), pos_y = VALUES(pos_y), home_x = VALUES(home_x), home_y = VALUES(home_y),
    remark = VALUES(remark);

-- ----------------------------------------------------------------------------
-- 6. 校验（执行后逐条核对）
-- ----------------------------------------------------------------------------
SELECT '库位数量' AS 检查项, COUNT(*) AS 结果 FROM wms_location;
SELECT '角色数量' AS 检查项, COUNT(*) AS 结果 FROM sys_role;
SELECT '菜单数量' AS 检查项, COUNT(*) AS 结果 FROM sys_menu;
SELECT r.role_code, r.role_name, COUNT(rm.menu_id) AS 菜单数
FROM sys_role r LEFT JOIN sys_role_menu rm ON rm.role_id = r.id
GROUP BY r.id, r.role_code, r.role_name ORDER BY r.id;
SELECT location_code, pos_x, pos_y FROM wms_location ORDER BY pos_y, pos_x;
