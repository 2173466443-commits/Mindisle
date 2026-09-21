import http from './http'

// 后端 com.mindisle.web.PostController（任务 3.3 发帖 / 3.5 列表与详情）。
// 读接口一律 silent：广场与详情页要自己把「为什么没数据」画出来（手册 §5.8 第 1 条），
// 不弹全局红条；写接口不 silent，因为发布失败的文案就该是那条消息本身。
export const listPosts = (params) => http.get('/posts', { params, silent: true })
export const postDetail = (id) => http.get('/posts/' + id, { silent: true })
export const createPost = (payload) => http.post('/posts', payload)

// 出参里出现的帖子形式，与后端 PostService.TYPES 一致；界面下拉只认这三个值。
export const POST_TYPES = [
  { value: 'normal', label: '分享' },
  { value: 'hole', label: '树洞' },
  { value: 'help', label: '求助' }
]
