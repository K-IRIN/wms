-- ============================================================
-- WMS 智能仓储管理系统 数据库脚本
-- 数据库：wms    字符集：utf8mb4
-- 内容：建库 + 19 张表 + 初始化数据
-- 执行方式：mysql -uroot -p --default-character-set=utf8mb4 < sql/wms.sql
-- ============================================================

CREATE DATABASE IF NOT EXISTS wms DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE wms;

-- ----------------------------
-- 一、权限相关（5 张表）
-- ----------------------------

-- 用户表
CREATE TABLE sys_user (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    username         VARCHAR(50)  NOT NULL COMMENT '登录账号',
    password         VARCHAR(100) NOT NULL COMMENT '密码（BCrypt加密）',
    nickname         VARCHAR(50)  DEFAULT NULL COMMENT '昵称',
    real_name        VARCHAR(50)  DEFAULT NULL COMMENT '姓名',
    dept             VARCHAR(50)  DEFAULT NULL COMMENT '部门',
    phone            VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
    avatar           VARCHAR(255) DEFAULT NULL COMMENT '头像URL',
    status           TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0禁用 1启用 2锁定',
    login_fail_count INT          NOT NULL DEFAULT 0 COMMENT '连续登录失败次数',
    lock_time        DATETIME     DEFAULT NULL COMMENT '锁定时间',
    last_login_time  DATETIME     DEFAULT NULL COMMENT '最后登录时间',
    last_login_ip    VARCHAR(50)  DEFAULT NULL COMMENT '最后登录IP',
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB COMMENT = '用户表';

-- 角色表
CREATE TABLE sys_role (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    role_code   VARCHAR(50) NOT NULL COMMENT '角色编码：ADMIN/MANAGER/OPERATOR/GUEST',
    role_name   VARCHAR(50) NOT NULL COMMENT '角色名称',
    description VARCHAR(255) DEFAULT NULL COMMENT '角色描述',
    status      TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：0禁用 1启用',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (role_code)
) ENGINE = InnoDB COMMENT = '角色表';

-- 菜单表（含按钮权限）
CREATE TABLE sys_menu (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    parent_id   BIGINT      NOT NULL DEFAULT 0 COMMENT '父菜单ID，0为顶级',
    menu_name   VARCHAR(50) NOT NULL COMMENT '菜单名称',
    menu_type   CHAR(1)     NOT NULL DEFAULT 'C' COMMENT '类型：M目录 C菜单 F按钮',
    path        VARCHAR(200) DEFAULT NULL COMMENT '前端路由地址',
    icon        VARCHAR(50)  DEFAULT NULL COMMENT '图标',
    perms       VARCHAR(100) DEFAULT NULL COMMENT '权限标识，如 wms:material:list',
    sort        INT         NOT NULL DEFAULT 0 COMMENT '排序',
    status      TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：0隐藏 1显示',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id)
) ENGINE = InnoDB COMMENT = '菜单权限表';

-- 用户-角色关联表
CREATE TABLE sys_user_role (
    id      BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_role (user_id, role_id)
) ENGINE = InnoDB COMMENT = '用户角色关联表';

-- 角色-菜单关联表
CREATE TABLE sys_role_menu (
    id      BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    menu_id BIGINT NOT NULL COMMENT '菜单ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_menu (role_id, menu_id)
) ENGINE = InnoDB COMMENT = '角色菜单关联表';

-- ----------------------------
-- 二、基础资料（7 张表）
-- ----------------------------

-- 仓库表
CREATE TABLE wms_warehouse (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    warehouse_code VARCHAR(50)  NOT NULL COMMENT '仓库编号',
    warehouse_name VARCHAR(100) NOT NULL COMMENT '仓库名称',
    address        VARCHAR(255) DEFAULT NULL COMMENT '仓库地址',
    warehouse_type VARCHAR(50)  DEFAULT NULL COMMENT '仓库类型：原料仓/半成品仓/成品仓/综合仓',
    manager        VARCHAR(50)  DEFAULT NULL COMMENT '负责人',
    phone          VARCHAR(20)  DEFAULT NULL COMMENT '联系电话',
    status         TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0停用 1启用',
    remark         VARCHAR(500) DEFAULT NULL COMMENT '备注',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_warehouse_code (warehouse_code)
) ENGINE = InnoDB COMMENT = '仓库表';

-- 库区表
CREATE TABLE wms_area (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    warehouse_id BIGINT      NOT NULL COMMENT '所属仓库ID',
    area_code   VARCHAR(50)  NOT NULL COMMENT '库区编号',
    area_name   VARCHAR(100) NOT NULL COMMENT '库区名称',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0停用 1启用',
    remark      VARCHAR(500) DEFAULT NULL COMMENT '备注',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_wh_area (warehouse_id, area_code)
) ENGINE = InnoDB COMMENT = '库区表';

-- 库位表
CREATE TABLE wms_location (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    area_id       BIGINT       NOT NULL COMMENT '所属库区ID',
    location_code VARCHAR(50)  NOT NULL COMMENT '库位编号',
    capacity      DECIMAL(10,2) DEFAULT NULL COMMENT '库位容量',
    goods_type    VARCHAR(100) DEFAULT NULL COMMENT '可存放物料类型',
    status        TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0空闲 1占用 2禁用',
    remark        VARCHAR(500) DEFAULT NULL COMMENT '备注',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_area_location (area_id, location_code)
) ENGINE = InnoDB COMMENT = '库位表';

-- 物料分类表
CREATE TABLE wms_material_category (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    parent_id     BIGINT       NOT NULL DEFAULT 0 COMMENT '父分类ID，0为顶级',
    category_name VARCHAR(100) NOT NULL COMMENT '分类名称',
    sort          INT          NOT NULL DEFAULT 0 COMMENT '排序',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0停用 1启用',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id)
) ENGINE = InnoDB COMMENT = '物料分类表';

-- 物料SKU表
CREATE TABLE wms_material (
    id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    material_code  VARCHAR(50)   NOT NULL COMMENT '物料编号',
    material_name  VARCHAR(100)  NOT NULL COMMENT '物料名称',
    spec           VARCHAR(100)  DEFAULT NULL COMMENT '规格型号',
    unit           VARCHAR(20)   DEFAULT NULL COMMENT '计量单位',
    purchase_price DECIMAL(10,2) DEFAULT 0.00 COMMENT '采购单价',
    sale_price     DECIMAL(10,2) DEFAULT 0.00 COMMENT '销售单价',
    category_id    BIGINT        DEFAULT NULL COMMENT '物料分类ID',
    shelf_life     INT           DEFAULT NULL COMMENT '保质期（天），NULL表示无保质期',
    stock_upper    INT           DEFAULT NULL COMMENT '库存上限（预警用）',
    stock_lower    INT           DEFAULT NULL COMMENT '库存下限（预警用）',
    status         TINYINT       NOT NULL DEFAULT 1 COMMENT '状态：0停用 1启用',
    remark         VARCHAR(500)  DEFAULT NULL COMMENT '备注',
    create_time    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_material_code (material_code)
) ENGINE = InnoDB COMMENT = '物料SKU表';

-- 供应商表
CREATE TABLE wms_supplier (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    supplier_code    VARCHAR(50)  NOT NULL COMMENT '供应商编号',
    supplier_name    VARCHAR(100) NOT NULL COMMENT '供应商名称',
    contact          VARCHAR(50)  DEFAULT NULL COMMENT '联系人',
    phone            VARCHAR(20)  DEFAULT NULL COMMENT '联系电话',
    address          VARCHAR(255) DEFAULT NULL COMMENT '地址',
    cooperate_status TINYINT      NOT NULL DEFAULT 1 COMMENT '合作状态：0终止合作 1合作中',
    remark           VARCHAR(500) DEFAULT NULL COMMENT '备注',
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_supplier_code (supplier_code)
) ENGINE = InnoDB COMMENT = '供应商表';

-- 客户表
CREATE TABLE wms_customer (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    customer_code VARCHAR(50)  NOT NULL COMMENT '客户编号',
    customer_name VARCHAR(100) NOT NULL COMMENT '客户名称',
    contact       VARCHAR(50)  DEFAULT NULL COMMENT '联系人',
    phone         VARCHAR(20)  DEFAULT NULL COMMENT '联系电话',
    address       VARCHAR(255) DEFAULT NULL COMMENT '地址',
    level         VARCHAR(20)  DEFAULT '普通' COMMENT '合作等级：普通/银牌/金牌',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0停用 1启用',
    remark        VARCHAR(500) DEFAULT NULL COMMENT '备注',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_customer_code (customer_code)
) ENGINE = InnoDB COMMENT = '客户表';

-- ----------------------------
-- 三、业务单据（7 张表）
-- ----------------------------

-- 入库单主表
CREATE TABLE wms_inbound_order (
    id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_no      VARCHAR(50)   NOT NULL COMMENT '入库单号，规则RK+日期+序号',
    supplier_id   BIGINT        NOT NULL COMMENT '供应商ID',
    warehouse_id  BIGINT        NOT NULL COMMENT '入库仓库ID',
    order_date    DATE          NOT NULL COMMENT '入库日期',
    total_amount  DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '入库总金额',
    status        TINYINT       NOT NULL DEFAULT 0 COMMENT '单据状态：0待审核 1已审核 2已驳回 3已完成',
    applicant     VARCHAR(50)   DEFAULT NULL COMMENT '制单人',
    auditor       VARCHAR(50)   DEFAULT NULL COMMENT '审核人',
    audit_time    DATETIME      DEFAULT NULL COMMENT '审核时间',
    audit_remark  VARCHAR(500)  DEFAULT NULL COMMENT '审核意见',
    invalid_flag  TINYINT       NOT NULL DEFAULT 0 COMMENT '作废标记：0正常 1已作废',
    invalid_reason VARCHAR(500) DEFAULT NULL COMMENT '作废原因',
    remark        VARCHAR(500)  DEFAULT NULL COMMENT '备注',
    create_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no)
) ENGINE = InnoDB COMMENT = '入库单主表';

-- 入库单明细表
CREATE TABLE wms_inbound_item (
    id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_id       BIGINT        NOT NULL COMMENT '入库单ID',
    material_id    BIGINT        NOT NULL COMMENT '物料ID',
    quantity       INT           NOT NULL COMMENT '入库数量',
    price          DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '入库单价',
    amount         DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '金额=数量×单价',
    quality_status TINYINT       NOT NULL DEFAULT 0 COMMENT '质检状态：0待质检 1合格 2不合格',
    location_id    BIGINT        DEFAULT NULL COMMENT '上架库位ID',
    shelf_status   TINYINT       NOT NULL DEFAULT 0 COMMENT '上架状态：0未上架 1已上架',
    remark         VARCHAR(500)  DEFAULT NULL COMMENT '备注',
    create_time    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_order_id (order_id)
) ENGINE = InnoDB COMMENT = '入库单明细表';

-- 出库单主表
CREATE TABLE wms_outbound_order (
    id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_no       VARCHAR(50)   NOT NULL COMMENT '出库单号，规则CK+日期+序号',
    customer_id    BIGINT        NOT NULL COMMENT '客户ID',
    warehouse_id   BIGINT        NOT NULL COMMENT '出库仓库ID',
    order_date     DATE          NOT NULL COMMENT '出库日期',
    total_amount   DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '出库总金额',
    status         TINYINT       NOT NULL DEFAULT 0 COMMENT '单据状态：0待审核 1已审核 2已驳回 3已完成',
    pick_status    TINYINT       NOT NULL DEFAULT 0 COMMENT '拣货状态：0未拣货 1拣货中 2已拣货',
    review_status  TINYINT       NOT NULL DEFAULT 0 COMMENT '复核状态：0未复核 1已复核',
    applicant      VARCHAR(50)   DEFAULT NULL COMMENT '制单人',
    auditor        VARCHAR(50)   DEFAULT NULL COMMENT '审核人',
    audit_time     DATETIME      DEFAULT NULL COMMENT '审核时间',
    audit_remark   VARCHAR(500)  DEFAULT NULL COMMENT '审核意见',
    invalid_flag   TINYINT       NOT NULL DEFAULT 0 COMMENT '作废标记：0正常 1已作废',
    invalid_reason VARCHAR(500)  DEFAULT NULL COMMENT '作废原因',
    remark         VARCHAR(500)  DEFAULT NULL COMMENT '备注',
    create_time    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no)
) ENGINE = InnoDB COMMENT = '出库单主表';

-- 出库单明细表
CREATE TABLE wms_outbound_item (
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_id    BIGINT        NOT NULL COMMENT '出库单ID',
    material_id BIGINT        NOT NULL COMMENT '物料ID',
    location_id BIGINT        DEFAULT NULL COMMENT '拣货库位ID',
    quantity    INT           NOT NULL COMMENT '出库数量',
    picked_qty  INT           NOT NULL DEFAULT 0 COMMENT '已拣数量',
    price       DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '出库单价',
    amount      DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '金额=数量×单价',
    remark      VARCHAR(500)  DEFAULT NULL COMMENT '备注',
    create_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_order_id (order_id)
) ENGINE = InnoDB COMMENT = '出库单明细表';

-- 盘点单主表
CREATE TABLE wms_check_order (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_no      VARCHAR(50)  NOT NULL COMMENT '盘点单号，规则PD+日期+序号',
    warehouse_id  BIGINT       NOT NULL COMMENT '盘点仓库ID',
    scope_type    TINYINT      NOT NULL DEFAULT 1 COMMENT '盘点范围：1按仓库 2按库区 3按物料分类',
    area_id       BIGINT       DEFAULT NULL COMMENT '库区ID（按库区时必填）',
    category_id   BIGINT       DEFAULT NULL COMMENT '物料分类ID（按分类时必填）',
    check_period  VARCHAR(20)  DEFAULT NULL COMMENT '盘点周期：月度/季度/年度',
    status        TINYINT      NOT NULL DEFAULT 0 COMMENT '单据状态：0待盘点 1待审核 2已审核 3已完成',
    checker       VARCHAR(50)  DEFAULT NULL COMMENT '盘点人',
    auditor       VARCHAR(50)  DEFAULT NULL COMMENT '审核人',
    audit_time    DATETIME     DEFAULT NULL COMMENT '审核时间',
    audit_remark  VARCHAR(500) DEFAULT NULL COMMENT '审核意见',
    remark        VARCHAR(500) DEFAULT NULL COMMENT '备注',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no)
) ENGINE = InnoDB COMMENT = '盘点单主表';

-- 盘点单明细表
CREATE TABLE wms_check_item (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_id    BIGINT      NOT NULL COMMENT '盘点单ID',
    material_id BIGINT      NOT NULL COMMENT '物料ID',
    location_id BIGINT      DEFAULT NULL COMMENT '库位ID',
    book_qty    INT         NOT NULL DEFAULT 0 COMMENT '账面数量',
    actual_qty  INT         DEFAULT NULL COMMENT '实际数量',
    diff_qty    INT         DEFAULT NULL COMMENT '盈亏数量=实际-账面',
    reason      VARCHAR(500) DEFAULT NULL COMMENT '盈亏原因说明',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_order_id (order_id)
) ENGINE = InnoDB COMMENT = '盘点单明细表';

-- 移库单表
CREATE TABLE wms_move_order (
    id               BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_no         VARCHAR(50) NOT NULL COMMENT '移库单号，规则YK+日期+序号',
    material_id      BIGINT      NOT NULL COMMENT '物料ID',
    from_location_id BIGINT      NOT NULL COMMENT '源库位ID',
    to_location_id   BIGINT      NOT NULL COMMENT '目标库位ID',
    quantity         INT         NOT NULL COMMENT '移库数量',
    status           TINYINT     NOT NULL DEFAULT 0 COMMENT '状态：0待确认 1已完成',
    operator         VARCHAR(50) DEFAULT NULL COMMENT '操作人',
    remark           VARCHAR(500) DEFAULT NULL COMMENT '备注',
    create_time      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no)
) ENGINE = InnoDB COMMENT = '移库单表';

-- ----------------------------
-- 四、库存与日志（3 张表）
-- ----------------------------

-- 库存表
CREATE TABLE wms_inventory (
    id           BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    warehouse_id BIGINT   NOT NULL COMMENT '仓库ID',
    location_id  BIGINT   NOT NULL COMMENT '库位ID',
    material_id  BIGINT   NOT NULL COMMENT '物料ID',
    quantity     INT      NOT NULL DEFAULT 0 COMMENT '库存数量',
    frozen_qty   INT      NOT NULL DEFAULT 0 COMMENT '冻结数量（不可出库）',
    create_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_wlm (warehouse_id, location_id, material_id)
) ENGINE = InnoDB COMMENT = '库存表';

-- 库存流水表
CREATE TABLE wms_inventory_log (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    material_id  BIGINT       NOT NULL COMMENT '物料ID',
    warehouse_id BIGINT       NOT NULL COMMENT '仓库ID',
    location_id  BIGINT       DEFAULT NULL COMMENT '库位ID',
    change_type  VARCHAR(20)  NOT NULL COMMENT '变动类型：入库/出库/移库/盘点/冻结/解冻',
    change_qty   INT          NOT NULL COMMENT '变动数量（正增负减）',
    before_qty   INT          NOT NULL COMMENT '变动前数量',
    after_qty    INT          NOT NULL COMMENT '变动后数量',
    order_no     VARCHAR(50)  DEFAULT NULL COMMENT '关联单据号',
    operator     VARCHAR(50)  DEFAULT NULL COMMENT '操作人',
    remark       VARCHAR(500) DEFAULT NULL COMMENT '备注',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_material_id (material_id),
    KEY idx_order_no (order_no)
) ENGINE = InnoDB COMMENT = '库存流水表';

-- 操作日志表
CREATE TABLE sys_operation_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id     BIGINT       DEFAULT NULL COMMENT '操作人ID',
    username    VARCHAR(50)  DEFAULT NULL COMMENT '操作人账号',
    operation   VARCHAR(200) NOT NULL COMMENT '操作内容',
    method      VARCHAR(200) DEFAULT NULL COMMENT '请求方式与路径',
    ip          VARCHAR(50)  DEFAULT NULL COMMENT '操作IP',
    params      TEXT         DEFAULT NULL COMMENT '请求参数',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    PRIMARY KEY (id),
    KEY idx_username (username),
    KEY idx_create_time (create_time)
) ENGINE = InnoDB COMMENT = '操作日志表';

-- ============================================================
-- 初始化数据
-- ============================================================

-- 4 个角色
INSERT INTO sys_role (id, role_code, role_name, description) VALUES
(1, 'ADMIN',    '管理员',     '拥有系统全部操作权限'),
(2, 'MANAGER',  '仓管员',     '管理库位、执行盘点、审核单据'),
(3, 'OPERATOR', '普通操作员', '仅可进行出入库操作、查看库存'),
(4, 'GUEST',    '访客',       '只读权限');

-- 管理员账号：admin / 123456（BCrypt 加密存储，登录模块上线后请立即修改）
INSERT INTO sys_user (id, username, password, nickname, real_name, dept, phone, status) VALUES
(1, 'admin', '$2b$12$MOW0do4DqzK3Q0pftJr0qubHaLMDMV46rXuq9Y2SYYO6gNHMw//h.', '系统管理员', '管理员', '信息部', '13800000000', 1);

-- admin 拥有管理员角色
INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 1);

-- 菜单（9 大模块 + 系统管理）
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, icon, perms, sort) VALUES
(1,  0,  '数据看板',   'C', '/dashboard',       'DataAnalysis', 'dashboard:view',      1),
(2,  0,  '基础资料',   'M', '/base',            'Files',        NULL,                 2),
(3,  2,  '仓库管理',   'C', '/base/warehouse',  NULL,           'wms:warehouse:list', 1),
(4,  2,  '物料管理',   'C', '/base/material',   NULL,           'wms:material:list',  2),
(5,  2,  '供应商管理', 'C', '/base/supplier',   NULL,           'wms:supplier:list',  3),
(6,  2,  '客户管理',   'C', '/base/customer',   NULL,           'wms:customer:list',  4),
(7,  0,  '采购入库',   'C', '/inbound',         'Download',     'wms:inbound:list',   3),
(8,  0,  '销售出库',   'C', '/outbound',        'Upload',       'wms:outbound:list',  4),
(9,  0,  '库存管理',   'C', '/inventory',       'Box',          'wms:inventory:list', 5),
(10, 0,  '条码管理',   'C', '/qrcode',          'Grid',         'wms:qrcode:view',    6),
(11, 0,  '库存盘点',   'C', '/check',           'Checked',      'wms:check:list',     7),
(12, 0,  '单据中心',   'C', '/doc',             'Document',     'wms:doc:list',       8),
(13, 0,  '操作日志',   'C', '/log',             'Tickets',      'sys:log:list',       9),
(14, 0,  '系统管理',   'M', '/system',          'Setting',      NULL,                 10),
(15, 14, '用户管理',   'C', '/system/user',     NULL,           'sys:user:list',      1),
(16, 14, '角色管理',   'C', '/system/role',     NULL,           'sys:role:list',      2),
(17, 14, '菜单管理',   'C', '/system/menu',     NULL,           'sys:menu:list',      3);

-- 管理员角色拥有全部菜单
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, id FROM sys_menu;

-- 示例仓库 → 库区 → 库位
INSERT INTO wms_warehouse (id, warehouse_code, warehouse_name, address, warehouse_type, manager, phone) VALUES
(1, 'WH001', '主仓库', 'XX市XX区物流园1号库', '综合仓', '张三', '13800000001');

INSERT INTO wms_area (id, warehouse_id, area_code, area_name) VALUES
(1, 1, 'A01', '收货区'),
(2, 1, 'A02', '存储区');

INSERT INTO wms_location (id, area_id, location_code, capacity, goods_type) VALUES
(1, 2, 'A02-01-01', 1000, '通用'),
(2, 2, 'A02-01-02', 1000, '通用'),
(3, 2, 'A02-02-01', 500,  '电子元件');

-- 物料分类与测试物料
INSERT INTO wms_material_category (id, parent_id, category_name, sort) VALUES
(1, 0, '原材料', 1),
(2, 0, '半成品', 2),
(3, 0, '成品',   3);

INSERT INTO wms_material (id, material_code, material_name, spec, unit, purchase_price, sale_price, category_id, shelf_life, stock_upper, stock_lower) VALUES
(1, 'M001', '十字螺丝',   'M4×10mm',     '盒',  5.50,   8.00,   1, NULL, 5000, 200),
(2, 'M002', '主控电路板', 'PCB-V2.1',    '块',  45.00,  68.00,  1, 730,  1000, 50),
(3, 'M003', '包装纸箱',   '40×30×30cm',  '个',  2.00,   3.50,   1, NULL, 8000, 500),
(4, 'M004', '蓝牙音箱',   'BS-2026 黑色', '台', 120.00, 199.00, 3, 1095, 500,  20);

-- 示例供应商与客户
INSERT INTO wms_supplier (id, supplier_code, supplier_name, contact, phone, address, cooperate_status) VALUES
(1, 'S001', '深圳电子元件厂', '李经理', '13900000001', '深圳市宝安区XX工业园', 1);

INSERT INTO wms_customer (id, customer_code, customer_name, contact, phone, address, level) VALUES
(1, 'C001', '杭州贸易有限公司', '王总', '13900000002', '杭州市西湖区XX大厦', '金牌');

-- ============================================================
-- 完成！共 22 张表：
-- 权限 5 张：sys_user / sys_role / sys_menu / sys_user_role / sys_role_menu
-- 基础 7 张：wms_warehouse / wms_area / wms_location / wms_material_category / wms_material / wms_supplier / wms_customer
-- 单据 7 张：wms_inbound_order / wms_inbound_item / wms_outbound_order / wms_outbound_item / wms_check_order / wms_check_item / wms_move_order
-- 库存日志 3 张：wms_inventory / wms_inventory_log / sys_operation_log
-- ============================================================
