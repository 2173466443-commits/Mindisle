<template>
  <!-- 笔记卡（2026-09-30 改版：小红书式「封面 + 两行标题 + 作者行」）。
       类名一个没删：article.post / .head / .title / .excerpt / .imgs / .topics / .meta / .stat /
       .act / .who / .hide / .destroy / [data-post-id] 是取证脚本与旧手册共同指着的那套契约，
       这一版改的是它们怎么摆、长什么样，不是它们叫什么。 -->
  <article class="mi-card post" :class="{ 'no-img': !hasImage }" :data-post-id="item.id" @click="goDetail">
    <div class="cover">
      <!-- 封面只画第一张图，其余的进查看器：一墙卡片要等高才像笔记墙，
           把三张缩略图横排下来就退化成表格行了。预览列表仍是全量，翻看得动。 -->
      <div v-if="hasImage" class="imgs" @click.stop>
        <el-image class="img" :src="item.images[0].url" :preview-src-list="previewList"
                  :initial-index="0" fit="cover" preview-teleported hide-on-click-modal />
        <span v-if="item.images.length > 1" class="img-count">共 {{ item.images.length }} 张</span>
      </div>
      <!-- 无图的帖子：把标题当封面排（2026-09-30 改版第 4 条，用户口头指令，参照小红书 explore 的纯文字笔记卡）。
           原先这里是一枚类型符号，理由是「不复读标题」—— 现在标题就摆在封面上，
           所以正文区那行同名标题用 .post.no-img .title 隐掉了（DOM 留着，取证契约不动），
           一张卡上同一句话只出现一次。写不下的用 line-clamp 裁掉，不撑高卡片：
           墙要等高才像墙，让一条长标题把整行顶高，一排卡片就参差了。
           注意这条注释描述的判据（.title 是否显示）和上面那行 :class 是绑在一起的，改一处必须改两处。 -->
      <div v-else class="cover-empty" :class="'tone-' + (item.type || 'normal')">
        <p class="cover-text">{{ item.title }}</p>
        <span class="glyph" aria-hidden="true">{{ coverGlyph }}</span>
      </div>

      <header class="head">
        <el-tag v-if="item.type === 'hole'" size="small" type="warning" effect="dark">树洞</el-tag>
        <el-tag v-else-if="item.type === 'help'" size="small" type="danger" effect="dark">求助</el-tag>
        <el-tag v-else size="small" effect="dark">分享</el-tag>
        <el-tag v-if="item.anonymous" size="small" type="info">匿名</el-tag>
        <el-tag v-if="showStatus && item.status" size="small" :type="statusTone" effect="plain">{{ statusText }}</el-tag>
        <el-tag v-if="showStatus && item.visibility === 'private'" size="small" type="info" effect="plain">仅自己可见</el-tag>
        <span v-if="destroyLine" class="destroy">{{ destroyLine }}</span>
        <el-button v-if="dismissable" class="hide" size="small" @click.stop="$emit('dismiss', item.id)">不感兴趣</el-button>
      </header>
    </div>

    <div class="body">
      <h3 class="title">{{ item.title }}</h3>
      <p class="excerpt">{{ item.excerpt }}</p>

      <div v-if="item.topics && item.topics.length" class="topics">
        <span v-for="t in item.topics" :key="t" class="topic"># {{ t }}</span>
      </div>

      <!-- 推荐位专属的那一行（任务 T7.7 · 手册 §10.2 7.7 · 需求 FR5.8）。
           刻意做成插槽而不是 reason 属性：需求 D6 要「推荐位卡片与广场同一形状」，
           而「同一形状」的意思正是——同一张卡片、多出来的一行由调用方决定内容。
           没有这个插槽时它一行 DOM 都不产生（广场 / 关注 / 主页 / 我的帖子四处都不传），
           比在组件里写 if (item.reason) 少一处「前端得自己记得哪些接口带 reason」。 -->
      <slot name="reason"></slot>

      <el-alert v-if="item.auditTip" type="warning" :closable="false" show-icon class="tip" :title="item.auditTip" />
      <crisis-card v-if="item.hotline" :hotline="item.hotline" level="inline"
                   text="这条内容提到了求助热线，你也可以拨打全国心理援助热线" />

      <!-- 互动条（T3.6）。三个约定写在这儿，免得后来人以为是漏了什么：
           1) 每个按钮都必须 @click.stop —— 卡片整体是「进详情」，不 stop 就是点一下赞顺带跳一页；
           2) 计数以服务端回执为准，本地只负责立刻点亮（见 usePostInteract 的注释）；
           3) 「评论 N」在一张卡片上只出现一次，作者行不重复它——上一轮真就为这句返工过。
           作者从卡头挪到了卡脚，这是小红书式排版的规矩（谁说的 / 什么时候 / 多少人赞），
           .who.link 与点击进主页的行为没变，变的只有位置。 -->
      <footer class="meta">
        <span class="who-line">
          <span class="mi-avatar" :class="{ 'is-anon': item.anonymous }">{{ initial }}</span>
          <span class="who" :class="{ link: canOpenAuthor }" @click.stop="openAuthor">{{ item.displayName || '屿友' }}</span>
          <span class="dot">·</span>
          <span class="time">{{ shownAt }}</span>
        </span>
        <span class="stat">浏览 {{ fmtCount(item.viewCnt) }}</span>
        <span class="stat">评论 {{ fmtCount(item.commentCnt) }}</span>
        <span class="grow"></span>
        <el-button class="act" :class="{ 'act-on': item.liked }" size="small" text
                   :disabled="isBusy(item, 'like')" @click.stop="act(item, 'like')">
          {{ item.liked ? '已赞' : '赞' }} {{ fmtCount(item.likeCnt) }}
        </el-button>
        <el-button class="act" :class="{ 'act-on': item.collected }" size="small" text
                   :disabled="isBusy(item, 'collect')" @click.stop="act(item, 'collect')">
          {{ item.collected ? '已收藏' : '收藏' }} {{ fmtCount(item.collectCnt) }}
        </el-button>
      </footer>
    </div>
  </article>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { fromNow, countdown, fmtCount } from '@/utils/format'
import { statusLabel } from '@/api/post'
import { usePostInteract } from '@/composables/usePostInteract'
import CrisisCard from '@/components/CrisisCard.vue'

// 广场卡片（U3）。入参就是后端 PostListItem 的原始字段，不做二次映射：
// 前端自己造一套字段名，接口一改就要在两处找 bug。
const props = defineProps({
  item: { type: Object, required: true },
  // 状态徽标只在「我的帖子 / 屿友主页」这类列表里画：广场按可见性过滤之后 status 恒为 PUBLISHED，
  // 在那儿显示徽标是一排毫无信息量的绿标签，还会让人误以为广场能看到未过审的帖。
  showStatus: { type: Boolean, default: false },
  // 「不感兴趣」是广场的负反馈交互，挂到自己的帖子上语义就成了「我不想再看到自己发的东西」，且后端还没有对应接口。
  dismissable: { type: Boolean, default: true }
})
defineEmits(['dismiss'])

const router = useRouter()
// 点赞与收藏：卡片只改列表里那一条原始对象，失败回滚由 composable 负责
const { isBusy, toggle } = usePostInteract()
const act = function (it, kind) { return toggle(it, kind) }

const shownAt = computed(() => fromNow(props.item.publishedAt) || '尚未发布')
// 只有树洞有 autoDestroyAt；普通帖这个字段是 null，倒计时不显示。
const destroyLine = computed(() => (props.item.autoDestroyAt ? countdown(props.item.autoDestroyAt) : ''))
const previewList = computed(() => (props.item.images || []).map((x) => x.url))
const hasImage = computed(() => !!(props.item.images && props.item.images.length))
const coverGlyph = computed(() => ({ hole: '🕳️', help: '🆘', normal: '🌿' }[props.item.type] || '🌿'))
// 首字母头像：PostListItem 里没有头像字段（后端不给，前端就不假装有）。
const initial = computed(() => {
  const n = String(props.item.displayName || '屿友').trim()
  return n ? n.slice(0, 1) : '屿'
})

function goDetail() {
  router.push({ name: 'post-detail', params: { id: props.item.id } })
}

// 匿名帖的 authorId 后端恒为 null（PostListItem 的注释写了理由：留着 user_id 抓包就能反查作者，匿名等于没做）。
// 所以「能不能点进作者主页」不必在前端再判一次 anonymous —— 字段为空就是不可点。
// 少一个 if，就少一处「前端得自己记得匿名规则」的地方，规则只留在服务端一处。
const canOpenAuthor = computed(() => props.item.authorId !== null && props.item.authorId !== undefined)
const statusText = computed(() => statusLabel(props.item.status))
const statusTone = computed(() => {
  const s = props.item.status
  if (s === 'PUBLISHED') return 'success'
  if (s === 'REJECTED' || s === 'TAKEDOWN' || s === 'DELETED') return 'danger'
  if (s === 'HUMAN_REVIEW' || s === 'MACHINE_REVIEW' || s === 'APPEALING') return 'warning'
  return 'info'
})

function openAuthor() {
  if (!canOpenAuthor.value) return
  router.push({ name: 'user-home', params: { id: props.item.authorId } })
}
</script>

<style scoped>
/* 卡片本体：封面通栏、正文内衬，所以 .mi-card 那份 18px 的内边距在这里归零。
   墙里的高度一致性来自两条：封面恒定 4/3，正文的标题与摘要各自钳到两行。 */
.post {
  padding: 0; overflow: hidden; cursor: pointer;
  display: flex; flex-direction: column;
  transition: box-shadow .18s ease, transform .18s ease;
}
.post:hover { box-shadow: var(--mi-shadow); transform: translateY(-2px); }

.cover { position: relative; aspect-ratio: 4 / 3; background: var(--mi-fill); overflow: hidden; }
/* 顶部压一层很淡的暗色渐变，是为了让压在图上的那一排徽标在任何封面上都读得清；
   没有这层，白底截图上的「树洞」黄标会直接糊掉。 */
.cover::after {
  content: ''; position: absolute; left: 0; right: 0; top: 0; height: 54px;
  background: linear-gradient(180deg, rgba(0, 0, 0, 0.30), rgba(0, 0, 0, 0));
  pointer-events: none;
}
.imgs { width: 100%; height: 100%; }
.img { width: 100%; height: 100%; display: block; }
.img :deep(.el-image__inner) { width: 100%; height: 100%; object-fit: cover; }
.img-count {
  position: absolute; right: 8px; bottom: 8px; z-index: 1;
  padding: 1px 8px; border-radius: 999px; font-size: 11px;
  background: rgba(0, 0, 0, 0.42); color: #fff;
}
/* 无图封面（大字卡）：小红书式「文案即封面」。整块改成纵向 flex ——
   徽标行（.head）从「绝对定位压在封面上」改成「排在封面顶部、占自己的高度」：
   上一版给大字留了 18px 顶衬，结果树洞卡的「树洞 / 匿名 / 还有 6 天 24 小时消失」
   在窄卡上换行成两排，直接盖住第一行字。让徽标先占位、文字再往下排，
   就不存在「到底留多少像素才够」这个永远算不准的数。 */
.post.no-img .cover { aspect-ratio: auto; min-height: 152px; display: flex; flex-direction: column; }
/* 顶部那条压暗渐变是给「白底截图上的徽标」准备的，浅色大字卡上它只会糊成一块脏灰。 */
.post.no-img .cover::after { display: none; }
.post.no-img .head { position: static; order: -1; padding: 8px 8px 0; } /* order:-1 是必须的：DOM 里 .head 排在封面文字之后，不把它挪回列首，徽标就会掉到封面下沿 */
.cover-empty {
  position: relative; flex: 1 1 auto; width: 100%; box-sizing: border-box;
  display: flex; align-items: center; padding: 6px 14px 20px;
}
/* 字号 19 是量出来的 —— 卡片 268px 宽时一行约 13 个汉字，5 行足够读完一条标题
   （标题上限 50 字）；第 6 行起交给 line-clamp 裁掉：写不下就隐藏，不撑破封面。 */
.cover-text {
  margin: 0; padding: 0; width: 100%;
  font-size: 19px; font-weight: 700; line-height: 1.55; color: var(--mi-text);
  word-break: break-word;
  display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 5; overflow: hidden;
}
/* 类型符号退成右下角的水印：它已经不是封面主角了，但留着能一眼扫出树洞/求助。 */
.cover-empty .glyph {
  position: absolute; right: 10px; bottom: 8px;
  font-size: 22px; line-height: 1; opacity: .5; filter: saturate(.9);
}
/* 标题已经排在封面上了，正文区那行就别再念一遍（DOM 保留，只是不显示）。 */
.post.no-img .title { display: none; }
.tone-hole { background: var(--mi-cover-hole); }
.tone-help { background: var(--mi-cover-help); }
.tone-normal { background: var(--mi-cover-normal); }

.head {
  position: absolute; left: 8px; right: 8px; top: 8px; z-index: 2;
  display: flex; align-items: center; gap: 6px; flex-wrap: wrap;
}
.head :deep(.el-tag) { border-radius: 6px; }
.destroy {
  font-size: 11px; line-height: 20px; padding: 0 8px; border-radius: 999px;
  background: rgba(0, 0, 0, 0.42); color: #fff; white-space: nowrap;
}
/* 「不感兴趣」靠右压着封面，鼠标不在卡上时退到七成透明：
   它是负反馈的出口，不是每张卡都想抢第一眼的内容。 */
.hide {
  margin-left: auto; flex: none; padding: 0 8px; height: 20px; line-height: 20px;
  font-size: 11px; color: #fff; background: rgba(0, 0, 0, 0.42); border: none;
  border-radius: 999px; opacity: .7;
}
.hide:hover { opacity: 1; background: rgba(0, 0, 0, 0.6); color: #fff; }

.body { display: flex; flex-direction: column; flex: 1 1 auto; padding: 10px 12px 12px; gap: 6px; }
.title { margin: 0; font-size: 14px; line-height: 1.45; font-weight: 600; color: var(--mi-text);
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
  word-break: break-word; }
.excerpt { margin: 0; font-size: 12px; line-height: 1.7; color: var(--mi-text-dim); white-space: pre-wrap;
  word-break: break-word; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.topics { display: flex; gap: 8px; flex-wrap: wrap; }
.topic { font-size: 11px; color: var(--mi-mist); background: var(--mi-mist-bg); padding: 1px 8px; border-radius: 999px; }
.tip { margin-top: 2px; }
.tip :deep(.el-alert__title) { font-size: 12px; line-height: 1.6; }
.body :deep(.crisis) { margin-top: 4px; padding: 8px; }

.meta {
  margin-top: auto; padding-top: 8px; display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  font-size: 11px; color: var(--mi-text-dim);
}
.who-line { display: inline-flex; align-items: center; gap: 5px; min-width: 0; }
.who { font-size: 12px; font-weight: 500; color: var(--mi-text-dim); overflow: hidden;
  text-overflow: ellipsis; white-space: nowrap; max-width: 92px; }
.who.link { cursor: pointer; }
.who.link:hover { color: var(--mi-text); }
.dot { opacity: .6; }
.stat { white-space: nowrap; }
.grow { flex: 1 1 auto; }
.act { padding: 0 2px; height: auto; font-size: 11px; color: var(--mi-text-dim); min-height: 0; }
.act :deep(span) { display: inline-flex; align-items: center; gap: 2px; }
.act.act-on { color: var(--mi-primary); font-weight: 600; }
.act + .act { margin-left: 2px; }
</style>
