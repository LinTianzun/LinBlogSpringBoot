<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()

function backHome(): void {
  void router.push('/')
}
</script>

<template>
  <div class="lb-not-found">
    <n-result status="404" title="404" description="页面不存在或已被移除">
      <template #footer>
        <n-button type="primary" @click="backHome">回到首页</n-button>
      </template>
    </n-result>
  </div>
</template>

<style scoped>
.lb-not-found {
  display: flex;
  justify-content: center;
  padding: 100px 0;
}
</style>
