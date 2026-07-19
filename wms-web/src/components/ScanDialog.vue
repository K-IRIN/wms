<template>
  <el-dialog v-model="visible" title="摄像头扫码" width="420px" destroy-on-close @close="stop" append-to-body>
    <div v-if="error" class="scan-error">
      <el-alert type="error" :title="error" :closable="false" />
      <p class="tip">提示：需要允许浏览器使用摄像头；若无摄像头，可直接在输入框手动输入编号。</p>
    </div>
    <div id="qr-reader" class="qr-reader"></div>
    <p class="tip">将二维码对准摄像头，识别成功后自动返回结果</p>
  </el-dialog>
</template>

<script setup>
import { ref, watch, nextTick, onBeforeUnmount } from 'vue'
import { Html5Qrcode } from 'html5-qrcode'

const props = defineProps({ modelValue: Boolean })
const emit = defineEmits(['update:modelValue', 'scan'])

const visible = ref(false)
const error = ref('')
let scanner = null

watch(() => props.modelValue, async (v) => {
  visible.value = v
  if (v) {
    error.value = ''
    await nextTick()
    start()
  }
})
watch(visible, (v) => emit('update:modelValue', v))

async function start() {
  try {
    scanner = new Html5Qrcode('qr-reader')
    await scanner.start(
      { facingMode: 'environment' },
      { fps: 10, qrbox: { width: 220, height: 220 } },
      (text) => {
        emit('scan', text)
        stop()
        visible.value = false
      },
      () => { /* 识别中的空帧，忽略 */ }
    )
  } catch (e) {
    error.value = '摄像头打开失败：' + (e?.message || e)
  }
}

async function stop() {
  if (scanner) {
    try {
      await scanner.stop()
      scanner.clear()
    } catch (e) { /* 忽略 */ }
    scanner = null
  }
}

onBeforeUnmount(stop)
</script>

<style scoped>
.qr-reader {
  width: 100%;
  min-height: 240px;
  border-radius: 8px;
  overflow: hidden;
  background: #000;
}
.tip {
  color: #909399;
  font-size: 12px;
  margin: 8px 0 0;
}
.scan-error {
  margin-bottom: 8px;
}
</style>
