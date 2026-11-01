-- V2：内容域（M2）索引补齐。
-- 依据《SpringLinBlog 数据库文档》§6.2 待确认清单与 M2 查询需求：
--   W3：subsets / subset_items 缺二级索引（按归属人/按内容反查合集会全表扫描）
--   W4：conversations 缺 user_b_id 单列索引（会话列表需同时过滤两侧）
--   W5：guestbook 缺 status/created_at 索引（留言墙列表查询）
--   M2：diaries 门户列表按 state 过滤 + diary_date 倒序
ALTER TABLE subsets
    ADD KEY idx_owner (owner_id),
    ADD KEY idx_cover (cover_media_id);

ALTER TABLE subset_items
    ADD KEY idx_content (content_id);

ALTER TABLE conversations
    ADD KEY idx_user_b (user_b_id);

ALTER TABLE guestbook
    ADD KEY idx_status (status, created_at);

ALTER TABLE diaries
    ADD KEY idx_state_date (state, diary_date);
