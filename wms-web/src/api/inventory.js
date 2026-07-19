import request from '../utils/request'

// 库存管理
export const getInventorySummary = (params) => request.get('/inventory/summary', { params })
export const getInventoryByLocation = (params) => request.get('/inventory/by-location', { params })
export const getInventoryLogs = (params) => request.get('/inventory/logs', { params })
export const moveInventory = (data) => request.post('/inventory/move', data)
export const pageMoveOrder = (params) => request.get('/inventory/move/page', { params })
export const getWarnings = () => request.get('/inventory/warnings')
export const freezeInventory = (data) => request.put('/inventory/freeze', data)
export const unfreezeInventory = (data) => request.put('/inventory/unfreeze', data)
