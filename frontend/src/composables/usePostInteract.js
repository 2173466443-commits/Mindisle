import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { actOnPost, POST_ACTION_PAIRS } from '@/api/post'
import { useUserStore } from '@/stores/user'

/**
 * 帖子互动（点赞 / 收藏）的共用逻辑（任务 T3.6 · FR4.4）。
 *
 * <p><b>为什么是一个函数改对象、而不是每个页面自己写一遍</b>：广场卡片、详情页、屿友主页、
 * 我的帖子用的是同一个 PostCard，四处都要「点了立刻变红、失败了变回去」。写在卡片里就得让卡片
 * 持有 props 的写权限；写在四个页面里就是四份回滚代码。这里改成由调用方把<b>列表里那一条原始对象</b>
 * 交进来，函数只改这个对象的字段——对象来自 usePagedPosts / stores/feed 的 ref，本身就是响应式的，
 * 所以「改数据」和「重渲染」之间不需要再多一层事件。</p>
 *
 * <p><b>为什么乐观更新还不算造假</b>：先改的是<b>界面预期</b>（这一颗心态亮起来），数字只是顺带 +1；
 * 请求一回来就用服务端回执把四个字段整体对齐（后端是按真实行重算计数的，并发时本地那个 +1 未必准），
 * 失败则整份回滚。所以界面上任何一刻都不会出现「后端没有但前端说有」的持久状态。</p>
 *
 * <p>未登录不发请求：后端这条路会返 401/10002，http 拦截器会把人直接带去登录页并弹一条错误——
 * 但「你还没登录」不该以错误形式出现，所以这里先判一次，跳登录页带上 redirect，语气是引导而不是报错。</p>
 */
export function usePostInteract() {
  const router = useRouter()
  const userStore = useUserStore()
  /** 正在提交的那一个「帖子:开关」，格式 "12:like"。只用于禁用当前这一颗按钮，不做全局遮罩。 */
  const busy = ref('')

  function busyKey(target, kind) {
    return target && target.id !== undefined ? String(target.id) + ':' + kind : ''
  }

  function isBusy(target, kind) {
    return busyKey(target, kind) === busy.value
  }

  function askLogin() {
    const current = router.currentRoute.value
    router.replace({ name: 'login', query: current && current.name ? { redirect: current.fullPath } : {} })
  }

  /**
   * @param target 带 id / liked / likeCnt / collected / collectCnt 的原始对象（列表条目或详情对象本身）
   * @param kind   'like' | 'collect'
   * @returns 成功 true；未登录、重复点击、请求失败一律 false（失败已回滚，文案由 http 层弹）
   */
  async function toggle(target, kind) {
    const pair = POST_ACTION_PAIRS[kind]
    if (!target || !pair) return false
    const key = busyKey(target, kind)
    if (busy.value) return false
    if (!userStore.isLogged) {
      askLogin()
      return false
    }
    const snapshot = { flag: !!target[pair.flag], cnt: Number(target[pair.cnt]) || 0 }
    const next = !snapshot.flag
    target[pair.flag] = next
    target[pair.cnt] = Math.max(0, snapshot.cnt + (next ? 1 : -1))
    busy.value = key
    try {
      const view = await actOnPost(target.id, next ? pair.on : pair.off)
      // 回执是四个计数唯一的事实来源：连点、双端同时点、别人也在点，都以它为准
      if (view && typeof view === 'object') {
        target.liked = !!view.liked
        target.likeCnt = Number(view.likeCnt) || 0
        target.collected = !!view.collected
        target.collectCnt = Number(view.collectCnt) || 0
      }
      return true
    } catch (e) {
      target[pair.flag] = snapshot.flag
      target[pair.cnt] = snapshot.cnt
      return false
    } finally {
      busy.value = ''
    }
  }

  return { busy, isBusy, toggle }
}
