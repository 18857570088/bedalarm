from pathlib import Path
import sys

sys.path.insert(0, r"D:\2026\codexwork\python-extra")

from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT, TA_RIGHT
from reportlab.lib.pagesizes import letter
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import inch
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.cidfonts import UnicodeCIDFont


ROOT = Path(r"D:\2026\202605\bed alarm")
OUT_DIR = ROOT / "docs"
PDF_PATH = OUT_DIR / "bed_alarm_presence_logic_20260603.pdf"
FONT = "STSong-Light"
PAGE_W, PAGE_H = letter

pdfmetrics.registerFont(UnicodeCIDFont(FONT))

styles = getSampleStyleSheet()
styles.add(ParagraphStyle("TitleCn", fontName=FONT, fontSize=23, leading=29, textColor=colors.HexColor("#0B2545"), spaceAfter=8))
styles.add(ParagraphStyle("SubtitleCn", fontName=FONT, fontSize=10.5, leading=14, textColor=colors.HexColor("#475569"), spaceAfter=16))
styles.add(ParagraphStyle("H1Cn", fontName=FONT, fontSize=15, leading=20, textColor=colors.HexColor("#2E74B5"), spaceBefore=14, spaceAfter=7))
styles.add(ParagraphStyle("H2Cn", fontName=FONT, fontSize=12.2, leading=16, textColor=colors.HexColor("#2E74B5"), spaceBefore=10, spaceAfter=5))
styles.add(ParagraphStyle("BodyCn", fontName=FONT, fontSize=9.6, leading=14, textColor=colors.HexColor("#0F172A"), wordWrap="CJK", spaceAfter=5))
styles.add(ParagraphStyle("BulletCn", fontName=FONT, fontSize=9.5, leading=13.5, textColor=colors.HexColor("#0F172A"), leftIndent=14, firstLineIndent=-10, wordWrap="CJK", spaceAfter=3))
styles.add(ParagraphStyle("NumberCn", fontName=FONT, fontSize=9.5, leading=13.5, textColor=colors.HexColor("#0F172A"), leftIndent=18, firstLineIndent=-14, wordWrap="CJK", spaceAfter=3))
styles.add(ParagraphStyle("CodeCn", fontName=FONT, fontSize=8.2, leading=10.8, textColor=colors.HexColor("#1F2937"), wordWrap="CJK", spaceAfter=0))
styles.add(ParagraphStyle("TableCn", fontName=FONT, fontSize=8.2, leading=11, textColor=colors.HexColor("#0F172A"), wordWrap="CJK", spaceAfter=0))
styles.add(ParagraphStyle("TableHeaderCn", fontName=FONT, fontSize=8.5, leading=11.5, textColor=colors.HexColor("#0F172A"), wordWrap="CJK", spaceAfter=0))


def esc(text):
    return str(text).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def p(text, style="BodyCn"):
    return Paragraph(esc(text), styles[style])


def h1(text):
    return p(text, "H1Cn")


def h2(text):
    return p(text, "H2Cn")


def bullet(text):
    return Paragraph("• " + esc(text), styles["BulletCn"])


def number(index, text):
    return Paragraph(f"{index}. " + esc(text), styles["NumberCn"])


def code(lines):
    content = [Paragraph(esc(line).replace(" ", "&nbsp;"), styles["CodeCn"]) for line in lines]
    table = Table([[content]], colWidths=[6.25 * inch])
    table.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), colors.HexColor("#F7F9FC")),
        ("BOX", (0, 0), (-1, -1), 0.5, colors.HexColor("#E2E8F0")),
        ("LEFTPADDING", (0, 0), (-1, -1), 8),
        ("RIGHTPADDING", (0, 0), (-1, -1), 8),
        ("TOPPADDING", (0, 0), (-1, -1), 6),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
    ]))
    return table


def callout(label, text):
    table = Table(
        [[Paragraph(f"<b>{esc(label)}：</b>{esc(text)}", styles["BodyCn"])]],
        colWidths=[6.25 * inch],
    )
    table.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), colors.HexColor("#F4F6F9")),
        ("BOX", (0, 0), (-1, -1), 0.5, colors.HexColor("#D8DEE9")),
        ("LEFTPADDING", (0, 0), (-1, -1), 9),
        ("RIGHTPADDING", (0, 0), (-1, -1), 9),
        ("TOPPADDING", (0, 0), (-1, -1), 7),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 7),
    ]))
    return table


def table(headers, rows, widths):
    data = [[Paragraph(esc(h), styles["TableHeaderCn"]) for h in headers]]
    for row in rows:
        data.append([Paragraph(esc(c), styles["TableCn"]) for c in row])
    result = Table(data, colWidths=[w * inch for w in widths], repeatRows=1)
    result.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#E8EEF5")),
        ("GRID", (0, 0), (-1, -1), 0.4, colors.HexColor("#D9E2F3")),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("LEFTPADDING", (0, 0), (-1, -1), 5),
        ("RIGHTPADDING", (0, 0), (-1, -1), 5),
        ("TOPPADDING", (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
    ]))
    return result


def gap(size=6):
    return Spacer(1, size)


def header_footer(canvas, doc):
    canvas.saveState()
    canvas.setFont(FONT, 8)
    canvas.setFillColor(colors.HexColor("#64748B"))
    canvas.drawRightString(PAGE_W - 0.85 * inch, PAGE_H - 0.48 * inch, "离床报警系统 · 判断逻辑说明")
    canvas.drawCentredString(PAGE_W / 2, 0.42 * inch, rf"D:\2026\202605\bed alarm · 第 {doc.page} 页")
    canvas.restoreState()


story = [
    p("离床、在床判断逻辑说明", "TitleCn"),
    p("当前 Android APP + 云端配置服务实现说明（2026-06-03）", "SubtitleCn"),
    callout("核心结论", "当前系统优先使用每床动态基线和在床模板判断；当某张床还没有完整动态模型时，自动回退到普通/低体重患者的固定阈值逻辑。动态模型和固定阈值都只处理 occupancy4pressure 四点压力包。"),
    gap(8),
    h1("1. 总体流程"),
    p("APP 每收到一个 MQTT 帧，会先解析协议并按网关号、传感器号匹配床位。对于 occupancy4pressure 包，APP 使用四个转换后的压力值作为判断依据。判断结果写入 runtime.pressurePresence，后续状态显示、语音报警、闪烁、确认离床等都围绕这个状态展开。"),
]
for item in [
    "数据包解析：读取 4 个原始 ADC 压力点，转换为 APP 内部压力值 pressure4。",
    "床位匹配：使用 gatewayId + sensorId 匹配床位绑定设备。",
    "重复包过滤：同一床位连续收到相同第 17 字节序列号时忽略本包。",
    "算法选择：有完整动态模型时使用动态算法；没有完整动态模型时使用固定阈值算法。",
    "状态输出：算法只输出 pressurePresence=true/false；true 表示在床，false 表示离床。",
]:
    story.append(bullet(item))
story += [gap(4), code([
    "occupancy4pressure -> 解析4点压力 -> 序列号去重 -> 选择算法",
    "    -> 动态模型完整：动态基线/模板比例判断",
    "    -> 动态模型不完整：固定阈值判断",
    "    -> pressurePresence=true/false -> UI状态和报警逻辑",
])]

story += [
    h1("2. 数据来源与压力值转换"),
    p("当前离床/在床判断使用 ParsedFrame.protocol == \"occupancy4pressure\" 的数据包。解析位置如下：网关号为帧内第 4-5 字节小端值，传感器号为第 6-7 字节小端值，四个原始 ADC 压力点从第 8 字节开始，每点 2 字节小端。"),
    p("APP 内部判断使用转换后的 pressure4，而不是原始 ADC。转换公式："),
    code(["pressure4[i] = min(255, adcUint16[i] / 16)   // 整数除法取整"]),
    p("同时，解析器会读取 occupancyPresent 标志和第 17 字节 sequenceId。但对于 occupancy4pressure 包，最终在床/离床状态优先由 pressurePresence 决定，occupancyPresent 只作为解析字段保留。"),
    h1("3. 状态机和重复包处理"),
    p("APP 为每张床维护 BedRuntime。与离床/在床判断直接相关的字段如下："),
    table(["字段", "作用", "说明"], [
        ("lastOccupancySequenceId", "重复包去重", "如果新包 sequenceId 与上一包相同，直接忽略。"),
        ("pressurePresence", "最终在床/离床压力判断", "true 为在床，false 为离床，null 为未知。"),
        ("pressureLowPacketCount", "离床确认计数", "只在疑似离床时递增，在床或不确定时清零。"),
        ("statusKey", "UI 与报警状态", "occupancy4pressure 下由 pressurePresence 转换为 in_bed/left/unknown。"),
        ("statusSince", "状态持续时间", "用于长久离床、动态采样稳定时间、报警显示。"),
    ], [1.55, 1.55, 3.15]),
    p("状态更新时有两个重要行为："),
    bullet("如果状态从 unknown 直接进入 left，系统把 statusSince 设为当前时间减 10 分钟，并把 leaveAlarmSpoken 设为 true。这样 APP 刚启动时已经离床的床位不会立刻语音报警，而是直接表现为长久离床。"),
    bullet("如果状态进入 in_bed，系统会停止离床语音、清除本地确认离床标志，并尝试清除云端确认离床状态。"),
]

story += [
    h1("4. 算法选择规则"),
    p("每张床都有一份动态压力参数 DynamicPressureProfile。只有当动态参数完整且可信时，APP 才使用动态算法。否则固定阈值逻辑继续生效。"),
    p("动态模型完整的条件："),
    bullet("emptyBaseline 必须包含 4 个空床基线压力值。"),
    bullet("inBedTemplate 必须包含 4 个在床模板压力值。"),
    bullet("templateDelta = max(1, inBedTemplate[i] - emptyBaseline[i])，4 点 templateDelta 总和必须 >= 8。"),
    callout("回退原则", "某张床只采集到空床基线或只采集到在床模板时，动态算法不会启用；该床继续按固定阈值判断，直到两类参数都采集完成。"),
    h1("5. 固定阈值备用逻辑"),
    p("固定阈值逻辑区分普通患者和低体重患者。工程师可在“离床在床判断逻辑设置”界面修改这些参数，并保存到云端 pressure_logic_setting 表。默认值如下："),
    table(["患者类型", "立即在床条件1", "立即在床条件2", "离床候选条件", "离床确认包数"], [
        ("普通患者", "任意1点 > 135", "任意2点 > 95", "4点全部 <= 80", "连续2包"),
        ("低体重患者", "任意1点 > 95", "任意2点 > 80", "4点全部 <= 75", "连续2包"),
    ], [0.8, 1.35, 1.35, 1.35, 1.15]),
    p("固定阈值伪代码如下："),
    code([
        "if any(pressure4 > inBedThreshold) or count(pressure4 > twoPointThreshold) >= 2:",
        "    pressurePresence = true      // 立即在床",
        "    pressureLowPacketCount = 0",
        "elif all(pressure4 <= leftThreshold):",
        "    pressureLowPacketCount += 1",
        "    if pressureLowPacketCount >= leftConfirmPackets:",
        "        pressurePresence = false // 确认离床",
        "elif any(pressure4 > leftThreshold):",
        "    pressureLowPacketCount = 0   // 不满足离床连续性，保持原状态",
    ]),
    p("固定逻辑中的“保持原状态”很重要：当压力值既不像明确在床，也不像明确离床时，系统不会贸然切换状态，只清零低压计数，等待下一包数据。"),
]

story += [
    h1("6. 动态基线/模板判断逻辑"),
    p("动态算法对普通患者和低体重患者都生效。患者类型不再改变动态比例阈值；患者类型只影响动态算法中的离床确认包数下限来源，以及动态模型未完成时的固定阈值备用逻辑。"),
    h2("6.1 去基线与模板差值"),
    p("每包数据先用当前压力减去空床基线，得到去基线后的有效压力 adjusted。负值按 0 处理。"),
    code(["adjusted[i] = max(0, pressure4[i] - emptyBaseline[i])", "templateDelta[i] = max(1, inBedTemplate[i] - emptyBaseline[i])"]),
    h2("6.2 三个比例指标"),
    table(["指标", "公式", "含义"], [
        ("sumRatio", "sum(adjusted) / sum(templateDelta)", "总压力接近个人在床模板的程度。"),
        ("maxRatio", "max(adjusted) / max(templateDelta)", "最大单点压力接近模板的程度。"),
        ("top2Ratio", "sum(最大两个 adjusted) / sum(最大两个 templateDelta)", "最大两个压力点合计接近模板的程度。"),
    ], [1.0, 2.55, 2.7]),
    h2("6.3 在床判断：快速进入在床"),
    p("只要三个比例指标中任意一个达到在床阈值，系统立即判定在床，并清零离床低压计数。"),
    code(["inBedEvidence =", "    sumRatio >= 0.35", "    OR maxRatio >= 0.45", "    OR top2Ratio >= 0.40", "", "if inBedEvidence:", "    pressurePresence = true", "    pressureLowPacketCount = 0"]),
    h2("6.4 离床判断：稳健确认离床"),
    p("离床必须三个比例指标同时足够低，才认为是“疑似离床”。疑似离床不会立刻切换，必须连续达到确认包数。"),
    code(["leftEvidence =", "    sumRatio <= 0.20", "    AND maxRatio <= 0.30", "    AND top2Ratio <= 0.25", "", "if leftEvidence:", "    pressureLowPacketCount += 1", "    confirmPackets = max(当前固定阈值配置的 leftConfirmPackets, 2)", "    if pressureLowPacketCount >= confirmPackets:", "        pressurePresence = false"]),
    p("如果既不满足在床证据，也不满足离床证据，系统只清零 pressureLowPacketCount，不改变当前 pressurePresence。这就是当前状态机的滞回区：它减少翻身、偏床、短时抖动造成的误切换。"),
]

story += [
    h1("7. 动态参数采集与每日自动更新"),
    p("动态参数由 APP 在运行过程中自动学习。学习不需要人工进入设置界面，但前提是 APP 持续运行并持续收到该床 occupancy4pressure 数据包。"),
    table(["要采集的参数", "触发状态", "稳定时间", "保存频率"], [
        ("emptyBaseline 空床基线", "statusKey == left", "连续保持离床 >= 10分钟", "每床每天最多1次"),
        ("inBedTemplate 在床模板", "statusKey == in_bed", "连续保持在床 >= 10分钟", "每床每天最多1次"),
    ], [1.7, 1.45, 1.85, 1.25]),
    p("采样细节："),
]
for item in [
    "每张床有独立采样状态。状态从 in_bed 切到 left，或从 left 切到 in_bed 时，采样起点和样本列表都会重置。",
    "每次收到有效 occupancy4pressure 包，保存该包的 4 点 pressure4 样本。",
    "每床最多保留最近 180 包样本。达到 10 分钟稳定状态后，对每个压力点分别取中位数，得到 4 点参数。",
    "当天已经更新过空床基线，则当天不再更新空床基线；当天已经更新过在床模板，则当天不再更新在床模板。",
    "参数先写入本地缓存，再异步上传云端。云端上传失败时，本地动态参数仍继续参与判断。",
]:
    story.append(bullet(item))

story += [
    h1("8. 云端保存与多终端同步"),
    p("动态参数存储在 bedalarm.86086.cn 对应服务器的 MySQL bedalarm 数据库，表名为 bed_pressure_dynamic_profile。APP 通过 bedalarm-config-api 读取和保存。"),
    table(["项目", "当前实现", "说明"], [
        ("读取接口", "GET /bedalarm-config/api/hospitals/{code}/dynamic-pressure-profiles", "APP 启动进入主界面时读取，后台同步也会读取。"),
        ("保存接口", "PUT /bedalarm-config/api/hospitals/{code}/dynamic-pressure-profiles", "每床每天自动更新参数后上传。"),
        ("本地缓存", "SharedPreferences dynamic_pressure_profile_*", "云端不可用时直接使用本地参数。"),
        ("同步间隔", "约 5 秒", "后台配置同步会刷新低体重、确认离床和动态参数。"),
    ], [1.0, 3.1, 2.15]),
    h1("9. UI显示和语音报警关系"),
    p("算法只决定 in_bed 或 left，UI 和报警再根据在线状态、确认状态、长久离床时间进行显示。"),
]
for item in [
    "在床：床位方块显示绿色，右上角显示“在床”。",
    "离床报警：离床且未确认、未超过 10 分钟时，红色闪烁并语音提示。",
    "确认离床：点击报警床位后，本机停止语音和闪烁，并保存云端确认状态；其他终端同步后显示粉红色。",
    "长久离床：离床持续 >= 10 分钟后，背景显示白色。",
    "离线：绑定设备 60 秒以上未收到 MQTT 数据时，显示离线。",
]:
    story.append(bullet(item))
story.append(p("语音报警条件：床位在线、状态为 left、未被确认、未播报过、且距离 statusSince 小于 10 分钟。语音内容为“X号床离床，请注意”，持续 10 秒。"))

story += [h1("10. 现场调试时的判读方法")]
for idx, item in enumerate([
    "先确认该床是否有动态模型：云端或本地必须同时有 4 点 emptyBaseline 和 4 点 inBedTemplate。",
    "如果没有完整动态模型，就按普通/低体重固定阈值检查当前 pressure4。",
    "如果有完整动态模型，就计算 adjusted、sumRatio、maxRatio、top2Ratio。",
    "任一在床比例达标会立即在床；三个离床比例同时达标且连续达到确认包数才离床。",
    "如果比例处于中间区域，状态保持不变，这是设计中的滞回区，不是漏判。",
    "如果刚打开 APP 时床位已经离床，系统可能直接显示长久离床且不语音，这是为了避免启动时大量误报警。",
], 1):
    story.append(number(idx, item))

story += [h1("11. 当前实现注意事项")]
for item in [
    "动态算法使用的是 APP 转换后的 pressure4 值，不是原始 ADC 值。",
    "动态参数自动学习依赖 APP 运行和 MQTT 数据持续到达；APP 关闭或设备离线时不会学习。",
    "空床基线和在床模板都是“每床每天最多更新一次”，并不是每次状态稳定都覆盖。",
    "动态模型未完整或模板与空床差异太小，会继续使用固定阈值备用逻辑。",
    "目前动态比例阈值普通患者和低体重患者相同；患者类型只影响备用固定阈值和离床确认包数配置。",
    "后台同步会合并云端动态参数；云端为空时保留本地已有参数。",
]:
    story.append(bullet(item))

story += [
    h1("12. 代码依据"),
    table(["源码位置", "对应逻辑"], [
        ("BedFrameParser.kt:82-97", "解析 occupancy4pressure、转换 pressure4、读取第17字节 sequenceId。"),
        ("MainActivity.kt:854-918", "匹配床位、序列号去重、更新 pressurePresence、状态切换和报警入口。"),
        ("MainActivity.kt:922-981", "固定阈值和动态比例判断。"),
        ("MainActivity.kt:984-1101", "动态参数采样、每日更新、本地缓存与云端保存。"),
        ("MainActivity.kt:2031-2053", "固定阈值默认值、动态比例阈值、10分钟稳定采样常量。"),
        ("BedAlarmConfigClient.kt:143-188", "动态参数云端读取/保存接口。"),
        ("server/bedalarm-config-api/app.py:306-405", "动态参数后端 GET/PUT 接口和数据库写入。"),
    ], [2.2, 4.05]),
]

doc = SimpleDocTemplate(
    str(PDF_PATH),
    pagesize=letter,
    rightMargin=0.85 * inch,
    leftMargin=0.85 * inch,
    topMargin=0.7 * inch,
    bottomMargin=0.65 * inch,
)
doc.build(story, onFirstPage=header_footer, onLaterPages=header_footer)
print(PDF_PATH)
