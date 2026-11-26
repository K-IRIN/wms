import request from '../utils/request'

// ==================== 仓库 ====================
export const pageWarehouse = (params) => request.get('/base/warehouse/page', { params })
export const listAllWarehouse = () => request.get('/base/warehouse/all')
export const addWarehouse = (data) => request.post('/base/warehouse/add', data)
export const updateWarehouse = (data) => request.put('/base/warehouse/update', data)
export const deleteWarehouse = (id) => request.delete(`/base/warehouse/${id}`)
export const updateWarehouseStatus = (id, status) => request.put(`/base/warehouse/status/${id}/${status}`)

// ==================== 库区库位 ====================
export const getLocationTree = () => request.get('/base/location/tree')
export const listAllLocation = () => request.get('/base/location/all')
export const addArea = (data) => request.post('/base/location/area/add', data)
export const updateArea = (data) => request.put('/base/location/area/update', data)
export const deleteArea = (id) => request.delete(`/base/location/area/${id}`)
export const addLocation = (data) => request.post('/base/location/add', data)
export const updateLocation = (data) => request.put('/base/location/update', data)
export const deleteLocation = (id) => request.delete(`/base/location/${id}`)
// 库位库存明细（库位 → 物料列表 + 合计）
export const getLocationStock = (id) => request.get(`/base/location/${id}/stock`)

// ==================== 物料分类 ====================
export const getCategoryTree = () => request.get('/base/material/category/tree')
export const getCategoryCounts = () => request.get('/base/material/category/counts')
export const addCategory = (data) => request.post('/base/material/category/add', data)
export const updateCategory = (data) => request.put('/base/material/category/update', data)
export const deleteCategory = (id) => request.delete(`/base/material/category/${id}`)

// ==================== 物料 ====================
export const pageMaterial = (params) => request.get('/base/material/page', { params })
export const addMaterial = (data) => request.post('/base/material/add', data)
export const updateMaterial = (data) => request.put('/base/material/update', data)
export const deleteMaterial = (id) => request.delete(`/base/material/${id}`)
export const updateMaterialStatus = (id, status) => request.put(`/base/material/status/${id}/${status}`)
export const importMaterial = (file) => {
  const fd = new FormData()
  fd.append('file', file)
  return request.post('/base/material/import', fd)
}
export const exportMaterial = () => request.get('/base/material/export', { responseType: 'blob' })

// ==================== 供应商 ====================
export const pageSupplier = (params) => request.get('/base/supplier/page', { params })
export const listAllSupplier = () => request.get('/base/supplier/all')
export const addSupplier = (data) => request.post('/base/supplier/add', data)
export const updateSupplier = (data) => request.put('/base/supplier/update', data)
export const updateSupplierStatus = (id, status) => request.put(`/base/supplier/status/${id}/${status}`)
export const importSupplier = (file) => {
  const fd = new FormData()
  fd.append('file', file)
  return request.post('/base/supplier/import', fd)
}
export const exportSupplier = () => request.get('/base/supplier/export', { responseType: 'blob' })

// ==================== 客户 ====================
export const pageCustomer = (params) => request.get('/base/customer/page', { params })
export const listAllCustomer = () => request.get('/base/customer/all')
export const addCustomer = (data) => request.post('/base/customer/add', data)
export const updateCustomer = (data) => request.put('/base/customer/update', data)
export const updateCustomerStatus = (id, status) => request.put(`/base/customer/status/${id}/${status}`)
export const importCustomer = (file) => {
  const fd = new FormData()
  fd.append('file', file)
  return request.post('/base/customer/import', fd)
}
export const exportCustomer = () => request.get('/base/customer/export', { responseType: 'blob' })

// 通用：触发浏览器下载 blob 文件
export function downloadBlob(response, fallbackName) {
  const disposition = response.headers['content-disposition'] || ''
  const match = disposition.match(/filename\*=utf-8''([^;]+)/i)
  const fileName = match ? decodeURIComponent(match[1]) : fallbackName
  const url = URL.createObjectURL(response.data)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  a.click()
  URL.revokeObjectURL(url)
}
