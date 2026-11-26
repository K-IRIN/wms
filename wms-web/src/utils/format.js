/**
 * 全站统一的时间格式化：后端返回 ISO 格式（2026-07-20T18:40:05），
 * 显示为 2026-07-20 18:40:05
 */
export function formatTime(t) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : '-'
}

/** 仅日期部分 */
export function formatDate(t) {
  return t ? String(t).slice(0, 10) : '-'
}
