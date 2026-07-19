import { defineStore } from 'pinia'
import { login as loginApi, getUserInfo as getUserInfoApi, logout as logoutApi } from '../api/auth'

// 登录用户全局状态：Token、用户信息、角色、菜单树
export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('wms-token') || '',
    userInfo: null,
    roles: [],
    menus: [],
    loaded: false
  }),
  actions: {
    async login(form) {
      const res = await loginApi(form)
      this.token = res.data.token
      localStorage.setItem('wms-token', res.data.token)
      this.setUserData(res.data)
    },
    async fetchUserInfo() {
      const res = await getUserInfoApi()
      this.setUserData(res.data)
    },
    setUserData(data) {
      this.userInfo = data.user
      this.roles = data.roles || []
      this.menus = data.menus || []
      this.loaded = true
    },
    async logout() {
      try {
        await logoutApi()
      } catch (e) {
        // 忽略退出接口异常，本地照常清理
      }
      this.reset()
      // 整页刷新以清空动态注册的路由
      window.location.href = '/login'
    },
    reset() {
      this.token = ''
      this.userInfo = null
      this.roles = []
      this.menus = []
      this.loaded = false
      localStorage.removeItem('wms-token')
    }
  }
})
