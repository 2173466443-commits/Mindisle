# 第三方代码来源（vendored）

| 文件 | 来源 | 版本 | 许可 | 改动 |
|---|---|---|---|---|
| `word-cloud.esm.js` | [@echarts-x/custom-word-cloud](https://github.com/apache/echarts-custom-series) `dist/word-cloud.esm.mjs` | 1.0.1 | Apache License 2.0 | **仅删掉 `import { number } from 'echarts';` 一行**，替换为文件头注释里的本地 `parsePercent`（语义照抄 `echarts/lib/util/number.js`）。词云布局算法零改动。 |

## 为什么要 vendor 而不是直接 import npm 包

`echarts@6.1.0` 的 `package.json` 把 `index.js` 标进了 `sideEffects`，Rollup 因此不会
对全量入口做 tree-shake。上游 dist 里那一句 `import { number } from 'echarts'` 会让整个
echarts（含地图、关系图、桑基图、主题、i18n…）进入引用它的路由 chunk。

A/B 实测（`npm run build`，同一份 `EmotionView.vue`，只换 import 来源）：

| 引用方式 | chunk 体积 | gzip |
|---|---|---|
| `@echarts-x/custom-word-cloud`（npm 原样） | 1,146.50 kB | 382.45 kB |
| `@/vendor/word-cloud.esm.js`（本地副本） | 625.39 kB | 212.14 kB |

**差 521.11 kB（min）/ 170.31 kB（gzip）**，全部是 echarts 的无关模块。情绪档案页是答辩
现场演示要走的路由，这个预算省得下来就该省。

## 许可合规

Apache-2.0 允许再分发与修改，条件是保留版权与许可声明。因此：

1. 副本文件头保留了上游原样的 ASF License 头（未删）；
2. 上游包内 `LICENSE`（Apache-2.0 全文）与 `NOTICE`（"Apache ECharts Custom Series /
   Copyright 2017-2026 The Apache Software Foundation"）的声明以本文件与文件尾注释的形式随
   副本一并传递；
3. 修改点在文件头注释与本表中明示，符合 Apache-2.0 §4(b)「标注修改」的要求。

echarts 本体仍以 npm 依赖方式使用（`echarts/core` + 按需 `echarts.use([...])`），未做任何修改。
