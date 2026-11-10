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
            "test-run", new BuiltinPrompt(
                    "test-run", "提示词试跑", "AI 管理页验证模板效果的通用试跑",
                    "{{prompt}} 模板渲染后的完整提示词",
                    String.join("\n",
                            "你是一名通用中文助手。请严格按用户消息中的指令完成任务，直接输出结果，不要添加解释。")));
}
