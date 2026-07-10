<script setup>
/**
 * SentimentIndicator.vue - 情感指示器.
 * 显示在输入框上方, 实时显示客户当前输入的情感.
 */
import { computed, watch } from 'vue'
import { useSentiment } from '@/composables/useSentiment'

const props = defineProps({
  text: { type: String, default: '' },
})

const { score, label, confidence, color, emoji, analyze } = useSentiment()

watch(() => props.text, (t) => analyze(t), { immediate: true })

const visible = computed(() => props.text && props.text.length > 1 && label.value !== 'neutral')

const labelText = computed(() => {
  const l = { angry: '愤怒', sad: '低落', happy: '满意', neutral: '中性' }[label.value]
  return `${emoji.value} ${l} (${(confidence * 100).toFixed(0)}%)`
})
</script>

<template>
  <Transition name="sentiment">
    <div v-if="visible" class="sentiment-indicator" :style="{ color, borderColor: color }">
      <span>{{ labelText }}</span>
    </div>
  </Transition>
</template>

<style scoped>
.sentiment-indicator {
  display: inline-flex;
  align-items: center;
  padding: 2px 10px;
  font-size: 11px;
  border: 1px solid;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.9);
  position: absolute;
  right: 12px;
  top: -22px;
  z-index: 5;
}
.sentiment-enter-active, .sentiment-leave-active {
  transition: all 0.2s;
}
.sentiment-enter-from, .sentiment-leave-to {
  opacity: 0;
  transform: translateY(4px);
}
</style>