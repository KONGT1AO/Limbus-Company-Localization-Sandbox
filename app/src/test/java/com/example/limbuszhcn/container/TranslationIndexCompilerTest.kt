package com.example.limbuszhcn.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.nio.file.Files
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.readText

/** 验证全文冲突、词典保护、短术语安全边界与索引原子发布。 */
class TranslationIndexCompilerTest {
    /** 纯标点仍能整句汉化，但不能作为任意字符串内部的替换片段。 */
    @Test
    fun keepsPunctuationTranslationsExactWithoutRewritingCodesAsEmbeddedTerms() {
        val compiler = TranslationIndexCompiler().apply {
            add(record("UI.json", "question", "content", "??", "？？"))
            add(record("UI.json", "ellipsis", "content", "...。", "…"))
        }
        val compiled = compiler.compile("test", "archive")
        assertEquals("？？", compiled.uniqueEntries["??"])
        assertEquals("…", compiled.uniqueEntries["...。"])
        assertTrue(compiled.termEntries.isEmpty())
    }

    /** 无上下文的同源异译不进入严格索引，但必须保留中文全文回退。 */
    @Test
    fun keepsOnlyUnambiguousSourcesInFastIndexAndRetainsConflictFallback() {
        val compiler = TranslationIndexCompiler()
        compiler.add(record("MainUIText.json", "1", "content", "Sinner", "罪人"))
        compiler.add(record("OtherUIText.json", "2", "content", "Sinner", "罪人"))
        compiler.add(record("Battle.json", "3", "dialog", "Ready", "准备"))
        compiler.add(record("Story.json", "4", "dialog", "Ready", "出发"))
        compiler.add(record("Same.json", "5", "teller", "Dante", "Dante"))

        val compiled = compiler.compile("2026071001", "archive")

        assertEquals(mapOf("Sinner" to "罪人"), compiled.uniqueEntries)
        assertEquals("罪人", compiled.termEntries["Sinner"])
        assertEquals("准备", compiled.fallbackEntries["Ready"])
        assertEquals(4, compiled.sourceRecordCount)
        assertEquals(1, compiled.conflictCount)
        assertEquals(0, compiled.dominantEntryCount)
        assertEquals(64, compiled.inputSha256.length)
    }

    /** 从明确术语派生括号或富文本内部名称，同时把扩展词限制为全文匹配。 */
    @Test
    fun resolvesDominantTerminologyAndExtractsBracketedTerms() {
        val compiler = TranslationIndexCompiler()
        repeat(10) { index ->
            compiler.add(record("Main$index.json", index.toString(), "content", "囚人", "罪人"))
        }
        compiler.add(record("Story.json", "other", "title", "囚人", "罪人们"))
        compiler.add(record("Keyword.json", "before", "content", "[攻撃前]", "[攻击前]"))
        compiler.add(record("Keyword.json", "tremor", "name", "振動", "震颤"))
        compiler.add(record("Keyword.json", "above", "content", "以上", "或以上"))
        compiler.add(record(
            "UnitKeyword.json",
            "sister",
            "content",
            "<color=#d40000><s>長姉</s></color>",
            "<color=#d40000><s>长姊</s></color>"
        ))
        compiler.add(record(
            "Keyword.json",
            "formatted-bracket-name",
            "name",
            "代行[テスト指令]",
            "代行[测试指令]"
        ))

        val compiled = compiler.compile("2026071001", "archive")

        assertEquals("罪人", compiled.uniqueEntries["囚人"])
        assertEquals("罪人", compiled.termEntries["囚人"])
        assertEquals("攻击前", compiled.termEntries["攻撃前"])
        assertEquals("震颤", compiled.termEntries["振動"])
        assertEquals("长姊", compiled.termEntries["長姉"])
        assertEquals("测试指令", compiled.termEntries["テスト指令"])
        assertEquals("或以上", compiled.uniqueEntries["以上"])
        assertFalse(compiled.termEntries.containsKey("以上"))
        assertEquals(1, compiled.dominantEntryCount)
    }

    /** 验证剧情、章节关联文本和解锁编号均进入 schema 8 显示字段策略。 */
    @Test
    fun includesVerifiedStoryAndUnlockDisplayFields() {
        val compiler = TranslationIndexCompiler().apply {
            add(record("Guide.json", "story", "story", "記録を読む。", "阅读记录。"))
            add(record("Unlock.json", "chapter", "relatedChapterText", "第九章", "第九章"))
            add(record("Unlock.json", "condition", "openConditionNumber", "九・五―十四", "9.5-14"))
        }

        val compiled = compiler.compile("2026080601", "archive")

        assertEquals("阅读记录。", compiled.uniqueEntries["記録を読む。"])
        assertEquals("9.5-14", compiled.uniqueEntries["九・五―十四"])
        assertFalse(compiled.uniqueEntries.containsKey("第九章"))
        assertTrue(TranslationTextPolicy.isDisplayField("story"))
        assertTrue(TranslationTextPolicy.isDisplayField("relatedChapterText"))
        assertTrue(TranslationTextPolicy.isDisplayField("openConditionNumber"))
    }

    /** 验证确定损坏的 Unicode/control 文本被跳过，而剧情符号文本仍被保留。 */
    @Test
    fun rejectsDefinitivelyCorruptedDisplayText() {
        val compiler = TranslationIndexCompiler().apply {
            add(record("Broken.json", "replacement", "content", "壊れた一", "损坏\uFFFD文本"))
            add(record("Broken.json", "control", "content", "壊れた二", "损坏\u0085文本"))
            add(record("Good.json", "symbol", "content", "信号", "?!>@!#$@!?!>#!#!"))
        }

        val compiled = compiler.compile("2026080601", "archive")

        assertFalse(compiled.uniqueEntries.containsKey("壊れた一"))
        assertFalse(compiled.uniqueEntries.containsKey("壊れた二"))
        assertEquals("?!>@!#$@!?!>#!#!", compiled.uniqueEntries["信号"])
    }

    /** 验证格式化术语全部从下载包派生，并优先采用包内关键词词典。 */
    @Test
    fun derivesFormattedTermsFromDownloadedGlossaries() {
        val compiler = TranslationIndexCompiler().apply {
            // 普通页面即使重复使用同一日文，也不能压过 Bufs 中的正式关键词译名。
            repeat(4) { index ->
                add(record("Passives.json", "passive-$index", "name", "呼吸", "喘息未定"))
            }
            add(record("Bufs.json", "Breath", "name", "呼吸", "呼吸法"))
            add(record("Bufs-a1c9p1.json", "KarmaOfIndexAlly", "name", "カルマ", "业"))
            add(record(
                "Bufs-a1c9p3.json",
                "StackRienSpecialSkill",
                "name",
                "代行[ヘルメス]",
                "代行[赫尔墨斯]"
            ))
            add(record(
                "Bufs-a1c9p3.json",
                "coin-condition",
                "desc",
                "- 打撃ダメージを与える\n- 破壊されずに命中時、自身に呼吸を2付与",
                "- 造成打击伤害\n- 未摧毁并命中时，使自身获得2层呼吸法"
            ))
        }

        val compiled = compiler.compile("2026080601", "archive")

        assertEquals("呼吸法", compiled.uniqueEntries["呼吸"])
        assertFalse(compiled.termEntries.containsKey("呼吸"))
        assertEquals("业", compiled.termEntries["カルマ"])
        assertEquals("赫尔墨斯", compiled.termEntries["ヘルメス"])
        assertEquals("未摧毁并命中时", compiled.termEntries["破壊されずに命中時"])
        assertFalse(compiled.termEntries.containsKey("喘息未定"))
    }

    /** formatter 压缩空格后的长词典名称仍必须完整替换，不能留下半中半日。 */
    @Test
    fun derivesCompactAliasesForLongCanonicalGlossaryNames() {
        val compiler = TranslationIndexCompiler().apply {
            add(record(
                "jp/JP_Bufs.json", "NoirBindArmorAlly", "name",
                "ル・ノワール ウーステッド生地コート", "黑派 精纺面料大衣"
            ))
            add(record(
                "jp/JP_BattleKeywords.json", "TimeToListenHonglu", "name",
                "傾聴の時間", "倾听的时间"
            ))
            add(record(
                "jp/JP_BattleKeywords.json", "ReadyToListenHonglu", "name",
                "朗読の準備", "朗读准备"
            ))
        }

        val compiled = compiler.compile("2026103102", "archive")

        assertEquals(
            "黑派 精纺面料大衣",
            compiled.termEntries["ル・ノワールウーステッド生地コート"]
        )
        assertEquals("倾听的时间", compiled.termEntries["傾聴の時間"])
        assertEquals("朗读准备", compiled.termEntries["朗読の準備"])
    }

    /** 验证生产 jp/JP_ 路径下词典权威优先级依然压过页面译名。 */
    @Test
    fun appliesGlossaryPriorityToProductionJapaneseSourcePaths() {
        val compiler = TranslationIndexCompiler().apply {
            repeat(4) { index ->
                add(record("jp/JP_Passives.json", "passive-$index", "name", "呼吸", "喘息未定"))
            }
            add(record("jp/JP_Bufs.json", "Breath", "name", "呼吸", "呼吸法"))
            add(record("jp/JP_LoginUIText.json", "retry", "content", "リトライ", "重试"))
            add(record("jp/JP_RailwayDungeonUI_2.json", "retry", "content", "リトライ", "再次挑战"))
            add(record("jp/JP_BattleKeywords.json", "retry", "name", "リトライ", "重试"))
        }

        val compiled = compiler.compile("2026102001", "archive")

        assertEquals("呼吸法", compiled.uniqueEntries["呼吸"])
        assertFalse(compiled.termEntries.containsKey("呼吸"))
        assertFalse(compiled.termEntries.containsKey("喘息未定"))
        assertEquals("重试", compiled.uniqueEntries["リトライ"])
    }

    /** 同字词典记录仍有语义：不能把技能“保存”替换为地牢 UI 的“检查点”。 */
    @Test
    fun protectsUnchangedGlossaryTermsFromUiHomographs() {
        val compiler = TranslationIndexCompiler().apply {
            repeat(4) { index ->
                add(record("jp/JP_StoryDungeonUI.json", "save-$index", "content", "保存", "检查点"))
            }
            add(record("jp/JP_Bufs-a1c10p1.json", "ChargeNoir", "name", "保存", "保存"))
            add(record("jp/JP_BattleKeywords.json", "ChargeNoirAlly", "name", "保存", "保存"))
            add(record("jp/JP_Bufs.json", "field", "name", "保存神域", "保存神域"))
            add(record("jp/JP_OtherUIText.json", "field", "content", "保存神域", "检查点神域"))
            add(record("jp/JP_Bufs.json", "long-field", "name", "保存神域 全体効果", "保存神域 全体効果"))
            add(record("jp/JP_OtherUIText.json", "long-field", "content", "保存神域 全体効果", "检查点神域"))
        }

        val compiled = compiler.compile("2026102001", "archive")

        assertEquals("保存", compiled.uniqueEntries["保存"])
        assertEquals("保存", compiled.termEntries["保存"])
        assertEquals("保存神域", compiled.uniqueEntries["保存神域"])
        assertEquals("保存神域", compiled.termEntries["保存神域"])
        assertEquals("保存神域 全体効果", compiled.uniqueEntries["保存神域 全体効果"])
    }

    /** 真实剧情和歌词短句只保留全文，语音及播报资源也不能污染跨页面术语。 */
    @Test
    fun excludesNarrativeContentFromEmbeddedTermsWithoutDroppingFullText() {
        val narrativeRecords = listOf(
            record("jp/StoryData/JP_S919B.json", "22", "content", "子。", "“孩子”。"),
            record(
                "jp/BgmLyrics/JP_BgmLyrics_Cromer2.json", "27", "content",
                "<style=\"djp\"><size=20>天空", "<style=\"djp\">一角<size=20>天空</size>"
            ),
            record("jp/PersonalityVoiceDlg/JP_Voice_Test.json", "1", "content", "同意する。", "我同意。"),
            record("jp/EGOVoiceDig/JP_Voice_Test.json", "1", "content", "静かに。", "安静。"),
            record("jp/BattleAnnouncerDlg/JP_Announcer_Test.json", "1", "content", "勝利だ。", "胜利了。"),
            record("jp/JP_BattleSpeechBubbleDlg.json", "1", "content", "始めよう。", "开始吧。")
        )
        val compiler = TranslationIndexCompiler().apply {
            narrativeRecords.forEach(::add)
            // 这些表虽然名称包含 Story/Voice，但承担设置和地牢界面，不是叙述正文。
            add(record("jp/JP_StoryDungeonUI.json", "rest", "content", "休憩", "休息"))
            add(record("jp/JP_StoryVoiceSettingUI.json", "confirm", "content", "確認", "确认"))
        }

        val compiled = compiler.compile("2026101602", "archive")

        narrativeRecords.forEach { record ->
            assertEquals(record.translation, compiled.uniqueEntries[record.source])
        }
        assertEquals(mapOf("休憩" to "休息", "確認" to "确认"), compiled.termEntries)
        assertFalse(TranslationTextPolicy.isTermSource("C:\\Localize\\StoryData\\JP_S919B.json", "content"))
        assertTrue(TranslationTextPolicy.isTermSource("jp/StoryData/JP_S919B.json", "name"))
    }

    /** 用已发现的但丁笔记与事件叙述回归，防止把逗号前任意片段当成条件术语。 */
    @Test
    fun derivesOnlyExplicitJapaneseTimingOrConditionClauses() {
        val geography = record(
            "jp/JP_StoryTheaterDanteNoteDetail_10.json", "DanteNoteDetail_139", "desc",
            "- 西部、東部、南部、北部によって協会の服飾が少しずつ異なるらしい。",
            "- 根据西部、东部、南部、北部的区分，协会的服饰似乎会有所不同。"
        )
        val narrative = record(
            "jp/JP_AbEvents_Refraction3.json", "857801", "successDesc",
            "当然、肌と筋肉が裂けて血がだらだらと流れたけど…。",
            "虽然，这导致他们的肌肉与身体被撕裂，血流如注……"
        )
        val compiler = TranslationIndexCompiler().apply {
            add(geography)
            add(narrative)
            add(record(
                "jp/JP_Skills.json", "conditions", "desc",
                "- 攻撃前、自身に効果を付与\n- 破壊されずに命中時、自身に効果を付与\n" +
                    "- 命中後、効果を解除\n- [体力が最大の場合]、効果を強化",
                "- 攻击前，使自身获得效果\n- 未摧毁并命中时，使自身获得效果\n" +
                    "- 命中后，解除效果\n- [体力为最大值时]，强化效果"
            ))
        }

        val compiled = compiler.compile("2026101602", "archive")

        assertEquals(geography.translation, compiled.uniqueEntries[geography.source])
        assertEquals(narrative.translation, compiled.uniqueEntries[narrative.source])
        assertFalse(compiled.termEntries.containsKey("西部"))
        assertFalse(compiled.termEntries.containsKey("当然"))
        assertEquals("攻击前", compiled.termEntries["攻撃前"])
        assertEquals("未摧毁并命中时", compiled.termEntries["破壊されずに命中時"])
        assertEquals("命中后", compiled.termEntries["命中後"])
        assertEquals("体力为最大值时", compiled.termEntries["体力が最大の場合"])
    }

    /** 扩展词不能进 trie，仍须保留规范化后的词典全文权威和同文跨度保护。 */
    @Test
    fun preservesCanonicalFullTextPriorityWhenExpandingTermsAreRejected() {
        val compiler = TranslationIndexCompiler().apply {
            repeat(4) { index ->
                add(record("jp/JP_Passives.json", "$index", "name", "呼吸", "喘息"))
            }
            // 游戏格式化器可能移除包内富文本，词典优先级仍须覆盖裸文本的其他译名。
            add(record("jp/JP_Bufs.json", "Breath", "name", "<b>呼吸</b>", "<b>呼吸法</b>"))
            add(record("jp/JP_BattleKeywords.json", "above", "content", "以上", "或以上"))
            add(record("jp/JP_Bufs.json", "save", "name", "保存", "保存"))
        }

        val compiled = compiler.compile("2026101602", "archive")

        assertEquals("<b>呼吸法</b>", compiled.uniqueEntries["<b>呼吸</b>"])
        assertEquals("呼吸法", compiled.uniqueEntries["呼吸"])
        assertEquals("或以上", compiled.uniqueEntries["以上"])
        assertFalse(compiled.termEntries.containsKey("呼吸"))
        assertFalse(compiled.termEntries.containsKey("以上"))
        assertEquals("保存", compiled.uniqueEntries["保存"])
        assertEquals("保存", compiled.termEntries["保存"])
    }

    /** 验证 2:1 多数派在放宽阈值后直接采纳，1:1 与 2:2 平票仍被丢弃。 */
    @Test
    fun adoptsTwoThirdsMajorityTranslations() {
        val compiler = TranslationIndexCompiler().apply {
            add(record("jp/JP_LoginUIText.json", "retry", "content", "リトライ", "重试"))
            add(record("jp/JP_MainUIText.json", "retry", "content", "リトライ", "重试"))
            add(record("jp/JP_RailwayDungeonUI_2.json", "retry", "content", "リトライ", "再次挑战"))
            add(record("jp/JP_Battle.json", "one", "content", "撤退", "退场"))
            add(record("jp/JP_Story.json", "two", "content", "撤退", "撤退失败"))
            add(record("jp/JP_StageA.json", "a", "content", "準備中", "准备中"))
            add(record("jp/JP_StageB.json", "b", "content", "準備中", "正在准备"))
            add(record("jp/JP_StageC.json", "c", "content", "準備中", "准备中"))
            add(record("jp/JP_StageD.json", "d", "content", "準備中", "正在准备"))
        }

        val compiled = compiler.compile("2026102001", "archive")

        assertEquals("重试", compiled.uniqueEntries["リトライ"])
        assertEquals(1, compiled.dominantEntryCount)
        assertFalse(compiled.uniqueEntries.containsKey("撤退"))
        assertFalse(compiled.uniqueEntries.containsKey("準備中"))
        assertEquals("撤退失败", compiled.fallbackEntries["撤退"])
        assertEquals("准备中", compiled.fallbackEntries["準備中"])
        assertEquals(2, compiled.conflictCount)
    }

    /** 恐慌说明同文异译仍须按稳定 ID 收集，并保留 PC 包中的多数中文全文。 */
    @Test
    fun retainsPanicDescriptionFallbackAcrossMixedResourceTranslations() {
        val compiler = TranslationIndexCompiler().apply {
            repeat(3) { index ->
                add(record(
                    "jp/JP_PanicInfo.json", "id=${9999 - index}", "panicDescription",
                    "1ターンの間行動しない", "一回合内无法行动。"
                ))
            }
            repeat(2) { index ->
                add(record(
                    "jp/JP_Bufs.json", "Inactible-$index", "desc",
                    "1ターンの間行動しない", "本回合内无法行动。"
                ))
            }
            add(record(
                "jp/JP_PanicInfo-a1c9p3.json", "id=1073", "panicDescription",
                "1ターンの間行動しない", "一回合内<noparse>无法行动</noparse>"
            ))
        }

        val compiled = compiler.compile("2026101501", "archive")

        assertFalse(compiled.uniqueEntries.containsKey("1ターンの間行動しない"))
        assertEquals("一回合内无法行动。", compiled.fallbackEntries["1ターンの間行動しない"])
        assertEquals(1, compiled.conflictCount)
    }

    /** 发布二进制索引和原子指针时，必须使用与 native 一致的 schema 14。 */
    @Test
    fun activatesImmutableBinaryIndexAndPointer() {
        val root = Files.createTempDirectory("limbus-index-test")
        val compiler = TranslationIndexCompiler().apply {
            add(record("MainUIText.json", "$[id=1]", "content", "Sinner", "罪人"))
        }
        val compiled = compiler.compile("2026071001", "archive-sha")

        val indexFile = TranslationIndexStore(root).activate(compiled)

        assertTrue(indexFile.exists())
        assertTrue(indexFile.parent.fileName.toString().contains("-s14-"))
        assertEquals(indexFile.toString().replace('\\', '/'), root.resolve("active-index.path").readText().trim())
        DataInputStream(indexFile.inputStream()).use { input ->
            assertEquals("LZTI1\u0000", String(input.readNBytes(6), Charsets.US_ASCII))
            assertEquals(14, input.readInt())
            assertEquals(1, input.readInt())
            assertEquals(1, input.readInt())
            assertEquals(0, input.readInt())
            assertEquals(1, input.readInt())
            assertEquals(0, input.readInt())
            assertEquals("Sinner", input.readSizedUtf8())
            assertEquals("罪人", input.readSizedUtf8())
            assertEquals("Sinner", input.readSizedUtf8())
            assertEquals("罪人", input.readSizedUtf8())
            assertEquals("id=1\u001fcontent\u001fSinner", input.readSizedUtf8())
            assertEquals("罪人", input.readSizedUtf8())
        }
        val properties = Properties().apply {
            indexFile.parent.resolve("manifest.properties").inputStream().use(::load)
        }
        assertEquals("14", properties.getProperty("schema"))
        assertEquals(TranslationIndexStore.COMPILER_VERSION, properties.getProperty("compilerVersion"))
        assertEquals("1", properties.getProperty("uniqueEntryCount"))
        assertEquals("1", properties.getProperty("termEntryCount"))
        assertEquals("0", properties.getProperty("fallbackEntryCount"))
        assertEquals("1", properties.getProperty("contextEntryCount"))
        assertEquals("0", properties.getProperty("battleHintEntryCount"))
        assertEquals("1", properties.getProperty("sourceRecordCount"))
        assertEquals("0", properties.getProperty("conflictCount"))
    }

    /** 冲突回退必须单独写入二进制索引，不能混入嵌入术语。 */
    @Test
    fun serializesFallbackWithoutAddingUnsafeEmbeddedTerms() {
        val root = Files.createTempDirectory("limbus-fallback-index-test")
        val compiler = TranslationIndexCompiler().apply {
            add(record("jp/JP_BattleUIText.json", "$[id=a]", "content", "詳細情報", "详情"))
            add(record("jp/JP_EGOSelectUI.json", "$[id=b]", "content", "詳細情報", "更多信息"))
        }
        val compiled = compiler.compile("2026103101", "archive-sha")

        val indexFile = TranslationIndexStore(root).activate(compiled)

        assertEquals("更多信息", compiled.fallbackEntries["詳細情報"])
        assertFalse(compiled.uniqueEntries.containsKey("詳細情報"))
        assertFalse(compiled.termEntries.containsKey("詳細情報"))
        DataInputStream(indexFile.inputStream()).use { input ->
            assertEquals("LZTI1\u0000", String(input.readNBytes(6), Charsets.US_ASCII))
            assertEquals(14, input.readInt())
            assertEquals(0, input.readInt())
            assertEquals(0, input.readInt())
            assertEquals(1, input.readInt())
            assertEquals(2, input.readInt())
            assertEquals(0, input.readInt())
            assertEquals("詳細情報", input.readSizedUtf8())
            assertEquals("更多信息", input.readSizedUtf8())
            assertEquals("id=a\u001fcontent\u001f詳細情報", input.readSizedUtf8())
            assertEquals("详情", input.readSizedUtf8())
            assertEquals("id=b\u001fcontent\u001f詳細情報", input.readSizedUtf8())
            assertEquals("更多信息", input.readSizedUtf8())
        }
    }

    /** 同一日文词在不同资源中含义不同，必须保留由稳定 ID 区分的精确映射。 */
    @Test
    fun keepsStableResourceIdentityForContextualTranslations() {
        val compiler = TranslationIndexCompiler().apply {
            add(record(
                "jp/JP_ProjectGS.json",
                "$[id=project_gs_condition_highest]",
                "content",
                "最高",
                "绝佳"
            ))
            add(record(
                "jp/JP_EGOgift_MirrorDungeon.json",
                "$.dataList[0][id=9127]",
                "desc",
                "[Breath]威力が最も高い",
                "[Breath]强度最高"
            ))
        }

        val compiled = compiler.compile("2026101501", "archive")

        assertEquals(
            "绝佳",
            compiled.contextEntries[
                "id=project_gs_condition_highest\u001fcontent\u001f最高"
            ]
        )
        assertEquals(
            "[Breath]强度最高",
            compiled.contextEntries[
                "id=9127\u001fdesc\u001f[Breath]威力が最も高い"
            ]
        )
        assertFalse(compiled.termEntries.containsKey("最高"))
    }

    /** 加载小贴士必须按资源表和稳定 ID 保存兼容译文，并与同 ID 的其他表隔离。 */
    @Test
    fun exposesBattleHintTranslationsByResourceTableAndId() {
        val root = Files.createTempDirectory("limbus-battle-hint-index-test")
        val compiler = TranslationIndexCompiler().apply {
            TranslationRecordCollector().collect(
                "jp/JP_BattleHint.json",
                """{"dataList":[{"id":"14","content":"戦闘時、人格は装備しているE.G.Oのうち最も等級の低いものの属性耐性を持った状態で始まります。"}]}""",
                """{"dataList":[{"id":"14","content":"战斗开始时，罪人的罪孽抗性为其所装备最低等级E.G.O的抗性。"}]}"""
            ).forEach(::add)
            TranslationRecordCollector().collect(
                "jp/JP_BattleHint_NormalBattle.json",
                """{"dataList":[{"id":"14","content":"通常戦闘用のヒント"}]}""",
                """{"dataList":[{"id":"14","content":"普通战斗提示"}]}"""
            ).forEach(::add)
            TranslationRecordCollector().collect(
                "jp/JP_BattleHint-a1c9p3.json",
                """{"dataList":[{"id":"14","content":"別ステージ用のヒント"}]}""",
                """{"dataList":[{"id":"14","content":"关卡专用提示"}]}"""
            ).forEach(::add)
        }
        val compiled = compiler.compile("2026110201", "archive-sha")

        assertEquals(
            "战斗开始时，罪人的罪孽抗性为其所装备最低等级E.G.O的抗性。",
            compiled.battleHintEntries["BattleHint\u001f14"]
        )
        assertEquals(
            "普通战斗提示",
            compiled.battleHintEntries["BattleHint_NormalBattle\u001f14"]
        )
        assertFalse(compiled.battleHintEntries.containsKey("BattleHint-a1c9p3\u001f14"))

        val indexFile = TranslationIndexStore(root).activate(compiled)
        DataInputStream(indexFile.inputStream()).use { input ->
            assertEquals("LZTI1\u0000", String(input.readNBytes(6), Charsets.US_ASCII))
            assertEquals(14, input.readInt())
            val entryCount = input.readInt()
            val termCount = input.readInt()
            val fallbackCount = input.readInt()
            val contextCount = input.readInt()
            val battleHintCount = input.readInt()
            repeat(entryCount + termCount + fallbackCount + contextCount) {
                input.readSizedUtf8()
                input.readSizedUtf8()
            }
            assertEquals(2, battleHintCount)
            assertEquals("BattleHint\u001f14", input.readSizedUtf8())
            assertEquals(
                "战斗开始时，罪人的罪孽抗性为其所装备最低等级E.G.O的抗性。",
                input.readSizedUtf8()
            )
            assertEquals("BattleHint_NormalBattle\u001f14", input.readSizedUtf8())
            assertEquals("普通战斗提示", input.readSizedUtf8())
        }
        val properties = Properties().apply {
            indexFile.parent.resolve("manifest.properties").inputStream().use(::load)
        }
        assertEquals("2", properties.getProperty("battleHintEntryCount"))
    }

    /** 停用只移除生效指针，保留不可变索引供下次激活或回退。 */
    @Test
    fun deactivationOnlyRemovesActivePointer() {
        val root = Files.createTempDirectory("limbus-index-test")
        val compiled = TranslationIndexCompiler().apply {
            add(record("MainUIText.json", "1", "content", "Sinner", "罪人"))
        }.compile("2026071001", "archive")
        val store = TranslationIndexStore(root)
        val indexFile = store.activate(compiled)

        assertTrue(store.deactivate())

        assertFalse(root.resolve("active-index.path").exists())
        assertTrue(indexFile.exists())
    }

    /** APK 更新后，旧 schema 或旧选择策略生成的索引必须触发重建。 */
    @Test
    fun rejectsStaleIndexManifestWithoutCurrentCompilerVersion() {
        val current = Properties().apply {
            setProperty("schema", TranslationIndexStore.INDEX_SCHEMA.toString())
            setProperty("compilerVersion", TranslationIndexStore.COMPILER_VERSION)
        }
        val oldSchema = Properties().apply {
            setProperty("schema", "11")
            setProperty("compilerVersion", TranslationIndexStore.COMPILER_VERSION)
        }
        val oldCompiler = Properties().apply {
            setProperty("schema", TranslationIndexStore.INDEX_SCHEMA.toString())
        }

        assertTrue(current.usesCurrentTranslationIndexCompiler())
        assertFalse(oldSchema.usesCurrentTranslationIndexCompiler())
        assertFalse(oldCompiler.usesCurrentTranslationIndexCompiler())
    }

    /** 同一汉化包版本号但归档内容变化时，旧索引必须重建。 */
    @Test
    fun rejectsIndexCompiledFromDifferentTranslationArchive() {
        val properties = Properties().apply {
            setProperty("schema", TranslationIndexStore.INDEX_SCHEMA.toString())
            setProperty("compilerVersion", TranslationIndexStore.COMPILER_VERSION)
            setProperty("archiveSha256", "old-archive")
        }

        assertTrue(properties.usesCurrentTranslationIndexCompiler())
        assertFalse(properties.matchesTranslationArchive("new-archive"))
        assertTrue(properties.matchesTranslationArchive("old-archive"))
    }

    /** 未达到日文源覆盖率门槛时，不能发布残缺索引替换旧版本。 */
    @Test
    fun requiresNinetyPercentOfJapaneseSourcesBeforeActivation() {
        assertTrue(hasSufficientJapaneseSources(90, 100))
        assertTrue(hasSufficientJapaneseSources(9, 10))
        assertFalse(hasSufficientJapaneseSources(89, 100))
        assertFalse(hasSufficientJapaneseSources(0, 0))
    }

    private fun record(
        path: String,
        id: String,
        field: String,
        source: String,
        translation: String
    ) = TranslationIndexRecord(path, id, field, source, translation)

    private fun DataInputStream.readSizedUtf8(): String {
        val size = readInt()
        return String(readNBytes(size), Charsets.UTF_8)
    }
}
