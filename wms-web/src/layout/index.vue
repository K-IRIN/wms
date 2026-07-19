<template>
  <el-container class="layout-container">
    <!-- 左侧菜单：按登录用户的角色菜单动态渲染 -->
    <el-aside width="220px" class="aside">
      <div class="logo">📦 WMS 智能仓储</div>
      <el-menu
        :default-active="$route.path"
        router
        background-color="#001529"
        text-color="#a6adb4"
        active-text-color="#ffffff"
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
    </el-aside>

    <!-- 右侧：顶栏 + 主内容 -->
    <el-container>
      <el-header class="header">
        <span class="page-title">{{ $route.meta.title || '首页' }}</span>
        <el-dropdown @command="onCommand">
          <span class="user-info">
            <el-avatar :size="28" :src="avatarUrl">{{ avatarText }}</el-avatar>
            <span class="nickname">{{ store.userInfo?.nickname || store.userInfo?.username }}</span>
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="profile">个人中心</el-dropdown-item>
              <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
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
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useUserStore } from '../store/user'

const store = useUserStore()
const router = useRouter()

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
    store.logout()
  }
}
</script>

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
  gap: 8px;
}
.nickname {
  font-size: 14px;
}
.main {
  background: #f0f2f5;
}
</style>
