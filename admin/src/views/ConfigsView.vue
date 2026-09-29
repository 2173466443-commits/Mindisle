<template>
  <div class="configs">
    <!-- 顶栏：内存生效版本 vs 库里的版本号，是两个不同的东西，必须分开显示 -->
    <div class="mi-card top">
      <div class="kv">
        <span class="k">引擎内存版本</span><span class="v">{{ dict ? dict.version : '—' }}</span>
      </div>
      <div class="kv">
        <span class="k">sys_config 记录版本</span><span class="v">{{ dict ? dict.dbVersion : '—' }}</span>
      </div>
      <div class="kv">
        <span class="k">生效词条</span><span class="v">{{ dict ? fmtNum(dict.wordCount) : '—' }}</span>
      </div>
      <div class="kv">
        <span class="k">缓存模式</span>
        <el-tag size="small" :type="dict && dict.cacheMode === 'redis' ? 'success' : 'warning'">{{ dict ? dict.cacheMode : '—' }}</el-tag>
      </div>
      <div class="kv wide">
        <span class="k">快照路径</span><span class="path">{{ (dict && dict.dictPath) || '（未配置，用 classpath 内置词库）' }}</span>
      </div>
      <el-button size="small" :loading="dBusy" @click="loadDict">重新读取状态</el-button>
      <el-button size="small" type="primary" :loading="reloading" @click="doReload">重建词库并热更新</el-button>
    </div>
    <p class="dim intro">
      两个版本号可能不相等：库里那条 <code>audit.wordlib_version</code> 是「上一次成功重建的结果」，
      内存版本是「当前正在拦东西的那一份」。只有 reload 成功才会三步依次执行——先换引擎、再写 sys_config、
      最后广播缓存版本；任何一步失败（空词库、列数不符、正则非法）都<strong>三步全不做</strong>，
      线上继续用旧词库。缓存模式为 <code>local</code> 时广播只影响本进程，多实例部署要切 redis 才能秒级一致。
    </p>

    <el-alert v-if="notice" :title="notice" :type="noticeTone" show-icon :closable="true" class="alert" @close="notice = ''" />

    <el-tabs v-model="tab" class="mi-card tabs">
      <!-- ============================ 敏感词词库 ============================ -->
      <el-tab-pane label="敏感词词库" name="words">
        <div class="bar">
          <el-form inline @submit.prevent>
            <el-form-item label="关键词">
              <el-input v-model="wf.keyword" clearable maxlength="64" placeholder="词面精确或模糊" style="width: 180px" @keyup.enter="reloadWords" />
            </el-form-item>
            <el-form-item label="词库组">
              <el-select v-model="wf.groupId" clearable placeholder="全部" style="width: 190px" @change="reloadWords">
                <el-option v-for="g in groups" :key="g.id" :label="g.name + '（' + g.level + '/' + g.action + '）'" :value="g.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="状态">
              <el-select v-model="wf.status" clearable placeholder="全部" style="width: 120px" @change="reloadWords">
                <el-option label="启用" :value="1" />
                <el-option label="停用" :value="0" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button :loading="wBusy" @click="reloadWords">查询</el-button>
              <el-button type="primary" @click="openAdd">新增词条</el-button>
            </el-form-item>
          </el-form>
          <div class="groups">
            <el-tag v-for="g in groups" :key="g.id" size="small" :type="statusTone(g.level)" effect="plain" class="gtag" @click="wf.groupId = g.id; reloadWords()">
              {{ g.name }} · 启用 {{ g.wordCnt }} / 入库 {{ g.storedWordCnt }} · {{ g.hitScope }}
            </el-tag>
          </div>
          <p class="dim">
            「启用 / 入库」两个数不同就说明有停用词条：停用的词留在库里但不参与匹配，重建快照时不会带上。
            匹配方式只有 <code>contains</code> 与 <code>regex</code> 两种，<code>whole</code>（整词）服务端明确拒写——
            中文没有词边界，整词匹配在 DUT 词面上是个伪需求。
          </p>
        </div>

        <el-table :data="words" size="small" height="380" empty-text="读不到词条，原因见上方提示">
          <el-table-column prop="id" label="#" width="62" />
          <el-table-column prop="word" label="词条" min-width="160" />
          <el-table-column label="组" width="130">
            <template #default="{ row }">{{ row.groupName || ('#' + row.groupId) }}</template>
          </el-table-column>
          <el-table-column label="级别" width="90">
            <template #default="{ row }"><el-tag size="small" :type="statusTone(row.level)">{{ LEVEL_TEXT[row.level] || row.level }}</el-tag></template>
          </el-table-column>
          <el-table-column label="动作" width="110">
            <template #default="{ row }">{{ ACTION_TEXT[row.action] || row.action }}</template>
          </el-table-column>
          <el-table-column label="作用侧" width="90" prop="hitScope" />
          <el-table-column label="匹配" width="90" prop="matchType" />
          <el-table-column label="命中" width="80" prop="hitCnt" />
          <el-table-column label="状态" width="80">
            <template #default="{ row }"><el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag></template>
          </el-table-column>
          <el-table-column label="操作" width="190">
            <template #default="{ row }">
              <el-button size="small" text @click="toggleWord(row)">{{ row.status === 1 ? '停用' : '启用' }}</el-button>
              <el-button size="small" text type="danger" @click="removeWord(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="pager">
          <span class="dim">共 {{ wPage.total }} 条 · 第 {{ wPage.page }} 页 · 改完词必须点上方「重建词库并热更新」才会生效</span>
          <el-pagination small layout="prev, pager, next" :total="wPage.total" :page-size="wPage.size"
                         :current-page="wPage.page" @current-change="onWordPage" />
        </div>
      </el-tab-pane>

      <!-- ============================ 试审 ============================ -->
      <el-tab-pane label="词库试审" name="trial">
        <div class="bar">
          <el-form @submit.prevent>
            <el-form-item label="作用侧">
              <el-radio-group v-model="tf.side">
                <el-radio-button value="user">user（发帖/评论侧）</el-radio-button>
                <el-radio-button value="ai">ai（模型输出侧）</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="文本">
              <el-input v-model="tf.text" type="textarea" :rows="3" maxlength="500" show-word-limit
                        placeholder="粘一段真实文本进来，只跑规则通道，不写库、不调模型" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="tBusy" @click="doTrial">试审</el-button>
              <el-button @click="tf.text = '我想伤害自己，不想活了'">放一条危机样例</el-button>
            </el-form-item>
          </el-form>
        </div>
        <template v-if="trial">
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item label="当时生效版本">{{ trial.dictVersion }}</el-descriptions-item>
            <el-descriptions-item label="是否命中">
              <el-tag size="small" :type="trial.hit ? 'danger' : 'success'">{{ trial.hit ? '命中' : '未命中' }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="命中数">{{ trial.hitCount }}</el-descriptions-item>
            <el-descriptions-item label="最高类别">{{ trial.category || '—' }}</el-descriptions-item>
            <el-descriptions-item label="最高级别">{{ trial.level || '—' }}</el-descriptions-item>
            <el-descriptions-item label="处置动作">{{ trial.action ? (ACTION_TEXT[trial.action] || trial.action) : '—' }}</el-descriptions-item>
          </el-descriptions>
          <h4 class="sec">归一化对照（这是 FR7.1 里「可回放」的两条依据：原文 + 归一化后 + 版本号）</h4>
          <div class="mi-card inner cmp">
            <div class="lbl">原文</div><div class="txt">{{ trial.raw }}</div>
            <div class="lbl">归一化后</div><div class="txt hit-text">{{ trial.normalized }}</div>
            <div v-if="trial.raw !== trial.normalized" class="dim">两行不同就说明发生了繁简/全半角/去空白等改写；命中下标是按归一化文本算的，展示时会映射回原文。</div>
          </div>
          <el-table :data="trial.hits || []" size="small" max-height="240" empty-text="没有任何命中">
            <el-table-column prop="word" label="命中词" width="150" />
            <el-table-column prop="category" label="类别" width="110" />
            <el-table-column label="级别" width="90">
              <template #default="{ row }"><el-tag size="small" :type="statusTone(row.level)">{{ LEVEL_TEXT[row.level] || row.level }}</el-tag></template>
            </el-table-column>
            <el-table-column label="动作" width="110">
              <template #default="{ row }">{{ ACTION_TEXT[row.action] || row.action }}</template>
            </el-table-column>
            <el-table-column prop="scope" label="作用侧" width="90" />
            <el-table-column label="区间" width="120">
              <template #default="{ row }">[{{ row.start }}, {{ row.end }})</template>
            </el-table-column>
          </el-table>
        </template>
        <p v-else-if="trialErr" class="err">试审失败：{{ trialErr }}</p>
        <p v-else class="dim">还没有试审结果。试审接口不落库、不计入 <code>hit_cnt</code>，可以放心反复点。</p>
      </el-tab-pane>

      <!-- ============================ 运行参数 ============================ -->
      <el-tab-pane label="运行参数 sys_config" name="params">
        <div class="bar">
          <el-form inline @submit.prevent>
            <el-form-item label="分组">
              <el-select v-model="cf.group" clearable placeholder="全部分组" style="width: 180px" @change="loadConfigs">
                <el-option v-for="g in cfgGroups" :key="g" :label="g" :value="g" />
              </el-select>
            </el-form-item>
            <el-form-item><el-button :loading="cBusy" @click="loadConfigs">刷新</el-button></el-form-item>
          </el-form>
          <p class="dim">
            分组是从实际返回的参数里现数出来的，不是写死的清单。<code>editable=0</code> 的行后端直接拒改
            （这类参数只能改配置文件或迁移脚本，防止在界面上把系统改成自相矛盾的状态）；
            值类型 <code>int / decimal / bool / json / string</code> 在服务端逐类校验，
            <code>decimal</code> 还额外要求 0~1 区间——概率类阈值给个 5 上去，规则通道就永远命中了。
            密钥类参数的值在这里只显示掩码（D13）。
          </p>
        </div>
        <el-table :data="configs" size="small" height="380" empty-text="读不到参数，原因见上方提示">
          <el-table-column prop="groupKey" label="分组" width="100" />
          <el-table-column prop="cfgKey" label="参数键" min-width="210" />
          <el-table-column label="当前值" min-width="150">
            <template #default="{ row }"><span class="val">{{ maskValue(row.cfgKey, row.cfgValue) }}</span></template>
          </el-table-column>
          <el-table-column prop="valueType" label="类型" width="90" />
          <el-table-column label="可改" width="70">
            <template #default="{ row }"><el-tag size="small" :type="row.editable ? 'success' : 'info'" effect="plain">{{ row.editable ? '是' : '否' }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="remark" label="说明" min-width="180" show-overflow-tooltip />
          <el-table-column label="改过时间" width="120">
            <template #default="{ row }">{{ fmtTime(row.updatedAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button size="small" :disabled="!row.editable" @click="openEdit(row)">{{ row.editable ? '修改' : '锁定' }}</el-button>
            </template>
          </el-table-column>
        </el-table>
        <p class="dim">共 {{ configs.length }} 条（这个接口不分页：参数表本身就在几百行以内）。</p>
      </el-tab-pane>
    </el-tabs>

    <!-- 新增词条 -->
    <el-dialog v-model="addDlg.show" title="新增敏感词条目" width="480px">
      <el-form label-width="90px">
        <el-form-item label="词条">
          <el-input v-model="addDlg.word" maxlength="64" show-word-limit placeholder="≤64 字符" />
        </el-form-item>
        <el-form-item label="词库组">
          <el-select v-model="addDlg.groupId" placeholder="必选（级别与动作由组决定，不在单条上配）" style="width: 100%">
            <el-option v-for="g in groups" :key="g.id" :label="g.name + ' · ' + g.level + ' · ' + g.action" :value="g.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="匹配方式">
          <el-select v-model="addDlg.matchType" style="width: 100%">
            <el-option v-for="m in MATCH_TYPES" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
      </el-form>
      <p class="dim">
        级别与处置动作跟着词库组走，单条不能自己声明——否则「黑名单词配成 WARN」这种自相矛盾的组合就会出现在库里。
        同词重复会被拒；如果那个词是软删状态，则会直接把它救活（保留原 id 与历史命中计数）。
        regex 词会先编译一次，编译不过当场 10001，不会把一个跑不起来正则存进库等下次 reload 才炸。
      </p>
      <template #footer>
        <el-button @click="addDlg.show = false">取消</el-button>
        <el-button type="primary" :loading="addDlg.busy" @click="submitAdd">保存</el-button>
      </template>
    </el-dialog>

    <!-- 修改参数 -->
    <el-dialog v-model="editDlg.show" :title="'修改参数 ' + editDlg.cfgKey" width="520px">
      <el-form label-width="90px">
        <el-form-item label="类型">{{ editDlg.valueType }}</el-form-item>
        <el-form-item label="说明"><span class="dim">{{ editDlg.remark }}</span></el-form-item>
        <el-form-item label="新值">
          <el-input v-model="editDlg.value" type="textarea" :rows="2" :placeholder="'当前值：' + maskValue(editDlg.cfgKey, editDlg.oldValue)" />
        </el-form-item>
      </el-form>
      <el-alert v-if="editDlg.slaPreview" class="prev" type="success" :closable="false" show-icon :title="editDlg.slaPreview" />
      <p class="dim">
        改完立刻生效的是「下一次读取」：SLA、阈值这类参数每次计算时都现读库，不需要重启；
        而词库要热更新，是因为一次 reload 要重建整张 DFA，不能在每条词的写入事务里做。
      </p>
      <template #footer>
        <el-button @click="editDlg.show = false">取消</el-button>
        <el-button type="primary" :loading="editDlg.busy" @click="submitEdit">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import {
  MATCH_TYPES, configList, configUpdate, dictReload, dictStatus, dictTrial,
  ticketSla, wordAdd, wordDelete, wordGroups, wordPage, wordStatus
} from '@/api/admin'
// ACTION_TEXT / LEVEL_TEXT 在 utils/format（渲染用词表），不在 api/admin（接口与白名单常量）。
// 这两个名字写错位置时 Rollup 直接报 MISSING_EXPORT——build 是 .vue 唯一的语法闸，所以每次都跑。
import { ACTION_TEXT, LEVEL_TEXT, errText, fmtNum, fmtTime, maskValue, statusTone } from '@/utils/format'

const tab = ref('words')
const notice = ref('')
const noticeTone = ref('success')
function say (text, tone) { notice.value = text; noticeTone.value = tone || 'success' }

/* ------------------------------ 引擎状态 ------------------------------ */
const dict = ref(null)
const dBusy = ref(false)
const reloading = ref(false)

async function loadDict () {
  dBusy.value = true
  try { dict.value = await dictStatus() } catch (e) { say('词库状态读取失败：' + errText(e), 'error') }
  dBusy.value = false
}

// 答辩演示点：改词 -> 不重建 = 不生效；重建 = 版本号 +0.1 且词条数立刻等于「启用」行数。
async function doReload () {
  reloading.value = true
  try {
    const r = await dictReload()
    say('热更新完成：' + r.version + ' · 引擎内词条 ' + fmtNum(r.wordCount) +
      ' · 本次扫描 ' + fmtNum(r.scanned) + ' 条 · 快照落盘 ' + (r.snapshotWritten ? '是' : '否') +
      '（路径 ' + (r.snapshotPath || '—') + '）· 审计行 #' + r.opLogId)
    await loadDict()
    await reloadWords()
    await loadGroups()
  } catch (e) {
    say('重建失败：' + errText(e) + ' —— 此时线上仍在使用旧词库，三个写入步骤全都没做', 'error')
  }
  reloading.value = false
}

/* ------------------------------ 词库组 ------------------------------ */
const groups = ref([])
async function loadGroups () {
  try { groups.value = await wordGroups() } catch (e) { say('词库组读取失败：' + errText(e), 'error') }
}

/* ------------------------------ 词条 ------------------------------ */
const words = ref([])
const wf = reactive({ keyword: '', groupId: '', status: '' })
const wPage = reactive({ total: 0, page: 1, size: 20 })
const wBusy = ref(false)
const addDlg = reactive({ show: false, busy: false, word: '', groupId: null, matchType: 'contains' })

async function reloadWords () {
  wBusy.value = true
  const params = { page: wPage.page, size: wPage.size }
  if (wf.keyword.trim()) params.keyword = wf.keyword.trim()
  if (wf.groupId) params.groupId = wf.groupId
  if (wf.status !== '' && wf.status !== null) params.status = wf.status
  try {
    const data = await wordPage(params)
    words.value = data.list || []
    wPage.total = Number(data.total || 0)
  } catch (e) {
    words.value = []
    wPage.total = 0
    say('词条列表读取失败：' + errText(e), 'error')
  }
  wBusy.value = false
}
function onWordPage (p) { wPage.page = p; reloadWords() }

function openAdd () { addDlg.word = ''; addDlg.groupId = groups.value.length ? groups.value[0].id : null; addDlg.matchType = 'contains'; addDlg.show = true }

async function submitAdd () {
  if (!addDlg.word.trim()) { say('词条不能为空', 'warning'); return }
  if (!addDlg.groupId) { say('必须选择词库组', 'warning'); return }
  addDlg.busy = true
  try {
    const w = await wordAdd({ word: addDlg.word.trim(), groupId: addDlg.groupId, matchType: addDlg.matchType })
    addDlg.show = false
    say('已新增词条 #' + w.id + '「' + w.word + '」，状态 ' + (w.status === 1 ? '启用' : '停用') + '；还差一步：点「重建词库并热更新」才会参与匹配')
    await reloadWords()
  } catch (e) {
    say('新增失败：' + errText(e), 'error')
  }
  addDlg.busy = false
}

async function toggleWord (row) {
  const next = row.status === 1 ? 0 : 1
  try {
    await wordStatus(row.id, next)
    say('词条「' + row.word + '」已' + (next === 1 ? '启用' : '停用') + '，重建词库后生效')
    await reloadWords()
  } catch (e) { say('状态修改失败：' + errText(e), 'error') }
}

async function removeWord (row) {
  try {
    await wordDelete(row.id)
    say('词条「' + row.word + '」已软删（保留 id 与历史命中计数，重复添加同一个词会把它救活而不是新建一行）')
    await reloadWords()
    await loadGroups()
  } catch (e) { say('删除失败：' + errText(e), 'error') }
}

/* ------------------------------ 试审 ------------------------------ */
const tf = reactive({ text: '', side: 'user' })
const trial = ref(null)
const trialErr = ref('')
const tBusy = ref(false)

async function doTrial () {
  if (!tf.text.trim()) { trialErr.value = ''; say('试审文本不能为空', 'warning'); return }
  tBusy.value = true
  trialErr.value = ''
  try {
    trial.value = await dictTrial(tf.text.trim(), tf.side)
  } catch (e) {
    trial.value = null
    trialErr.value = errText(e)
  }
  tBusy.value = false
}

/* ------------------------------ 参数 ------------------------------ */
const configs = ref([])
const cf = reactive({ group: '' })
const cBusy = ref(false)
const cfgGroups = computed(() => Array.from(new Set(configs.value.map((c) => c.groupKey).filter(Boolean))).sort())
const editDlg = reactive({
  show: false, busy: false, cfgKey: '', oldValue: '', value: '', valueType: '', remark: '', slaPreview: ''
})

async function loadConfigs () {
  cBusy.value = true
  try {
    const data = await configList(cf.group || undefined)
    configs.value = Array.isArray(data) ? data : []
    if (!configs.value.length) say('该分组下确实没有参数（不是读取失败）', 'info')
  } catch (e) {
    configs.value = []
    say('参数列表读取失败：' + errText(e), 'error')
  }
  cBusy.value = false
}

function openEdit (row) {
  editDlg.cfgKey = row.cfgKey
  editDlg.oldValue = row.cfgValue
  editDlg.value = row.cfgValue
  editDlg.valueType = row.valueType
  editDlg.remark = row.remark || ''
  editDlg.slaPreview = ''
  editDlg.show = true
}

async function submitEdit () {
  if (editDlg.value === '' || editDlg.value === null) { say('参数值不能为空（要清空语义请给显式取值）', 'warning'); return }
  editDlg.busy = true
  try {
    const c = await configUpdate(editDlg.cfgKey, String(editDlg.value))
    editDlg.show = false
    let msg = '已保存 ' + c.cfgKey + ' = ' + maskValue(c.cfgKey, c.cfgValue) + '（' + c.valueType + '）'
    // 必做的答辩点：改 SLA 参数后当场把新的截止时刻算给评委看，证明参数不是「写进库就完事」。
    if (/^risk\.sla_(l2|l3)_minutes$/.test(c.cfgKey)) {
      const lv = c.cfgKey.slice(9, 11).toUpperCase()
      try {
        const at = await ticketSla(lv)
        msg += ' —— 现场验证：现在建一张 ' + lv + ' 工单，SLA 截止时刻会是 ' + fmtTime(at, true)
        editDlg.slaPreview = msg
      } catch (e) {
        msg += '；但读取新 SLA 失败：' + errText(e)
      }
    } else {
      msg += '，下次读取即生效，无需重启'
    }
    say(msg)
    await loadConfigs()
  } catch (e) {
    say('保存失败：' + errText(e), 'error')
  }
  editDlg.busy = false
}

onMounted(() => {
  loadDict()
  loadGroups()
  reloadWords()
  loadConfigs()
})
</script>

<style scoped>
.top { display: flex; align-items: center; gap: 22px; flex-wrap: wrap; padding: 12px 18px; }
.kv { display: flex; flex-direction: column; gap: 2px; }
.kv .k { font-size: 12px; color: var(--mi-text-dim); }
.kv .v { font-size: 19px; font-weight: 700; }
.kv.wide { min-width: 220px; }
.kv .path { font-size: 12px; word-break: break-all; }
.intro { margin: 8px 2px 0; }
.alert { margin-top: 10px; }
.tabs { margin-top: 10px; padding: 6px 16px 12px; }
.bar { padding: 4px 0 0; }
.groups { display: flex; gap: 8px; flex-wrap: wrap; margin: 4px 0 8px; }
.gtag { cursor: pointer; }
.pager { display: flex; align-items: center; gap: 12px; margin-top: 10px; }
.dim { font-size: 12px; line-height: 1.8; color: var(--mi-text-dim); margin: 6px 0 0; }
.err { color: #D9534F; font-size: 13px; }
.sec { margin: 18px 0 8px; font-size: 14px; font-weight: 700; }
.inner { padding: 12px; }
.cmp .lbl { font-size: 12px; color: var(--mi-text-dim); }
.cmp .txt { font-size: 13px; line-height: 1.8; white-space: pre-wrap; margin-bottom: 8px; }
.cmp .hit-text { color: var(--mi-primary); }
.val { font-family: ui-monospace, Consolas, monospace; font-size: 12px; }
.prev { margin: 8px 0; }
</style>