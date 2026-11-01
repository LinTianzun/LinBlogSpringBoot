package com.linblog.module.comment.dto;

import java.util.List;

/** GET /portal/{targetType}/{id}/comments（§5.5）：一级 + 楼中楼两段（对应原 rootComments/replyComments）。 */
public record CommentThread(List<CommentView> rootComments, List<CommentView> replyComments) {
}
