package com.mindisle.audit;

/**
 * 文本归一化（任务 3.2 · 需求 FR7.1 的「变体绕过」防线）。
 *
 * <p>敏感词引擎最容易被打穿的地方不是词库不全，而是写法变形：「枪支弹药」写成全角空格分隔、
 * 写成 {@code 枪-支-弹-药}（连字符）、写成 {@code 槍支彈藥}（繁体）、
 * 用 emoji 或零宽空格（U+200B）把字隔开、把 {@code p2p} 写成 {@code р2р}（西里尔同形字）。
 * 本类做的事就是把这些写法压回同一种形态，让一条词库规则覆盖全部变体。</p>
 *
 * <p><b>处理顺序（顺序不能换）</b>：全半角折叠 → {@code toLowerCase} → 同形/异体字映射 → 判定是否忽略。
 * 先折叠再判忽略，才能把全角减号 {@code U+FF0D}（折叠成 {@code '-'}）一并当作分隔符删掉。</p>
 *
 * <p><b>刻意删除的字符</b>：空白与控制符、零宽字符与双向控制符（U+200B–U+206F 整段，
 * 其中也包含项目符号、破折号、引号等「装饰性分隔」）、组合附加符、变体选择符、
 * 私用区、片假名中黑点（U+30FB）、半角连字符、emoji 与杂项符号段。删除而不是替换成空格，
 * 是为了让 {@code 敏·感·词} 与 {@code 敏感词} 得到同一个串。</p>
 *
 * <p><b>已知的取舍</b>：删除空白会把数字串首尾相接，于是「2026 年 9 月 20 日 138xxxx8000」
 * 这类文本有可能命中「16–19 位连续数字」的银行卡正则。这里选择「宁可多进人审，不可漏放」，
 * 因为隐私泄露组的 level 是 grey、action 是 REVIEW（需求 §18.3：只预警不删帖），
 * 误报的代价是一次人工查看，漏报的代价是一次真实泄露。</p>
 *
 * <p><b>位置回写</b>：{@link Normalized#sourceIndex()} 记录归一化串每个 UTF-16 单元
 * 对应的原文下标（代理对的第二个单元指向同一码位起点），命中区间因此能还原成
 * 前端可直接切片的原文偏移，供 U5 发布页做高亮。</p>
 */
public final class TextNormalizer {

    private TextNormalizer() {
    }

    /**
     * 归一化结果。
     *
     * @param raw         原文，保留它是为了让 {@link #toRawRange(int, int)} 能自算区间末尾
     * @param text        归一化后的串，匹配一律在它上面做
     * @param sourceIndex 长度等于 {@code text.length()}，{@code sourceIndex[i]} 为 {@code text} 第 i 个单元的原文下标
     */
    public record Normalized(String raw, String text, int[] sourceIndex) {

        /**
         * 把归一化坐标系里的左闭右开区间换算成原文坐标系。
         *
         * <p>区间<b>起点</b>取第一个命中单元的原文下标、<b>终点</b>取最后一个命中单元那个字符的末尾，
         * 因此夹在命中内容<b>中间</b>的被删分隔符会一并被覆盖（高亮「枪 支 弹 药」是完整一段），
         * 而命中<b>之后</b>的空格、标点不会被顺带划进去——否则前端会把一句正常文本的尾巴标成红色。</p>
         *
         * <p>越界与退化区间不抛异常，只做夹紧：位置只是给前端画高亮用的提示信息，
         * 不该有任何一条链路因为偏移算错而 500。</p>
         */
        public int[] toRawRange(int start, int end) {
            int length = raw.length();
            if (sourceIndex.length == 0 || start < 0 || end <= start || end > sourceIndex.length) {
                return new int[] {clamp(start, length), clamp(end, length)};
            }
            int from = sourceIndex[start];
            int last = sourceIndex[end - 1];
            return new int[] {from, last + Character.charCount(raw.codePointAt(last))};
        }

        private static int clamp(int index, int length) {
            return Math.max(0, Math.min(index, length));
        }
    }

    /** 归一化。入参为 null 时返回空结果，调用方（发帖、评论、AI 输入）不必各自判空。 */
    public static Normalized normalize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new Normalized(raw == null ? "" : raw, "", new int[0]);
        }
        StringBuilder folded = new StringBuilder(raw.length());
        int[] index = new int[raw.length()];
        int size = 0;
        int cursor = 0;
        while (cursor < raw.length()) {
            int cp = raw.codePointAt(cursor);
            int normalized = fold(cp);
            if (!isIgnored(normalized)) {
                folded.appendCodePoint(normalized);
                // 代理对的第二个单元也指回该码位在原文中的起点，保证下标数组与归一化串等长。
                for (int k = 0; k < Character.charCount(cp); k++) {
                    index[size++] = cursor;
                }
            }
            cursor += Character.charCount(cp);
        }
        int[] sourceIndex = new int[size];
        System.arraycopy(index, 0, sourceIndex, 0, size);
        return new Normalized(raw, folded.toString(), sourceIndex);
    }

    /** 单码位折叠：全半角 → 小写 → 同形/异体字表。 */
    private static int fold(int cp) {
        int folded = cp;
        if (folded >= 0xFF01 && folded <= 0xFF5E) {
            // 全角 ASCII 与半角相差固定 0xFEE0，一次减法就够，不必查表。
            folded -= 0xFEE0;
        }
        folded = Character.toLowerCase(folded);
        return foldLookalike(folded);
    }

    /**
     * 同形字与异体字最小表：繁→简 + 西里尔→拉丁。
     *
     * <p>刻意不做「完整 OpenCC 式繁简转换」：本项目词库只有 139 条，
     * 引一个 10 万字节的转换字典属于需求 §12 禁止的过度设计。
     * 表里每一项都对应词库中真实存在的字，新增词条时按需补行即可。</p>
     */
    private static int foldLookalike(int cp) {
        return switch (cp) {
            case 0x0410 -> 0x0061;  // А -> a
            case 0x0412 -> 0x0062;  // В -> b
            case 0x0415 -> 0x0065;  // Е -> e
            case 0x041A -> 0x006B;  // К -> k
            case 0x041C -> 0x006D;  // М -> m
            case 0x041D -> 0x0068;  // Н -> h
            case 0x041E -> 0x006F;  // О -> o
            case 0x0420 -> 0x0070;  // Р -> p
            case 0x0421 -> 0x0063;  // С -> c
            case 0x0422 -> 0x0074;  // Т -> t
            case 0x0423 -> 0x0079;  // У -> y
            case 0x0425 -> 0x0078;  // Х -> x
            case 0x0430 -> 0x0061;  // а -> a
            case 0x0435 -> 0x0065;  // е -> e
            case 0x043E -> 0x006F;  // о -> o
            case 0x0440 -> 0x0070;  // р -> p
            case 0x0441 -> 0x0063;  // с -> c
            case 0x0443 -> 0x0079;  // у -> y
            case 0x0445 -> 0x0078;  // х -> x
            case 0x0475 -> 0x0079;  // ѵ -> y
            case 0x0513 -> 0x006C;  // ԓ -> l
            case 0x4E82 -> 0x4E71;  // 亂 -> 乱
            case 0x4F86 -> 0x6765;  // 來 -> 来
            case 0x500B -> 0x4E2A;  // 個 -> 个
            case 0x5011 -> 0x4EEC;  // 們 -> 们
            case 0x5B78 -> 0x5B66;  // 學 -> 学
            case 0x5BE6 -> 0x5B9E;  // 實 -> 实
            case 0x5C0D -> 0x5BF9;  // 對 -> 对
            case 0x5C0E -> 0x5BFC;  // 導 -> 导
            case 0x5E63 -> 0x5E01;  // 幣 -> 币
            case 0x5F8C -> 0x540E;  // 後 -> 后
            case 0x5F9E -> 0x4ECE;  // 從 -> 从
            case 0x611B -> 0x7231;  // 愛 -> 爱
            case 0x61C9 -> 0x5E94;  // 應 -> 应
            case 0x65BC -> 0x4E8E;  // 於 -> 于
            case 0x6703 -> 0x4F1A;  // 會 -> 会
            case 0x69CD -> 0x67AA;  // 槍 -> 枪
            case 0x6BBA -> 0x6740;  // 殺 -> 杀
            case 0x70BA -> 0x4E3A;  // 為 -> 为
            case 0x7523 -> 0x4EA7;  // 産 -> 产
            case 0x7576 -> 0x5F53;  // 當 -> 当
            case 0x7661 -> 0x75F4;  // 癡 -> 痴
            case 0x767C -> 0x53D1;  // 發 -> 发
            case 0x78BC -> 0x7801;  // 碼 -> 码
            case 0x7DB2 -> 0x7F51;  // 網 -> 网
            case 0x7F75 -> 0x9A82;  // 罵 -> 骂
            case 0x807D -> 0x542C;  // 聽 -> 听
            case 0x8207 -> 0x4E0E;  // 與 -> 与
            case 0x85E5 -> 0x836F;  // 藥 -> 药
            case 0x861A -> 0x85D3;  // 蘚 -> 藓
            case 0x865F -> 0x53F7;  // 號 -> 号
            case 0x8667 -> 0x4E8F;  // 虧 -> 亏
            case 0x87FB -> 0x8681;  // 蟻 -> 蚁
            case 0x8831 -> 0x86CA;  // 蠱 -> 蛊
            case 0x88E1 -> 0x91CC;  // 裡 -> 里
            case 0x8A0A -> 0x8BAF;  // 訊 -> 讯
            case 0x8A71 -> 0x8BDD;  // 話 -> 话
            case 0x8AAA -> 0x8BF4;  // 說 -> 说
            case 0x8ACB -> 0x8BF7;  // 請 -> 请
            case 0x8B3E -> 0x8C29;  // 謾 -> 谩
            case 0x8B49 -> 0x8BC1;  // 證 -> 证
            case 0x8B93 -> 0x8BA9;  // 讓 -> 让
            case 0x8CED -> 0x8D4C;  // 賭 -> 赌
            case 0x9019 -> 0x8FD9;  // 這 -> 这
            case 0x904E -> 0x8FC7;  // 過 -> 过
            case 0x91AB -> 0x533B;  // 醫 -> 医
            case 0x9280 -> 0x94F6;  // 銀 -> 银
            case 0x95DC -> 0x5173;  // 關 -> 关
            case 0x967D -> 0x9633;  // 陽 -> 阳
            case 0x96AA -> 0x9669;  // 險 -> 险
            case 0x982D -> 0x5934;  // 頭 -> 头
            case 0x98A8 -> 0x98CE;  // 風 -> 风
            case 0x9A30 -> 0x817E;  // 騰 -> 腾
            case 0x9B31 -> 0x90C1;  // 鬱 -> 郁
            case 0x5F48 -> 0x5F39;  // 彈 -> 弹
            case 0x846F -> 0x836F;  // 葯 -> 药
            case 0x705F -> 0x70DF;  // 灟 -> 烟
            default -> cp;
        };
    }

    /** 是否作为分隔符删除（入参已折叠，故全角符号在此看到的是半角形态）。 */
    public static boolean isIgnored(int cp) {
        return cp <= 0x20                    // 控制符与空格
                || (cp >= 0x7F && cp <= 0xA0)  // DEL、C1 控制符、不换行空格
                || cp == 0xAD                    // 软连字符
                || cp == 0xB7                    // 间隔号「·」，中文最常见的词内分隔写法（敏·感·词）
                || cp == 0x2D                    // 连字符（含全角 U+FF0D 折叠后的形态）
                || cp == 0x3000                  // 全角空格
                || (cp >= 0x300 && cp <= 0x36F)  // 组合附加符（重点、变音符）
                || (cp >= 0x2000 && cp <= 0x206F) // 空白/零宽/双向控制/破折号/引号/项目符号
                || (cp >= 0x2E00 && cp <= 0x2E7F) // 修饰符与标点补充
                || (cp >= 0xFE00 && cp <= 0xFE0F) // 变体选择符
                || (cp >= 0xFFF9 && cp <= 0xFFFB) // 行间注记
                || cp == 0x30FB                   // 片假名中黑点，常见于「敏・感・词」
                || (cp >= 0xE000 && cp <= 0xF8FF)  // 私用区（改字体绕过）
                // emoji 与杂项符号段：「敏\uD83D\uDE0A感」应当等于「敏感」
                || (cp >= 0x1F000 && cp <= 0x1FAFF)
                || (cp >= 0x1FC00 && cp <= 0x1FFFD)
                || (cp >= 0x2300 && cp <= 0x23FF)
                || (cp >= 0x2600 && cp <= 0x27BF)
                || (cp >= 0x2B00 && cp <= 0x2BFF);
    }
}
