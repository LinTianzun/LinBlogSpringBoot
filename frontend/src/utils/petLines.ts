/**
 * petLines.ts — BlogPet 的台词库：碎碎念 / 戳一戳 / 被拎起 / 被甩飞 / 睡醒 / 梦话。
 * 纯数据 + pickLine 纯函数，方便单测；语气走 editorial：短、口语、不过度卖萌。
 */

export const IDLE_LINES: readonly string[] = [
  '今天也要好好写字哦。',
  '墨水瓶好像又快见底了……',
  '博客更新慢是常态，别催它。',
  '我在帮你盯着访客呢。',
  '这里挺舒服的，纸是暖的。',
  '咦，刚才是不是有人路过？',
  '摸摸我可以，要轻一点。',
  '前端要是报错，可不赖我。',
]

export const POKE_LINES: readonly string[] = [
  '戳戳，嘟。',
  '别戳啦，痒！',
  '嘿嘿，被发现了。',
  '再戳要收费的哦。',
  '唔——怎么了？',
]

export const LIFT_LINES: readonly string[] = [
  '哇——放我下来！',
  '拎小动物之前要打招呼！',
  '呼，风好大。',
]

export const THROW_LINES: readonly string[] = [
  '啊啊啊——',
  '我要飞啦——！',
  '稳住，能落地！',
]

export const WAKE_LINES: readonly string[] = [
  '……嗯？我睡着了吗？',
  '呼啊——几点了？',
  '梦到好多小鱼干。',
]

export const SLEEP_LINES: readonly string[] = [
  'z……z……',
  'zzz……别关灯……',
]

/** 从台词里随机挑一句；rng 可注入以便测试 */
export function pickLine(lines: readonly string[], rng: () => number = Math.random): string {
  if (lines.length === 0) return ''
  return lines[Math.floor(rng() * lines.length)] ?? lines[0]
}
