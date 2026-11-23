package com.cryptoassistant.app.feature.guide

/**
 * 使用说明工具书的内容。
 *
 * 只放数据与查询函数，不依赖 Compose；章节号、节号写死在数据里，
 * 由 `GuideContentTest` 校验编号连续、节数、相关条目与占位词。
 * 内容只描述当前真实行为，不写计划中功能，也不贴内部类名/日志字段。
 */
internal data class GuideChapter(
    val id: String,
    val number: Int,
    val title: String,
    val summary: String,
    val keyPoints: List<String>,
    val sections: List<GuideSection>,
)

internal data class GuideSection(
    val id: String,
    val number: String,
    val title: String,
    val summary: String,
    val lead: String,
    val blocks: List<GuideBlock>,
    val related: List<GuideRef> = emptyList(),
)

internal sealed interface GuideBlock {
    data class Heading(val text: String) : GuideBlock
    data class Paragraph(val text: String) : GuideBlock
    data class Steps(val items: List<String>) : GuideBlock
    data class Bullets(val items: List<String>) : GuideBlock
    data class Note(val title: String, val text: String) : GuideBlock
}

/** 相关条目只存定位，不存标题，避免标题改动后不同步。 */
internal data class GuideRef(val chapterId: String, val sectionId: String)

/** 工具书当前支持的语言。 */
internal enum class GuideLanguage { ZH, EN }

/**
 * 一本已按语言组装好的工具书。
 *
 * 结构（ID、章号、节号、related）来自同一份中文数据；英文只覆盖展示文本，
 * 所以两种语言的路由与相关条目必然一致。
 */
internal class GuideBook(
    val language: GuideLanguage,
    val chapters: List<GuideChapter>,
) {
    fun chapter(id: String?): GuideChapter? = chapters.firstOrNull { it.id == id }

    fun section(chapterId: String?, sectionId: String?): GuideSection? =
        chapter(chapterId)?.sections?.firstOrNull { it.id == sectionId }

    fun nextSection(chapterId: String, sectionId: String): GuideSection? {
        val sections = chapter(chapterId)?.sections ?: return null
        val index = sections.indexOfFirst { it.id == sectionId }
        return sections.getOrNull(index + 1)
    }

    fun previousSection(chapterId: String, sectionId: String): GuideSection? {
        val sections = chapter(chapterId)?.sections ?: return null
        val index = sections.indexOfFirst { it.id == sectionId }
        if (index <= 0) return null
        return sections.getOrNull(index - 1)
    }

    fun nextChapter(chapterId: String): GuideChapter? {
        val index = chapters.indexOfFirst { it.id == chapterId }
        return chapters.getOrNull(index + 1)
    }

    fun previousChapter(chapterId: String): GuideChapter? {
        val index = chapters.indexOfFirst { it.id == chapterId }
        if (index <= 0) return null
        return chapters.getOrNull(index - 1)
    }

    fun titleOf(ref: GuideRef): String? =
        section(ref.chapterId, ref.sectionId)?.let { "${it.number} ${it.title}" }

    fun totalSections(): Int = chapters.sumOf { it.sections.size }
}

internal object GuideContent {

    val chapters: List<GuideChapter> = listOf(
        GuideChapter(
            id = "start",
            number = 1,
            title = "开始使用",
            summary = "第一次使用的顺序、首页的三条路径，以及这个工具能做什么、不做什么。",
            keyPoints = listOf(
                "先创建本机密钥并导出备份，再添加联系人。",
                "文字靠悬浮球，文件靠首页入口或系统分享。",
                "完全离线；不会自动发送，也不会替你粘贴到聊天框。",
            ),
            sections = listOf(
                GuideSection(
                    id = "first-run",
                    number = "1.1",
                    title = "第一次使用",
                    summary = "创建密钥、备份、添加联系人、打开悬浮球。",
                    lead = "第一次使用只需要四步：创建密钥、备份、添加联系人、打开悬浮球。",
                    blocks = listOf(
                        GuideBlock.Steps(
                            listOf(
                                "首页点「本机密钥」→「创建密钥」，等待 4096 位密钥生成。",
                                "在密钥页导出 .txt 备份，设置口令并离线保存。",
                                "在联系人页粘贴对方的公钥，核对指纹后保存。",
                                "设置 →「悬浮球与无障碍」：授权悬浮窗后打开球，选择便捷或标准模式。",
                            ),
                        ),
                        GuideBlock.Paragraph(
                            "不想开悬浮球也能用：首页的「加密文件 / 解密文件」和系统「打开方式 / 分享」都能处理文件。" +
                                "文字加解密只发生在悬浮球上，应用内没有独立的文字加解密页。",
                        ),
                        GuideBlock.Note(
                            "先备份再使用",
                            "删除本机密钥后无法恢复；备份口令丢失后，这份备份也无法再打开。",
                        ),
                    ),
                    related = listOf(GuideRef("keys", "key-backup"), GuideRef("ball", "ball-enable")),
                ),
                GuideSection(
                    id = "home-paths",
                    number = "1.2",
                    title = "首页与三条路径",
                    summary = "首页有什么，以及文字、文件、系统入口三种用法怎么选。",
                    lead = "首页是本机状态和三个入口；按需要选一条路径即可。",
                    blocks = listOf(
                        GuideBlock.Bullets(
                            listOf(
                                "「本机密钥」：查看或创建身份，进入密钥页做备份、复制公钥与删除。",
                                "「加密文件 / 解密文件」：多选文件或递归选文件夹，按队列流式处理。",
                                "「使用说明」：就是你正在看的这本工具书，按主题查阅。",
                            ),
                        ),
                        GuideBlock.Paragraph(
                            "文字路径需要悬浮球；文件路径不依赖无障碍；系统入口由其他应用把文件分享或打开给本应用，" +
                                "同样进文件页处理。三条路径共用同一套密钥与联系人。",
                        ),
                        GuideBlock.Note(
                            "还没有密钥时",
                            "加密文件和加密文字都会被阻止。先创建本机密钥，再添加至少一个联系人。",
                        ),
                    ),
                    related = listOf(GuideRef("text", "text-quick"), GuideRef("files", "file-home")),
                ),
                GuideSection(
                    id = "boundaries",
                    number = "1.3",
                    title = "适用边界",
                    summary = "离线工具能做什么、不能做什么。",
                    lead = "这是本机离线工具，不是聊天插件，也不替你发送消息。",
                    blocks = listOf(
                        GuideBlock.Bullets(
                            listOf(
                                "默认不联网；只有可选的「检查更新」会访问 GitHub 读取版本号。没有云端、没有统计；密钥、明文和密文只在本机处理。",
                                "加密需要对方的公钥；解密只对发给本机密钥的密文有效。",
                                "不会自动发送、不会自动粘贴，也不会替你点击聊天窗口里的发送按钮。",
                                "微信、QQ 等应用可能隐藏第三方无障碍节点；读不到输入框时只能走剪贴板兜底。",
                            ),
                        ),
                        GuideBlock.Paragraph(
                            "安全目标是减少明文外露与误操作，不是对抗已经控制设备的人。设备被 root、" +
                                "被安装恶意软件或被他人解锁时，本应用无法提供额外保护。",
                        ),
                        GuideBlock.Note(
                            "自己保管的东西",
                            "备份文件与口令、联系人的公钥来源，以及设备本身的锁屏与系统更新，都需要你自己维护。",
                        ),
                    ),
                ),
            ),
        ),

        GuideChapter(
            id = "text",
            number = 2,
            title = "文字加解密",
            summary = "两种工作模式、接收方、解密展示与剪贴板兜底。",
            keyPoints = listOf(
                "便捷模式需要无障碍，标准模式不需要。",
                "多联系人未选接收方时阻止加密。",
                "解密明文只在输入框或胶囊中，关闭、超时或退后台即清除。",
            ),
            sections = listOf(
                GuideSection(
                    id = "text-quick",
                    number = "2.1",
                    title = "便捷模式",
                    summary = "点球就地读写当前输入框。",
                    lead = "点球时直接读取当前输入框，加密或解密后写回同一个框。",
                    blocks = listOf(
                        GuideBlock.Paragraph(
                            "便捷模式需要无障碍服务。它只处理当前聚焦、可编辑、非密码的输入框；" +
                                "按输入框整体内容处理，选中一段也不会只替换一半。",
                        ),
                        GuideBlock.Steps(
                            listOf(
                                "在目标输入框点一下，让光标停在要处理的位置。",
                                "点悬浮球：普通文字会加密，本工具密文会解密。",
                                "加密结果直接写回输入框；解密明文会写回并用拉长的胶囊展示。",
                            ),
                        ),
                        GuideBlock.Note(
                            "会主动跳过的情况",
                            "密码框、PIN、支付与验证码类界面不会被读取或写入；读不到时只给提示，不会猜内容。",
                        ),
                    ),
                    related = listOf(GuideRef("ball", "ball-mode"), GuideRef("text", "text-recipient")),
                ),
                GuideSection(
                    id = "text-standard",
                    number = "2.2",
                    title = "标准模式",
                    summary = "文字面板，不碰无障碍。",
                    lead = "不碰无障碍，也不后台监听剪贴板；点球打开文字面板。",
                    blocks = listOf(
                        GuideBlock.Paragraph(
                            "在面板里手动粘贴或输入。空输入时标题是「文字加密解密」；有内容后会判断方向：" +
                                "普通文字按加密处理，本工具密文按解密处理。",
                        ),
                        GuideBlock.Bullets(
                            listOf(
                                "「从剪贴板填入」只有你主动点击才会读取剪贴板。",
                                "加密结果复制到剪贴板，由你自己粘贴到目标位置。",
                                "解密结果展示在面板里，需要手动复制。",
                                "多联系人且未选接收方时，加密会被阻止。",
                            ),
                        ),
                        GuideBlock.Note(
                            "适合谁",
                            "不想开无障碍、或目标应用不支持写入时，用标准模式最稳妥。它本来就不会读其他应用的输入框。",
                        ),
                    ),
                    related = listOf(GuideRef("ball", "ball-mode"), GuideRef("text", "text-clipboard")),
                ),
                GuideSection(
                    id = "text-recipient",
                    number = "2.3",
                    title = "接收方与长按菜单",
                    summary = "加密给谁，以及只有一个/多个联系人时的行为。",
                    lead = "加密给谁由接收方决定；接收方来自联系人里的公钥。",
                    blocks = listOf(
                        GuideBlock.Bullets(
                            listOf(
                                "只有一个联系人时自动选中，不需要每次选择。",
                                "多个联系人且未选择时，加密会被阻止，避免发错人。",
                                "长按悬浮球打开菜单，选接收方；菜单只负责选人，不再放动作行。",
                                "最近一次选择会保留，下一次点球继续使用。",
                            ),
                        ),
                        GuideBlock.Paragraph(
                            "文件加解密的入口从首页或系统分享进入，不经过长按菜单。" +
                                "接收方列表只保存名称、公钥与指纹，不保存聊天关系。",
                        ),
                        GuideBlock.Note(
                            "不能选自己",
                            "自己的公钥会被拒绝；重复添加同一个公钥也会被拒绝，避免联系列表里出现两个相同身份。",
                        ),
                    ),
                    related = listOf(GuideRef("keys", "key-contacts"), GuideRef("text", "text-quick")),
                ),
                GuideSection(
                    id = "text-decrypt",
                    number = "2.4",
                    title = "解密结果与胶囊",
                    summary = "签名校验、明文展示、胶囊展开与清理。",
                    lead = "只有识别为本工具密文才解密，并校验签名与发送者。",
                    blocks = listOf(
                        GuideBlock.Bullets(
                            listOf(
                                "便捷模式会尝试把明文写回同一个输入框，并把球拉长为胶囊展示明文。",
                                "明文不会自动复制到剪贴板；需要复制时请自己操作。",
                                "胶囊普通状态约 20 秒，展开后最长约 120 秒；关闭胶囊或退到后台即清除。",
                                "签名有效时会标明「来自 X」；发送者不在联系人里会显示未识别。",
                            ),
                        ),
                        GuideBlock.Paragraph(
                            "签名校验只能说明密文由对应私钥签名、内容未被改动，不能证明对方现实身份。" +
                                "身份是否可信仍要靠你核对指纹与公钥来源。",
                        ),
                        GuideBlock.Note(
                            "解密失败",
                            "提示里出现「不是发给本机密钥的，或内容已被修改」时，先确认收件人，" +
                                "再确认密文有没有被聊天软件改动。",
                        ),
                    ),
                    related = listOf(GuideRef("trouble", "trouble-writeback"), GuideRef("text", "text-clipboard")),
                ),
                GuideSection(
                    id = "text-clipboard",
                    number = "2.5",
                    title = "剪贴板兜底、磁贴与提示",
                    summary = "读不到输入框时怎么走，以及常见提示。",
                    lead = "读不到输入框时，剪贴板是备选路径，不会预读。",
                    blocks = listOf(
                        GuideBlock.Bullets(
                            listOf(
                                "复制明文后点球：已有接收方时加密并放回剪贴板，提示「已加密给 X · 长按粘贴」。",
                                "明文但没有接收方时只提示先长按球选联系人，不会加密。",
                                "剪贴板是密文时按密文解密；剪贴板为空或读不到时只给提示，不改剪贴板。",
                                "控制中心可添加「剪贴板加解密」磁贴；只有点磁贴或点球兜底时才读剪贴板。",
                            ),
                        ),
                        GuideBlock.Paragraph(
                            "应用不会自动粘贴到目标应用，也不会自动发送。剪贴板里的密文由你长按粘贴，" +
                                "发送动作始终由你在聊天软件里完成。",
                        ),
                        GuideBlock.Note(
                            "剪贴板不是保险箱",
                            "其他应用也可能读取剪贴板；发送完成后建议复制一段无关文字覆盖掉密文或明文。",
                        ),
                    ),
                    related = listOf(GuideRef("trouble", "trouble-wechat"), GuideRef("ball", "ball-tiles")),
                ),
            ),
        ),

        GuideChapter(
            id = "files",
            number = 3,
            title = "文件加解密",
            summary = "首页文件入口、系统打开/分享、文件快捷处理与保存清理。",
            keyPoints = listOf(
                "文件可以多选，也可以递归选整个文件夹。",
                "系统入口按文件名/文件头自动判型，混合批量会让你选子集。",
                "结果先留在应用私有目录，分享/打开后约 6 小时清理。",
            ),
            sections = listOf(
                GuideSection(
                    id = "file-home",
                    number = "3.1",
                    title = "首页文件入口",
                    summary = "多选、递归选文件夹、流式处理与结果列表。",
                    lead = "首页的「加密文件 / 解密文件」支持多选文件和整目录。",
                    blocks = listOf(
                        GuideBlock.Bullets(
                            listOf(
                                "处理是流式的，不会把整个文件读进内存；有进度、取消和逐个结果。",
                                "目录会被递归收集；解密只收 .aegix，其他文件会跳过并显示数量。",
                                "单文件超过所选分卷大小时自动切成多卷；每卷仍小于 2 GB。目录队列上限 200 文件 / 4 GB / 5000 条目 / 20 层。",
                                "每个成功结果都可以单独分享、另存或用其他应用打开；多个结果可「分享全部」。",
                            ),
                        ),
                        GuideBlock.Paragraph(
                            "结果先写入应用私有目录，再决定分享、保存还是打开。保存只是复制到你选的位置，" +
                                "临时结果仍按离开页面或租约规则清理。",
                        ),
                        GuideBlock.Note(
                            "加密前准备",
                            "文件加密需要本机密钥和接收方；解密只需要本机密钥。缺少任何一项时，页面会阻止开始。",
                        ),
                    ),
                    related = listOf(GuideRef("keys", "key-local"), GuideRef("files", "file-cleanup")),
                ),
                GuideSection(
                    id = "file-system",
                    number = "3.2",
                    title = "系统打开方式与分享",
                    summary = "VIEW/SEND 单入口、判型与混合批量。",
                    lead = "其他应用把文件交给本应用时，只有一个「加密助手」入口。",
                    blocks = listOf(
                        GuideBlock.Paragraph(
                            "本应用接收系统的打开、分享和批量分享。进入后按后缀与文件头判型：" +
                                ".aegix 或识别为密文头 → 解密，其余 → 加密。",
                        ),
                        GuideBlock.Bullets(
                            listOf(
                                "单文件不再弹二选一，直接进入对应页面。",
                                "多文件混合时让你选「只解密密文 / 只加密普通文件 / 取消」，不会替你猜整批方向。",
                                "判不出来或读取超时时按加密兜底；不确定方向的普通文件不会被当作密文解密。",
                            ),
                        ),
                        GuideBlock.Note(
                            "候选列表不出现",
                            "这是发送方或系统的过滤行为，不是本应用没有声明。找不到入口时，先用首页文件入口处理。",
                        ),
                    ),
                    related = listOf(GuideRef("trouble", "trouble-entry"), GuideRef("files", "file-home")),
                ),
                GuideSection(
                    id = "file-quick",
                    number = "3.3",
                    title = "文件快捷处理",
                    summary = "便捷模式下外部单文件的 HUD 流程。",
                    lead = "便捷模式下，外部单文件可以先加/解密并确认，再打开或分享。",
                    blocks = listOf(
                        GuideBlock.Bullets(
                            listOf(
                                "默认关闭；开关在设置 →「悬浮球与无障碍」→ 工作模式。",
                                "只对单文件、便捷模式、有本机身份与接收方、且有外部目标时生效。",
                                "处理中显示可见的 HUD；返回或取消会停止并清理本次临时结果。",
                                "不满足条件时仍进文件页，与普通流程完全一致。",
                            ),
                        ),
                        GuideBlock.Paragraph(
                            "它不会自动选择聊天对象，也不会自动发送。HUD 只是让你确认方向和结果，" +
                                "真正的发送仍由你在目标应用里完成。",
                        ),
                        GuideBlock.Note(
                            "为什么默认关闭",
                            "外部入口直接处理文件会跳过文件页的确认。默认关闭，把选择留给需要快捷处理的人。",
                        ),
                    ),
                    related = listOf(GuideRef("ball", "ball-mode"), GuideRef("files", "file-system")),
                ),
                GuideSection(
                    id = "file-cleanup",
                    number = "3.4",
                    title = "保存、清理与限制",
                    summary = "保存/分享/打开、租约、VOLU 与容量上限。",
                    lead = "结果先留在应用私有目录，再决定保存、分享还是打开。",
                    blocks = listOf(
                        GuideBlock.Bullets(
                            listOf(
                                "「保存到…」会复制到你选择的位置；保存成功只标记已保存，不立即删除临时结果。",
                                "分享或交给其他应用打开成功后，结果按 6 小时租约保留，方便对方从最近文件重开。",
                                "离开页面、处理下一个或冷启动会按规则清理；设置页的「清除缓存」可手动清理。",
                                "正在处理或保存时，手动清理会被拒绝，避免删掉正在使用的文件。",
                            ),
                        ),
                        GuideBlock.Paragraph(
                            "Android 只读打开桌面端写出的 VOLU 分卷，不生成内部多卷；文件夹加密输出 zip，" +
                                "不自动解压；解密结果页可选择「解压到文件夹」按结构安全写出。",
                        ),
                        GuideBlock.Note(
                            "长期保留",
                            "需要长期保存时用「保存到…」自己选位置；分享出去的明文会在应用私有目录里多留一段时间。",
                        ),
                    ),
                    related = listOf(GuideRef("appendix", "formats"), GuideRef("security", "privacy")),
                ),
            ),
        ),

        GuideChapter(
            id = "keys",
            number = 4,
            title = "密钥与联系人",
            summary = "本机密钥、备份恢复、公钥指纹与常见错误。",
            keyPoints = listOf(
                "私钥由 Android Keystore 包装，日常使用不需要口令。",
                "备份是带口令的 .txt；删除密钥后不可恢复。",
                "联系人只保存对方公钥与指纹，不经过服务器。",
            ),
            sections = listOf(
                GuideSection(
                    id = "key-local",
                    number = "4.1",
                    title = "本机密钥",
                    summary = "4096 位 RSA、Keystore 包装与删除后果。",
                    lead = "本机密钥就是你的身份：私钥签名，公钥给别人加密。",
                    blocks = listOf(
                        GuideBlock.Bullets(
                            listOf(
                                "生成 4096 位 RSA 密钥；创建后显示指纹与创建时间。",
                                "私钥由 Android Keystore 包装，日常加解密不需要输入口令。",
                                "复制公钥给对方；对方用它加密给你，你用本机私钥解密。",
                                "删除本机密钥后无法恢复，联系人与历史备份不会自动补回身份。",
                            ),
                        ),
                        GuideBlock.Paragraph(
                            "密钥页点按整张身份卡即可复制公钥。指纹是公钥的短摘要，用来当面或通过可信渠道核对。",
                        ),
                        GuideBlock.Note(
                            "不要频繁重建",
                            "遇到问题先排查备份、口令和接收方；反复删除/创建密钥会让旧密文无法再解开。",
                        ),
                    ),
                    related = listOf(GuideRef("keys", "key-backup"), GuideRef("appendix", "terms")),
                ),
                GuideSection(
                    id = "key-backup",
                    number = "4.2",
                    title = "备份与恢复",
                    summary = "完整备份与导入校验。",
                    lead = "备份是带口令的 .txt 文件，导入只接受本应用导出的完整备份。",
                    blocks = listOf(
                        GuideBlock.Paragraph(
                            "导出前可开启安全锁验证。备份口令只用于这份文件；导入时会校验公钥与私钥是否一致，" +
                                "旧格式备份不再接受。",
                        ),
                        GuideBlock.Bullets(
                            listOf(
                                "完整备份保留创建时间；缺失时间只显示为「未知」。",
                                "最近导入时间会记录在本机，方便判断当前身份来自哪次恢复。",
                                "「从文件恢复（替换现有密钥）」会覆盖本机身份；替换前先确认备份可用。",
                            ),
                        ),
                        GuideBlock.Note(
                            "口令丢失",
                            "备份口令无法找回；没有口令就无法解密备份。备份文件与口令建议分开保管。",
                        ),
                    ),
                    related = listOf(GuideRef("keys", "key-errors"), GuideRef("security", "security-lock")),
                ),
                GuideSection(
                    id = "key-contacts",
                    number = "4.3",
                    title = "公钥、指纹与联系人",
                    summary = "复制公钥、核对指纹、添加与去重。",
                    lead = "联系人只保存对方公钥与指纹，不经过服务器。",
                    blocks = listOf(
                        GuideBlock.Bullets(
                            listOf(
                                "密钥页点身份卡复制公钥，粘贴给对方。",
                                "联系人页粘贴对方公钥，核对指纹后保存；名称只用于本机显示。",
                                "自己的公钥与重复公钥会被拒绝；删除联系人只影响本机列表。",
                                "指纹一致说明公钥相同，但不能证明屏幕另一端的人就是你以为的人。",
                            ),
                        ),
                        GuideBlock.Paragraph(
                            "公钥可以通过聊天、邮件或当面扫码之外的任意渠道传递，但首次信任最好通过可信渠道核对指纹。" +
                                "之后公钥变化时，应重新核对。",
                        ),
                        GuideBlock.Note(
                            "陌生人发来的公钥",
                            "不要只看名称就信任。名称可以随便改，指纹才是公钥的真实摘要。",
                        ),
                    ),
                    related = listOf(GuideRef("text", "text-recipient"), GuideRef("appendix", "terms")),
                ),
                GuideSection(
                    id = "key-errors",
                    number = "4.4",
                    title = "常见错误",
                    summary = "口令、公钥不一致与重复。",
                    lead = "多数错误来自口令、公钥不一致或备份格式，不需要重装应用。",
                    blocks = listOf(
                        GuideBlock.Bullets(
                            listOf(
                                "口令错误：确认输入的是备份口令，不是系统锁屏密码。",
                                "公钥与私钥不一致：备份文件损坏或选错了文件；换一份可用备份再试。",
                                "重复公钥：该联系人已经存在；先删除旧条目或直接使用已有条目。",
                                "创建时间未知：备份元数据缺失时的显示，不代表导入失败。",
                            ),
                        ),
                        GuideBlock.Paragraph(
                            "解密失败先确认密文是不是发给本机。提示收件人不对或内容被改时，" +
                                "让对方重新加密一次，不要反复重建本机密钥。",
                        ),
                        GuideBlock.Note(
                            "重装不能修复",
                            "卸载或清数据会丢失本机密钥与联系人。遇到问题先导出备份，再考虑恢复或替换。",
                        ),
                    ),
                    related = listOf(GuideRef("keys", "key-backup"), GuideRef("trouble", "trouble-writeback")),
                ),
            ),
        ),

        GuideChapter(
            id = "ball",
            number = 5,
            title = "悬浮球与无障碍",
            summary = "开启悬浮球、工作模式、披露、点球决策与磁贴。",
            keyPoints = listOf(
                "悬浮球需要悬浮窗权限；开关在设置里。",
                "便捷模式需要无障碍，标准模式不需要。",
                "无障碍只在点球时读取当前输入框，不监听屏幕。",
            ),
            sections = listOf(
                GuideSection(
                    id = "ball-enable",
                    number = "5.1",
                    title = "开启悬浮球",
                    summary = "悬浮窗权限、拖动吸附与服务停止。",
                    lead = "悬浮球需要悬浮窗权限，开关在设置 →「悬浮球与无障碍」。",
                    blocks = listOf(
                        GuideBlock.Steps(
                            listOf(
                                "打开「悬浮球」开关。",
                                "按系统提示授予悬浮窗权限。",
                                "球出现在屏幕上；可拖动到边缘，松手后自动吸附。",
                                "旋转屏幕或分屏后，球会重新约束在安全区域内。",
                            ),
                        ),
                        GuideBlock.Paragraph(
                            "关闭开关会停止前台服务并移除球。悬浮窗权限在系统里被撤销时，服务也会停止，" +
                                "回到应用后状态会显示为已暂停。",
                        ),
                        GuideBlock.Note(
                            "点球没反应",
                            "部分系统限制后台启动，或当前页面隐藏第三方悬浮窗。先看设置里的状态，再关/开一次开关。",
                        ),
                    ),
                    related = listOf(GuideRef("trouble", "trouble-ball"), GuideRef("ball", "ball-mode")),
                ),
                GuideSection(
                    id = "ball-mode",
                    number = "5.2",
                    title = "工作模式",
                    summary = "便捷/标准差异、切换与影响。",
                    lead = "便捷模式与标准模式的差别是「是否读写输入框」。",
                    blocks = listOf(
                        GuideBlock.Bullets(
                            listOf(
                                "便捷模式：需要无障碍；点球就地读写当前输入框。",
                                "标准模式：不碰无障碍、不监听剪贴板；点球打开文字面板。",
                                "切换立即生效，并关闭开着的菜单或胶囊。",
                                "用户选择的模式优先于系统权限状态；无障碍没连上时，便捷模式会先弹引导面板。",
                            ),
                        ),
                        GuideBlock.Paragraph(
                            "两种模式共用同一套密钥、联系人与接收方。标准模式不是「降级版」，" +
                                "它只是不接触无障碍，适合不想授权的人。",
                        ),
                        GuideBlock.Note(
                            "标准模式不读输入框",
                            "所以在标准模式下点球没有就地变化是正常的；它会打开文字面板。",
                        ),
                    ),
                    related = listOf(GuideRef("text", "text-quick"), GuideRef("text", "text-standard")),
                ),
                GuideSection(
                    id = "ball-accessibility",
                    number = "5.3",
                    title = "无障碍披露与权限",
                    summary = "披露 v3、独立同意与关闭后的降级。",
                    lead = "无障碍是敏感权限，应用只在点球时读取当前输入框。",
                    blocks = listOf(
                        GuideBlock.Bullets(
                            listOf(
                                "首次进入相关流程会显示披露；需要你独立同意后才能继续。",
                                "服务由你在系统设置里开启；应用不会自行开启，也不会伪装成系统服务。",
                                "不监听屏幕、不采集事件、不缓存文字；只在点球时读当前焦点输入框。",
                                "拒绝或关闭无障碍后，便捷模式退化为引导面板 + 剪贴板兜底。",
                            ),
                        ),
                        GuideBlock.Paragraph(
                            "披露版本更新后会再次要求确认。拒绝不影响文件加解密、标准模式和密钥管理。",
                        ),
                        GuideBlock.Note(
                            "系统状态与运行状态",
                            "「系统里已启用」和「服务正在运行」是两件事。设置页显示的是服务真实连接状态。",
                        ),
                    ),
                    related = listOf(GuideRef("security", "privacy"), GuideRef("ball", "ball-tap")),
                ),
                GuideSection(
                    id = "ball-tap",
                    number = "5.4",
                    title = "点球决策与引导面板",
                    summary = "未开启、无焦点、密码框与剪贴板兜底。",
                    lead = "点球后先看当前状态，再决定读输入框、读剪贴板还是弹面板。",
                    blocks = listOf(
                        GuideBlock.Bullets(
                            listOf(
                                "无障碍未开启或服务没运行：打开引导面板，提供去开启、去修复、先按剪贴板处理。",
                                "已连接但没有焦点、空框或密码框：提示原因并按剪贴板兜底。",
                                "剪贴板是密文：解密；明文且有接收方：加密并放回剪贴板。",
                                "剪贴板为空或读不到：只提示，不改动任何内容。",
                            ),
                        ),
                        GuideBlock.Paragraph(
                            "引导面板打开时不预读剪贴板；只有你点「先按剪贴板处理」，才读取一次。" +
                                "面板内也可以直接开关便捷模式。",
                        ),
                        GuideBlock.Note(
                            "不猜内容",
                            "读不到就是读不到，应用不会遍历其他窗口、不会读取历史消息，也不会尝试绕过对方应用的限制。",
                        ),
                    ),
                    related = listOf(GuideRef("text", "text-clipboard"), GuideRef("trouble", "trouble-wechat")),
                ),
                GuideSection(
                    id = "ball-tiles",
                    number = "5.5",
                    title = "磁贴与厂商 ROM",
                    summary = "三种磁贴、添加方式与后台限制。",
                    lead = "控制中心磁贴提供不打开应用的快捷入口。",
                    blocks = listOf(
                        GuideBlock.Bullets(
                            listOf(
                                "提供「悬浮球」「剪贴板加解密」「便捷模式」三种磁贴。",
                                "Android 13 及以上可一键添加；更早版本按弹窗里的步骤手动拖入控制中心。",
                                "服务未运行时点磁贴会尝试拉起服务；磁贴状态是进程内的，重启后可能显示关闭。",
                                "部分厂商 ROM 不实现一键添加，或限制后台启动；以实际行为为准。",
                            ),
                        ),
                        GuideBlock.Paragraph(
                            "磁贴只是入口，不会改变工作模式或接收方。需要选接收方时仍然长按悬浮球。",
                        ),
                        GuideBlock.Note(
                            "添加失败",
                            "先确认应用没有被系统冻结；再按弹窗步骤手动添加，或直接在设置里打开悬浮球。",
                        ),
                    ),
                    related = listOf(GuideRef("ball", "ball-enable"), GuideRef("trouble", "trouble-ball")),
                ),
            ),
        ),

        GuideChapter(
            id = "security",
            number = 6,
            title = "安全、日志与隐私",
            summary = "安全锁、应用锁、日志手势与本地隐私边界。",
            keyPoints = listOf(
                "安全锁保护低频敏感操作，不改变日常加解密。",
                "日志只记录诊断事实，不含内容。",
                "所有数据留在本机；清理规则与权限共同构成边界。",
            ),
            sections = listOf(
                GuideSection(
                    id = "security-lock",
                    number = "6.1",
                    title = "安全锁",
                    summary = "保护范围、应用锁与验证方式。",
                    lead = "安全锁保护低频敏感操作，不改变日常加解密。",
                    blocks = listOf(
                        GuideBlock.Bullets(
                            listOf(
                                "受保护：导出/导入/删除本机密钥，导出/清空日志，更改日志等级。",
                                "开启与关闭安全锁本身也要验证，避免被他人直接关掉。",
                                "应用锁可选：冷启动必须验证；从后台回来可选「立即」或「10 分钟后」，默认 10 分钟。",
                                "应用锁只锁主界面；悬浮球、球面板、文件入口与系统选择器不受影响。",
                            ),
                        ),
                        GuideBlock.Paragraph(
                            "验证使用系统指纹/人脸，设备支持时可用锁屏凭据兜底。应用不保存生物特征，" +
                                "也不自绘验证界面。没有可用验证方式时不能开启安全锁。",
                        ),
                        GuideBlock.Note(
                            "取消即不执行",
                            "验证取消或失败时，原操作不会继续；不会出现「跳过验证」的隐藏入口。",
                        ),
                    ),
                    related = listOf(GuideRef("keys", "key-backup"), GuideRef("security", "logs")),
                ),
                GuideSection(
                    id = "logs",
                    number = "6.2",
                    title = "日志",
                    summary = "等级、点按/长按手势、清空与脱敏。",
                    lead = "日志只记录诊断事实，不含内容。",
                    blocks = listOf(
                        GuideBlock.Bullets(
                            listOf(
                                "等级：OFF / ERROR / WARN / INFO / DEBUG / VERBOSE。",
                                "点按等级行更改等级，长按等级行导出日志，清空使用单独按钮。",
                                "「最近记录」可手动刷新；完整内容请导出查看。",
                                "记录事件名、结果、数量、耗时、错误类别与输入框结构元数据。",
                                "不写明文、密文、密钥、口令、剪贴板内容、文件名或路径。",
                            ),
                        ),
                        GuideBlock.Paragraph(
                            "改等级只影响新记录，历史日志保留。OFF 时连崩溃堆栈也不写。",
                        ),
                        GuideBlock.Note(
                            "导出位置",
                            "导出走系统文件选择器，不需要存储权限；导出的 .txt 只含诊断事实。",
                        ),
                    ),
                    related = listOf(GuideRef("trouble", "trouble-feedback"), GuideRef("security", "privacy")),
                ),
                GuideSection(
                    id = "privacy",
                    number = "6.3",
                    title = "本地数据与隐私",
                    summary = "默认离线、可选更新检查、私有目录与权限范围。",
                    lead = "所有内容留在本机，权限与清理规则共同构成边界。",
                    blocks = listOf(
                        GuideBlock.Bullets(
                            listOf(
                                "默认不联网；只有你打开「允许检查更新」后，应用才访问 GitHub Releases API 读取版本号，不上传设备、账号或文件信息。",
                                "密钥在应用私有目录，私钥由 Keystore 包装；联系人只存公钥与指纹。",
                                "临时结果在应用私有目录，按离开页面、6 小时租约与冷启动清理。",
                                "无障碍只读当前焦点输入框；不监听屏幕、不采集事件、不缓存文字。",
                            ),
                        ),
                        GuideBlock.Paragraph(
                            "明文不落盘、不写日志。备份是唯一需要你主动保管的长期文件；" +
                                "分享出去的临时结果会在私有目录里多留一段时间。",
                        ),
                        GuideBlock.Note(
                            "设备被控制时",
                            "root、恶意系统软件或他人解锁设备都可能绕过应用内保护；应用不承诺对抗这些情况。",
                        ),
                    ),
                    related = listOf(GuideRef("files", "file-cleanup"), GuideRef("ball", "ball-accessibility")),
                ),
            ),
        ),

        GuideChapter(
            id = "trouble",
            number = 7,
            title = "故障排查",
            summary = "点球、微信/QQ、写回、系统入口与日志反馈。",
            keyPoints = listOf(
                "先看权限、服务与工作模式，再看具体应用。",
                "微信/QQ 读不到输入框属对方行为，改用剪贴板。",
                "反馈时给日志和版本信息，不要给明文或密钥。",
            ),
            sections = listOf(
                GuideSection(
                    id = "trouble-ball",
                    number = "7.1",
                    title = "点球没反应",
                    summary = "权限、服务、模式与页面限制。",
                    lead = "先确认权限、服务和工作模式，再怀疑球本身。",
                    blocks = listOf(
                        GuideBlock.Bullets(
                            listOf(
                                "悬浮窗权限是否还在；设置里悬浮球开关是否开着。",
                                "无障碍服务是否「已启用且在运行」；只看系统里启用不够。",
                                "标准模式本来就不读输入框，点球会打开文字面板。",
                                "部分页面会隐藏第三方悬浮窗：球看得见但点不到。",
                            ),
                        ),
                        GuideBlock.Steps(
                            listOf(
                                "设置 →「悬浮球与无障碍」查看状态行。",
                                "关掉再打开一次悬浮球开关，或重新授权无障碍。",
                                "仍无反应时重启应用；换一个普通输入框再试。",
                            ),
                        ),
                        GuideBlock.Note(
                            "不要反复点球",
                            "连续点球不会提高成功率；先看状态，再决定是修权限还是改用剪贴板。",
                        ),
                    ),
                    related = listOf(GuideRef("ball", "ball-enable"), GuideRef("ball", "ball-accessibility")),
                ),
                GuideSection(
                    id = "trouble-wechat",
                    number = "7.2",
                    title = "微信/QQ 读不到输入框",
                    summary = "对方隐藏节点时的剪贴板路径。",
                    lead = "部分应用会隐藏第三方无障碍节点，读不到是对方行为，不是本机权限。",
                    blocks = listOf(
                        GuideBlock.Paragraph(
                            "微信聊天框目前属于这种情况：系统里无障碍已开启，但本应用读不到可编辑节点。" +
                                "应用不会尝试绕过，也不会伪装成系统无障碍服务。",
                        ),
                        GuideBlock.Steps(
                            listOf(
                                "在聊天框复制要加密的明文。",
                                "先在联系人/长按球里选好接收方。",
                                "点球：密文会放回剪贴板，并提示「已加密给 X · 长按粘贴」。",
                                "回聊天框长按粘贴并发送；收到密文时同样复制后点球解密。",
                            ),
                        ),
                        GuideBlock.Note(
                            "不要期待提示已替换",
                            "对方不接收写入时，写回不会发生。以输入框实际内容为准；失败时改用剪贴板。",
                        ),
                    ),
                    related = listOf(GuideRef("text", "text-clipboard"), GuideRef("text", "text-standard")),
                ),
                GuideSection(
                    id = "trouble-writeback",
                    number = "7.3",
                    title = "写回/解密失败",
                    summary = "快照、密码框、收件人与写入限制。",
                    lead = "按提示判断是读取、快照、密码框还是收件人问题。",
                    blocks = listOf(
                        GuideBlock.Bullets(
                            listOf(
                                "读不到或空框：改用剪贴板路径。",
                                "写回前内容变了：应用会放弃写入并提示，不会覆盖你刚输入的文字。",
                                "密码框/PIN：主动跳过，不读取也不写入。",
                                "提示收件人不对或内容被改：密文不是发给本机，或传输中被修改。",
                                "写回后没变：部分应用不接收写入，改用剪贴板。",
                            ),
                        ),
                        GuideBlock.Paragraph(
                            "失败不会破坏原输入框或剪贴板原文。日志里只有结果与原因，不含文字内容。",
                        ),
                        GuideBlock.Note(
                            "先备份再排查",
                            "需要替换密钥或重装前，先导出可用备份；不要用删除密钥来「修复」解密失败。",
                        ),
                    ),
                    related = listOf(GuideRef("keys", "key-errors"), GuideRef("text", "text-decrypt")),
                ),
                GuideSection(
                    id = "trouble-entry",
                    number = "7.4",
                    title = "系统入口不出现",
                    summary = "发送方/ROM 过滤与文件入口兜底。",
                    lead = "候选列表由发送方和系统决定，本应用只能声明自己能接收。",
                    blocks = listOf(
                        GuideBlock.Paragraph(
                            "微信、QQ、文件管理器的「打开方式 / 分享」是否出现「加密助手」，取决于对方是否暴露文件、" +
                                "是否过滤第三方、ROM 是否限制后台或候选列表。",
                        ),
                        GuideBlock.Bullets(
                            listOf(
                                "先用首页「加密/解密文件」兜底，功能不受影响。",
                                "完全不带 type 的分享与 file:// 仍是已知边界。",
                                "有些应用只允许分享图片/文件，不允许分享任意文本。",
                            ),
                        ),
                        GuideBlock.Note(
                            "不是权限问题",
                            "应用已经声明了能接收的文件类型；入口不出现通常是发送方过滤，不需要反复重装。",
                        ),
                    ),
                    related = listOf(GuideRef("files", "file-system"), GuideRef("files", "file-home")),
                ),
                GuideSection(
                    id = "trouble-feedback",
                    number = "7.5",
                    title = "导出日志与反馈",
                    summary = "日志导出步骤与反馈时的隐私边界。",
                    lead = "反馈问题时给日志和版本信息，不要给明文或密钥。",
                    blocks = listOf(
                        GuideBlock.Steps(
                            listOf(
                                "设置 →「日志」：确认当前等级不是 OFF。",
                                "长按等级行导出日志，选择保存位置。",
                                "在「设置 → 关于」找到版本号与构建 UUID。",
                                "把导出的 .txt 和这两项信息一起提供。",
                            ),
                        ),
                        GuideBlock.Paragraph(
                            "日志不含内容。描述问题时用「某应用某页面」代替真实姓名、联系人和文件名；" +
                                "不要发送备份文件、私钥、口令或聊天截图里的密文。",
                        ),
                        GuideBlock.Note(
                            "反馈前先清空",
                            "如果日志里混入了无关的历史记录，可以先清空，再复现一次问题后导出。",
                        ),
                    ),
                    related = listOf(GuideRef("security", "logs"), GuideRef("appendix", "version")),
                ),
            ),
        ),

        GuideChapter(
            id = "appendix",
            number = 8,
            title = "附录",
            summary = "术语、文件格式、版本许可与设置速查。",
            keyPoints = listOf(
                "术语按本应用的使用方式解释，不追求教科书定义。",
                "统一 .aegix；旧 .enc 与旧内部 VOLU 不再识别。",
                "版本、许可证与构建信息在设置 → 关于。",
            ),
            sections = listOf(
                GuideSection(
                    id = "terms",
                    number = "8.1",
                    title = "术语表",
                    summary = "公钥、私钥、指纹、密文、签名等。",
                    lead = "读正文时遇到不认识的词，可以回到这里。",
                    blocks = listOf(
                        GuideBlock.Bullets(
                            listOf(
                                "公钥：可以公开给别人的加密钥匙；对方用它加密给你。",
                                "私钥：本机身份的核心，用来解密和签名；不离开设备。",
                                "指纹：公钥的短摘要，用来核对两把公钥是否相同。",
                                "明文：未加密的原始内容；密文：加密后的内容。",
                                "签名：证明内容来自某把私钥且未被改动。",
                                "线格式：密文在设备之间的编码与结构约定。",
                                "VOLU：外部分卷容器；每卷一个独立文件，元数据加密在卷内。",
                                "租约：分享/打开后临时结果保留的时间，当前约 6 小时。",
                            ),
                        ),
                        GuideBlock.Paragraph(
                            "这里只解释本应用用到的部分。密码学完整定义请参考公开教材；本工具书不替代专业审计。",
                        ),
                    ),
                    related = listOf(GuideRef("appendix", "formats"), GuideRef("keys", "key-local")),
                ),
                GuideSection(
                    id = "formats",
                    number = "8.2",
                    title = "文件格式与兼容",
                    summary = ".aegix、VOLU、zip 与容量上限。",
                    lead = "统一 .aegix；旧 .enc 与旧内部 VOLU 不再识别。",
                    blocks = listOf(
                        GuideBlock.Bullets(
                            listOf(
                                ".aegix：单文件密文的唯一后缀；.enc 不再当作密文。",
                                "VOLU：每卷一个独立文件的外部分卷容器；Android 与桌面都可写可读。",
                                "文件夹/多卷合并输出 zip；不自动解压，但结果页可选择「解压到文件夹」，重名加 _2、不覆盖已有文件。",
                                "单文件超过所选分卷大小时自动分卷，每卷小于 2 GB；目录队列 200 文件 / 4 GB / 5000 条目 / 20 层。",
                                "与桌面端共用 RSA-OAEP + AES-256-GCM 线格式。",
                            ),
                        ),
                        GuideBlock.Paragraph(
                            "后缀不是安全边界：判型还会看文件头。把普通文件改成 .aegix 不会让它变成密文。",
                        ),
                        GuideBlock.Note(
                            "大文件空间",
                            "处理期间需要接近结果大小的可用空间；输出先写入应用私有目录，再保存或分享。",
                        ),
                    ),
                    related = listOf(GuideRef("files", "file-cleanup"), GuideRef("appendix", "terms")),
                ),
                GuideSection(
                    id = "version",
                    number = "8.3",
                    title = "版本、许可证与构建信息",
                    summary = "版本号、GPLv3、构建 UUID 与依赖。",
                    lead = "版本、构建 UUID、许可证在设置 →「关于加密助手」。",
                    blocks = listOf(
                        GuideBlock.Paragraph(
                            "版本号与 versionCode 显示在「设置 → 关于加密助手」，以那里为准。应用以 GNU GPL v3.0（GPL-3.0-only）发布，" +
                                "源码随项目提供；按开源、侧载分发，不通过应用商店更新。",
                        ),
                        GuideBlock.Bullets(
                            listOf(
                                "「关于」页可查看版本、构建类型、构建 UUID 与编译时间。",
                                "构建 UUID 与时间只用于区分安装包，不含设备或用户信息。",
                                "可查看 GPLv3 全文与开源依赖列表。",
                            ),
                        ),
                        GuideBlock.Note(
                            "反馈时",
                            "提供版本号与构建 UUID 能帮助定位安装包；不要附带密钥、备份或明文。",
                        ),
                    ),
                    related = listOf(GuideRef("trouble", "trouble-feedback"), GuideRef("appendix", "settings")),
                ),
                GuideSection(
                    id = "settings",
                    number = "8.4",
                    title = "设置项速查",
                    summary = "默认值与作用一览。",
                    lead = "所有开关的默认值与作用。",
                    blocks = listOf(
                        GuideBlock.Bullets(
                            listOf(
                                "外观：浅色/深色/跟随系统；可选品牌色与纯黑深色；动态配色默认关闭。",
                                "悬浮球：默认关闭；开启需要悬浮窗权限。",
                                "悬浮球外观：可选小/标准/大三档尺寸与三档透明度，只影响球本体。",
                                "工作模式：便捷/标准；便捷模式需要无障碍。",
                                "文件快捷处理：默认关闭；只在便捷模式对外部单文件生效。",
                                "安全锁：默认关闭；保护密钥与日志敏感操作。",
                                "应用锁：默认关闭；重新验证可选「立即」或「10 分钟后」。",
                                "日志：等级可改；点按改等级、长按导出、单独按钮清空。",
                                "使用说明：首页入口；内容随版本更新。",
                            ),
                        ),
                        GuideBlock.Paragraph(
                            "设置项跟随所属模式；低频开关不放在首页。改设置不会影响已有密文、备份或联系人。",
                        ),
                        GuideBlock.Note(
                            "恢复默认",
                            "应用没有「一键恢复所有设置」；需要时逐项改回即可，不影响密钥与文件。",
                        ),
                    ),
                    related = listOf(GuideRef("security", "security-lock"), GuideRef("ball", "ball-mode")),
                ),
            ),
        ),
    )

    /**
     * 英文数据：只替换文本，ID/编号/related 原样沿用 [chapters]。
     *
     * 用 `lazy` 是为了只有英文界面才构建；结构性缺失由 [GuideContentTest] 在开发期拦住。
     */
    val englishChapters: List<GuideChapter> by lazy {
        chapters.map { chapter ->
            val text = GuideContentEn.chapters[chapter.id]
                ?: error("Missing English chapter text: ${chapter.id}")
            chapter.copy(
                title = text.title,
                summary = text.summary,
                keyPoints = text.keyPoints,
                sections = chapter.sections.map { section ->
                    val sectionText = GuideContentEn.sections[chapter.id to section.id]
                        ?: error("Missing English section text: ${chapter.id}/${section.id}")
                    section.copy(
                        title = sectionText.title,
                        summary = sectionText.summary,
                        lead = sectionText.lead,
                        blocks = sectionText.blocks,
                    )
                },
            )
        }
    }

    fun book(language: GuideLanguage): GuideBook =
        GuideBook(language, if (language == GuideLanguage.EN) englishChapters else chapters)

    fun languageOf(languageTag: String?): GuideLanguage =
        if (languageTag?.startsWith("en", ignoreCase = true) == true) {
            GuideLanguage.EN
        } else {
            GuideLanguage.ZH
        }
}
