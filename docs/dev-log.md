# 开发日志（dev-log）—— 心屿 MindIsle · 009

> 约定：每过一个 Gate、每踩一个新坑，**当场**追加，不事后补写。
> 用途：论文第 6 章「系统与实验实现过程」的过程性证据；答辩演示前的变更记录母本。

---

## 2026-09-18 阶段 0 —— 环境与仓库地基

### 已完成，附实测证据（是结果，不是计划）

| 任务 | 状态 | 证据 |
|---|---|---|
| 0.1 目录 | 完成 | `_tools`、`_cache\m2\repository`、`_cache\downloads`、`_cache\npm`、`_cache\pip` 全部建于 E 盘 |
| 0.2 Maven | 完成 | dlcdn 下载 9 395 475 B，解压至 `_tools\apache-maven-3.9.16`；`mvn -v` → `Apache Maven 3.9.16 (2bdd9fddda4b155ebf8000e807eb73fd829a51d5)`，`platform encoding: UTF-8` |
| 0.3 本地仓库 | 完成 | `mvn help:evaluate -Dexpression=settings.localRepository -q -DforceStdout` → `E:\codex workspace\_cache\m2\repository` |
| 0.4 Redis | 完成（方案 B） | 方案 A 失败：Docker 客户端 29.8.0 在，但 `dockerDesktopLinuxEngine` 25 s 无响应、`wsl -l` 报未安装（装 WSL 需管理员+重启，超出毕设必要）。改用 `redis-windows` 便携版 **7.2.16**（10 955 482 B），`redis-cli ping` → `PONG` |
| 0.5 建库 | **待用户执行** | `mysql -u root -e ...` → `ERROR 1045 Access denied (using password: NO)`。口令不由我持有、不写文件，改由 `docs/init-db.ps1` 用 `Read-Host -AsSecureString` 交互输入，临时明文 SQL 用后即删 |
| 0.6 包管理缓存 | 完成 | `npm config get cache` → `E:\codex workspace\_cache\npm`；`pip config list` → `global.cache-dir` 指 E 盘。（注：`pip.ini` 本体位于 `C:\Users\...\Roaming\pip\`，系 pip 唯一支持的配置路径；**只有配置在 C，缓存数据全在 E**） |
| 0.7 仓库 | 完成 | `.gitignore`（668 B / 无 BOM / 46 行，**先于任何代码创建**）、`.gitattributes`（589 B，锁行尾口径）、`git init -b main` |
| 0.8 自检脚本 | 完成 | `docs/check-env.ps1`（4 200 B / 纯 ASCII / 免 BOM），已做**正反两次**验证 |
| 0.9 凭据约定 | 完成 | `.env.example`（866 B）；真实 `.env` 已被 ignore；`JWT_SECRET` 给生成命令不给示例值 |
| 0.10 DeepSeek 探活 | **待用户 Key** | 本机无 `DEEPSEEK_API_KEY`。未凭记忆写死模型名：`.env.example` 填 `deepseek-flash` 并注明「以 `/models` 实测为准」 |

### 本日新增坑

1. **检查脚本会「说谎」**（最值得记的一条）。`chk 'mvn' [bool](Get-Command mvn) 'hint'` 里，PowerShell 把 `[bool]` 当作**独立字符串参数**绑给 `$ok`（非空即为真），`(Get-Command mvn)` 的结果被挤到 `$hint` 位置——于是 **mvn 没装也打印 `[OK]`**。
   修法：传给函数的表达式**必须整体加括号** → `([bool](Get-Command mvn))`。
   验证方式：**先在 mvn 不在 PATH 的 shell 里跑一次**，确认它报 `[!!] False`；再把 PATH 加回去跑一次确认 `[OK] True`。只跑一次全绿等于没测。
2. **msys2 版 Redis 不吃绝对路径配置文件**。传 `E:\...\mindisle.conf` 时被按 POSIX 解析成 `/E:\...`，直接 `Fatal error, can't open config file`。且 Redis 配置行按空格切分，`dir` 值含空格会**被静默截断**。
   修法：`Start-Process -WorkingDirectory $dir -ArgumentList 'mindisle.conf'`，配置内写相对 `dir data`。
3. **stock `settings.xml` 的 `<localRepository>` 示例行在注释块里**。用 `-notmatch '<localRepository>'` 判断「是否已配置」会被注释里的示例骗过，导致既没加真值又误判成功；而按 `^<settings[\s>]+` 定位开标签插入，会落进**跨行标签内部**，Maven 直接 `Non-parseable settings ... start tag unexpected character < @48:4`。
   修法：定位「以 `>` 结束 schemaLocation 的那一行」之后再插；改完必须 `mvn help:evaluate` 做**功能级**验证，不能只看 XML 能否解析。
4. **环境体检不能只看 PATH**。上一轮据 `Get-Command` 判定「MySQL 非服务态」，实际 `Get-Service MySQL` = Running / Automatic、3306 在监听——PATH 缺失只说明没进命令行环境，不代表服务没跑。服务类组件必须 `Get-Service` + 端口双查。

### 许可与合规决定

- 选 Redis **7.2.16** 而非最新 7.4.11 / 8.10.2：Redis 自 7.4 起改三重许可（RSALv2/SSPLv1/AGPLv3），**7.2 及以前仍是 BSD-3-Clause**。本机构建仅作开发期本地依赖、不随项目分发，但为避免答辩被追问许可，直接取 BSD 版本。
- `redis-windows` 是第三方 Windows 移植构建（非 Redis 官方发行），最近发布 2026-08-18 属活跃维护；其 `data/` 已在 `.gitignore` 内，二进制不进仓库。
- MySQL 沿用 C 盘现有安装（用户 2026-09-18 选定方案 ②「就地启用 + 记例外」），例外已写入《全局复利与踩坑日志》；**阶段 8 前**评估是否迁 E 盘或换 Docker。

### 下一步

- 用户执行 `docs\init-db.ps1` 完成 0.5；提供 DeepSeek Key 完成 0.10（`docs/deepseek-models.json` 落盘）。
- 随即进 **阶段 1B**（开题报告 + 文献综述 + 23 页线框图，2026-10 上旬截止）与 **阶段 2**（工程骨架 + 30 表）。