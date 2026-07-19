import request from '../utils/request'

// 入库单
export const pageInbound = (params) => request.get('/inbound/page', { params })
export const getInboundDetail = (id) => request.get(`/inbound/detail/${id}`)
export const createInbound = (data) => request.post('/inbound/create', data)
export const updateInbound = (data) => request.put('/inbound/update', data)
export const qualityInbound = (data) => request.put('/inbound/quality', data)
export const scanMaterial = (code) => request.get(`/inbound/scan/${code}`)
export const recommendLocation = (materialId, warehouseId) =>
  request.get('/inbound/recommend-location', { params: { materialId, warehouseId } })
export const freeLocations = (warehouseId) =>
  request.get('/inbound/free-locations', { params: { warehouseId } })
export const shelfInbound = (data) => request.put('/inbound/shelf', data)
export const cancelShelfInbound = (itemId) => request.put(`/inbound/cancel-shelf/${itemId}`)
export const auditInbound = (data) => request.put('/inbound/audit', data)
export const exportInbound = (params) => request.get('/inbound/export', { params, responseType: 'blob' })
