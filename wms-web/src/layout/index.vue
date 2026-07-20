<template>
  <el-container class="layout-container">
    <!-- 左侧菜单：按登录用户的角色菜单动态渲染 -->
    <el-aside width="224px" class="aside">
      <div class="logo">
        <div class="logo-icon">
          <el-icon :size="22"><Box /></el-icon>
        </div>
        <div class="logo-text">
          <div class="logo-title">WMS 智能仓储</div>
          <div class="logo-sub">SMART WAREHOUSE</div>
        </div>
      </div>
      <el-scrollbar class="menu-scroll">
        <el-menu
          :default-active="$route.path"
          router
          class="side-menu"
        >
          <template v-for="menu in store.menus" :key="menu.id">
            <!-- 目录（有子菜单） -->
            <el-sub-menu v-if="menu.menuType === 'M'" :index="menu.path || String(menu.id)">
              <template #title>
                <el-icon><component :is="menu.icon || 'Folder'" /></el-icon>
                <span>{{ menu.menuName }}</span>
              </template>
              <el-menu-item v-for="child in menu.children" :key="child.id" :index="child.path">
                <el-icon><component :is="child.icon || 'Document'" /></el-icon>
                <span>{{ child.menuName }}</span>
              </el-menu-item>
            </el-sub-menu>
            <!-- 普通菜单 -->
            <el-menu-item v-else :index="menu.path">
              <el-icon><component :is="menu.icon || 'Document'" /></el-icon>
              <span>{{ menu.menuName }}</span>
            </el-menu-item>
          </template>
        </el-menu>
      </el-scrollbar>
      <div class="aside-footer">v1.1 · 智能仓储管理平台</div>
    </el-aside>

    <!-- 右侧：顶栏 + 主内容 -->
    <el-container>
      <el-header class="header" height="56px">
        <el-breadcrumb separator="/">
          <el-breadcrumb-item :to="{ path: '/dashboard' }">首页</el-breadcrumb-item>
          <el-breadcrumb-item v-if="$route.meta.title && $route.path !== '/dashboard'">
            {{ $route.meta.title }}
          </el-breadcrumb-item>
        </el-breadcrumb>
        <el-dropdown @command="onCommand">
          <span class="user-info">
            <el-avatar :size="30" :src="avatarUrl" class="user-avatar">{{ avatarText }}</el-avatar>
            <span class="nickname">{{ store.userInfo?.nickname || store.userInfo?.username }}</span>
            <el-icon class="arrow"><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="profile">
                <el-icon><User /></el-icon>个人中心
              </el-dropdown-item>
              <el-dropdown-item divided command="logout">
                <el-icon><SwitchButton /></el-icon>退出登录
              </el-dropdown-item>
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

<script setup>
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useUserStore } from '../store/user'
import { useAgvStore } from '../store/agv'

const store = useUserStore()
const agvStore = useAgvStore()
const router = useRouter()

// 登录进入布局后建立 AGV 实时推送连接（全局仅一次，地图页/监控页共用）
onMounted(() => {
  agvStore.connect()
})

// 头像走 /api 前缀，开发环境由 vite 代理到后端
const avatarUrl = computed(() => {
  const a = store.userInfo?.avatar
  return a ? '/api' + a : ''
})
const avatarText = computed(() => (store.userInfo?.nickname || store.userInfo?.username || 'U').charAt(0))

const onCommand = async (cmd) => {
  if (cmd === 'profile') {
    router.push('/profile')
  } else if (cmd === 'logout') {
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', { type: 'warning' })
    agvStore.disconnect()
    store.logout()
  }
}
</script>

<style scoped>
.layout-container {
  height: 100vh;
}

/* ==================== 侧边导航 ==================== */
.aside {
  background: var(--wms-aside-bg);
  display: flex;
  flex-direction: column;
  box-shadow: 2px 0 12px rgba(8, 24, 58, 0.35);
  z-index: 10;
}

.logo {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 18px 14px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.07);
}

.logo-icon {
  width: 38px;
  height: 38px;
  border-radius: 10px;
  background: linear-gradient(135deg, #2e6bff, #5b8cff);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  box-shadow: 0 4px 12px rgba(46, 107, 255, 0.45);
  flex-shrink: 0;
}

.logo-title {
  color: #fff;
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0.5px;
  line-height: 1.2;
}

.logo-sub {
  color: rgba(142, 162, 201, 0.75);
  font-size: 10px;
  letter-spacing: 1.5px;
  margin-top: 2px;
  transform: scale(0.9);
  transform-origin: left;
}

.menu-scroll {
  flex: 1;
}

/* 菜单整体 */
.side-menu {
  border-right: none;
  background: transparent;
  padding: 8px 10px;
}

/* 菜单项（含子菜单标题） */
.side-menu :deep(.el-menu-item),
.side-menu :deep(.el-sub-menu__title) {
  color: var(--wms-aside-text);
  height: 44px;
  line-height: 44px;
  margin: 3px 0;
  border-radius: 8px;
  transition: all 0.2s ease;
}

.side-menu :deep(.el-menu-item:hover),
.side-menu :deep(.el-sub-menu__title:hover) {
  color: #fff;
  background: rgba(255, 255, 255, 0.06);
}

/* 子菜单弹层/展开区 */
.side-menu :deep(.el-sub-menu .el-menu) {
  background: rgba(6, 16, 38, 0.4);
  border-radius: 8px;
  padding: 4px 6px;
}

.side-menu :deep(.el-sub-menu.is-active > .el-sub-menu__title) {
  color: #fff;
}

/* 选中项：发光条 + 渐变底 */
.side-menu :deep(.el-menu-item.is-active) {
  color: #fff;
  font-weight: 600;
  background: var(--wms-aside-active-bg);
  position: relative;
}

.side-menu :deep(.el-menu-item.is-active)::before {
  content: '';
  position: absolute;
  left: 0;
  top: 20%;
  height: 60%;
  width: 3px;
  border-radius: 2px;
  background: linear-gradient(180deg, #5b8cff, #2e6bff);
  box-shadow: 0 0 8px rgba(91, 140, 255, 0.9);
}

.aside-footer {
  padding: 12px 18px;
  color: rgba(142, 162, 201, 0.45);
  font-size: 11px;
  letter-spacing: 0.5px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}

/* ==================== 顶栏 ==================== */
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(8px);
  border-bottom: 1px solid #e8edf6;
  box-shadow: 0 1px 4px rgba(16, 42, 97, 0.04);
  z-index: 5;
}

.header :deep(.el-breadcrumb__inner) {
  color: var(--wms-text-sub);
  font-weight: 500;
}

.header :deep(.el-breadcrumb__item:last-child .el-breadcrumb__inner) {
  color: var(--wms-text-main);
  font-weight: 600;
}

.user-info {
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 10px;
  border-radius: 8px;
  transition: background 0.2s;
}

.user-info:hover {
  background: #f2f6ff;
}

.user-avatar {
  background: linear-gradient(135deg, #2e6bff, #5b8cff);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
}

.nickname {
  font-size: 14px;
  color: var(--wms-text-main);
  font-weight: 500;
}

.arrow {
  color: var(--wms-text-sub);
  font-size: 12px;
}

/* ==================== 主内容区 ==================== */
.main {
  background: var(--wms-content-bg);
  padding: 0;
  overflow-y: auto;
}
</style>
