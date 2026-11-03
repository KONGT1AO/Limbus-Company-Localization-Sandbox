package com.example.limbuszhcn.container

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.outputStream
import kotlin.io.path.writeText

/**
 * 表示一条从官方日文显示字段到中文显示字段的结构化配对记录。
 *
 * @property path 译文资源在汉化包内的相对路径。
 * @property id 由对象稳定标识与递归路径组成的诊断上下文。
 * @property field 已通过显示文本白名单的字段名。
 * @property source 容器内官方日文原文。
 * @property translation 与原文结构对齐的中文译文。
 */
internal data class TranslationIndexRecord(
    val path: String,
    val id: String,
    val field: String,
    val source: String,
    val translation: String
)

/**
 * 保存一次日文单源索引编译的不可变结果与完整性统计。
 *
 * @property version 汉化包版本。
 * @property archiveSha256 汉化归档的 SHA-256。
 * @property inputSha256 所有有效配对记录的确定性摘要。
 * @property uniqueEntries 无歧义或已满足优势阈值的完整文本映射。
 * @property termEntries 可幂等地嵌入格式化文本的短术语映射。
 * @property fallbackEntries 同文异译时按出现次数与来源优先级选择的完整文本回退映射。
 * @property contextEntries 带稳定资源对象 ID 的精确上下文映射，优先于全局字符串映射。
 * @property battleHintEntries 按小贴士资源表和数字 ID 建立的兼容映射。
 * @property sourceRecordCount 参与本次编译的去重源记录数量。
 * @property conflictCount 因译文冲突而未进入完整索引的原文数量。
 * @property dominantEntryCount 通过优势阈值解决冲突的原文数量。
 * @property candidateSourceFileCount 汉化包中候选 JSON 文件数量。
 * @property availableSourceFileCount 容器中可读取日文对应文件的数量。
 */
internal data class CompiledTranslationIndex(
    val version: String,
    val archiveSha256: String,
    val inputSha256: String,
    val uniqueEntries: Map<String, String>,
    val termEntries: Map<String, String>,
    val fallbackEntries: Map<String, String>,
    val contextEntries: Map<String, String>,
    val battleHintEntries: Map<String, String>,
    val sourceRecordCount: Int,
    val conflictCount: Int,
    val dominantEntryCount: Int,
    val candidateSourceFileCount: Int = 0,
    val availableSourceFileCount: Int = 0
)

/**
 * 将结构化的日中字段配对编译为确定性的完整文本索引与短术语索引。
 */
internal class TranslationIndexCompiler {
    private val records = mutableListOf<TranslationIndexRecord>()

    /** 保存一条由下载包配对记录派生的短术语及其来源优先级。 */
    private data class TermCandidate(
        val source: String,
        val translation: String,
        val priority: Int
    )

    /** 冲突回退译名的票数与来源优先级。 */
    private data class FallbackScore(var records: Int = 0, var priority: Int = 0)

    /**
     * 添加一条通过字段与编码质量校验的配对记录。
     *
     * @param record 待验证的日中显示文本记录。
     */
    fun add(record: TranslationIndexRecord) {
        if (
            !TranslationTextPolicy.isDisplayField(record.field) ||
            record.source.isBlank() ||
            record.translation.isBlank() ||
            (record.source == record.translation && !isCanonicalTerm(record)) ||
            !TranslationTextPolicy.isUsableDisplayText(record.source) ||
            !TranslationTextPolicy.isUsableDisplayText(record.translation)
        ) {
            return
        }
        // 不维护宿主自定义译名；有效译文原样来自用户已下载并校验的汉化包。
        records += record
    }

    /**
     * 解析冲突、生成幂等术语并计算输入摘要。
     *
     * @param version 汉化包版本。
     * @param archiveSha256 汉化归档 SHA-256。
     * @param candidateSourceFileCount 候选译文 JSON 数量。
     * @param availableSourceFileCount 已找到官方日文对应文件的数量。
     * @return 可写入当前版本二进制文件的不可变索引。
     */
    fun compile(
        version: String,
        archiveSha256: String,
        candidateSourceFileCount: Int = 0,
        availableSourceFileCount: Int = 0
    ): CompiledTranslationIndex {
        val sorted = records.distinct().sortedWith(
            compareBy(
                TranslationIndexRecord::source,
                TranslationIndexRecord::translation,
                TranslationIndexRecord::path,
                TranslationIndexRecord::id,
                TranslationIndexRecord::field
            )
        )
        val bySource = sorted.groupBy(TranslationIndexRecord::source)
        val unique = linkedMapOf<String, String>()
        val terms = linkedMapOf<String, String>()
        val fallback = linkedMapOf<String, String>()
        val context = linkedMapOf<String, String>()
        val battleHint = linkedMapOf<String, String>()
        var conflicts = 0
        var dominantEntries = 0
        sorted.mapNotNull { record ->
            record.contextKey()?.let { key -> key to record }
        }.groupBy({ it.first }, { it.second }).toSortedMap().forEach { (key, occurrences) ->
            val translations = occurrences.groupingBy(TranslationIndexRecord::translation).eachCount()
            resolveTranslation(translations)?.let { translation ->
                context[key] = translation
            }
        }
        sorted.mapNotNull { record ->
            record.battleHintKey()?.let { key -> key to record }
        }.groupBy({ it.first }, { it.second }).toSortedMap().forEach { (key, occurrences) ->
            val translations = occurrences.groupingBy(TranslationIndexRecord::translation).eachCount()
            resolveTranslation(translations)?.let { translation ->
                battleHint[key] = translation
            }
        }
        bySource.toSortedMap().forEach { (source, occurrences) ->
            val authoritative = occurrences.filter(::isCanonicalTerm)
            val candidates = authoritative.ifEmpty { occurrences }
            val translations = candidates.groupingBy(TranslationIndexRecord::translation).eachCount()
            val resolved = resolveTranslation(translations)
            if (resolved != null) {
                unique[source] = resolved
                if (translations.size > 1) dominantEntries += 1
            } else {
                conflicts += 1
                // 文本纯字符串索引无法区分同一个日文在多个资源中的上下文，但完全保留
                // 原文会让玩家直接看到日文。冲突项单独进入低优先级全文回退，优先选择
                // 出现次数最多的译名，再以词典/界面来源和确定性的译名排序打破平票。
                selectFallbackTranslation(candidates)?.let { fallback[source] = it }
            }
        }
        val termCandidates = mutableListOf<TermCandidate>()
        sorted.forEach { record ->
            val priority = termSourcePriority(record.path)
            if (TranslationTextPolicy.isTermSource(record.path, record.field)) {
                normalizedTerms(record.source, record.translation, isCanonicalTerm(record))
                    .forEach { (source, translation) ->
                        termCandidates += TermCandidate(source, translation, priority)
                    }
            }
            if (TranslationTextPolicy.isStructuredDescriptionField(record.field)) {
                normalizedLeadingClauseTerms(record.source, record.translation)
                    .forEach { (source, translation) ->
                        termCandidates += TermCandidate(source, translation, priority)
                    }
            }
        }
        termCandidates.groupBy(TermCandidate::source).toSortedMap().forEach { (source, candidates) ->
            val highestPriority = candidates.maxOf(TermCandidate::priority)
            val preferred = candidates.filter { candidate -> candidate.priority == highestPriority }
            val translations = preferred.groupingBy(TermCandidate::translation).eachCount()
            resolveTranslation(translations)?.let { translation ->
                if (highestPriority == CANONICAL_GLOSSARY_PRIORITY) {
                    // TMP 最终只提供字符串、没有资源 ID。发生同文异译时，以下载包的
                    // Buff/战斗关键词词典作为界面格式化后的唯一权威译名。
                    unique[source] = translation
                }
                // 先确定权威译名，再筛掉非幂等扩展词。不能先丢弃词典候选，否则同源的
                // 低优先级译名会重新进入 trie；词典全文优先级也不能依赖术语是否安全。
                candidateTerm(
                    source, translation,
                    allowIdentity = highestPriority == CANONICAL_GLOSSARY_PRIORITY
                )?.let { (safeSource, safeTranslation) -> terms[safeSource] = safeTranslation }
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        fun updateDigest(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update((bytes.size ushr 24).toByte())
            digest.update((bytes.size ushr 16).toByte())
            digest.update((bytes.size ushr 8).toByte())
            digest.update(bytes.size.toByte())
            digest.update(bytes)
        }
        sorted.forEach { record ->
            listOf(record.path, record.id, record.field, record.source, record.translation)
                .forEach(::updateDigest)
        }
        return CompiledTranslationIndex(
            version = version,
            archiveSha256 = archiveSha256,
            inputSha256 = digest.digest().toHex(),
            uniqueEntries = unique,
            termEntries = terms,
            fallbackEntries = fallback,
            contextEntries = context,
            battleHintEntries = battleHint,
            sourceRecordCount = sorted.size,
            conflictCount = conflicts,
            dominantEntryCount = dominantEntries,
            candidateSourceFileCount = candidateSourceFileCount,
            availableSourceFileCount = availableSourceFileCount
        )
    }

    private fun resolveTranslation(counts: Map<String, Int>): String? {
        if (counts.size == 1) return counts.keys.single()
        val ranked = counts.entries.sortedByDescending(Map.Entry<String, Int>::value)
        val winner = ranked.first()
        val runnerUp = ranked.getOrNull(1)?.value ?: 0
        val total = counts.values.sum()
        // 阈值从 ≥3 票/80%/3 倍放宽为 ≥2 票/2/3/2 倍：真实包里「リトライ→重试」这类
        // 2:1 多数派此前被整体丢弃，界面因此直接显示日文；1:1 与 2:2 平票仍保持丢弃。
        return winner.key.takeIf {
            winner.value >= 2 && winner.value * 3 >= total * 2 && winner.value >= runnerUp * 2
        }
    }

    /**
     * 为无法取得严格共识的完整文本选择稳定的中文回退译名。
     *
     * 计数仍以实际显示记录为准，来源优先级和译名仅用于同票时的确定性选择。该映射只
     * 参与完整字符串匹配，既不进入短术语 trie，也不会在其它句子内部做片段替换。
     *
     * @param occurrences 同一日文原文对应的全部结构化记录。
     * @return 可供中文优先显示的回退译名；没有候选时为空。
     */
    private fun selectFallbackTranslation(occurrences: List<TranslationIndexRecord>): String? {
        if (occurrences.isEmpty()) return null
        val scores = linkedMapOf<String, FallbackScore>()
        occurrences.forEach { record ->
            val score = scores.getOrPut(record.translation) { FallbackScore() }
            score.records += 1
            score.priority += fallbackSourcePriority(record)
        }
        return scores.entries.sortedWith(
            compareByDescending<Map.Entry<String, FallbackScore>> { it.value.records }
                .thenByDescending { it.value.priority }
                .thenBy { it.key }
        ).firstOrNull()?.key
    }

    /** 固定 UI/词典来源在平票时优先于剧情正文，但不改变记录票数本身。 */
    private fun fallbackSourcePriority(record: TranslationIndexRecord): Int {
        var priority = termSourcePriority(record.path)
        if (TranslationTextPolicy.isTermSource(record.path, record.field)) priority += 1
        return priority
    }

    private fun termSourcePriority(path: String): Int {
        val rawFileName = path.substringAfterLast('/').substringAfterLast('\\').lowercase()
        // activateRedirect 传入的是 japaneseSourceFor 生成的 jp/<目录>/JP_<文件>.json，
        // 直接匹配 bufs/battlekeywords 前缀永远落空，词典权威优先级在真机上从未生效。
        val fileName = rawFileName.removePrefix("jp_")
        return when {
            fileName.startsWith("bufs") || fileName.startsWith("battlekeywords") ->
                CANONICAL_GLOSSARY_PRIORITY
            "keyword" in fileName -> 2
            else -> 1
        }
    }

    private fun isCanonicalTerm(record: TranslationIndexRecord): Boolean =
        TranslationTextPolicy.isTermField(record.field) &&
            termSourcePriority(record.path) == CANONICAL_GLOSSARY_PRIORITY

    private fun normalizedTerms(
        source: String,
        translation: String,
        allowIdentity: Boolean
    ): Set<Pair<String, String>> {
        return buildSet {
            // 此处只收集形状合适的候选；在统一解决来源优先级之后再检查嵌入幂等性。
            candidateTerm(source, translation, allowIdentity, requireIdempotence = false)?.let(::add)
            candidateTerm(
                RICH_TEXT_TAG.replace(source, ""),
                RICH_TEXT_TAG.replace(translation, ""),
                allowIdentity,
                requireIdempotence = false
            )?.let(::add)
            // 游戏 formatter 会删除术语名称中的空格。为空格压缩后的可见字符串建立
            // 独立别名，不改变译文本身的排版。
            val compactSource = source.filterNot(Char::isWhitespace)
            val compactTaglessSource = RICH_TEXT_TAG.replace(source, "").filterNot(Char::isWhitespace)
            if (compactSource != source) {
                candidateTerm(
                    compactSource, translation, allowIdentity, requireIdempotence = false
                )?.let(::add)
            }
            if (compactTaglessSource != compactSource) {
                candidateTerm(
                    compactTaglessSource,
                    RICH_TEXT_TAG.replace(translation, ""),
                    allowIdentity,
                    requireIdempotence = false
                )?.let(::add)
            }
            val sourceBracketTerms = SQUARE_BRACKET_CONTENT.findAll(source)
                .map { match -> match.groupValues[1] }
                .toList()
            val translationBracketTerms = SQUARE_BRACKET_CONTENT.findAll(translation)
                .map { match -> match.groupValues[1] }
                .toList()
            if (sourceBracketTerms.size == translationBracketTerms.size) {
                // 游戏格式化器可能只输出方括号内部的可见名称。同步派生内部术语，
                // 让所有同类标签在格式化后仍能二次汉化，而不是只修某个页面或角色。
                sourceBracketTerms.zip(translationBracketTerms).forEach { (sourceTerm, translationTerm) ->
                    candidateTerm(
                        sourceTerm, translationTerm, allowIdentity, requireIdempotence = false
                    )?.let(::add)
                }
            }
        }
    }

    private fun normalizedLeadingClauseTerms(
        source: String,
        translation: String
    ): Set<Pair<String, String>> {
        val sourceLines = RICH_TEXT_TAG.replace(source, "").lines()
        val translationLines = RICH_TEXT_TAG.replace(translation, "").lines()
        if (sourceLines.size != translationLines.size) return emptySet()
        return buildSet {
            sourceLines.zip(translationLines).forEach { (sourceLine, translationLine) ->
                // 只有明确以“时/前/后/情况”等日文条件结尾的前缀才能跨页面复用。
                // 普通叙述中的“西部、东部…”或“当然、…”仍只参与全文匹配。
                val sourceClause = sourceLine.leadingClause()
                val translationClause = translationLine.leadingClause()
                if (sourceClause != null && sourceClause.isTimingOrConditionClause() &&
                    translationClause != null) {
                    candidateTerm(
                        sourceClause, translationClause, requireIdempotence = false
                    )?.let(::add)
                }
            }
        }
    }

    private fun candidateTerm(
        sourceValue: String,
        translationValue: String,
        allowIdentity: Boolean = false,
        requireIdempotence: Boolean = true
    ): Pair<String, String>? {
        val normalizedSource = sourceValue.trim().stripPairedBrackets()
        val normalizedTranslation = translationValue.trim().stripPairedBrackets()
        val maximumCodePoints = if (allowIdentity) 64 else 16
        return (normalizedSource to normalizedTranslation).takeIf {
            normalizedSource.isTermLike(minimumCodePoints = 2, maximumCodePoints = maximumCodePoints) &&
                normalizedSource.any(Char::isLetter) &&
                normalizedTranslation.isTermLike(
                    minimumCodePoints = 1,
                    maximumCodePoints = maximumCodePoints,
                    allowWhitespace = allowIdentity
                ) &&
                (allowIdentity || normalizedSource != normalizedTranslation) &&
                // “以上 -> 或以上”“呼吸 -> 呼吸法”再次扫描时仍会命中原词。
                // 这类映射保留全文，只有词典同文保护可以安全进入嵌入 trie。
                (!requireIdempotence || normalizedSource == normalizedTranslation ||
                    !normalizedTranslation.contains(normalizedSource))
        }
    }

    /**
     * 生成运行时对象映射使用的上下文键。
     *
     * 只取记录路径中的第一个稳定对象标识；该 ID 仍与字段名和日文原文组合，
     * 避免不同字段或同名但内容变化的对象互相覆盖。
     */
    private fun TranslationIndexRecord.contextKey(): String? {
        var start = id.indexOf('[')
        while (start >= 0) {
            val end = id.indexOf(']', start + 1)
            if (end <= start + 1) return null
            val identity = id.substring(start + 1, end).trim()
            if (identity.isNotEmpty() && '=' in identity && identity.length <= 160) {
                return "$identity\u001f$field\u001f$source"
            }
            // 数组位置会出现在对象 ID 之前，例如 `$.dataList[0][id=9127]`。
            start = id.indexOf('[', end + 1)
        }
        return null
    }

    /**
     * 为加载小贴士建立资源表加数字 ID 的兼容键。
     *
     * 游戏更新后可能在运行时继续使用旧版文案，而当前日文资源表只保留新版原文。
     * 使用稳定数字 ID 可以让同一张表的旧、新文案都取到同一份包内译文。
     */
    private fun TranslationIndexRecord.battleHintKey(): String? {
        if (field != "content") return null
        val fileName = path.substringAfterLast('/').substringAfterLast('\\')
        val table = BATTLE_HINT_FILE.matchEntire(fileName)
            ?.groupValues
            ?.getOrNull(1)
            ?: return null
        val numericId = BATTLE_HINT_ID.find(id)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: return null
        return "$table\u001f$numericId"
    }

    /** 仅识别明确的日文时机/条件标签，不从逗号前普通叙述猜测通用译名。 */
    private fun String.isTimingOrConditionClause(): Boolean {
        val clause = trim().stripPairedBrackets()
        return CONDITION_SUFFIXES.any { suffix -> clause.length > suffix.length && clause.endsWith(suffix) }
    }

    private fun String.leadingClause(): String? {
        val normalized = replace(LEADING_BULLET, "").trim()
        val delimiter = normalized.indexOfFirst { character -> character in CLAUSE_DELIMITERS }
        if (delimiter !in 2..32) return null
        return normalized.substring(0, delimiter)
    }

    private fun String.stripPairedBrackets(): String =
        if (length >= 3 && first() == '[' && last() == ']') {
            substring(1, length - 1)
        } else {
            this
        }

    private fun String.isTermLike(
        minimumCodePoints: Int,
        maximumCodePoints: Int = 16,
        allowWhitespace: Boolean = false
    ): Boolean {
        val codePoints = codePointCount(0, length)
        return codePoints in minimumCodePoints..maximumCodePoints && none {
            (!allowWhitespace && it.isWhitespace()) ||
                it == '<' || it == '>' || it == '{' || it == '}' || it == '%'
        }
    }

    companion object {
        private val RICH_TEXT_TAG = Regex("<[^>]+>")
        private val SQUARE_BRACKET_CONTENT = Regex("\\[([^\\[\\]]+)]")
        private val LEADING_BULLET = Regex("^\\s*[-·•]\\s*")
        private val BATTLE_HINT_FILE = Regex(
            "JP_(BattleHint(?:_[A-Za-z0-9]+)?)\\.json",
            RegexOption.IGNORE_CASE
        )
        private val BATTLE_HINT_ID = Regex("\\[id=(-?\\d+)]")
        private val CLAUSE_DELIMITERS = setOf('、', '，', ',', '：', ':')
        private val CONDITION_SUFFIXES = setOf("時", "前", "後", "場合", "際")
        private const val CANONICAL_GLOSSARY_PRIORITY = 3
    }
}

/**
 * 以不可变版本目录和原子指针发布运行时翻译索引。
 *
 * @param root 宿主私有的 `translation-index` 根目录。
 */
internal class TranslationIndexStore(private val root: Path) {
    /**
     * 写入或复用指定版本索引，并原子切换当前生效指针。
     *
     * @param index 已完成冲突与质量校验的编译结果。
     * @return 当前激活的二进制索引绝对路径。
     */
    fun activate(index: CompiledTranslationIndex): Path {
        val versions = root.resolve("versions")
        versions.createDirectories()
        val directoryName =
            "${index.version.safePathSegment()}-s$INDEX_SCHEMA-" +
                "${COMPILER_VERSION.safePathSegment()}-${index.inputSha256.take(12)}"
        val versionDirectory = versions.resolve(directoryName)
        if (!versionDirectory.isDirectory()) {
            val staging = versions.resolve(".$directoryName.staging-${System.nanoTime()}")
            staging.createDirectories()
            writeIndex(
                staging.resolve(INDEX_FILE),
                index.uniqueEntries,
                index.termEntries,
                index.fallbackEntries,
                index.contextEntries,
                index.battleHintEntries
            )
            writeManifest(staging.resolve(MANIFEST_FILE), index)
            moveAtomically(staging, versionDirectory)
        }
        val indexFile = versionDirectory.resolve(INDEX_FILE).toAbsolutePath().normalize()
        require(Files.isRegularFile(indexFile)) { "Translation index is missing: $indexFile" }
        root.createDirectories()
        val pointerTemp = root.resolve(".$ACTIVE_POINTER.tmp-${System.nanoTime()}")
        pointerTemp.writeText(indexFile.toString().replace('\\', '/') + "\n", Charsets.UTF_8)
        moveAtomically(pointerTemp, root.resolve(ACTIVE_POINTER), replaceExisting = true)
        return indexFile
    }

    /**
     * 仅移除当前索引指针，保留不可变版本目录以便回退。
     *
     * @return 指针存在且已删除时返回 `true`。
     */
    fun deactivate(): Boolean = Files.deleteIfExists(root.resolve(ACTIVE_POINTER))

    private fun writeIndex(
        path: Path,
        entries: Map<String, String>,
        terms: Map<String, String>,
        fallback: Map<String, String>,
        context: Map<String, String>,
        battleHint: Map<String, String>
    ) {
        DataOutputStream(BufferedOutputStream(path.outputStream())).use { output ->
            output.write(INDEX_MAGIC)
            output.writeInt(INDEX_SCHEMA)
            output.writeInt(entries.size)
            output.writeInt(terms.size)
            output.writeInt(fallback.size)
            output.writeInt(context.size)
            output.writeInt(battleHint.size)
            entries.forEach { (source, translation) ->
                output.writeSizedUtf8(source)
                output.writeSizedUtf8(translation)
            }
            terms.forEach { (source, translation) ->
                output.writeSizedUtf8(source)
                output.writeSizedUtf8(translation)
            }
            fallback.forEach { (source, translation) ->
                output.writeSizedUtf8(source)
                output.writeSizedUtf8(translation)
            }
            context.forEach { (source, translation) ->
                output.writeSizedUtf8(source)
                output.writeSizedUtf8(translation)
            }
            battleHint.forEach { (key, translation) ->
                output.writeSizedUtf8(key)
                output.writeSizedUtf8(translation)
            }
        }
    }

    private fun writeManifest(path: Path, index: CompiledTranslationIndex) {
        val properties = Properties().apply {
            setProperty("schema", INDEX_SCHEMA.toString())
            setProperty("compilerVersion", COMPILER_VERSION)
            setProperty("version", index.version)
            setProperty("archiveSha256", index.archiveSha256)
            setProperty("inputSha256", index.inputSha256)
            setProperty("uniqueEntryCount", index.uniqueEntries.size.toString())
            setProperty("termEntryCount", index.termEntries.size.toString())
            setProperty("fallbackEntryCount", index.fallbackEntries.size.toString())
            setProperty("contextEntryCount", index.contextEntries.size.toString())
            setProperty("battleHintEntryCount", index.battleHintEntries.size.toString())
            setProperty("sourceRecordCount", index.sourceRecordCount.toString())
            setProperty("conflictCount", index.conflictCount.toString())
            setProperty("dominantEntryCount", index.dominantEntryCount.toString())
            setProperty("candidateSourceFileCount", index.candidateSourceFileCount.toString())
            setProperty("availableSourceFileCount", index.availableSourceFileCount.toString())
        }
        path.outputStream().use { properties.store(it, "Limbus translation index") }
    }

    private fun moveAtomically(source: Path, target: Path, replaceExisting: Boolean = false) {
        val options = mutableListOf(StandardCopyOption.ATOMIC_MOVE)
        if (replaceExisting) options += StandardCopyOption.REPLACE_EXISTING
        try {
            Files.move(source, target, *options.toTypedArray())
        } catch (_: AtomicMoveNotSupportedException) {
            val fallback = if (replaceExisting) {
                arrayOf(StandardCopyOption.REPLACE_EXISTING)
            } else {
                emptyArray()
            }
            Files.move(source, target, *fallback)
        }
    }

    private fun DataOutputStream.writeSizedUtf8(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun String.safePathSegment(): String {
        val safe = map { character ->
            if (character.isLetterOrDigit() || character == '.' || character == '-' || character == '_') {
                character
            } else {
                '_'
            }
        }.joinToString("")
        return safe.ifBlank { "unknown" }
    }

    /** 统一约定宿主与 native 读取的索引文件和激活指针名称。 */
    companion object {
        /** 保存当前激活索引绝对路径的原子指针文件名。 */
        const val ACTIVE_POINTER = "active-index.path"
        /** 每个不可变索引版本目录内的二进制正文文件名。 */
        const val INDEX_FILE = "index.bin"
        private const val MANIFEST_FILE = "manifest.properties"
        // schema 14 增加小贴士表 ID 兼容映射，避免游戏更新后的旧文案无法命中。
        // 与 native 端 kIndexSchema 保持同步。
        internal const val INDEX_SCHEMA = 14
        /**
         * 索引布局或选择策略变化时递增，启动前可据此强制重建旧缓存。
         */
        internal const val COMPILER_VERSION = "battle-hint-index-v1"
        private val INDEX_MAGIC = byteArrayOf('L'.code.toByte(), 'Z'.code.toByte(), 'T'.code.toByte(), 'I'.code.toByte(), '1'.code.toByte(), 0)
    }
}

/** 判断索引清单是否由当前布局与选择策略生成，供启动前触发无损重建。 */
internal fun Properties.usesCurrentTranslationIndexCompiler(): Boolean =
    getProperty("schema") == TranslationIndexStore.INDEX_SCHEMA.toString() &&
        getProperty("compilerVersion") == TranslationIndexStore.COMPILER_VERSION

/** 判断当前索引是否由最近一次汉化归档编译，而不是同版本号的旧包内容。 */
internal fun Properties.matchesTranslationArchive(archiveSha256: String): Boolean =
    getProperty("archiveSha256") == archiveSha256

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
