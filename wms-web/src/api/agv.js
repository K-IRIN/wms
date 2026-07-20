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
