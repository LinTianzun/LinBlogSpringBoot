package com.linblog.module.ai.service;

import java.util.Map;

/**
 * 内置提示词（自原版 LinBlog aiPromptTemplates 移植，措辞保持一致）。
 * 模板被停用或写坏时回退到这里，保证功能不因模板损坏不可用。
 */
public final class AiBuiltInPrompts {

    private AiBuiltInPrompts() {
    }

    public record BuiltinPrompt(String templateKey, String name, String description,
                                String variables, String content) {
    }

    public static final Map<String, BuiltinPrompt> ALL = Map.of(
            "suggest", new BuiltinPrompt(
                    "suggest", "写作建议", "根据文章标题与正文生成简介和标签",
                    "{{title}} 文章标题\n{{content}} 正文纯文本",
                    String.join("\n",
                            "你是一名中文博客编辑助手。请根据文章标题和正文，生成一段面向读者的简介和 2-4 个内容标签。",
                            "要求：",
                            "1. 简介不超过 120 字，概括文章主题，不夸大、不剧透细节；",
                            "2. 标签使用简短名词短语，贴合文章主题，不要重复标题里已有的完整短语；",
                            "3. 只输出一个 JSON 对象，格式为 {\"summary\":\"...\",\"labels\":[\"...\"]}，不要输出任何解释或 Markdown 代码块。",
                            "",
                            "标题：{{title}}",
                            "正文：{{content}}")),
            "writing", new BuiltinPrompt(
                    "writing", "写作辅助", "润色、精简、扩写与续写正文",
                    "{{action}} 动作标识（polish/shorten/expand/continue）\n{{actionLabel}} 动作中文名\n{{title}} 文章标题（可为空）\n{{text}} 原文片段",
                    String.join("\n",
                            "你是一名中文博客编辑助手。请根据要求改写或续写用户提供的正文片段。",
                            "{{requirement}}",
                            "2. 只输出处理后的纯文本，不要解释、标题、Markdown 代码块或引号；",
                            "3. 保留原文语言，使用博客可读的自然段落；",
                            "",
                            "{{titleLine}}",
                            "原文片段：{{text}}")),
            "gallery", new BuiltinPrompt(
                    "gallery", "图库策展", "为摄影图库生成候选标题、简介与标签",
                    "{{title}} 当前标题\n{{introduce}} 当前简介\n{{theme}} 作者补充主题\n{{photoCount}} 照片数量",
                    String.join("\n",
                            "你是一名中文摄影图库策展编辑。请根据当前图库信息和作者补充主题，给出候选元信息。",
                            "要求：",
                            "1. 给出 3 个候选标题，每个不超过 24 字，风格一致但表达角度不同；",
                            "2. 给出一段不超过 160 字的策展简介，说明画面氛围、主题与适合的浏览情境；",
                            "3. 给出 2-4 个简短标签，不要编造照片中无法判断的事实；",
                            "4. 只输出一个 JSON 对象，格式为 {\"titles\":[\"...\"],\"summary\":\"...\",\"labels\":[\"...\"]}，不要输出解释或 Markdown 代码块。",
                            "",
                            "当前标题：{{title}}",
                            "当前简介：{{introduce}}",
                            "作者补充主题：{{theme}}",
                            "照片数量：{{photoCount}}")),
            "report-review", new BuiltinPrompt(
                    "report-review", "举报辅助", "对举报内容给出处置建议",
                    "{{reasonLabel}} 读者选择的举报原因\n{{detail}} 读者补充说明\n{{commentUserName}} 被举报评论作者\n{{commentContent}} 被举报评论内容\n{{sourceTitle}} 所在内容标题\n{{complaint}} 累计被举报次数",
                    String.join("\n",
                            "你是一名中文内容社区的审核助理。请根据举报信息给出处置建议。",
                            "要求：",
                            "1. 判断举报是否成立，只能取 \"成立\"、\"部分成立\"、\"不成立\" 三种；",
                            "2. 给出一个更贴切的举报原因，只能取：spam（广告或引流）、abuse（辱骂或攻击）、porn（色情或低俗）、illegal（违法或危险）、misinformation（虚假或误导）、other（其他原因）；",
                            "3. 建议处置只能取：process（删除评论并标记已处理）、dismiss（忽略举报）、warn（保留评论但标记已处理）；",
                            "4. 写一段不超过 80 字的处理备注，说明判断依据；",
                            "5. 只输出一个 JSON 对象，格式为 {\"verdict\":\"...\",\"reason\":\"...\",\"action\":\"...\",\"note\":\"...\"}，不要输出解释或 Markdown 代码块。",
                            "",
                            "举报原因（读者选择）：{{reasonLabel}}",
                            "读者补充说明：{{detail}}",
                            "被举报评论作者：{{commentUserName}}",
                            "被举报评论内容：{{commentContent}}",
                            "所在内容标题：{{sourceTitle}}",
                            "累计被举报次数：{{complaint}}")),
            "operations", new BuiltinPrompt(
                    "operations", "运营周报", "根据真实统计数据组织周报文字",
                    "{{days}} 统计周期天数\n{{visits}} 页面访问次数\n{{comments}} 新增评论数\n{{messages}} 新增私信数\n{{contents}} 内容总数\n{{topPaths}} 热门路径",
                    String.join("\n",
                            "你是一名中文博客运营助理。请根据下面这一周的站点统计数据写一份简短的运营周报。",
                            "要求：",
                            "1. 只使用给出的数字，不要编造任何未提供的数据或对比结论；",
                            "2. 输出 3-4 个小节标题（如「本周概况」「内容表现」「互动情况」「下周建议」），每节 1-3 句话；",
                            "3. 语气客观平实，数字变化只做陈述，不要夸大；",
                            "4. 不要输出 Markdown 标记（#、*、-），用纯文本小标题与句子；",
                            "5. 只输出周报正文，不要额外解释。",
                            "",
                            "统计周期：最近 {{days}} 天",
                            "内容总数：{{contents}}，新增评论：{{comments}} 条，新增私信：{{messages}} 条",
                            "页面访问：{{visits}} 次",
                            "热门路径：{{topPaths}}")),
            "message", new BuiltinPrompt(
                    "message", "私信拟回复", "根据对话上下文生成候选回复话术",
                    "{{peerName}} 对话对方\n{{history}} 最近对话\n{{tone}} 作者期望的语气（可为空）",
                    String.join("\n",
                            "你是一名中文博客博主，正在回复读者的私信。请根据对话上下文给出候选回复。",
                            "要求：",
                            "1. 给出 2 条候选回复，语气不同：一条热情友好，一条简洁克制；",
                            "2. 每条不超过 120 字，口语化、直接，不要客套长文；",
                            "3. 不要编造事实、不要承诺做不到的事，也不要替作者做决定；",
                            "4. 只输出一个 JSON 对象，格式为 {\"replies\":[\"...\",\"...\"]}，不要输出解释或 Markdown 代码块。",
                            "{{toneLine}}",
                            "",
                            "对话对方：{{peerName}}",
                            "最近对话：",
                            "{{history}}")),
            "test-run", new BuiltinPrompt(
                    "test-run", "提示词试跑", "AI 管理页验证模板效果的通用试跑",
                    "{{prompt}} 模板渲染后的完整提示词",
                    String.join("\n",
                            "你是一名通用中文助手。请严格按用户消息中的指令完成任务，直接输出结果，不要添加解释。")));
}
