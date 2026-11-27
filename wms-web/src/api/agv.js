import request from '../utils/request'

// ==================== AGV 设备档案（v2.0 WCS-AGV 扩展） ====================
export const pageAgvDevice = (params) => request.get('/agv/device/page', { params })
export const listAgvDevice = () => request.get('/agv/device/list')
export const addAgvDevice = (data) => request.post('/agv/device/add', data)
export const updateAgvDevice = (data) => request.put('/agv/device/update', data)
export const updateAgvDeviceStatus = (id, enabled) => request.put(`/agv/device/status/${id}/${enabled}`)
export const chargeAgvDevice = (id) => request.put(`/agv/device/charge/${id}`)
export const finishChargeAgvDevice = (id) => request.put(`/agv/device/charge/finish/${id}`)
export const deleteAgvDevice = (id) => request.delete(`/agv/device/${id}`)

// ==================== AGV 运行日志 ====================
export const pageAgvLog = (params) => request.get('/agv/device/log/page', { params })
// 告警事件回看（告警/故障/冲突，阶段 6.3）
export const pageAgvAlert = (params) => request.get('/agv/device/alerts', { params })
// 导出 AGV 运行报表（Excel：设备台账 + 告警记录，阶段 6.3）
export const exportAgvReport = () => request.get('/agv/device/export', { responseType: 'blob' })

// ==================== AGV 搬运任务 ====================
export const pageAgvTask = (params) => request.get('/agv/task/page', { params })
export const listActiveAgvTask = () => request.get('/agv/task/active')
export const getAgvTaskStat = () => request.get('/agv/task/stat')
export const createAgvTask = (data) => request.post('/agv/task/create', data)
export const cancelAgvTask = (id) => request.put(`/agv/task/cancel/${id}`)
export const updateAgvTaskPriority = (id, priority) => request.put(`/agv/task/priority/${id}`, { priority })

// ==================== AGV 异常模拟（仅管理员/设备运维员） ====================
// type: fault故障停机 / lowBattery电量不足 / block通道堵塞 / pickFail取货失败
export const injectAgvFault = (agvId, type) => request.put(`/agv/fault/inject/${agvId}/${type}`)
export const repairAgvFault = (agvId) => request.put(`/agv/fault/repair/${agvId}`)
