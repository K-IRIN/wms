import request from '../utils/request'

// 盘点
export const pageCheck = (params) => request.get('/check/page', { params })
export const getCheckDetail = (id) => request.get(`/check/detail/${id}`)
export const createCheck = (data) => request.post('/check/create', data)
export const entryCheck = (data) => request.put('/check/entry', data)
export const submitCheck = (id) => request.put(`/check/submit/${id}`)
export const auditCheck = (data) => request.put('/check/audit', data)
export const exportCheck = (params) => request.get('/check/export', { params, responseType: 'blob' })
