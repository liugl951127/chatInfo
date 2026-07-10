/**
 * 表情快速回复 (从 Customer/Agent 拆出, 两个页面共享).
 *  - 64 个常用 emoji 列表
 *  - insertEmoji(e): 追加到 draft (由调用方传 draft.value)
 *  - emojiOpen 控制 popover 显隐
 */
import { ref } from 'vue'

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