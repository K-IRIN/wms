<template>
  <el-container class="layout-container">
    <!-- 左侧菜单 -->
    <el-aside width="220px" class="aside">
      <div class="logo">📦 WMS 智能仓储</div>
      <el-menu
        :default-active="$route.path"
        router
        background-color="#001529"
        text-color="#a6adb4"
        active-text-color="#ffffff"
      >
        <el-menu-item index="/dashboard">
          <el-icon><DataAnalysis /></el-icon><span>数据看板</span>
        </el-menu-item>
        <el-menu-item index="/base">
          <el-icon><Files /></el-icon><span>基础资料</span>
        </el-menu-item>
        <el-menu-item index="/inbound">
          <el-icon><Download /></el-icon><span>采购入库</span>
        </el-menu-item>
        <el-menu-item index="/outbound">
          <el-icon><Upload /></el-icon><span>销售出库</span>
        </el-menu-item>
        <el-menu-item index="/inventory">
          <el-icon><Box /></el-icon><span>库存管理</span>
        </el-menu-item>
        <el-menu-item index="/qrcode">
          <el-icon><Grid /></el-icon><span>条码管理</span>
        </el-menu-item>
        <el-menu-item index="/check">
          <el-icon><Checked /></el-icon><span>库存盘点</span>
        </el-menu-item>
        <el-menu-item index="/log">
          <el-icon><Document /></el-icon><span>单据日志</span>
        </el-menu-item>
        <el-menu-item index="/system">
          <el-icon><Setting /></el-icon><span>系统管理</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <!-- 右侧：顶栏 + 主内容 -->
    <el-container>
      <el-header class="header">
        <span class="page-title">{{ $route.meta.title || '首页' }}</span>
        <el-dropdown>
          <span class="user-info">
            管理员 <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item>个人中心</el-dropdown-item>
              <el-dropdown-item divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout-container {
  height: 100vh;
}
.aside {
  background-color: #001529;
}
.logo {
  height: 60px;
  line-height: 60px;
  text-align: center;
  color: #fff;
  font-size: 16px;
  font-weight: bold;
}
.aside :deep(.el-menu) {
  border-right: none;
}
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e4e7ed;
  background: #fff;
}
.page-title {
  font-size: 16px;
  font-weight: 600;
}
.user-info {
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 4px;
}
.main {
  background: #f0f2f5;
}
</style>
