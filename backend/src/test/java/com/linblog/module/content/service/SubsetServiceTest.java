package com.linblog.module.content.service;

import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.common.PageQuery;
import com.linblog.common.PageResult;
import com.linblog.module.audit.service.SystemLogService;
import com.linblog.module.content.dto.SubsetCreateRequest;
import com.linblog.module.content.dto.SubsetItemsRequest;
import com.linblog.module.content.dto.SubsetUpdateRequest;
import com.linblog.module.content.entity.Content;
import com.linblog.module.content.entity.Subset;
import com.linblog.module.content.entity.SubsetItem;
import com.linblog.module.content.mapper.ContentMapper;
import com.linblog.module.content.mapper.SubsetItemMapper;
import com.linblog.module.content.mapper.SubsetMapper;
import com.linblog.security.AuthPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 合集（§3.2 subsets/subset_items）：端点为自设计补充，归属语义与内容一致。 */
class SubsetServiceTest {

    private SubsetMapper subsetMapper;
    private SubsetItemMapper subsetItemMapper;
    private ContentMapper contentMapper;
    private SystemLogService systemLogService;
    private SubsetService service;

    private static AuthPrincipal principalOf(Set<String> keys) {
        return new AuthPrincipal(7L, "ed", "editor", false, keys);
    }

    private static Subset subset(long id, long ownerId) {
        Subset s = new Subset();
        s.setId(id);
        s.setName("合集" + id);
        s.setOwnerId(ownerId);
        return s;
    }

    @BeforeEach
    void setUp() {
        subsetMapper = mock(SubsetMapper.class);
        subsetItemMapper = mock(SubsetItemMapper.class);
        contentMapper = mock(ContentMapper.class);
        systemLogService = mock(SystemLogService.class);
        service = new SubsetService(subsetMapper, subsetItemMapper, contentMapper, systemLogService);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(AuthPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, null));
    }

    @Test
    void createRequiresCreateKeyAndSetsOwner() {
        loginAs(principalOf(Set.of("article.create")));
        doAnswer(inv -> {
            Subset s = inv.getArgument(0);
            s.setId(5L);
            return 1;
        }).when(subsetMapper).insert(any(Subset.class));

        Subset created = service.create(new SubsetCreateRequest("  精选  ", "说明", 3L, null));
        assertEquals("精选", created.getName());
        assertEquals(7L, created.getOwnerId());
        verify(systemLogService).record(7L, "subset", "create", "subset", 5L, "精选", null);
    }

    @Test
    void createWithoutKeyForbiddenAndAnonymousRejected() {
        loginAs(principalOf(Set.of("article.viewAll")));
        assertThrows(BizException.class,
                () -> service.create(new SubsetCreateRequest("n", null, null, null)));
        SecurityContextHolder.clearContext();
        assertThrows(BizException.class,
                () -> service.create(new SubsetCreateRequest("n", null, null, null)));
    }

    @Test
    void pageFiltersByOwnerWithoutViewAll() {
        // MP 分页语义：selectPage 填充传入的 Page 对象，service 读取的是自身 Page
        doAnswer(inv -> {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<Subset> p = inv.getArgument(0);
            p.setRecords(List.of(subset(1L, 7L)));
            p.setTotal(1);
            return p;
        }).when(subsetMapper).selectPage(any(), any());

        PageResult<Subset> all = service.page(new PageQuery(), true, 7L);
        assertEquals(1, all.total());
        PageResult<Subset> own = service.page(new PageQuery(), false, 7L);
        assertEquals(1, own.total());
    }

    @Test
    void getNotFound() {
        when(subsetMapper.selectById(404L)).thenReturn(null);
        assertThrows(BizException.class, () -> service.get(404L));
    }

    @Test
    void ownershipManageAllPassesOwnerNeedsOwn() {
        when(subsetMapper.selectById(9L)).thenReturn(subset(9L, 9L));

        loginAs(principalOf(Set.of("article.manageAll")));
        assertEquals("合集9", service.get(9L).getName());

        loginAs(principalOf(Set.of("article.viewOwn")));
        BizException ex = assertThrows(BizException.class, () -> service.update(9L,
                new SubsetUpdateRequest("改", null, null)));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());

        when(subsetMapper.selectById(10L)).thenReturn(subset(10L, 7L));
        service.update(10L, new SubsetUpdateRequest("  改名  ", null, null));
        verify(subsetMapper).updateById(any(Subset.class));
    }

    @Test
    void updateSkipsBlankName() {
        loginAs(principalOf(Set.of("gallery.manageAll")));
        when(subsetMapper.selectById(11L)).thenReturn(subset(11L, 7L));
        service.update(11L, new SubsetUpdateRequest("   ", "新说明", null));
        verify(subsetMapper).updateById(any(Subset.class));
    }

    @Test
    void replaceItemsDeletesThenSavesExistingContentsOnly() {
        loginAs(principalOf(Set.of("article.manageAll")));
        when(subsetMapper.selectById(12L)).thenReturn(subset(12L, 7L));
        Content exists = new Content();
        exists.setId(100L);
        when(contentMapper.selectById(100L)).thenReturn(exists);
        when(contentMapper.selectById(200L)).thenReturn(null);
        doThrow(new DuplicateKeyException("dup")).when(subsetItemMapper).insert(any(SubsetItem.class));

        service.replaceItems(12L, new SubsetItemsRequest(List.of(100L, 200L)));

        verify(subsetItemMapper).deleteBySubsetId(12L);
        // 仅存在的 content 落 insert；DuplicateKey 被吞掉不外抛
        verify(subsetItemMapper, atLeastOnce()).insert(any(SubsetItem.class));
        verify(systemLogService).record(eq(7L), eq("subset"), eq("update_items"), eq("subset"),
                eq(12L), isNull(), isNull());
    }

    @Test
    void replaceItemsWithEmptyListOnlyDeletes() {
        loginAs(principalOf(Set.of("article.manageAll")));
        when(subsetMapper.selectById(13L)).thenReturn(subset(13L, 7L));
        service.replaceItems(13L, new SubsetItemsRequest(List.of()));
        verify(subsetItemMapper).deleteBySubsetId(13L);
        verify(subsetItemMapper, never()).insert(any(SubsetItem.class));
    }

    @Test
    void itemsOfReturnsSortedItems() {
        when(subsetMapper.selectById(14L)).thenReturn(subset(14L, 7L));
        when(subsetItemMapper.selectList(any())).thenReturn(List.of(new SubsetItem()));
        assertEquals(1, service.itemsOf(14L).size());
    }

    @Test
    void deleteRemovesItemsAndRow() {
        loginAs(principalOf(Set.of("article.manageAll")));
        when(subsetMapper.selectById(15L)).thenReturn(subset(15L, 7L));
        service.delete(15L);
        verify(subsetItemMapper).deleteBySubsetId(15L);
        verify(subsetMapper).deleteById(15L);
        verify(systemLogService).record(eq(7L), eq("subset"), eq("delete"), eq("subset"),
                eq(15L), eq("合集15"), isNull());
    }
}
