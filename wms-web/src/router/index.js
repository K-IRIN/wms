import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '../store/user'

// 静态路由：登录、注册（无需权限）
const staticRoutes = [
  { path: '/login', name: 'Login', component: () => import('../views/login/index.vue'), meta: { title: '登录' } },
  { path: '/register', name: 'Register', component: () => import('../views/register/index.vue'), meta: { title: '注册' } }
]

const router = createRouter({
  history: createWebHistory(),
  routes: staticRoutes
})

// 收集 views 下所有页面，用于按菜单路径动态匹配组件
const viewModules = import.meta.glob('../views/**/*.vue')
const Placeholder = () => import('../views/placeholder/index.vue')

// 菜单树 → 动态路由（挂在 Layout 下）
function buildRoutes(menus) {
  const children = []
  const walk = (list) => {
    list.forEach((m) => {
      if (m.menuType === 'C' && m.path) {
        children.push({
          path: m.path.replace(/^\//, ''),
          name: m.path,
          component: viewModules[`../views${m.path}/index.vue`] || Placeholder,
          meta: { title: m.menuName, icon: m.icon }
        })
      }
      if (m.children && m.children.length) walk(m.children)
    })
  }
  walk(menus)
  children.push({
    path: 'profile',
    name: 'Profile',
    component: () => import('../views/profile/index.vue'),
    meta: { title: '个人中心' }
  })
  return [{
    path: '/',
    component: () => import('../layout/index.vue'),
    redirect: '/dashboard',
    children
  }]
}

let dynamicRegistered = false

// 路由守卫：未登录跳登录页；登录后按角色菜单动态注册路由
router.beforeEach(async (to) => {
  const store = useUserStore()
  document.title = (to.meta.title ? to.meta.title + ' - ' : '') + 'WMS 智能仓储管理系统'

  // 登录/注册页：已登录则回首页
  if (to.path === '/login' || to.path === '/register') {
    return store.token ? '/' : true
  }
  // 未登录：去登录页，并记住目标地址
  if (!store.token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  // 已登录但用户信息未加载（如刷新页面）：先拉取
  if (!store.loaded) {
    try {
      await store.fetchUserInfo()
    } catch (e) {
      store.reset()
      return '/login'
    }
  }
  // 首次进入：注册动态路由后重定向，让新路由生效
  if (!dynamicRegistered) {
    buildRoutes(store.menus).forEach((r) => router.addRoute(r))
    router.addRoute({ path: '/:pathMatch(.*)*', redirect: '/' })
    dynamicRegistered = true
    return to.fullPath
  }
  return true
})

export default router
