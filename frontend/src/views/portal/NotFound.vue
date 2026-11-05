<template>
  <div class="lb-ed lb-nf">
    <header class="lb-nf-bar">
      <RouterLink to="/" class="lb-nf-mark">LinBlog</RouterLink>
    </header>

    <main class="lb-nf-stage">
      <p class="lb-nf-code">404</p>
      <h1 class="lb-nf-title">这一页不存在。</h1>
      <p class="lb-nf-sub">链接可能已经过期，或者文章被作者悄悄收进了抽屉。</p>
      <div class="lb-nf-actions">
        <RouterLink to="/" class="lb-ed-btn lb-ed-btn--primary">回到首页</RouterLink>
        <RouterLink to="/gallery" class="lb-ed-btn lb-ed-btn--ghost">去逛逛画廊</RouterLink>
      </div>
    </main>
  </div>
</template>

<style scoped>
.lb-nf {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.lb-nf-bar {
  padding: clamp(18px, 3vw, 28px) var(--ed-margin);
}

.lb-nf-mark {
  font-family: var(--ed-font-ui);
  font-size: var(--ed-step-small);
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--ed-ink);
}

.lb-nf-mark:hover {
  opacity: 0.7;
}

.lb-nf-stage {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: flex-start;
  padding: 0 var(--ed-margin) clamp(64px, 10vh, 140px);
  max-width: var(--ed-wrap);
  width: 100%;
  margin: 0 auto;
}

/* 巨型 404：Newsreader 衬线数字即是画面 */
.lb-nf-code {
  margin: 0;
  font-family: var(--ed-font-display);
  font-weight: 300;
  font-size: clamp(7rem, 22vw, 19rem);
  line-height: 0.82;
  letter-spacing: -0.03em;
  color: var(--ed-ink);
  font-variant-numeric: lining-nums;
}

.lb-nf-title {
  margin: clamp(18px, 3vw, 30px) 0 10px;
  font-family: var(--ed-font-display);
  font-weight: 400;
  font-size: var(--ed-step-h2);
  line-height: var(--ed-leading-tight);
  letter-spacing: var(--ed-tracking-display);
  color: var(--ed-ink);
}

.lb-nf-sub {
  margin: 0 0 30px;
  max-width: 46ch;
  font-size: var(--ed-step-small);
  line-height: 1.7;
  color: var(--ed-ink-soft);
}

.lb-nf-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
</style>
