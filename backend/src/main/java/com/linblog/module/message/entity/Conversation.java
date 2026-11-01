package com.linblog.module.message.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** conversations 表实体（§3.4）：user_a_id < user_b_id 配对去重。 */
@TableName("conversations")
public class Conversation {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userAId;
    private Long userBId;
    private LocalDateTime lastMessageAt;
    private Integer aUnread;
    private Integer bUnread;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserAId() {
        return userAId;
    }

    public void setUserAId(Long userAId) {
        this.userAId = userAId;
    }

    public Long getUserBId() {
        return userBId;
    }

    public void setUserBId(Long userBId) {
        this.userBId = userBId;
    }

    public LocalDateTime getLastMessageAt() {
        return lastMessageAt;
    }

    public void setLastMessageAt(LocalDateTime lastMessageAt) {
        this.lastMessageAt = lastMessageAt;
    }

    public Integer getAUnread() {
        return aUnread;
    }

    public void setAUnread(Integer aUnread) {
        this.aUnread = aUnread;
    }

    public Integer getBUnread() {
        return bUnread;
    }

    public void setBUnread(Integer bUnread) {
        this.bUnread = bUnread;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
