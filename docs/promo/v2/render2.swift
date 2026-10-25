//
//  纸盒 · 宣传片 v2（90 秒解说版）画面渲染器
//
//  纯 CoreGraphics 现画 + 内嵌真机录屏/截图。零第三方依赖，macOS 命令行编译即可：
//      swiftc -O -o /tmp/promo2 render2.swift && /tmp/promo2
//
//  场景起止时间不写死在这里，运行时读同目录的 timeline.json
//  （由 make_timeline.py 按解说语音长度生成）——改文案/改语速只要重跑那个脚本。
//

import Foundation
import AVFoundation
import CoreGraphics
import CoreText
import ImageIO
import VideoToolbox

// ---------- 基本参数 ----------
let W: CGFloat = 1080
let H: CGFloat = 1920
let FPS: Double = 30
let BASE = "/Users/panzhihe/DSH/AI手机/docs/promo/v2"
let RAW = BASE + "/raw"
let OUT = BASE + "/paperbox-promo-v2.mp4"

// ---------- 时间线 ----------
struct SceneTime { let id: String; let start: Double; let end: Double }
let timelineData = try! Data(contentsOf: URL(fileURLWithPath: BASE + "/timeline.json"))
let timelineJSON = try! JSONSerialization.jsonObject(with: timelineData) as! [String: Any]
let SCENES_TIME: [SceneTime] = (timelineJSON["scenes"] as! [[String: Any]]).map {
    SceneTime(id: $0["id"] as! String, start: $0["start"] as! Double, end: $0["end"] as! Double)
}
let DURATION: Double = timelineJSON["total"] as! Double
func sceneSpan(_ id: String) -> (Double, Double) {
    guard let s = SCENES_TIME.first(where: { $0.id == id }) else { fatalError("缺场景 \(id)") }
    return (s.start, s.end)
}

// ---------- 颜色 ----------
func col(_ hex: UInt32, _ a: CGFloat = 1) -> CGColor {
    let r = CGFloat((hex >> 16) & 0xFF) / 255
    let g = CGFloat((hex >> 8) & 0xFF) / 255
    let b = CGFloat(hex & 0xFF) / 255
    return CGColor(red: r, green: g, blue: b, alpha: a)
}
let KRAFT: UInt32 = 0xE8D5B7
let BROWN: UInt32 = 0x6B4F2A
let BLUE: UInt32 = 0x5AA9FF
let TXT: UInt32 = 0xF4EEE5
let SUB: UInt32 = 0xA99C8B

// ---------- 缓动 ----------
func clamp(_ v: Double, _ a: Double = 0, _ b: Double = 1) -> Double { min(max(v, a), b) }
func easeOutCubic(_ t: Double) -> Double { let x = clamp(t); return 1 - pow(1 - x, 3) }
func easeInOutCubic(_ t: Double) -> Double {
    let x = clamp(t)
    return x < 0.5 ? 4 * x * x * x : 1 - pow(-2 * x + 2, 3) / 2
}
func easeOutBack(_ t: Double) -> Double {
    let c1 = 1.20, c3 = c1 + 1, x = clamp(t)
    return 1 + c3 * pow(x - 1, 3) + c1 * pow(x - 1, 2)
}
func prog(_ t: Double, _ start: Double, _ dur: Double) -> Double { clamp((t - start) / max(dur, 0.0001)) }

// ---------- 文字 ----------
@discardableResult
func drawText(_ ctx: CGContext, _ s: String, size: CGFloat, weight: String = "STHeitiSC-Medium",
              color: UInt32 = TXT, alpha: CGFloat = 1, centerX: CGFloat, centerY: CGFloat,
              maxWidth: CGFloat = 960, kern: CGFloat = 0, align: String = "center") -> CGFloat {
    if alpha <= 0.001 || s.isEmpty { return 0 }
    var fs = size
    var font = CTFontCreateWithName(weight as CFString, fs, nil)
    var line: CTLine!
    var width: CGFloat = 0
    func build() {
        var attrs: [NSAttributedString.Key: Any] = [
            kCTFontAttributeName as NSAttributedString.Key: font,
            kCTForegroundColorAttributeName as NSAttributedString.Key: col(color, alpha),
        ]
        if kern != 0 { attrs[kCTKernAttributeName as NSAttributedString.Key] = kern }
        line = CTLineCreateWithAttributedString(NSAttributedString(string: s, attributes: attrs))
        var asc: CGFloat = 0, desc: CGFloat = 0, lead: CGFloat = 0
        width = CGFloat(CTLineGetTypographicBounds(line, &asc, &desc, &lead))
    }
    build()
    if width > maxWidth {
        fs *= maxWidth / width
        font = CTFontCreateWithName(weight as CFString, fs, nil)
        build()
    }
    var asc: CGFloat = 0, desc: CGFloat = 0, lead: CGFloat = 0
    _ = CTLineGetTypographicBounds(line, &asc, &desc, &lead)
    let x: CGFloat = (align == "center") ? centerX - width / 2 : centerX
    let baseline = centerY - (asc + desc) / 2 + desc
    ctx.saveGState()
    ctx.textPosition = CGPoint(x: x, y: baseline)
    CTLineDraw(line, ctx)
    ctx.restoreGState()
    return width
}

/// 一组字幕：前一条完全退场后下一条才进场，避免压字
struct Caption { let start: Double; let end: Double; let title: String; let sub: String }
func drawCaptions(_ ctx: CGContext, _ captions: [Caption], t: Double, y: CGFloat, subY: CGFloat,
                  maxW: CGFloat = 900, titleSize: CGFloat = 52, subSize: CGFloat = 30) {
    for c in captions {
        let fin = easeOutCubic(prog(t, c.start, 0.45))
        let fout = 1 - easeInOutCubic(prog(t, c.end - 0.35, 0.35))
        let a = CGFloat(fin * fout)
        if a <= 0.002 { continue }
        let dy = CGFloat((1 - fin) * 18)
        _ = drawText(ctx, c.title, size: titleSize, weight: "STHeitiSC-Medium", color: TXT, alpha: a,
                     centerX: W / 2, centerY: y + dy, maxWidth: maxW, kern: 1)
        if !c.sub.isEmpty {
            _ = drawText(ctx, c.sub, size: subSize, weight: "STHeitiSC-Light", color: SUB, alpha: a * 0.96,
                         centerX: W / 2, centerY: subY + dy, maxWidth: maxW + 60, kern: 1)
        }
    }
}

func fillRound(_ ctx: CGContext, _ r: CGRect, _ rad: CGFloat, _ color: CGColor) {
    ctx.saveGState(); ctx.addPath(roundedPath(r, rad)); ctx.setFillColor(color); ctx.fillPath(); ctx.restoreGState()
}
func strokeRound(_ ctx: CGContext, _ r: CGRect, _ rad: CGFloat, _ color: CGColor, _ lw: CGFloat) {
    ctx.saveGState(); ctx.addPath(roundedPath(r, rad)); ctx.setStrokeColor(color)
    ctx.setLineWidth(lw); ctx.strokePath(); ctx.restoreGState()
}
func roundedPath(_ r: CGRect, _ radius: CGFloat) -> CGPath {
    CGPath(roundedRect: r, cornerWidth: radius, cornerHeight: radius, transform: nil)
}

// ---------- 背景 ----------
func bgGradient(_ ctx: CGContext, _ t: Double) {
    let cs = CGColorSpaceCreateDeviceRGB()
    let g = CGGradient(colorsSpace: cs, colors: [col(0x1B1610), col(0x0B0908)] as CFArray, locations: [0, 1])!
    ctx.saveGState()
    ctx.drawLinearGradient(g, start: CGPoint(x: 0, y: H), end: CGPoint(x: 0, y: 0), options: [])
    ctx.restoreGState()

    let pulse = 1.0 + 0.05 * sin(t * 0.6)
    let warm = CGGradient(colorsSpace: cs, colors: [col(0x6B4F2A, 0.30), col(0x6B4F2A, 0.0)] as CFArray,
                          locations: [0, 1])!
    ctx.saveGState()
    ctx.drawRadialGradient(warm, startCenter: CGPoint(x: 540, y: 1150), startRadius: 0,
                           endCenter: CGPoint(x: 540, y: 1150), endRadius: 920 * pulse, options: [])
    ctx.restoreGState()

    let cool = CGGradient(colorsSpace: cs, colors: [col(BLUE, 0.09), col(BLUE, 0.0)] as CFArray,
                          locations: [0, 1])!
    ctx.saveGState()
    ctx.drawRadialGradient(cool, startCenter: CGPoint(x: 880, y: 420), startRadius: 0,
                           endCenter: CGPoint(x: 880, y: 420), endRadius: 640, options: [])
    ctx.restoreGState()

    let vig = CGGradient(colorsSpace: cs,
                         colors: [col(0x000000, 0.0), col(0x000000, 0.0), col(0x000000, 0.55)] as CFArray,
                         locations: [0, 0.55, 1])!
    ctx.saveGState()
    ctx.drawRadialGradient(vig, startCenter: CGPoint(x: 540, y: 960), startRadius: 240,
                           endCenter: CGPoint(x: 540, y: 960), endRadius: 1180, options: [.drawsAfterEndLocation])
    ctx.restoreGState()
}

// ---------- logo（与 App 内 ic_box_logo 同一份几何） ----------
let logoSubpaths: [[CGPoint]] = [
    [CGPoint(x: 20, y: 88), CGPoint(x: 20, y: 46), CGPoint(x: 46, y: 20), CGPoint(x: 88, y: 20),
     CGPoint(x: 88, y: 62), CGPoint(x: 62, y: 88), CGPoint(x: 20, y: 88)],
    [CGPoint(x: 20, y: 46), CGPoint(x: 62, y: 46), CGPoint(x: 62, y: 88)],
    [CGPoint(x: 62, y: 46), CGPoint(x: 88, y: 20)],
]
func pathLength(_ p: [CGPoint]) -> CGFloat {
    var l: CGFloat = 0
    for i in 1..<p.count { l += hypot(p[i].x - p[i - 1].x, p[i].y - p[i - 1].y) }
    return l
}
func partialPoints(_ p: [CGPoint], _ frac: CGFloat) -> [CGPoint] {
    if frac <= 0 { return [] }
    var remaining = pathLength(p) * frac
    var out: [CGPoint] = [p[0]]
    for i in 1..<p.count {
        let seg = hypot(p[i].x - p[i - 1].x, p[i].y - p[i - 1].y)
        if remaining >= seg { out.append(p[i]); remaining -= seg }
        else {
            let k = remaining / seg
            out.append(CGPoint(x: p[i - 1].x + (p[i].x - p[i - 1].x) * k,
                               y: p[i - 1].y + (p[i].y - p[i - 1].y) * k))
            break
        }
    }
    return out
}
func drawLogo(_ ctx: CGContext, rect: CGRect, progress: Double, color: UInt32 = KRAFT,
              alpha: CGFloat = 1, lineWidth: CGFloat = 3.4) {
    let s = rect.width / 108.0
    let pr = clamp(progress)
    ctx.saveGState()
    ctx.translateBy(x: rect.minX, y: rect.minY)
    ctx.scaleBy(x: s, y: s)
    ctx.translateBy(x: 0, y: 108)
    ctx.scaleBy(x: 1, y: -1)
    ctx.setLineCap(.round); ctx.setLineJoin(.round)
    ctx.setStrokeColor(col(color, alpha))
    ctx.setLineWidth(lineWidth)
    let weights = [0.52, 0.85, 1.0]
    for (i, sp) in logoSubpaths.enumerated() {
        let from = i == 0 ? 0.0 : weights[i - 1]
        let to = weights[i]
        let frac = CGFloat(clamp((pr - from) / (to - from)))
        let pts = partialPoints(sp, frac)
        if pts.count >= 2 {
            ctx.beginPath()
            ctx.move(to: pts[0])
            for p in pts.dropFirst() { ctx.addLine(to: p) }
            ctx.strokePath()
        }
    }
    ctx.restoreGState()
}

// ---------- 素材 ----------
func loadImage(_ name: String) -> CGImage? {
    let p = "\(RAW)/\(name)"
    guard let src = CGImageSourceCreateWithURL(URL(fileURLWithPath: p) as CFURL, nil),
          let img = CGImageSourceCreateImageAtIndex(src, 0, nil) else {
        FileHandle.standardError.write("!! 素材载入失败: \(p)\n".data(using: .utf8)!)
        return nil
    }
    return img
}

final class ClipReader {
    private var output: AVAssetReaderTrackOutput!
    private var reader: AVAssetReader!
    private var currentImg: CGImage?
    private var currentT: Double = -1
    private var finished = false
    init?(url: URL) {
        let asset = AVURLAsset(url: url)
        guard let track = asset.tracks(withMediaType: .video).first,
              let r = try? AVAssetReader(asset: asset) else { return nil }
        let out = AVAssetReaderTrackOutput(track: track, outputSettings: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
        ])
        out.alwaysCopiesSampleData = false
        r.add(out)
        r.startReading()
        reader = r; output = out
    }
    func image(at t: Double) -> CGImage? {
        if finished { return currentImg }
        if currentImg != nil && currentT > t + 0.001 { return currentImg }
        while true {
            guard let sb = output.copyNextSampleBuffer() else { finished = true; return currentImg }
            let pts = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sb))
            guard let pb = CMSampleBufferGetImageBuffer(sb) else { continue }
            var img: CGImage?
            VTCreateCGImageFromCVPixelBuffer(pb, options: nil, imageOut: &img)
            if let img = img {
                currentImg = img; currentT = pts
                if pts + 0.033 >= t { return currentImg }
            }
        }
    }
}

func drawFilled(_ ctx: CGContext, _ img: CGImage, in rect: CGRect, zoom: CGFloat = 1.0,
                offset: CGPoint = .zero, alpha: CGFloat = 1) {
    let iw = CGFloat(img.width), ih = CGFloat(img.height)
    let target = rect.width / rect.height
    let srcAspect = iw / ih
    var cropW = iw, cropH = ih
    if srcAspect > target { cropW = ih * target } else { cropH = iw / target }
    cropW /= zoom; cropH /= zoom
    let cx = (iw - cropW) / 2 + offset.x, cy = (ih - cropH) / 2 + offset.y
    guard let sub = img.cropping(to: CGRect(x: max(0, cx), y: max(0, cy),
                                            width: min(cropW, iw), height: min(cropH, ih))) else { return }
    ctx.saveGState()
    ctx.setAlpha(alpha)
    ctx.interpolationQuality = .high
    ctx.draw(sub, in: rect)
    ctx.restoreGState()
}

/// 把截图里的某一块（归一化坐标，原点左上）画进一个圆角卡片，用来做"细节特写"
func drawRegion(_ ctx: CGContext, _ img: CGImage, nx: CGFloat, ny: CGFloat, nw: CGFloat, nh: CGFloat,
                in dst: CGRect, radius: CGFloat = 26, alpha: CGFloat = 1) {
    let iw = CGFloat(img.width), ih = CGFloat(img.height)
    let src = CGRect(x: nx * iw, y: ny * ih, width: nw * iw, height: nh * ih)
    guard let sub = img.cropping(to: src) else { return }
    ctx.saveGState()
    ctx.setAlpha(alpha)
    ctx.interpolationQuality = .high
    ctx.addPath(roundedPath(dst, radius)); ctx.clip()
    ctx.draw(sub, in: dst)
    ctx.restoreGState()
    strokeRound(ctx, dst, radius, col(KRAFT, 0.20 * alpha), 2)
}

func drawPhone(_ ctx: CGContext, rect: CGRect, radius: CGFloat = 58) {    ctx.saveGState()
    ctx.setShadow(offset: CGSize(width: 0, height: -22), blur: 48, color: col(0x000000, 0.55))
    fillRound(ctx, rect.insetBy(dx: -1, dy: -1), radius, col(0x2A2521))
    ctx.restoreGState()
    strokeRound(ctx, rect.insetBy(dx: -1, dy: -1), radius, col(0xFFF6E6, 0.16), 2)
}

func drawPhoneWithContent(_ ctx: CGContext, phone: CGRect, radius: CGFloat,
                          content: (CGContext, CGRect) -> Void, enter: Double, lift: CGFloat = -110) {
    let e = easeOutBack(enter)
    let sc = 0.95 + 0.05 * CGFloat(easeOutCubic(enter))
    ctx.saveGState()
    ctx.translateBy(x: phone.midX, y: phone.midY + CGFloat(1 - e) * lift)
    ctx.scaleBy(x: sc, y: sc)
    ctx.translateBy(x: -phone.midX, y: -phone.midY)
    ctx.setAlpha(CGFloat(easeOutCubic(enter)))
    drawPhone(ctx, rect: phone, radius: radius)
    let sr = phone.insetBy(dx: 10, dy: 10)
    ctx.saveGState()
    ctx.addPath(roundedPath(sr, radius - 8)); ctx.clip()
    content(ctx, sr)
    ctx.restoreGState()
    ctx.restoreGState()
}

/// 在屏幕图上圈一块区域（坐标按 0~1 归一化，原点左上）
func ringOnScreen(_ ctx: CGContext, sr: CGRect, x0: CGFloat, y0: CGFloat, x1: CGFloat, y1: CGFloat,
                  t: Double, color: UInt32 = BLUE, lw: CGFloat = 5, radius: CGFloat = 18) {
    let rect = CGRect(x: sr.minX + x0 * sr.width, y: sr.minY + (1 - y1) * sr.height,
                      width: (x1 - x0) * sr.width, height: (y1 - y0) * sr.height)
    let pulse = 0.55 + 0.3 * sin(t * 2.6)
    strokeRound(ctx, rect, radius, col(color, CGFloat(pulse)), lw)
    fillRound(ctx, rect, radius, col(color, CGFloat(0.06 + 0.04 * sin(t * 2.6))))
}

func rippleRings(_ ctx: CGContext, t: Double, center: CGPoint, every: Double, count: Int) {
    for k in 0..<count {
        let base = 0.5 + Double(k) * every
        let p = prog(t, base, 1.5)
        if p <= 0 || p >= 1 { continue }
        let r = 16 + 150 * CGFloat(easeOutCubic(p))
        let a = CGFloat(1 - p) * 0.45
        ctx.saveGState()
        ctx.setStrokeColor(col(BLUE, a))
        ctx.setLineWidth(5 * CGFloat(1 - p) + 1.5)
        ctx.addEllipse(in: CGRect(x: center.x - r, y: center.y - r, width: r * 2, height: r * 2))
        ctx.strokePath()
        ctx.restoreGState()
    }
}

// ---------- 素材实例 ----------
let imgMain = loadImage("main-empty.png")
let imgSettings = loadImage("settings-model.png")
let imgPerm = loadImage("permissions.png")
let clipTask = ClipReader(url: URL(fileURLWithPath: "\(RAW)/task-loop.mp4"))

// ---------- 场景 ----------
struct SceneCtx { var t: Double; var lt: Double; var dur: Double }
struct Scene { let start: Double; let end: Double; let draw: (CGContext, SceneCtx) -> Void }

/// 顶部小标签（章节感）
func kicker(_ ctx: CGContext, _ s: String, alpha: Double, y: CGFloat = 1740) {
    _ = drawText(ctx, s, size: 26, weight: "STHeitiSC-Medium", color: BLUE,
                 alpha: CGFloat(alpha), centerX: W / 2, centerY: y, kern: 8)
}

// S1 开场
func s1(_ ctx: CGContext, _ s: SceneCtx) {
    let t = s.lt
    let logoSize: CGFloat = 250
    let logoRect = CGRect(x: (W - logoSize) / 2, y: 1040, width: logoSize, height: logoSize)
    let pop = 0.97 + 0.03 * CGFloat(easeOutCubic(prog(t, 0.15, 1.2)))
    ctx.saveGState()
    ctx.translateBy(x: W / 2, y: logoRect.midY)
    ctx.scaleBy(x: pop, y: pop)
    ctx.translateBy(x: -W / 2, y: -logoRect.midY)
    drawLogo(ctx, rect: logoRect, progress: easeInOutCubic(prog(t, 0.25, 1.9)), lineWidth: 3.2)
    ctx.restoreGState()

    let a1 = easeOutCubic(prog(t, 2.0, 0.8))
    _ = drawText(ctx, "纸盒", size: 108, weight: "STHeitiSC-Medium", color: TXT, alpha: CGFloat(a1),
                 centerX: W / 2, centerY: 905 + CGFloat((1 - a1) * 24), kern: 10)
    let a2 = easeOutCubic(prog(t, 2.45, 0.8))
    _ = drawText(ctx, "让 AI 直接操作你的安卓手机", size: 40, weight: "STHeitiSC-Light", color: SUB,
                 alpha: CGFloat(a2), centerX: W / 2, centerY: 810 + CGFloat((1 - a2) * 18), kern: 2)
    let a3 = easeOutCubic(prog(t, 3.1, 0.8))
    ctx.saveGState()
    ctx.setStrokeColor(col(KRAFT, CGFloat(a3) * 0.4))
    ctx.setLineWidth(2)
    ctx.move(to: CGPoint(x: W / 2 - 100, y: 748)); ctx.addLine(to: CGPoint(x: W / 2 + 100, y: 748))
    ctx.strokePath()
    ctx.restoreGState()
    _ = drawText(ctx, "不需要 root  ·  不需要电脑", size: 28, weight: "STHeitiSC-Light",
                 color: SUB, alpha: CGFloat(easeOutCubic(prog(t, 3.4, 0.8))),
                 centerX: W / 2, centerY: 690, kern: 3)
}

// S2 痛点
func s2(_ ctx: CGContext, _ s: SceneCtx) {
    let t = s.lt
    kicker(ctx, "现  在", alpha: easeOutCubic(prog(t, 0.15, 0.7)))

    let rows: [(Double, String, Int)] = [
        (0.35, "关掉 Wi-Fi", 4),
        (0.85, "清一下缓存", 3),
        (1.35, "把那段话复制出来", 5),
    ]
    var y: CGFloat = 1450
    for (st, text, taps) in rows {
        let p = prog(t, st, 0.7)
        if p <= 0 { y -= 150; continue }
        let a = CGFloat(easeOutCubic(p))
        let dx = CGFloat((1 - easeOutBack(p)) * -60)
        // 计数：从 0 数到 taps，随后标成"×N 下"
        let countP = clamp((t - st - 0.5) / 1.6)
        let shown = Int((Double(taps) * countP).rounded())
        ctx.saveGState()
        ctx.setAlpha(a)
        fillRound(ctx, CGRect(x: 120 + dx, y: y - 44, width: 840, height: 88), 24, col(0xFFFFFF, 0.055))
        strokeRound(ctx, CGRect(x: 120 + dx, y: y - 44, width: 840, height: 88), 24, col(KRAFT, 0.16), 2)
        ctx.restoreGState()
        _ = drawText(ctx, text, size: 36, weight: "STHeitiSC-Medium", color: TXT, alpha: a * 0.95,
                     centerX: 160 + dx, centerY: y, maxWidth: 560, align: "left")
        _ = drawText(ctx, "\(shown) 下", size: 30, weight: "SFMono-Regular", color: BLUE,
                     alpha: a * 0.9, centerX: 900 + dx, centerY: y, maxWidth: 160, align: "right")
        y -= 150
    }

    let aLine = easeInOutCubic(prog(t, 4.6, 0.9))
    ctx.saveGState()
    ctx.setStrokeColor(col(KRAFT, CGFloat(aLine) * 0.3))
    ctx.setLineWidth(2)
    let half = 190 * CGFloat(aLine)
    ctx.move(to: CGPoint(x: W / 2 - half, y: 905)); ctx.addLine(to: CGPoint(x: W / 2 + half, y: 905))
    ctx.strokePath()
    ctx.restoreGState()

    _ = drawText(ctx, "你重复过太多遍", size: 72, weight: "STHeitiSC-Medium", color: KRAFT,
                 alpha: CGFloat(easeOutCubic(prog(t, 4.9, 0.9))), centerX: W / 2, centerY: 800, kern: 2)
    _ = drawText(ctx, "每次还得自己盯着屏幕，一下一下点过去", size: 34, weight: "STHeitiSC-Light",
                 color: SUB, alpha: CGFloat(easeOutCubic(prog(t, 8.0, 0.9))), centerX: W / 2,
                 centerY: 700, maxWidth: 940, kern: 1)
}

// S3 它是怎么做的（循环图）
func s3(_ ctx: CGContext, _ s: SceneCtx) {
    let t = s.lt
    let cx: CGFloat = 540, cy: CGFloat = 1010, R: CGFloat = 250
    let nodes: [(String, String, Double)] = [
        ("看屏幕", "读控件树 / 截图", 90),
        ("想一下", "模型决定下一步", 210),
        ("动手", "点击 · 输入 · 滑动", 330),
    ]
    let appear = easeOutCubic(prog(t, 0.2, 0.8))
    ctx.saveGState()
    ctx.setAlpha(CGFloat(appear))
    ctx.setStrokeColor(col(KRAFT, 0.22))
    ctx.setLineWidth(3)
    ctx.addEllipse(in: CGRect(x: cx - R, y: cy - R, width: R * 2, height: R * 2))
    ctx.strokePath()
    ctx.restoreGState()

    // 沿环走的点
    if appear > 0.5 {
        let ang = (t * 0.55).truncatingRemainder(dividingBy: 1.0) * 2 * Double.pi
        let px = cx + R * CGFloat(cos(ang)), py = cy + R * CGFloat(sin(ang))
        ctx.saveGState()
        ctx.setFillColor(col(BLUE, 0.85))
        ctx.addEllipse(in: CGRect(x: px - 9, y: py - 9, width: 18, height: 18))
        ctx.fillPath()
        ctx.setFillColor(col(BLUE, 0.18))
        ctx.addEllipse(in: CGRect(x: px - 26, y: py - 26, width: 52, height: 52))
        ctx.fillPath()
        ctx.restoreGState()
    }

    for (i, (title, note, deg)) in nodes.enumerated() {
        let p = prog(t, 0.5 + Double(i) * 0.28, 0.7)
        if p <= 0 { continue }
        let a = CGFloat(easeOutCubic(p))
        let rad = deg * Double.pi / 180
        let nx = cx + R * CGFloat(cos(rad))
        let ny = cy + R * CGFloat(sin(rad))
        ctx.saveGState()
        ctx.setAlpha(a)
        ctx.setFillColor(col(0x1F1A15))
        ctx.addEllipse(in: CGRect(x: nx - 96, y: ny - 96, width: 192, height: 192))
        ctx.fillPath()
        ctx.setStrokeColor(col(KRAFT, 0.35))
        ctx.setLineWidth(2)
        ctx.addEllipse(in: CGRect(x: nx - 96, y: ny - 96, width: 192, height: 192))
        ctx.strokePath()
        ctx.restoreGState()
        _ = drawText(ctx, title, size: 40, weight: "STHeitiSC-Medium", color: TXT, alpha: a,
                     centerX: nx, centerY: ny + 12, maxWidth: 180)
        _ = drawText(ctx, note, size: 20, weight: "STHeitiSC-Light", color: SUB, alpha: a * 0.9,
                     centerX: nx, centerY: ny - 36, maxWidth: 200)
    }

    let aC = CGFloat(easeOutCubic(prog(t, 1.5, 0.8)))
    _ = drawText(ctx, "一步接一步", size: 40, weight: "STHeitiSC-Medium", color: KRAFT, alpha: aC,
                 centerX: cx, centerY: cy + 26, maxWidth: 300)
    _ = drawText(ctx, "直到做完", size: 32, weight: "STHeitiSC-Light", color: SUB, alpha: aC * 0.9,
                 centerX: cx, centerY: cy - 26, maxWidth: 300)

    let aB = CGFloat(easeOutCubic(prog(t, 7.6, 0.9)))
    fillRound(ctx, CGRect(x: 150, y: 520, width: 780, height: 96), 48, col(0xFFFFFF, 0.06 * aB))
    _ = drawText(ctx, "不用 root，也不用连着电脑", size: 32, weight: "STHeitiSC-Medium", color: KRAFT,
                 alpha: aB, centerX: W / 2, centerY: 568, maxWidth: 700, kern: 1)
}

// S4 主界面空态
func s4(_ ctx: CGContext, _ s: SceneCtx) {
    let t = s.lt
    let pw: CGFloat = 660
    let ph = pw * 1600 / 900
    let phone = CGRect(x: (W - pw) / 2, y: 430, width: pw, height: ph)
    drawPhoneWithContent(ctx, phone: phone, radius: 56, content: { c, sr in
        if let img = imgMain {
            drawFilled(c, img, in: sr, zoom: 1.0 + 0.05 * CGFloat(clamp(t / s.dur)))
        } else { fillRound(c, sr, 48, col(0x14110E)) }
    }, enter: prog(t, 0.0, 0.9))
    // 指一下底部输入区
    let aP = CGFloat(easeOutCubic(prog(t, 1.6, 0.7)) * (1 - easeInOutCubic(prog(t, 4.6, 0.5))))
    if aP > 0.01 {
        let sr = CGRect(x: phone.minX + 10, y: phone.minY + 10, width: phone.width - 20, height: phone.height - 20)
        let rect = CGRect(x: sr.minX + 0.015 * sr.width, y: sr.minY + (1 - 0.995) * sr.height,
                          width: 0.97 * sr.width, height: 0.085 * sr.height)
        strokeRound(ctx, rect, 30, col(BLUE, aP * 0.75), 4)
        _ = drawText(ctx, "一句话，一个发送键", size: 26, weight: "STHeitiSC-Light", color: BLUE,
                     alpha: aP, centerX: W / 2, centerY: phone.minY - 46, maxWidth: 700)
    }
    _ = drawText(ctx, "打开就这样，没有第二个页面要学", size: 30, weight: "STHeitiSC-Light", color: SUB,
                 alpha: CGFloat(easeOutCubic(prog(t, 2.2, 0.8))), centerX: W / 2, centerY: 260, maxWidth: 900)
}

// S5 真跑一轮任务（真机录屏：悬浮条 → 自己打开设置）
func s5(_ ctx: CGContext, _ s: SceneCtx) {
    let t = s.lt
    let pw: CGFloat = 720
    let ph = pw * 1600 / 900
    let phone = CGRect(x: (W - pw) / 2, y: 400, width: pw, height: ph)
    // 这段录屏里 0–9.4s 是"还没按发送"，9.4s 起任务真的开跑（悬浮条推步数），
    // 11.5s 回到桌面、13.2s 白屏、14.4s 起定在系统「设置」列表
    // （模拟器合成器在 14s 后不再重绘，所以后段用定格 + 慢推镜）。
    let clipStart: Double = 9.9
    let cutAt: Double = 2.2

    drawPhoneWithContent(ctx, phone: phone, radius: 60, content: { c, sr in
        // 前 2.2s 顺着播（步数在推 → 它自己回到桌面），之后定在已经打开的「设置」列表
        let ct = t < cutAt ? clipStart + t : 14.45
        if let f = clipTask?.image(at: ct) {
            let z = 1.0 + 0.09 * CGFloat(clamp((t - cutAt) / max(s.dur - cutAt, 0.1)))
            drawFilled(c, f, in: sr, zoom: z)
        } else { fillRound(c, sr, 48, col(0x14110E)) }
        // 悬浮条：录屏里固定在屏幕下方 0.775~0.930
        if t < 2.0 {
            let a = CGFloat(clamp(t / 0.35)) * CGFloat(1 - clamp((t - 1.4) / 0.6))
            let pulse = 0.5 + 0.3 * sin(t * 2.8)
            let rect = CGRect(x: sr.minX + 0.02 * sr.width, y: sr.minY + (1 - 0.930) * sr.height,
                              width: 0.96 * sr.width, height: 0.155 * sr.height)
            strokeRound(c, rect, 20, col(BLUE, a * CGFloat(pulse)), 5)
        }
    }, enter: prog(t, 0.0, 0.9))

    let caps = [
        Caption(start: 0.55, end: 3.3, title: "按下去，它就开始干活",
                sub: "屏幕下方那条：这一步在做什么 · 下一步打算做什么"),
        Caption(start: 3.55, end: s.dur - 0.3, title: "然后它自己打开了设置",
                sub: "手指没碰过屏幕，一步都没替它点"),
    ]
    drawCaptions(ctx, caps, t: t, y: 1760, subY: 1698, maxW: 960, titleSize: 44, subSize: 26)
}

// S6 模型与思考强度（截图局部做成两张"细节卡"）
func s6(_ ctx: CGContext, _ s: SceneCtx) {
    let t = s.lt
    _ = drawText(ctx, "模型自己选", size: 56, weight: "STHeitiSC-Medium", color: TXT,
                 alpha: CGFloat(easeOutCubic(prog(t, 0.2, 0.7))), centerX: W / 2, centerY: 1690,
                 maxWidth: 900, kern: 1)

    // 卡 A：接口地址 / API Key / 模型名 / 获取模型列表（整块都在框里，不切字）
    let aA = CGFloat(easeOutBack(prog(t, 0.5, 0.8)))
    if aA > 0.01, let img = imgSettings {
        let r = CGRect(x: 90, y: 940 + (1 - aA) * 40, width: 900, height: 632)
        drawRegion(ctx, img, nx: 0, ny: 0.028, nw: 1, nh: 0.395, in: r, alpha: aA)
        _ = drawText(ctx, "接口地址 + API Key + 模型名", size: 26, weight: "STHeitiSC-Light", color: SUB,
                     alpha: aA * 0.95, centerX: W / 2, centerY: 890, maxWidth: 860)
    }
    // 卡 B：思考强度整段（含"实际发什么参数"那三行说明）
    let aB = CGFloat(easeOutBack(prog(t, 1.05, 0.8)))
    if aB > 0.01, let img = imgSettings {
        let r = CGRect(x: 90, y: 500 + (1 - aB) * 40, width: 900, height: 360)
        drawRegion(ctx, img, nx: 0, ny: 0.435, nw: 1, nh: 0.225, in: r, alpha: aB)
        _ = drawText(ctx, "思考强度：每一档实际发什么参数，页面上写着", size: 26, weight: "STHeitiSC-Light",
                     color: SUB, alpha: aB * 0.95, centerX: W / 2, centerY: 450, maxWidth: 880)
    }

    let caps = [
        Caption(start: 2.6, end: 6.6, title: "DeepSeek · OpenRouter · 本机 Ollama 都行", sub: ""),
        Caption(start: 6.8, end: s.dur - 0.3, title: "不想花钱？内置免费通道，不填 Key 也能跑", sub: ""),
    ]
    drawCaptions(ctx, caps, t: t, y: 300, subY: 300, maxW: 940, titleSize: 32, subSize: 26)
}

// S7 只在你手机上跑
func s7(_ ctx: CGContext, _ s: SceneCtx) {
    let t = s.lt
    let pw: CGFloat = 470
    let ph = pw * 1600 / 900
    let phone = CGRect(x: (W - pw) / 2, y: 760, width: pw, height: ph)
    drawPhoneWithContent(ctx, phone: phone, radius: 44, content: { c, sr in
        if let img = imgPerm {
            drawFilled(c, img, in: sr, zoom: 1.0 + 0.04 * CGFloat(clamp(t / s.dur)))
        } else { fillRound(c, sr, 38, col(0x14110E)) }
    }, enter: prog(t, 0.0, 0.9))

    _ = drawText(ctx, "它只在你手机上跑", size: 52, weight: "STHeitiSC-Medium", color: TXT,
                 alpha: CGFloat(easeOutCubic(prog(t, 0.4, 0.7))), centerX: W / 2, centerY: 1760,
                 maxWidth: 900, kern: 1)

    let items: [(Double, String)] = [
        (0.9, "API Key 加密后只存本机，密钥在系统 Keystore 里"),
        (1.5, "没有广告，没有统计，不后台联网"),
        (2.1, "每一步都留下日志，出事能一键导出 zip"),
    ]
    var y: CGFloat = 620
    for (st, text) in items {
        let a = CGFloat(easeOutCubic(prog(t, st, 0.7)))
        if a <= 0.002 { y -= 92; continue }
        ctx.saveGState()
        ctx.setFillColor(col(KRAFT, a * 0.9))
        ctx.addPath(roundedPath(CGRect(x: 98, y: y - 12, width: 22, height: 22), 7))
        ctx.fillPath()
        ctx.restoreGState()
        _ = drawText(ctx, text, size: 29, weight: "STHeitiSC-Light", color: TXT, alpha: a * 0.95,
                     centerX: 146, centerY: y, maxWidth: 820, align: "left")
        y -= 92
    }
}

// S8 三步上手
func s8(_ ctx: CGContext, _ s: SceneCtx) {
    let t = s.lt
    _ = drawText(ctx, "上手只要三步", size: 58, weight: "STHeitiSC-Medium", color: TXT,
                 alpha: CGFloat(easeOutCubic(prog(t, 0.15, 0.7))), centerX: W / 2, centerY: 1560,
                 maxWidth: 900, kern: 2)
    let rows: [(Double, String, String, String)] = [
        (0.55, "01", "装一个 APK", "允许「安装未知来源应用」"),
        (1.05, "02", "开一次无障碍权限", "系统设置里点一下，长期有效"),
        (1.55, "03", "说一句话", "剩下的它自己动手"),
    ]
    var y: CGFloat = 1330
    for (st, num, title, note) in rows {
        let a = CGFloat(easeOutCubic(prog(t, st, 0.7)))
        _ = drawText(ctx, num, size: 38, weight: "SFMono-Regular", color: KRAFT, alpha: a,
                     centerX: 250, centerY: y, maxWidth: 120)
        _ = drawText(ctx, title, size: 42, weight: "STHeitiSC-Medium", color: TXT, alpha: a,
                     centerX: 330, centerY: y, maxWidth: 620, align: "left")
        _ = drawText(ctx, note, size: 26, weight: "STHeitiSC-Light", color: SUB, alpha: a * 0.9,
                     centerX: 332, centerY: y - 56, maxWidth: 660, align: "left")
        y -= 172
    }
    let a2 = CGFloat(easeOutCubic(prog(t, 2.9, 0.8)))
    fillRound(ctx, CGRect(x: 150, y: 600, width: 780, height: 96), 48, col(0xFFFFFF, 0.06 * a2))
    _ = drawText(ctx, "免费模型开箱即用，不填 Key 也能跑", size: 30, weight: "STHeitiSC-Medium",
                 color: KRAFT, alpha: a2, centerX: W / 2, centerY: 648, maxWidth: 720)
}

// S9 收尾
func s9(_ ctx: CGContext, _ s: SceneCtx) {
    let t = s.lt
    let logoSize: CGFloat = 140
    let logoRect = CGRect(x: (W - logoSize) / 2, y: 1230, width: logoSize, height: logoSize)
    drawLogo(ctx, rect: logoRect, progress: easeInOutCubic(prog(t, 0.05, 0.9)),
             alpha: CGFloat(easeOutCubic(prog(t, 0.05, 0.45))), lineWidth: 3.4)
    _ = drawText(ctx, "纸盒", size: 58, weight: "STHeitiSC-Medium", color: TXT,
                 alpha: CGFloat(easeOutCubic(prog(t, 0.5, 0.7))), centerX: W / 2, centerY: 1138, kern: 8)

    let lines: [(Double, String, CGFloat, UInt32, String)] = [
        (0.9, "装一个 APK，开一次无障碍权限", 44, TXT, "STHeitiSC-Medium"),
        (1.35, "然后就不用管了", 44, KRAFT, "STHeitiSC-Medium"),
        (2.1, "不绑定厂商，不限定机型", 40, KRAFT, "STHeitiSC-Medium"),
        (2.6, "免费模型开箱即用 · 无 root · 无广告 · 无统计", 25, SUB, "STHeitiSC-Light"),
    ]
    var y: CGFloat = 1020
    for l in lines {
        _ = drawText(ctx, l.1, size: l.2, weight: l.4, color: l.3,
                     alpha: CGFloat(easeOutCubic(prog(t, l.0, 0.75))), centerX: W / 2, centerY: y,
                     maxWidth: 940, kern: 1)
        y -= (l.2 > 36 ? 82 : 66)
    }
    _ = drawText(ctx, "github.com/panzih/AI-phone-use-bypanzhihe", size: 26, weight: "SFMono-Regular",
                 color: SUB, alpha: CGFloat(easeOutCubic(prog(t, 3.1, 0.8)) * 0.92),
                 centerX: W / 2, centerY: 620, maxWidth: 940)
    _ = drawText(ctx, "MIT · By 潘纸盒 · v0.10.7", size: 24, weight: "STHeitiSC-Light", color: SUB,
                 alpha: CGFloat(easeOutCubic(prog(t, 3.4, 0.8)) * 0.85), centerX: W / 2,
                 centerY: 552, kern: 2)
}

// ---------- 组装 ----------
func mk(_ id: String, _ f: @escaping (CGContext, SceneCtx) -> Void) -> Scene {
    let (a, b) = sceneSpan(id)
    return Scene(start: a, end: b, draw: f)
}
let scenes: [Scene] = [mk("s1", s1), mk("s2", s2), mk("s3", s3), mk("s4", s4),
                       mk("s5", s5), mk("s6", s6), mk("s7", s7), mk("s8", s8), mk("s9", s9)]
let FADE = 0.55

// ---------- 渲染 ----------
let outURL = URL(fileURLWithPath: OUT)
try? FileManager.default.removeItem(at: outURL)
let writer = try AVAssetWriter(outputURL: outURL, fileType: .mp4)
let vsettings: [String: Any] = [
    AVVideoCodecKey: AVVideoCodecType.h264,
    AVVideoWidthKey: Int(W),
    AVVideoHeightKey: Int(H),
    AVVideoCompressionPropertiesKey: [
        AVVideoAverageBitRateKey: 9_000_000,
        AVVideoMaxKeyFrameIntervalKey: 45,
        AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel,
    ],
]
let vinput = AVAssetWriterInput(mediaType: .video, outputSettings: vsettings)
vinput.expectsMediaDataInRealTime = false
let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: vinput, sourcePixelBufferAttributes: [
    kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
    kCVPixelBufferWidthKey as String: Int(W),
    kCVPixelBufferHeightKey as String: Int(H),
])
writer.add(vinput)
writer.startWriting()
writer.startSession(atSourceTime: .zero)

let cs = CGColorSpaceCreateDeviceRGB()
let totalFrames = Int(DURATION * FPS)
let wall = Date()
for frame in 0..<totalFrames {
    while !vinput.isReadyForMoreMediaData { usleep(2000) }
    guard let pool = adaptor.pixelBufferPool else { fatalError("no pool") }
    var pbOut: CVPixelBuffer?
    CVPixelBufferPoolCreatePixelBuffer(nil, pool, &pbOut)
    guard let pb = pbOut else { fatalError("no pb") }
    CVPixelBufferLockBaseAddress(pb, [])
    let base = CVPixelBufferGetBaseAddress(pb)!
    let bpr = CVPixelBufferGetBytesPerRow(pb)
    let ctx = CGContext(data: base, width: Int(W), height: Int(H), bitsPerComponent: 8, bytesPerRow: bpr,
                        space: cs, bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)!
    ctx.setShouldAntialias(true)
    ctx.interpolationQuality = .high
    let t = Double(frame) / FPS
    bgGradient(ctx, t)
    for sc in scenes {
        if t < sc.start - FADE || t > sc.end + FADE { continue }
        var a = min(clamp((t - sc.start) / FADE), clamp((sc.end - t) / FADE))
        if sc.start <= 0.001 { a = min(1.0, clamp((sc.end - t) / FADE)) }
        if abs(sc.end - DURATION) < 0.001 { a = min(clamp((t - sc.start) / FADE), 1.0) }
        if a <= 0.001 { continue }
        ctx.saveGState()
        ctx.setAlpha(CGFloat(a))
        sc.draw(ctx, SceneCtx(t: t, lt: max(0, t - sc.start), dur: sc.end - sc.start))
        ctx.restoreGState()
    }
    let fadeOut = clamp((DURATION - 0.8 - t) / 0.8)
    if fadeOut < 1 {
        ctx.setFillColor(col(0x000000, CGFloat(1 - fadeOut)))
        ctx.fill(CGRect(x: 0, y: 0, width: W, height: H))
    }
    CVPixelBufferUnlockBaseAddress(pb, [])
    adaptor.append(pb, withPresentationTime: CMTime(value: CMTimeValue(frame), timescale: CMTimeScale(FPS)))
    if frame % 300 == 0 {
        print("frame \(frame)/\(totalFrames)  \(String(format: "%.1f", Date().timeIntervalSince(wall)))s")
    }
}
vinput.markAsFinished()
let sem = DispatchSemaphore(value: 0)
writer.finishWriting { sem.signal() }
sem.wait()
print("video done status:", writer.status.rawValue, "dur:", DURATION)
