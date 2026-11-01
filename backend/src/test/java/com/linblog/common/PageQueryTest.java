package com.linblog.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PageQueryTest {

    @Test
    void defaults() {
        PageQuery q = new PageQuery();
        assertEquals(1, q.getPage());
        assertEquals(10, q.getPageSize());
        assertEquals("", q.getKeyword());
        assertEquals(0, q.offset());
    }

    @Test
    void settersAndOffset() {
        PageQuery q = new PageQuery();
        q.setPage(3);
        q.setPageSize(50);
        assertEquals(100, q.offset());
    }

    @Test
    void nullSettersKeepDefaults() {
        PageQuery q = new PageQuery();
        q.setPage(null);
        q.setPageSize(null);
        q.setKeyword(null);
        assertEquals(1, q.getPage());
        assertEquals(10, q.getPageSize());
        assertEquals("", q.getKeyword());
    }

    @Test
    void keywordTrimmedAndTruncatedTo100() {
        PageQuery q = new PageQuery();
        q.setKeyword("  hello world  ");
        assertEquals("hello world", q.getKeyword());

        q.setKeyword("x".repeat(150));
        assertEquals(100, q.getKeyword().length());

        q.setKeyword("   ");
        assertEquals("", q.getKeyword());
    }

    @Test
    void pageResultOfNullRecords() {
        PageResult<String> r = PageResult.of(null, 5);
        assertEquals(0, r.records().size());
        assertEquals(5, r.total());
    }
}
