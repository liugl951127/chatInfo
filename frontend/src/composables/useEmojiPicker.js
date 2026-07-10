/**
 * useEmojiPicker.js - 表情快速回复 composable.
 * ----------------------------------------------------------------------------
 * 用途: Customer/Agent 共享的表情选择器 (64 个常用 emoji).
 *
 * Returns:
 *   - emojiOpen: Ref<boolean>      popover 显隐
 *   - insertEmoji: (e, draftRef)   追加 emoji 到 draft (支持 string / ref / 函数)
 *   - EMOJI_LIST: 64 个常用 emoji  (常量, 可被 ChatComposer 用 v-for 渲染)
 *
 * 使用:
 *   const { emojiOpen, insertEmoji, EMOJI_LIST } = useEmojiPicker()
 *   insertEmoji('😀', draft)   // draft 是 ref 或 function
 */
import { ref } from 'vue'

/** 64 个常用 emoji (拆分后直接用于 v-for 渲染) */
export const EMOJI_LIST = (
  '😀😁😂🤣😃😄😅😆😉😊😋😎😍😘🥰😗😙😚🙂🤗🤩🤔🤨😐😑😶🙄😏😣😥😮🤐😯😪😫😴😌😛😜😝🤤😒😓😔😕🙃🤑😲☹️🙁😖😞😟😤😢😭😦😧😨😩🤯😬😰😱🥵🥶😳🤪😵😡😠🤬😷🤒🤕🤢🤮🤧🥳🥺🤠🤡🤥🤫🤭🧐🤓👻💀☠️👽👾🤖💩❤️🧡💛💚💙💜🖤🤍🤎💔❣️💕💞💓💗💖💘💝👍👎👌✌️🤞🤟🤘🤙👈👉👆👇✋🤚🖐️🖖👋🤝🙏💪🦾'
).split('')

export function useEmojiPicker() {
  const emojiOpen = ref(false)
  const insertEmoji = (e, draftRef) => {
    if (typeof draftRef === 'function') draftRef(draftRef.value || '' + e)
    else if (draftRef && 'value' in draftRef) draftRef.value = (draftRef.value || '') + e
    else if (typeof draftRef === 'string') return draftRef + e
    emojiOpen.value = false
  }
  return { emojiOpen, insertEmoji, EMOJI_LIST }
}