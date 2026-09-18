-- =============================================================================
-- 09_seed.sql  心屿 / MindIsle 初始数据（手册 §5.2 种子数据规范 · 需求 §18.3 / §18.4 / FR8.6）
-- 内容：S1 系统参数 21 条 · S2 敏感词分组 7 类 · S3 敏感词词条 139 条
--       S4 官方话题 20 个 · S5 演示账号 6 个及其资料与同意留痕
-- 性质：本文件只含 INSERT，不含 CREATE / ALTER，可在 01~08 建表之后重复执行。
-- 幂等：一律 INSERT IGNORE，依赖各表唯一键——
--       sys_config.uk_cfg_key / sensitive_word_group.uk_name / sensitive_word.uk_word
--       topic.uk_name / user.uk_username / user_profile.PRIMARY(user_id)
--       user_consent 无唯一键（只追加表），故此处显式指定主键 id 1~12 以保证重跑不翻倍。
-- -----------------------------------------------------------------------------
-- ⚠ 安全红线（手册 §5.2 第 4 条）：
--   1. 演示账号口令统一为 Test1234，BCrypt 强度 10 摘要，仅用于 dev profile 与答辩演示；
--   2. 昵称、签名、话题简介一律不含真实手机号、QQ、微信号、姓名、学号、学校院系；
--   3. 隐私泄露词组里的正则是「检测规则」而非真实号码，不含任何可识别到个人的数据；
--   4. 自伤自杀词组是放行标记词，用于危机识别链路演示，不得作为删除帖子的依据。
-- =============================================================================

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- S1 sys_config：可配置参数 21 条（需求 FR8.6 · 论文参数对照表来源）
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `sys_config` (`cfg_key`,`cfg_value`,`value_type`,`group_key`,`remark`) VALUES
  ('risk.cascade_threshold','0.55','decimal','risk','级联判定阈值：词典模型置信度低于此值才升级调用 LLM（需求 §6.1 创新点①）'),
  ('risk.recall_target','0.95','decimal','risk','危机样本召回率目标，代价敏感 Fβ=2 调参约束（需求 §1.5 ③）'),
  ('risk.l2_score','0.60','decimal','risk','L2 中度风险判定分阈，触发置顶求助卡（需求 §18.4）'),
  ('risk.l3_score','0.80','decimal','risk','L3 高危判定分阈，30 分钟内人工介入（需求 §18.4）'),
  ('risk.sla_l2_hours','4','int','risk','L2 工单处理时限（小时），来自 sys_config 而非硬编码（FR8.6）'),
  ('risk.sla_l3_minutes','30','int','risk','L3 工单处理时限（分钟），宁可误报不可漏报'),
  ('rec.emotion_boost.enabled','true','bool','rec','情绪感知加权总开关，false 即退化为普通 item-CF（消融实验 A/B 组）'),
  ('rec.emotion_beta','0.7','decimal','rec','综合打分权重：score=β·cf+(1-β)·emotion_match（需求 §6.4）'),
  ('rec.topk_neighbor','200','int','rec','item-CF 每物品保留的近邻数 K，离线截断存 item_similarity'),
  ('rec.explore_ratio','0.20','decimal','rec','探索流量比例 ε-greedy，缓解马太效应（需求 §6.4 多样性）'),
  ('rec.diversity_topic_max','2','int','rec','单列 feed 中同一话题最多出现条数，打散规则'),
  ('rec.expose_dedup_days','7','int','rec','已曝光物品去重回溯天数，读 recommend_result.is_exposed'),
  ('rec.weight_profile','{"view":1,"like":3,"collect":5,"comment":4,"read_through":4,"dislike":-5}','json','rec','隐式反馈打分权重表，离线相似度的 r(u,i) 来源（需求 §6.4）'),
  ('prompt.version','v1','string','ai','当前生效的 AI 提示词版本，与 ai_call_log.prompt_version 对齐可回溯'),
  ('prompt.crisis_card','{"hotline":"12356","text":"你现在的感受很重要，专业的人愿意听你说。可以拨打心理援助热线 12356，也可以预约学校心理咨询中心。","display":"L2/L3 置顶卡片，整卡不可关闭，单次提示可关闭"}','json','risk','危机转介卡片文案（需求 §18.4 强制项：禁诊断、必转人工）'),
  ('audit.model','deepseek-v4-pro','string','audit','内容安全二审与大模型判定用模型（需求 §18.3 通道②）'),
  ('audit.wordlib_version','v0.1','string','audit','敏感词词库版本号，audit_record.engine_version 记录当次命中版本'),
  ('ai.model','deepseek-flash','string','ai','AI 陪伴对话主模型（需求 FR2），低延迟优先'),
  ('ai.daily_budget_cent','2000','int','ai','每日 token 成本预算（分），超限降级为词典兜底话术（需求 §11 R3）'),
  ('ai.temperature','0.70','decimal','ai','生成温度，共情对话取 0.7；风险判定另设 0 不在本键'),
  ('ai.timeout_ms','8000','int','ai','LLM 单次调用超时（毫秒），超时走兜底话术并写 ai_call_log.status');

-- -----------------------------------------------------------------------------
-- S2 sensitive_word_group：七类（需求 §18.3 级别 / 处置动作 / 作用侧）
-- word_cnt 与 S3 实际插入条数一一对应：20+20+20+20+19+20+20 = 139
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `sensitive_word_group` (`id`,`name`,`level`,`action`,`hit_scope`,`word_cnt`,`remark`) VALUES
  (1,'政治违法','black','BLOCK','both',20,'只放违禁交易类通用词跑链路，不含真实人物与事件词条；正式词表在阶段 6 经合规确认后导入（需求 §18.3）'),
  (2,'色情低俗','black','BLOCK','both',20,'用户输入与 AI 输出双侧拦截，命中即拒答并留证 audit_record（需求 §18.3）'),
  (3,'辱骂攻击','grey','REVIEW','user',20,'仅用户侧进人审队列，AI 回复不会出现此类表述，避免误伤共情语境中的引用（需求 §18.3）'),
  (4,'自伤自杀','risk','TAG','both',20,'放行但不删除——删除等于把人推回沉默；命中触发 L2/L3 分级转介与 12356 卡片（需求 §18.3 · §1.5 ③）'),
  (5,'隐私泄露','grey','REVIEW','both',19,'手机号/QQ/微信/身份证/银行卡走正则，其余为引导性话术词；命中后人审并打码展示（需求 §18.3）'),
  (6,'广告导流','black','BLOCK','both',20,'社区商业化前一律拦截站外导流，保护学生用户不被引流至兼职诈骗（需求 §18.3）'),
  (7,'医疗越界词','black','BLOCK','ai',20,'仅 AI 回复侧（BR8）：命中即改写为不诊断、不建议用药、不替代就医的话术，改判失败直接拒答（需求 §13.2 BR8）');

-- -----------------------------------------------------------------------------
-- S3 sensitive_word：DFA 词典词条 139 条（需求 §7.2 #16）
-- variant_hash = MD5(词条 UTF-8 原文)，是变体归一化算法的等价占位实现；
-- 正式实现在 T5.x 由「繁简转换 → 全半角归一 → 去插入符 → 拼音首字母」四步归一后取 MD5。
-- match_type：隐私泄露组前 5 条为 regex（手机号/QQ/微信/身份证/银行卡），其余 contains。
-- -----------------------------------------------------------------------------

-- 组 1「政治违法」→ group_id=1，条数 20
INSERT IGNORE INTO `sensitive_word` (`group_id`,`word`,`variant_hash`,`match_type`) VALUES
  (1,'枪支弹药','2cf1a561f38a6b3fb332e4f4ac0e3e75','contains'),
  (1,'仿真枪','049e1e845e3e1962c072a02815aa293a','contains'),
  (1,'毒品','5eee6d08fbf21ded69b2d4ec047a7162','contains'),
  (1,'冰毒','c52727744c8e33cbcdad90c6111f0fb2','contains'),
  (1,'迷幻药','6b4bd85f4761f9197318a5f32989fd3d','contains'),
  (1,'代办证件','231697474f8555c96c1a35962e54ecbb','contains'),
  (1,'假证','b2b9b896e2b13c4187d1ff2cfb818010','contains'),
  (1,'代开发票','9d82bf6fdbd67dc27000af862a76c2d2','contains'),
  (1,'洗钱','50f8ab2d615a503e3e1768750201c776','contains'),
  (1,'高利贷','2cbcaa419bf2f3d3d7d5707bf75139ed','contains'),
  (1,'赌博网站','172e6dbb9119f006d268b63e33d62e2c','contains'),
  (1,'博彩平台','d371a6568c33d4c461f36e7040824f87','contains'),
  (1,'私彩','d79ddfba13707f5bfbd246107381daf6','contains'),
  (1,'走私','00a397684b10bb14c16aa170ba975134','contains'),
  (1,'偷渡','df61d85d1a819be2f207731d6088568a','contains'),
  (1,'刷单返利','0bb93784e0a07be2cab65ae661d32e7c','contains'),
  (1,'非法网贷','465ba48e29d7e491b13da0badb64693b','contains'),
  (1,'套现','472abb294d5c375edf9d549472bb9b51','contains'),
  (1,'走私烟弹','f0bfd39204eac8414e7e16223200569c','contains'),
  (1,'电报购','c432df02b20053d1fe8f17d56d48eabf','contains');

-- 组 2「色情低俗」→ group_id=2，条数 20
INSERT IGNORE INTO `sensitive_word` (`group_id`,`word`,`variant_hash`,`match_type`) VALUES
  (2,'裸聊','3686019559a3edf587cd567fffde8d3f','contains'),
  (2,'约炮','8e338ee8919dff35298f5d75485a78d4','contains'),
  (2,'福利姬','f8962c50373ba95d68d5eda976b5469f','contains'),
  (2,'色图','f5de3b5c66f30f5b7f887769e3ece1e9','contains'),
  (2,'黄图','a229c0f0205e8862ad2343b3f83fcc37','contains'),
  (2,'成人网站','5a7b3f4422cf701bfeb37f5211fb2a09','contains'),
  (2,'资源群','5a72671d0203bac6b2b94a57b7eff59e','contains'),
  (2,'大尺度','2b92587ec9a39fb015bcb7e1dae75329','contains'),
  (2,'擦边','797336b779c4e0567b48ea7c1e4044ee','contains'),
  (2,'福利视频','2665df2ddb51a97b8a68f76ebac16cce','contains'),
  (2,'一夜情','d62301d83b00886ece6f074a17ce67f4','contains'),
  (2,'援交','c849f9c63857371426ec5b129df11d8d','contains'),
  (2,'春药','fe6df48685d760ae3d7452b8eb387cb9','contains'),
  (2,'催情','eb0036c964207994590da262644da216','contains'),
  (2,'伟哥代购','9108d5307972004c17968a221fdbfe56','contains'),
  (2,'私聊看','77fd05370cce774e23c8a6662ab65ca0','contains'),
  (2,'加我私密','d9e6e3ea54ebc6cd9f176d234c2551aa','contains'),
  (2,'开房','239a6f2e8b91d5edbbf0a04694185c80','contains'),
  (2,'私密视频','30b5d54f97547f8dd263a3e7c3735b90','contains'),
  (2,'涉黄直播','106f9a5579c800f285bbaea489120174','contains');

-- 组 3「辱骂攻击」→ group_id=3，条数 20
INSERT IGNORE INTO `sensitive_word` (`group_id`,`word`,`variant_hash`,`match_type`) VALUES
  (3,'傻逼','2c338dcb94da22361b0a34c84b07e796','contains'),
  (3,'滚蛋','3521257fc593c5d202474f6ac0c245ae','contains'),
  (3,'废物','e89b01036caeb2f877ee6ad6bcfd939e','contains'),
  (3,'去死吧','63eacbc28077c06dc0e110d63938d20f','contains'),
  (3,'脑残','a0b6b2952ba258fff63c7dc07ea859d7','contains'),
  (3,'智障','dcea42017f9de5b0b50f0e479591a6c7','contains'),
  (3,'白痴','5ce4a349d9c8799b45655b80b89a4bbb','contains'),
  (3,'人渣','dbc6cafe9016f29e5f7e24b71538e2b2','contains'),
  (3,'垃圾','3c7426979958efa6fb5de907fa215269','contains'),
  (3,'渣女','147e7a70ccb038f905a9e55d87d0eacb','contains'),
  (3,'渣男','eccbd871955a5343d43c47b157c13120','contains'),
  (3,'恶心','2e8563011d3878226b64cfd55cf2cacd','contains'),
  (3,'没人要你','82cd87e8f4f03bc4e32b51b5752f66f7','contains'),
  (3,'土狗','26e8a86d9f52c022feacd50e842e1251','contains'),
  (3,'滚出宿舍','d8d1c4e8cf3e4674b5d2ceaeda8141dc','contains'),
  (3,'闭嘴','5350a4e971492215be8133756665d999','contains'),
  (3,'真烦','fce633b6a3e8b8b02ded4a4b621e1a8e','contains'),
  (3,'讨厌你','9ebf35e6eb8dc69b34022d5a140d5b33','contains'),
  (3,'长相差','b8f717098eee25c4edfff2ffb97cbe8d','contains'),
  (3,'鄙视你','6477ef09efeb025180f303d321944314','contains');

-- 组 4「自伤自杀」→ group_id=4，条数 20
INSERT IGNORE INTO `sensitive_word` (`group_id`,`word`,`variant_hash`,`match_type`) VALUES
  (4,'不想活','92f8fbbe92d4b6d2925e1b0499ebd553','contains'),
  (4,'活着没意思','016664957037f98ac4c969a7f9289cd6','contains'),
  (4,'死了就好','93cd78091556d42958ddc82236f3bbf2','contains'),
  (4,'消失一段时间','bc74180cae5346bf4151f02c8e1063b6','contains'),
  (4,'一了百了','01a532263eb56866c32af8a818bbe0c5','contains'),
  (4,'彻底解脱','4ca3fb7709f6259de012edf35a5285b1','contains'),
  (4,'不想醒来','443e767c425568b0e22e2449ef8d9d05','contains'),
  (4,'结束生命','64be13f4a042bd8d28658576585e57f6','contains'),
  (4,'自我了断','91cbad9bf8c75a8f79c8a013b8f8ef43','contains'),
  (4,'撑不下去','bbe4bf7b540b857a41ddc8ec05eb5007','contains'),
  (4,'写遗书','c926c957c2c483b76e3b7cca11d9f674','contains'),
  (4,'留遗书','027bac4c09573e45deacaa9909530320','contains'),
  (4,'把东西分给室友','26cf91f69f7833e68d2cc8e5b66c315b','contains'),
  (4,'最后一次跟这里说说话','ad290cbd147df3d3dfa96928af765385','contains'),
  (4,'割腕','bf57b004c2484fd15895b91f6de38397','contains'),
  (4,'自残','c79829097c9f875888a26b8f54242783','contains'),
  (4,'吞药','1095d5bceb9962b7744f6e7d3641a807','contains'),
  (4,'烧炭','d1a9a56ef940283a49ba82b89dd20845','contains'),
  (4,'跳下去','ad9667f8cf38079887fab1a52d8b04e1','contains'),
  (4,'伤害自己','0e3c8447dde670c103146b52cfe5e806','contains');

-- 组 5「隐私泄露」→ group_id=5，条数 19
INSERT IGNORE INTO `sensitive_word` (`group_id`,`word`,`variant_hash`,`match_type`) VALUES
  (5,'1[3-9][0-9]{9}','af810d78b7dfeae6d1031498743eefdc','regex'),
  (5,'(qq|QQ|企鹅)[：: ]*[1-9][0-9]{4,10}','249e70bf6e449288af56cc9fc5e415f3','regex'),
  (5,'(微信|weixin|wx|vx)[：: ]*[a-zA-Z][-_a-zA-Z0-9]{5,19}','248494ae05651d87169b32664d20f5a4','regex'),
  (5,'[1-9][0-9]{5}(19|20)[0-9]{2}[01][0-9][0-3][0-9][0-9]{3}[0-9xX]','a1b09ecc1e3d37f761a992ee95ca6705','regex'),
  (5,'[0-9]{16,19}','47c094b4c7b698e562acf7386e78113a','regex'),
  (5,'我的手机号是','a6381081b52de97df6bbb56ae9613d62','contains'),
  (5,'我的身份证是','fe87d24bab87da3b19c5e6001a36da08','contains'),
  (5,'加我微信','2e2bba6461c3157c73d7a8e314c91206','contains'),
  (5,'私聊我微信','f628a4ef9b4043724ee719201c7f25dc','contains'),
  (5,'有我电话','d89c19281c861a02286e2b6d6a263d14','contains'),
  (5,'住址是','e13970f6dd6e4b45f617e46f641b98d9','contains'),
  (5,'房间号是','05738f2d0f7dc987ca87e9410aa549ef','contains'),
  (5,'学籍号','730322f8f2f5b8b5e6181402efb15089','contains'),
  (5,'工牌号','e6d37315252f347ba8892ca59cd6c59a','contains'),
  (5,'邮箱是','9469c860c6987b8a7c9e2925b318fc92','contains'),
  (5,'真实姓名','7eacb4d906910bddf002695f3ae6c790','contains'),
  (5,'身份证后四位','af1f0075bcaf50d913bc905a2a709c9c','contains'),
  (5,'银行卡号','d98e9d0ef3aec017984a08e6f73a3e3f','contains'),
  (5,'社交账号','ae785fb4f45e4ea2f89dc975a0d3a966','contains');

-- 组 6「广告导流」→ group_id=6，条数 20
INSERT IGNORE INTO `sensitive_word` (`group_id`,`word`,`variant_hash`,`match_type`) VALUES
  (6,'加微信领','6ee87f028e853190f6c51757721abb1b','contains'),
  (6,'免费领课','d7fcf745392940229fd8dcd619bc4d0d','contains'),
  (6,'兼职日结','12e9595729c707040dd76ca325bc01db','contains'),
  (6,'代理加盟','2d6923e3bc1c3c5356e3514a345b836b','contains'),
  (6,'优惠券群','7e7154e04e382c02669cf7222b403713','contains'),
  (6,'砍一刀','075ede1ec463fef0426c19c988ed618a','contains'),
  (6,'扫码进群','50f4b47f9e34fca5906801d7d10b7ad3','contains'),
  (6,'点击链接','9d63f3f337d666287842217841e51f3a','contains'),
  (6,'引流','687b8b14f7558cc5b2310a44dddf732e','contains'),
  (6,'私聊报价','9e7900803302075258ea6e90392fe702','contains'),
  (6,'代练','b0f48c7a42254c41339289595433932f','contains'),
  (6,'低价代购','2693614ea4f948de75e230e57baa0a0e','contains'),
  (6,'内推码','af6ba533c8e1469136ac991918ad62c9','contains'),
  (6,'返利','b8f9dfd976790e149877cf08e87282f8','contains'),
  (6,'拼单群','6b1bd88bd6c8dd32b3a3604a20659546','contains'),
  (6,'招代理','56165a4cda5a49d4db85825e9c392b46','contains'),
  (6,'日赚三百','82734c784fdd79b240758949b52e6fc0','contains'),
  (6,'零基础月入','e637a05e559c60986f138babb9270378','contains'),
  (6,'公众号','215feec56c2ae2ebc5c15e0bf7baf63b','contains'),
  (6,'进群买','42e37e8bf7df0aa95b34582b4f928847','contains');

-- 组 7「医疗越界词」→ group_id=7，条数 20
INSERT IGNORE INTO `sensitive_word` (`group_id`,`word`,`variant_hash`,`match_type`) VALUES
  (7,'开药','1e4ea167a808a9873e7ee053f1b550da','contains'),
  (7,'处方','ab4ba60191e627e719b9574611934c76','contains'),
  (7,'用药剂量','512930e88c9ecb622f9e408c327ef32f','contains'),
  (7,'吃几片','b26beebbf65fa387eef319f7a6b5e4b9','contains'),
  (7,'药物叠加','fdb005c29c564caf2acb04e51e3c07a1','contains'),
  (7,'停药','a63db6d997d3869a880c92c785cde880','contains'),
  (7,'加量','dd0b850b1ff5b52dd910a1b0a9ec9f5b','contains'),
  (7,'确诊','fbdb9382c4e3724ca4844d00eec277e1','contains'),
  (7,'自我确诊','e07098ac8f1ce7aa5643ef286d8b55ec','contains'),
  (7,'抑郁症确诊','90a0107d4017866cab98310075c258e2','contains'),
  (7,'双相','87129b9326e5ba5eca124ceb7928a339','contains'),
  (7,'躁郁','25fea8684454f2c1635d15bf1f370cb9','contains'),
  (7,'氟西汀','1f601b33014427da9deae31094ef0293','contains'),
  (7,'舍曲林','5ea3e3c6b1e5de12a99249dc2c661d84','contains'),
  (7,'帕罗西汀','b00ac36cd0be00b53c56ee69abfa8863','contains'),
  (7,'阿普唑仑','faffef598936bd14d15ef17ccac075e8','contains'),
  (7,'劳拉西泮','3bac3c9d15d87738c8f0a9f1658f17d1','contains'),
  (7,'米氮平','f5d824d5b16fea7b8377e50f74c3c004','contains'),
  (7,'副作用','fe55650df39660acbe398b99908725d6','contains'),
  (7,'买药','6a4b3bf71f21043e26ff7a84c736dee1','contains');

-- -----------------------------------------------------------------------------
-- S4 topic：官方话题 20 个（需求 FR1.6 话题体系 · §7.2 #6）
-- 全部 is_official=1 且 audit_status='APPROVED'，作为冷启动标签召回与话题广场初始内容
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `topic` (`id`,`name`,`desc_txt`,`is_official`,`audit_status`) VALUES
  (1,'失眠夜','睡不着的时候这里还有人醒着',1,'APPROVED'),
  (2,'考研加油站','备考节奏、崩溃与自愈',1,'APPROVED'),
  (3,'失恋博物馆','把没说完的话留在这里',1,'APPROVED'),
  (4,'原生家庭','和爸妈的关系也可以慢慢谈',1,'APPROVED'),
  (5,'秋招焦虑','简历、面试与等结果的日子里',1,'APPROVED'),
  (6,'社恐互助','不想社交不是缺陷',1,'APPROVED'),
  (7,'情绪日记','每天一句话记录心情',1,'APPROVED'),
  (8,'毕业倒计时','倒计时里的舍不得与慌',1,'APPROVED'),
  (9,'宿舍关系','距离产生不了美的时候',1,'APPROVED'),
  (10,'留学申请','语言成绩与文书的拉锯',1,'APPROVED'),
  (11,'体重焦虑','和身体和解这件事',1,'APPROVED'),
  (12,'失去至亲','想念是可以一直存在的',1,'APPROVED'),
  (13,'和抑郁共处','不评判，只陪伴',1,'APPROVED'),
  (14,'考试周','周周都是考试周',1,'APPROVED'),
  (15,'恋爱中','恋爱里的甜与难',1,'APPROVED'),
  (16,'社死现场','尴尬到想换个学校',1,'APPROVED'),
  (17,'自律打卡','一起把日子过顺',1,'APPROVED'),
  (18,'独居日记','一个人的安全感',1,'APPROVED'),
  (19,'兼职避坑','别急着交钱',1,'APPROVED'),
  (20,'心理咨询初体验','第一次预约前你想知道的',1,'APPROVED');

-- -----------------------------------------------------------------------------
-- S5 演示账号：1 个超管 + 5 个普通用户（T2.4 冒烟与 T9.x 演示用）
-- 口令统一 Test1234（BCrypt strength=10 摘要，下方内联串已用 bcrypt.checkpw 校验通过）
-- 严禁写入真实手机号 / 邮箱 / 姓名 / 学校；email 全为 NULL，昵称走匿名化编号
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `user`
  (`id`,`username`,`password`,`nickname`,`avatar`,`email`,`status`,`role`,`ai_style`,`reg_source`,`agree_privacy_at`,`last_login_at`,`last_login_ip`) VALUES
  (1,'admin','$2a$10$id9Gedd6teYxH8Hs3mqEn.0xmVK5CceWZOE4/LyChAEYhl8nuIjUu','心屿管理员',NULL,NULL,'ACTIVE','SUPER','rational','seed',CURRENT_TIMESTAMP(3),NULL,NULL),
  (2,'demo01','$2a$10$id9Gedd6teYxH8Hs3mqEn.0xmVK5CceWZOE4/LyChAEYhl8nuIjUu','屿01',NULL,NULL,'ACTIVE','USER','warm','seed',CURRENT_TIMESTAMP(3),NULL,NULL),
  (3,'demo02','$2a$10$id9Gedd6teYxH8Hs3mqEn.0xmVK5CceWZOE4/LyChAEYhl8nuIjUu','屿02',NULL,NULL,'ACTIVE','USER','rational','seed',CURRENT_TIMESTAMP(3),NULL,NULL),
  (4,'demo03','$2a$10$id9Gedd6teYxH8Hs3mqEn.0xmVK5CceWZOE4/LyChAEYhl8nuIjUu','屿03',NULL,NULL,'ACTIVE','USER','humorous','seed',CURRENT_TIMESTAMP(3),NULL,NULL),
  (5,'demo04','$2a$10$id9Gedd6teYxH8Hs3mqEn.0xmVK5CceWZOE4/LyChAEYhl8nuIjUu','屿04',NULL,NULL,'ACTIVE','USER','warm','seed',CURRENT_TIMESTAMP(3),NULL,NULL),
  (6,'demo05','$2a$10$id9Gedd6teYxH8Hs3mqEn.0xmVK5CceWZOE4/LyChAEYhl8nuIjUu','屿05',NULL,NULL,'ACTIVE','USER','rational','seed',CURRENT_TIMESTAMP(3),NULL,NULL);

-- 资料：年级与兴趣标签决定冷启动标签召回（需求 §6.3），情绪分享单独同意仅 demo01 授予
INSERT IGNORE INTO `user_profile`
  (`user_id`,`grade`,`school`,`gender`,`interest_tags`,`bio`,`onboarding_done`,`emotion_share_consent`) VALUES
  (1,'OTHER',NULL,'UNSET','["情绪日记","兼职避坑"]','系统账号，不参与社区互动',1,0),
  (2,'FRESH',NULL,'UNSET','["失眠夜","考试周","社恐互助"]','演示账号 1，只用于本地联调',1,1),
  (3,'SOPH',NULL,'UNSET','["考研加油站","情绪日记"]','演示账号 2，只用于本地联调',1,0),
  (4,'JUNIOR',NULL,'UNSET','["秋招焦虑","宿舍关系","自律打卡"]','演示账号 3，只用于本地联调',1,0),
  (5,'SENIOR',NULL,'UNSET','["毕业倒计时","原生家庭"]','演示账号 4，只用于本地联调',1,0),
  (6,'OTHER',NULL,'UNSET','["失恋博物馆","和抑郁共处","独居日记"]','演示账号 5，只用于本地联调',1,0);

-- 同意留痕：每人两条（用户协议 / 隐私政策），本表只追加不覆盖，故显式给 id 保证幂等
-- 列集合注意：user_consent 无 updated_at、无 deleted（需求 §7.2 #3 追加式审计表）
INSERT IGNORE INTO `user_consent` (`id`,`user_id`,`consent_type`,`action`,`content_version`,`source_page`,`ip`,`user_agent`) VALUES
  (1,1,'TERMS','GRANT','v1.0','register_modal',NULL,'seed/09_seed.sql'),
  (2,1,'PRIVACY','GRANT','v1.0','register_modal',NULL,'seed/09_seed.sql'),
  (3,2,'TERMS','GRANT','v1.0','register_modal',NULL,'seed/09_seed.sql'),
  (4,2,'PRIVACY','GRANT','v1.0','register_modal',NULL,'seed/09_seed.sql'),
  (5,3,'TERMS','GRANT','v1.0','register_modal',NULL,'seed/09_seed.sql'),
  (6,3,'PRIVACY','GRANT','v1.0','register_modal',NULL,'seed/09_seed.sql'),
  (7,4,'TERMS','GRANT','v1.0','register_modal',NULL,'seed/09_seed.sql'),
  (8,4,'PRIVACY','GRANT','v1.0','register_modal',NULL,'seed/09_seed.sql'),
  (9,5,'TERMS','GRANT','v1.0','register_modal',NULL,'seed/09_seed.sql'),
  (10,5,'PRIVACY','GRANT','v1.0','register_modal',NULL,'seed/09_seed.sql'),
  (11,6,'TERMS','GRANT','v1.0','register_modal',NULL,'seed/09_seed.sql'),
  (12,6,'PRIVACY','GRANT','v1.0','register_modal',NULL,'seed/09_seed.sql');

-- demo01 额外授予情绪内容分享单独同意（PIPL 第 29 条），与 user_profile.emotion_share_consent=1 同步
INSERT IGNORE INTO `user_consent` (`id`,`user_id`,`consent_type`,`action`,`content_version`,`source_page`,`ip`,`user_agent`) VALUES
  (13,2,'EMOTION_SHARE','GRANT','v1.0','emotion_share_modal',NULL,'seed/09_seed.sql');

-- -----------------------------------------------------------------------------
-- 自检（手工执行，勿放入自动流程）：
-- SELECT (SELECT COUNT(*) FROM sys_config) cfg, (SELECT COUNT(*) FROM sensitive_word_group) grp,
--        (SELECT COUNT(*) FROM sensitive_word) word, (SELECT COUNT(*) FROM topic) topic,
--        (SELECT COUNT(*) FROM `user`) usr, (SELECT COUNT(*) FROM user_profile) prof,
--        (SELECT COUNT(*) FROM user_consent) consent;
-- 期望 21 / 7 / 139 / 20 / 6 / 6 / 13；并核对 word_cnt 与分组实收：
-- SELECT g.id, g.name, g.word_cnt, COUNT(w.id) real_cnt FROM sensitive_word_group g
--   LEFT JOIN sensitive_word w ON w.group_id = g.id GROUP BY g.id HAVING g.word_cnt <> COUNT(w.id);
-- 该查询应返回空集。

