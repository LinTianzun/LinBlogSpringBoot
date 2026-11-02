<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()

function backHome(): void {
  void router.push('/')
}
</script>

<template>
  <div class="lb-not-found">
    <el-result icon="warning" title="404" sub-title="页面不存在或已被移除">
      <template #extra>
        <el-button type="primary" @click="backHome">回到首页</el-button>
      </template>
    </el-result>
  </div>
</template>

<style scoped>
.lb-not-found {
  display: flex;
  justify-content: center;
  padding: 100px 0;
}
</style>
