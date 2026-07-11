/**
 * useDragUpload.js - 拖拽上传 composable.
 * ----------------------------------------------------------------------------
 * 业务场景: 用户拖文件到聊天区域, 自动上传 + 发送.
 *
 * 行为:
 *   - dragenter: 显示遮罩 (高亮)
 *   - dragover: 阻止默认 (允许 drop)
 *   - dragleave: 离开时隐藏遮罩
 *   - drop: 解析 files, 触发 onFiles
 *
 * 用法:
 *   const { isDragging } = useDragUpload(ref(chatRef), (files) => {
 *     files.forEach(f => uploadAndSend(f))
 *   })
 */
import { ref } from 'vue'

export function useDragUpload(targetRef, onFiles) {
  const isDragging = ref(false)
  let counter = 0  // dragenter / leave 配对计数

  function onDragEnter(e) {
    e.preventDefault()
    counter++
    if (counter === 1) isDragging.value = true
  }

  function onDragOver(e) {
    e.preventDefault()
    e.dataTransfer.dropEffect = 'copy'
  }

  function onDragLeave(e) {
    e.preventDefault()
    counter = Math.max(0, counter - 1)
    if (counter === 0) isDragging.value = false
  }

  function onDrop(e) {
    e.preventDefault()
    counter = 0
    isDragging.value = false

    const dt = e.dataTransfer
    if (!dt) return

    // 检查是否拖到内部元素 (text/uri-list 也算)
    const files = []
    if (dt.items) {
      for (const item of dt.items) {
        if (item.kind === 'file') {
          const f = item.getAsFile()
          if (f) files.push(f)
        }
      }
    } else if (dt.files) {
      files.push(...Array.from(dt.files))
    }

    if (files.length > 0) {
      onFiles?.(files)
    }
  }

  function bind() {
    const el = targetRef.value
    if (!el) return
    el.addEventListener('dragenter', onDragEnter)
    el.addEventListener('dragover', onDragOver)
    el.addEventListener('dragleave', onDragLeave)
    el.addEventListener('drop', onDrop)
  }

  function unbind() {
    const el = targetRef.value
    if (!el) return
    el.removeEventListener('dragenter', onDragEnter)
    el.removeEventListener('dragover', onDragOver)
    el.removeEventListener('dragleave', onDragLeave)
    el.removeEventListener('drop', onDrop)
  }

  return {
    isDragging,
    bind,
    unbind,
  }
}