import request from '../utils/request'

// 二维码
export const materialQrBlob = (id) => request.get(`/qrcode/material/${id}`, { responseType: 'blob' })
export const locationQrBlob = (id) => request.get(`/qrcode/location/${id}`, { responseType: 'blob' })
export const batchMaterialQr = (ids) =>
  request.get('/qrcode/material/batch', { params: { ids: ids.join(',') }, responseType: 'blob' })
export const batchLocationQr = (ids) =>
  request.get('/qrcode/location/batch', { params: { ids: ids.join(',') }, responseType: 'blob' })
export const getLocationStock = (code) => request.get(`/qrcode/location-stock/${code}`)
export const getMaterialByCode = (code) => request.get(`/qrcode/material-info/${code}`)
