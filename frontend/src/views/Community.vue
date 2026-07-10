<script setup>
/**
 * Community.vue - 客户社区主页 (V3 阶段 3).
 * ----------------------------------------------------------------------------
 * 列表 + 发帖 + 回复 + 点赞 (简化 MVP).
 */
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ChatLineRound, View, Star, EditPen } from '@element-plus/icons-vue'
import { communityApi } from '@/api/community'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()
const posts = ref([])
const loading = ref(false)
const selectedCategory = ref('ALL')
const showCreate = ref(false)
const newTitle = ref('')
const newContent = ref('')
const newCategory = ref('QA')
const categories = [
  { value: 'ALL', label: '全部' },
  { value: 'QA', label: '问答' },
  { value: 'EXPERIENCE', label: '经验' },
  { value: 'FEEDBACK', label: '反馈' },
]

async function load() {
  loading.value = true
  try {
    const r = selectedCategory.value === 'ALL'
      ? await communityApi.listRecent(20)
      : await communityApi.listByCategory(selectedCategory.value, 20)
    if (r.code === 200) posts.value = r.data
  } catch (e) { console.error(e) }
  finally { loading.value = false }
}

onMounted(load)

async function onCreate() {
  if (!newTitle.value || !newContent.value) {
    ElMessage.warning('请填写标题和内容')
    return
  }
  const r = await communityApi.createPost(newTitle.value, newContent.value, newCategory.value)
  if (r.code === 200) {
    ElMessage.success('发布成功')
    showCreate.value = false
    newTitle.value = ''
    newContent.value = ''
    load()
  } else {
    ElMessage.error(r.message || '发布失败')
  }
}

async function onLike(id) {
  await communityApi.like(id)
  load()
}

async function onReply(post) {
  try {
    const { value } = await ElMessageBox.prompt('输入您的回复', `回复: ${post.title}`, {
      confirmButtonText: '回复',
      cancelButtonText: '取消',
    })
    if (value) {
      await communityApi.reply(post.id, value)
      ElMessage.success('回复成功')
      load()
    }
  } catch (e) { /* cancel */ }
}

function timeAgo(t) {
  if (!t) return ''
  const d = (Date.now() - new Date(t).getTime()) / 1000
  if (d < 60) return '刚刚'
  if (d < 3600) return Math.floor(d / 60) + ' 分钟前'
  if (d < 86400) return Math.floor(d / 3600) + ' 小时前'
  return Math.floor(d / 86400) + ' 天前'
}
</script>

<template>
  <div class="community-shell" v-loading="loading">
    <header class="cm-header">
      <h2>客户帮</h2>
      <el-button type="primary" :icon="EditPen" @click="showCreate = true">发帖</el-button>
    </header>
    <div class="cm-categories">
      <span v-for="c in categories" :key="c.value"
            class="cm-cat" :class="{ active: selectedCategory === c.value }"
            @click="selectedCategory = c.value; load()">
        {{ c.label }}
      </span>
    </div>
    <div class="cm-list">
      <div v-for="p in posts" :key="p.id" class="cm-post">
        <div class="cm-post-header">
          <el-tag size="small" :type="p.category === 'QA' ? 'primary' : (p.category === 'FEEDBACK' ? 'warning' : 'success')">
            {{ p.category }}
          </el-tag>
          <span class="cm-post-title">{{ p.title }}</span>
        </div>
        <div class="cm-post-content">{{ p.content }}</div>
        <div class="cm-post-footer">
          <span>👤 用户 #{{ p.userId }} · {{ timeAgo(p.createdAt) }}</span>
          <span class="cm-stat"><el-icon><View /></el-icon> {{ p.viewCount || 0 }}</span>
          <span class="cm-stat" @click="onReply(p)"><el-icon><ChatLineRound /></el-icon> {{ p.replyCount || 0 }}</span>
          <span class="cm-stat" @click="onLike(p.id)"><el-icon><Star /></el-icon> {{ p.likeCount || 0 }}</span>
        </div>
      </div>
      <el-empty v-if="!loading && posts.length === 0" description="暂无帖子, 来发第一个吧!" />
    </div>

    <!-- 发帖弹窗 -->
    <el-dialog v-model="showCreate" title="发帖" width="500px">
      <el-form>
        <el-form-item label="标题">
          <el-input v-model="newTitle" maxlength="200" show-word-limit />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="newCategory">
            <el-option label="问答" value="QA" />
            <el-option label="经验" value="EXPERIENCE" />
            <el-option label="反馈" value="FEEDBACK" />
          </el-select>
        </el-form-item>
        <el-form-item label="内容">
          <el-input v-model="newContent" type="textarea" :rows="6" maxlength="5000" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreate = false">取消</el-button>
        <el-button type="primary" @click="onCreate">发布</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.community-shell {
  max-width: 800px;
  margin: 0 auto;
  padding: 16px;
}
.cm-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 12px 0;
  border-bottom: 2px solid #409EFF;
  margin-bottom: 16px;
}
.cm-header h2 { margin: 0; color: #303133; }
.cm-categories {
  display: flex; gap: 8px; margin-bottom: 16px;
}
.cm-cat {
  padding: 6px 14px;
  border-radius: 16px;
  font-size: 13px;
  cursor: pointer;
  background: #f5f7fa;
  color: #606266;
  transition: all 0.2s;
}
.cm-cat:hover { background: #ecf5ff; }
.cm-cat.active {
  background: #409EFF;
  color: #fff;
}
.cm-list { display: flex; flex-direction: column; gap: 12px; }
.cm-post {
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 16px;
  transition: all 0.2s;
}
.cm-post:hover {
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
  border-color: #c6e2ff;
}
.cm-post-header { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.cm-post-title { font-size: 16px; font-weight: 600; color: #303133; }
.cm-post-content {
  color: #606266;
  font-size: 14px;
  line-height: 1.6;
  margin-bottom: 12px;
  display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical;
  overflow: hidden;
}
.cm-post-footer {
  display: flex; gap: 16px; align-items: center;
  font-size: 12px; color: #909399;
}
.cm-stat {
  display: inline-flex; align-items: center; gap: 2px;
  cursor: pointer;
}
.cm-stat:hover { color: #409EFF; }
</style>