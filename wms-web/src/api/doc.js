import request from '../utils/request'

// 单据中心
export const pageDoc = (params) => request.get('/doc/page', { params })
export const cancelDoc = (data) => request.put('/doc/cancel', data)
