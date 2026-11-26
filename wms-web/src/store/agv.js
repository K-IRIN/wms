import { defineStore } from 'pinia'

// ==================== AGV 实时数据全局状态（v2.0 E3） ====================
// 设计说明：
//  1. WebSocket 全局只连一次（布局挂载时 connect，退出登录时 disconnect），
//     地图页/监控页共用这里的响应式数据，避免每个页面各建一条连接。
//  2. 断线后 3 秒自动重连；无 token（未登录）时不连接也不重连。
//  3. 消息格式：{ type: 'agv' | 'task' | 'alert', data: {...} }，由后端调度引擎广播。

let ws = null               // 当前 WebSocket 实例（非响应式，不放 state）
let reconnectTimer = null   // 重连定时器
let manualClose = false     // 是否为主动断开（主动断开不再重连）

export const useAgvStore = defineStore('agv', {
  state: () => ({
    connected: false,   // WS 是否在线
    agvs: {},           // AGV 实时状态：id → 设备对象（坐标/电量/状态随 WS 更新）
    tasks: {},          // 任务实时状态：id → 任务对象
    alerts: [],         // 最近告警（新消息在前，最多保留 20 条）
    lastMsgAt: ''       // 最近一次收到消息的时间（HH:mm:ss）
  }),
  getters: {
    // 按 id 排序的 AGV 列表，供卡片/地图遍历
    agvList: (s) => Object.values(s.agvs).sort((a, b) => a.id - b.id),
    // 未结束的任务列表（待分配 ~ 返回待命区）
    activeTasks: (s) => Object.values(s.tasks).filter((t) => t.status < 6),
    // 某台 AGV 当前正在执行的任务
    taskOfAgv: (s) => (agvId) =>
      Object.values(s.tasks).find((t) => t.agvId === agvId && t.status < 6) || null
  },
  actions: {
    /** 建立 WebSocket 连接（已连接或连接中则不重复连） */
    connect() {
      const token = localStorage.getItem('wms-token')
      if (!token || (ws && ws.readyState <= WebSocket.OPEN)) {
        return
      }
      manualClose = false
      // 开发环境走 vite 代理（/api → 8080，已开 ws:true），按当前页面地址动态拼接
      const scheme = location.protocol === 'https:' ? 'wss' : 'ws'
      ws = new WebSocket(`${scheme}://${location.host}/api/ws/agv?token=${token}`)

      ws.onopen = () => {
        this.connected = true
      }
      ws.onmessage = (e) => {
        try {
          this.handleMessage(JSON.parse(e.data))
        } catch (err) {
          // 单条消息解析失败不影响后续消息
          console.warn('AGV WS 消息解析失败', err)
        }
      }
      ws.onclose = () => {
        this.connected = false
        ws = null
        // 非主动断开且仍登录：3 秒后重连
        if (!manualClose && localStorage.getItem('wms-token')) {
          clearTimeout(reconnectTimer)
          reconnectTimer = setTimeout(() => this.connect(), 3000)
        }
      }
      ws.onerror = () => {
        // 出错后浏览器会紧接着触发 close，统一在 close 里处理重连
        ws?.close()
      }
    },

    /** 主动断开（退出登录时调用），不再自动重连 */
    disconnect() {
      manualClose = true
      clearTimeout(reconnectTimer)
      reconnectTimer = null
      if (ws) {
        ws.close()
        ws = null
      }
      this.connected = false
    },

    /** 分发 WS 消息到响应式状态 */
    handleMessage(msg) {
      const now = new Date()
      this.lastMsgAt = now.toTimeString().slice(0, 8)
      if (msg.type === 'agv' && msg.data?.id != null) {
        // 合并式更新：保留 WS 未推送的字段（如名称等初始 HTTP 数据已有）
        this.agvs[msg.data.id] = { ...this.agvs[msg.data.id], ...msg.data }
      } else if (msg.type === 'task' && msg.data?.id != null) {
        this.tasks[msg.data.id] = { ...this.tasks[msg.data.id], ...msg.data }
      } else if (msg.type === 'alert') {
        this.alerts.unshift({ ...msg.data, _seq: Date.now() + Math.random() })
        if (this.alerts.length > 20) {
          this.alerts.length = 20
        }
      }
    },

    /** 初始数据灌入：页面通过 HTTP 拉到的全量设备/任务先放进来，等 WS 增量刷新 */
    seedAgvs(list) {
      const map = {}
      for (const a of list || []) {
        map[a.id] = { ...this.agvs[a.id], ...a }
      }
      this.agvs = map
    },
    seedTasks(list) {
      const map = { ...this.tasks }
      for (const t of list || []) {
        map[t.id] = { ...map[t.id], ...t }
      }
      this.tasks = map
    }
  }
})
