/**
 * useSentiment.js - 情感分析 composable.
 * ----------------------------------------------------------------------------
 * 数据流:
 *   1. 优先调后端 /api/ai/sentiment (Java 自研, 多语言支持)
 *   2. 后端不可达时, 降级为本地关键词匹配 (即时, 0 依赖)
 *
 * 状态: score (-1..1) / label (angry/sad/neutral/happy) / confidence (0..1)
 */
import { ref, computed } from 'vue'
import { aiApi } from '@/api/ai'

/** 本地兜底: 关键词 + 否定 + 程度修饰 */
const POSITIVE = ['好', '棒', '优秀', '满意', '喜欢', '感谢', '谢谢', '赞', '开心', '高兴', '完美', '贴心', '专业', '快速', '高效']
const NEGATIVE = ['差', '烂', '垃圾', '差评', '投诉', '退款', '失望', '生气', '愤怒', '烦', '麻烦', '慢', '卡', '气死', '气人', '讨厌', '可恶', '无语']
const NEGATIONS = ['不', '没', '无', '未', '别']
const INTENSIFIERS = { '很': 1.5, '非常': 2.0, '特别': 1.8, '极': 2.0, '超级': 2.0, '太': 1.8 }

export function useSentiment() {
  const score = ref(0)
  const label = ref('neutral')  // angry / sad / neutral / happy
  const confidence = ref(0)
  const source = ref('local')   // 'remote' / 'local' - 用于调试

  const color = computed(() => {
    if (label.value === 'angry') return '#F56C6C'
    if (label.value === 'sad') return '#E6A23C'
    if (label.value === 'happy') return '#67C23A'
    return '#909399'
  })

  const emoji = computed(() => {
    if (label.value === 'angry') return '😠'
    if (label.value === 'sad') return '😟'
    if (label.value === 'happy') return '😊'
    return '😐'
  })

  /**
   * 分析文本. 优先后端, 失败降级本地.
   * @param {string} text 输入文本
   */
  async function analyze(text) {
    if (!text) { reset(); return }
    // 短文本直接本地, 省一次请求
    if (text.length < 4) {
      analyzeLocal(text)
      return
    }
    try {
      const r = await aiApi.sentiment(text)
      if (r && typeof r.score === 'number') {
        score.value = r.score
        confidence.value = r.confidence ?? 0.5
        // 后端返回 label 转成统一格式
        if (r.sentiment === 'angry' || r.sentiment === 'negative' || r.score < -0.3) {
          label.value = r.score < -0.6 ? 'angry' : 'sad'
        } else if (r.sentiment === 'positive' || r.score > 0.3) {
          label.value = 'happy'
        } else {
          label.value = 'neutral'
        }
        source.value = 'remote'
        return
      }
      analyzeLocal(text)
    } catch (e) {
      // 后端不可达, 降级本地
      analyzeLocal(text)
    }
  }

  /** 本地关键词分析 (降级) */
  function analyzeLocal(text) {
    const toks = tokenize(text)
    let s = 0, hits = 0
    for (let i = 0; i < toks.length; i++) {
      let intens = 1.0
      for (let k = Math.max(0, i - 2); k < i; k++) {
        const v = INTENSIFIERS[toks[k].toLowerCase()]
        if (v) intens = Math.max(intens, v)
      }
      let negated = false
      for (let k = Math.max(0, i - 2); k < i; k++) {
        if (NEGATIONS.includes(toks[k])) { negated = true; break }
      }
      if (POSITIVE.includes(toks[i])) {
        s += intens; hits++
        if (negated) s -= intens * 2
      } else if (NEGATIVE.includes(toks[i])) {
        s -= intens; hits++
        if (negated) s += intens * 2
      }
    }
    if (hits === 0) { reset(); return }
    const norm = Math.max(-1, Math.min(1, s / hits))
    score.value = Math.round(norm * 1000) / 1000
    confidence.value = Math.min(1, hits / 5)
    if (norm < -0.3) label.value = 'angry'
    else if (norm < 0) label.value = 'sad'
    else if (norm > 0.3) label.value = 'happy'
    else label.value = 'neutral'
    source.value = 'local'
  }

  function reset() {
    score.value = 0
    label.value = 'neutral'
    confidence.value = 0
  }

  return { score, label, confidence, color, emoji, source, analyze, reset }
}

/** 中文 + 英文 token 化 (保留 1-2 字中文组合) */
function tokenize(text) {
  const toks = []
  let buf = ''
  let cn = []
  for (const c of text) {
    if (/[a-zA-Z0-9]/.test(c)) {
      flushCn(cn, toks); cn = []
      buf += c
    } else {
      if (buf) { toks.push(buf); buf = '' }
      if (/[\u4e00-\u9fff]/.test(c)) cn.push(c)
      else { flushCn(cn, toks); cn = [] }
    }
  }
  if (buf) toks.push(buf)
  flushCn(cn, toks)
  return toks
}

function flushCn(cn, out) {
  for (const w of cn) out.push(w)
  for (let i = 0; i < cn.length - 1; i++) {
    const bg = cn[i] + cn[i + 1]
    if (!out.includes(bg)) out.push(bg)
  }
}