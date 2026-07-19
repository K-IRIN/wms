import request from '../utils/request'

// 出库单
export const pageOutbound = (params) => request.get('/outbound/page', { params })
export const getOutboundDetail = (id) => request.get(`/outbound/detail/${id}`)
export const createOutbound = (data) => request.post('/outbound/create', data)
export const updateOutbound = (data) => request.put('/outbound/update', data)
export const auditOutbound = (data) => request.put('/outbound/audit', data)
export const pickOutbound = (data) => request.put('/outbound/pick', data)
export const reviewOutbound = (id) => request.put(`/outbound/review/${id}`)
export const exportOutbound = (params) => request.get('/outbound/export', { params, responseType: 'blob' })
