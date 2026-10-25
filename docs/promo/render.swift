
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
let DURATION: Double = 60.0
let RAW = "/Users/panzhihe/DSH/AI手机/docs/promo/raw"
let OUT_DIR = "/Users/panzhihe/DSH/AI手机/docs/promo"

// ---------- 颜色 ----------
func col(_ hex: UInt32, _ a: CGFloat = 1) -> CGColor {
    let r = CGFloat((hex >> 16) & 0xFF) / 255
    let g = CGFloat((hex >> 8) & 0xFF) / 255
    let b = CGFloat(hex & 0xFF) / 255
    return CGColor(red: r, green: g, blue: b, alpha: a)
}
let KRAFT: UInt32 = 0xE8D5B7
let BROWN: UInt32 = 0x6B4F2A
let BLUE:  UInt32 = 0x5AA9FF
let TXT:   UInt32 = 0xF4EEE5
let SUB:   UInt32 = 0xA99C8B

// ---------- 缓动 ----------
func clamp(_ v: Double, _ a: Double = 0, _ b: Double = 1) -> Double { min(max(v, a), b) }
func easeOutCubic(_ t: Double) -> Double { let x = clamp(t); return 1 - pow(1 - x, 3) }
func easeInOutCubic(_ t: Double) -> Double {
    let x = clamp(t)
    return x < 0.5 ? 4*x*x*x : 1 - pow(-2*x + 2, 3) / 2
}
func easeOutBack(_ t: Double) -> Double {
    let c1 = 1.20, c3 = c1 + 1, x = clamp(t)
    return 1 + c3 * pow(x - 1, 3) + c1 * pow(x - 1, 2)
}
func prog(_ t: Double, _ start: Double, _ dur: Double) -> Double { clamp((t - start) / max(dur, 0.0001)) }

// ---------- 文字 ----------
var measureLog: [String] = []
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
    measureLog.append("\(s.prefix(12)) w=\(Int(width))/\(Int(maxWidth)) size=\(Int(fs))")
    let x: CGFloat = (align == "center") ? centerX - width/2 : centerX
    let baseline = centerY - (asc + desc)/2 + desc
    ctx.saveGState()
    ctx.textPosition = CGPoint(x: x, y: baseline)
    CTLineDraw(line, ctx)
    ctx.restoreGState()
    return width
}

/// 一组字幕：同一位置先后出现，前一条完全退场后下一条才进场
struct Caption { let start: Double; let end: Double; let title: String; let sub: String }
func drawCaptions(_ ctx: CGContext, _ captions: [Caption], t: Double, y: CGFloat, subY: CGFloat,
                  maxW: CGFloat = 900, titleSize: CGFloat = 54, subSize: CGFloat = 32) {
    for c in captions {
        let fin = easeOutCubic(prog(t, c.start, 0.5))
        let fout = 1 - easeInOutCubic(prog(t, c.end - 0.4, 0.4))
        let a = CGFloat(fin * fout)
        if a <= 0.002 { continue }
        let dy = CGFloat((1 - fin) * 20)
        _ = drawText(ctx, c.title, size: titleSize, weight: "STHeitiSC-Medium", color: TXT, alpha: a,
                     centerX: W/2, centerY: y + dy, maxWidth: maxW, kern: 1)
        _ = drawText(ctx, c.sub, size: subSize, weight: "STHeitiSC-Light", color: SUB, alpha: a * 0.96,
                     centerX: W/2, centerY: subY + dy, maxWidth: maxW + 40, kern: 1)
    }
}

func fillRound(_ ctx: CGContext, _ r: CGRect, _ rad: CGFloat, _ color: CGColor) {
    ctx.saveGState(); ctx.addPath(roundedPath(r, rad)); ctx.setFillColor(color); ctx.fillPath(); ctx.restoreGState()
}
func roundedPath(_ r: CGRect, _ radius: CGFloat) -> CGPath {
    CGPath(roundedRect: r, cornerWidth: radius, cornerHeight: radius, transform: nil)
}

func bgGradient(_ ctx: CGContext, _ t: Double) {
    let cs = CGColorSpaceCreateDeviceRGB()
    let g = CGGradient(colorsSpace: cs, colors: [col(0x1B1610), col(0x0B0908)] as CFArray, locations: [0, 1])!
    ctx.saveGState()
    ctx.drawLinearGradient(g, start: CGPoint(x: 0, y: H), end: CGPoint(x: 0, y: 0), options: [])
    ctx.restoreGState()
    let pulse = 1.0 + 0.04 * sin(t * 0.7)
    let warm = CGGradient(colorsSpace: cs, colors: [col(0x6B4F2A, 0.30), col(0x6B4F2A, 0.0)] as CFArray, locations: [0, 1])!
    ctx.saveGState()
    ctx.drawRadialGradient(warm, startCenter: CGPoint(x: 540, y: 1180), startRadius: 0,
                           endCenter: CGPoint(x: 540, y: 1180), endRadius: 900 * pulse, options: [])
    ctx.restoreGState()
    let cool = CGGradient(colorsSpace: cs, colors: [col(BLUE, 0.10), col(BLUE, 0.0)] as CFArray, locations: [0, 1])!
    ctx.saveGState()
    ctx.drawRadialGradient(cool, startCenter: CGPoint(x: 880, y: 430), startRadius: 0,
                           endCenter: CGPoint(x: 880, y: 430), endRadius: 620, options: [])
    ctx.restoreGState()
    let vig = CGGradient(colorsSpace: cs,
                         colors: [col(0x000000, 0.0), col(0x000000, 0.0), col(0x000000, 0.55)] as CFArray,
                         locations: [0, 0.55, 1])!
    ctx.saveGState()
    ctx.drawRadialGradient(vig, startCenter: CGPoint(x: 540, y: 960), startRadius: 220,
                           endCenter: CGPoint(x: 540, y: 960), endRadius: 1150, options: [.drawsAfterEndLocation])
    ctx.restoreGState()
}

// ---------- logo ----------
let logoSubpaths: [[CGPoint]] = [
    [CGPoint(x:20,y:88), CGPoint(x:20,y:46), CGPoint(x:46,y:20), CGPoint(x:88,y:20),
     CGPoint(x:88,y:62), CGPoint(x:62,y:88), CGPoint(x:20,y:88)],
    [CGPoint(x:20,y:46), CGPoint(x:62,y:46), CGPoint(x:62,y:88)],
    [CGPoint(x:62,y:46), CGPoint(x:88,y:20)],
]
func pathLength(_ p: [CGPoint]) -> CGFloat {
    var l: CGFloat = 0
    for i in 1..<p.count { l += hypot(p[i].x - p[i-1].x, p[i].y - p[i-1].y) }
    return l
}
func partialPoints(_ p: [CGPoint], _ frac: CGFloat) -> [CGPoint] {
    if frac <= 0 { return [] }
    let total = pathLength(p)
    var remaining = total * frac
    var out: [CGPoint] = [p[0]]
    for i in 1..<p.count {
        let seg = hypot(p[i].x - p[i-1].x, p[i].y - p[i-1].y)
        if remaining >= seg { out.append(p[i]); remaining -= seg }
        else {
            let k = remaining / seg
            out.append(CGPoint(x: p[i-1].x + (p[i].x - p[i-1].x) * k, y: p[i-1].y + (p[i].y - p[i-1].y) * k))
            break
        }
    }
    return out
}
func drawLogo(_ ctx: CGContext, rect: CGRect, progress: Double, color: UInt32 = KRAFT, alpha: CGFloat = 1,
              lineWidth: CGFloat = 3.4) {
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
        let from = i == 0 ? 0.0 : weights[i-1]
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
    guard let src = CGImageSourceCreateWithURL(URL(fileURLWithPath: p) as CFURL, nil) else {
        FileHandle.standardError.write("!! 载入失败: \(p)\n".data(using: .utf8)!)
        return nil
    }
    guard let img = CGImageSourceCreateImageAtIndex(src, 0, nil) else {
        FileHandle.standardError.write("!! 解码失败: \(p)\n".data(using: .utf8)!)
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
        guard let track = asset.tracks(withMediaType: .video).first else { return nil }
        guard let r = try? AVAssetReader(asset: asset) else { return nil }
        let out = AVAssetReaderTrackOutput(track: track, outputSettings: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
        ])
        out.alwaysCopiesSampleData = false
        r.add(out)
        r.startReading()
        reader = r; output = out
    }
    /// t 必须是单调递增的绝对时间
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
    let cx = (iw - cropW)/2 + offset.x, cy = (ih - cropH)/2 + offset.y
    guard let sub = img.cropping(to: CGRect(x: max(0,cx), y: max(0,cy), width: min(cropW, iw), height: min(cropH, ih))) else { return }
    ctx.saveGState()
    ctx.setAlpha(alpha)
    ctx.interpolationQuality = .high
    ctx.draw(sub, in: rect)
    ctx.restoreGState()
}

func drawPhone(_ ctx: CGContext, rect: CGRect, radius: CGFloat = 58) {
    ctx.saveGState()
    ctx.setShadow(offset: CGSize(width: 0, height: -22), blur: 48, color: col(0x000000, 0.55))
    fillRound(ctx, rect.insetBy(dx: -1, dy: -1), radius, col(0x2A2521))
    ctx.restoreGState()
    ctx.saveGState()
    ctx.addPath(roundedPath(rect.insetBy(dx: -1, dy: -1), radius))
    ctx.setStrokeColor(col(0xFFF6E6, 0.16))
    ctx.setLineWidth(2)
    ctx.strokePath()
    ctx.restoreGState()
}
func screenRect(_ phone: CGRect, inset: CGFloat = 11) -> CGRect { phone.insetBy(dx: inset, dy: inset) }

// 手机 + 内容图（带入场动画与缓慢推镜）
func drawPhoneWithContent(_ ctx: CGContext, phone: CGRect, radius: CGFloat, content: (CGContext, CGRect) -> Void,
                          enter: Double, zoom: CGFloat = 1.0) {
    let e = easeOutBack(enter)
    let sc = 0.94 + 0.06 * CGFloat(easeOutCubic(enter))
    ctx.saveGState()
    ctx.translateBy(x: phone.midX, y: phone.midY + CGFloat(1 - e) * -120)
    ctx.scaleBy(x: sc, y: sc)
    ctx.translateBy(x: -phone.midX, y: -phone.midY)
    ctx.setAlpha(CGFloat(easeOutCubic(enter)))
    drawPhone(ctx, rect: phone, radius: radius)
    let sr = screenRect(phone, inset: 10)
    ctx.saveGState()
    ctx.addPath(roundedPath(sr, radius - 8)); ctx.clip()
    content(ctx, sr)
    ctx.restoreGState()
    ctx.restoreGState()
}

func rippleRings(_ ctx: CGContext, sr: CGRect, t: Double, center: CGPoint, every: Double, count: Int) {
    for k in 0..<count {
        let base = 0.6 + Double(k) * every
        let p = prog(t, base, 1.4)
        if p <= 0 || p >= 1 { continue }
        let r = 14 + 150 * CGFloat(easeOutCubic(p))
        let a = CGFloat(1 - p) * 0.5
        ctx.saveGState()
        ctx.setStrokeColor(col(BLUE, a))
        ctx.setLineWidth(5 * CGFloat(1 - p) + 1.5)
        ctx.addEllipse(in: CGRect(x: center.x - r, y: center.y - r, width: r*2, height: r*2))
        ctx.strokePath()
        ctx.setFillColor(col(BLUE, a * 0.4))
        let r2 = max(0, 11 - 9 * CGFloat(easeOutCubic(p)))
        ctx.addEllipse(in: CGRect(x: center.x - r2, y: center.y - r2, width: r2*2, height: r2*2))
        ctx.fillPath()
        ctx.restoreGState()
    }
}

// ---------- 素材实例 ----------
// 每个片段独立读，避免来回跳时间
let clipMain = ClipReader(url: URL(fileURLWithPath: "\(RAW)/clip2.mp4"))     // 主界面空态
let clipSet  = ClipReader(url: URL(fileURLWithPath: "\(RAW)/clip2.mp4"))     // 设置页
let clipPerm = ClipReader(url: URL(fileURLWithPath: "\(RAW)/clip2.mp4"))     // 权限页
let imgOverlay = loadImage("02-悬浮窗.png")
let imgMain = loadImage("01-主界面.png")
let imgSettings = loadImage("03-设置页.png")
let imgPerm = loadImage("permissions.png")

// ---------- 场景 ----------
struct SceneCtx { var t: Double; var lt: Double; var dur: Double }
struct Scene { let start: Double; let end: Double; let draw: (CGContext, SceneCtx) -> Void }

// S1 开场
func s1(_ ctx: CGContext, _ s: SceneCtx) {
    let t = s.lt
    let drawP = easeInOutCubic(prog(t, 0.35, 1.9))
    let logoSize: CGFloat = 300
    let logoRect = CGRect(x: (W - logoSize)/2, y: 1010, width: logoSize, height: logoSize)
    let pop = 0.97 + 0.03 * easeOutCubic(prog(t, 0.2, 1.2))
    ctx.saveGState()
    ctx.translateBy(x: W/2, y: logoRect.midY)
    ctx.scaleBy(x: pop, y: pop)
    ctx.translateBy(x: -W/2, y: -logoRect.midY)
    drawLogo(ctx, rect: logoRect, progress: drawP, alpha: 1, lineWidth: 3.2)
    ctx.restoreGState()

    let a1 = easeOutCubic(prog(t, 1.7, 0.9))
    _ = drawText(ctx, "纸盒", size: 116, weight: "STHeitiSC-Medium", color: TXT, alpha: CGFloat(a1),
                 centerX: W/2, centerY: 880 + CGFloat((1 - a1) * 26), kern: 8)
    let a2 = easeOutCubic(prog(t, 2.25, 0.9))
    _ = drawText(ctx, "让 AI 直接操作你的安卓手机", size: 42, weight: "STHeitiSC-Light", color: SUB,
                 alpha: CGFloat(a2), centerX: W/2, centerY: 780 + CGFloat((1 - a2) * 20), kern: 2)
    let a3 = easeOutCubic(prog(t, 2.95, 0.9))
    ctx.saveGState()
    ctx.setStrokeColor(col(KRAFT, CGFloat(a3) * 0.45))
    ctx.setLineWidth(2)
    ctx.move(to: CGPoint(x: W/2 - 90, y: 700)); ctx.addLine(to: CGPoint(x: W/2 + 90, y: 700))
    ctx.strokePath()
    ctx.restoreGState()
    _ = drawText(ctx, "不需要 root  ·  不需要电脑  ·  不要 Shizuku", size: 30, weight: "STHeitiSC-Light",
                 color: SUB, alpha: CGFloat(easeOutCubic(prog(t, 3.2, 0.9))), centerX: W/2, centerY: 640, kern: 2)
}

// S2 痛点
func s2(_ ctx: CGContext, _ s: SceneCtx) {
    let t = s.lt
    _ = drawText(ctx, "现  在", size: 28, weight: "STHeitiSC-Medium", color: BLUE,
                 alpha: CGFloat(easeOutCubic(prog(t, 0.15, 0.7))), centerX: W/2, centerY: 1330, kern: 6)
    _ = drawText(ctx, "「帮我把 Wi-Fi 关了」", size: 66, weight: "STHeitiSC-Medium", color: TXT,
                 alpha: CGFloat(easeOutCubic(prog(t, 0.45, 0.8))), centerX: W/2, centerY: 1190, maxWidth: 900)
    _ = drawText(ctx, "然后你还是自己戳了 8 下屏幕。", size: 44, weight: "STHeitiSC-Light", color: SUB,
                 alpha: CGFloat(easeOutCubic(prog(t, 1.35, 0.8))), centerX: W/2, centerY: 1076, maxWidth: 900)

    let a3 = easeInOutCubic(prog(t, 2.4, 0.8))
    ctx.saveGState()
    ctx.setStrokeColor(col(KRAFT, CGFloat(a3) * 0.35))
    ctx.setLineWidth(2)
    let half = 160 * CGFloat(a3)
    ctx.move(to: CGPoint(x: W/2 - half, y: 946)); ctx.addLine(to: CGPoint(x: W/2 + half, y: 946))
    ctx.strokePath()
    ctx.restoreGState()

    _ = drawText(ctx, "这件事，本该它自己来", size: 72, weight: "STHeitiSC-Medium", color: KRAFT,
                 alpha: CGFloat(easeOutCubic(prog(t, 2.7, 0.9))), centerX: W/2, centerY: 800, maxWidth: 940, kern: 2)
    _ = drawText(ctx, "装一个 APK，开一次无障碍权限", size: 34, weight: "STHeitiSC-Light", color: SUB,
                 alpha: CGFloat(easeOutCubic(prog(t, 3.4, 0.9))), centerX: W/2, centerY: 700, kern: 2)
}

// S3 主界面空态（真机录屏）
func s3(_ ctx: CGContext, _ s: SceneCtx) {
    let t = s.lt
    let pw: CGFloat = 620
    let ph = pw * 1600 / 900
    let phone = CGRect(x: (W - pw)/2, y: 400, width: pw, height: ph)
    drawPhoneWithContent(ctx, phone: phone, radius: 54, content: { c, sr in
        if let f = clipMain?.image(at: 0.35 + t * 0.72) { drawFilled(c, f, in: sr, zoom: 1.0 + 0.04 * CGFloat(clamp(t / s.dur))) }
        else { fillRound(c, sr, 46, col(0x14110E)) }
    }, enter: prog(t, 0.0, 0.9))
    let caps = [Caption(start: 0.8, end: s.dur - 0.2, title: "一句话告诉它要干什么",
                        sub: "剩下的它自己看屏幕、点按钮、打字")]
    drawCaptions(ctx, caps, t: t, y: 300, subY: 226)
}

// S4 设置页（真机录屏）
func s4(_ ctx: CGContext, _ s: SceneCtx) {
    let t = s.lt
    let pw: CGFloat = 620
    let ph = pw * 1600 / 900
    let phone = CGRect(x: (W - pw)/2, y: 400, width: pw, height: ph)
    drawPhoneWithContent(ctx, phone: phone, radius: 54, content: { c, sr in
        if let f = clipSet?.image(at: 3.7 + t) { drawFilled(c, f, in: sr, zoom: 1.0) }
        else { fillRound(c, sr, 46, col(0x14110E)) }
    }, enter: 1.0)
    let caps = [
        Caption(start: 0.7, end: 4.6, title: "想用哪个模型，自己选",
                sub: "填自己的 Key，或用内置免费通道"),
        Caption(start: 4.6, end: s.dur - 0.3, title: "思考强度，一条滑块",
                sub: "OFF · LOW · HIGH · MAX，实际发什么参数都写在页面上"),
    ]
    drawCaptions(ctx, caps, t: t, y: 300, subY: 226)
}

// S5 悬浮窗 / 水波 / 急停（真截图）
func s5(_ ctx: CGContext, _ s: SceneCtx) {
    let t = s.lt
    let pw: CGFloat = 660
    let ph = pw * 1600 / 900
    let phone = CGRect(x: (W - pw)/2, y: 340, width: pw, height: ph)
    drawPhoneWithContent(ctx, phone: phone, radius: 56, content: { c, sr in
        let z = 1.02 + 0.05 * CGFloat(clamp(t / s.dur))
        if let img = imgOverlay {
            drawFilled(c, img, in: sr, zoom: z)
        } else { fillRound(c, sr, 48, col(0x14110E)) }
        let center = CGPoint(x: sr.minX + sr.width * 0.55, y: sr.minY + sr.height * 0.40)
        rippleRings(c, sr: sr, t: t, center: center, every: 2.2, count: 4)
        // 悬浮条高亮框：截图里它固定在左上（图内 x 0.028~0.535 / y 0.098~0.215）
        func mapX(_ u: CGFloat) -> CGFloat { sr.minX + (0.5 + (u - 0.5) / z) * sr.width }
        func mapY(_ v: CGFloat) -> CGFloat { sr.minY + (1 - (0.5 + (v - 0.5) / z)) * sr.height }
        let ring = CGRect(x: mapX(0.028), y: mapY(0.215), width: mapX(0.535) - mapX(0.028),
                          height: mapY(0.098) - mapY(0.215))
        let pulse = 0.55 + 0.25 * sin(t * 2.4)
        c.saveGState()
        c.addPath(roundedPath(ring, 18))
        c.setStrokeColor(col(BLUE, CGFloat(pulse)))
        c.setLineWidth(4)
        c.strokePath()
        c.restoreGState()
    }, enter: prog(t, 0.0, 0.9))
    let caps = [
        Caption(start: 0.7, end: 4.8, title: "点在哪，看得见",
                sub: "每一次落点闪一圈水波，点错了一眼就能发现"),
        Caption(start: 4.8, end: s.dur - 0.3, title: "随时可以急停",
                sub: "左上角悬浮条写着它在干嘛，一句话不对就按停"),
    ]
    drawCaptions(ctx, caps, t: t, y: 1660, subY: 1584)
}

// S6 两种模型（排版本）
func s6(_ ctx: CGContext, _ s: SceneCtx) {
    let t = s.lt
    _ = drawText(ctx, "两种模型，随便切", size: 60, weight: "STHeitiSC-Medium", color: TXT,
                 alpha: CGFloat(easeOutCubic(prog(t, 0.15, 0.7))), centerX: W/2, centerY: 1600,
                 maxWidth: 920, kern: 1)
    _ = drawText(ctx, "设置页顶部一键切", size: 30, weight: "STHeitiSC-Light", color: SUB,
                 alpha: CGFloat(easeOutCubic(prog(t, 0.45, 0.8)) * 0.95), centerX: W/2, centerY: 1528, maxWidth: 900)

    struct Card { let x: CGFloat; let kicker: String; let lines: [String]; let start: Double }
    let cards: [Card] = [
        Card(x: 320, kicker: "自定义模型",
             lines: ["接口地址 + API Key + 模型名", "一键拉取模型列表", "DeepSeek / OpenRouter / Ollama"],
             start: 0.7),
        Card(x: 760, kicker: "免费模型",
             lines: ["内置免密聚合通道", "不用填 Key 也能直接跑", "模型列表实时读取"],
             start: 1.0),
    ]
    for c0 in cards {
        let p = prog(t, c0.start, 0.8)
        if p <= 0 { continue }
        let a = CGFloat(easeOutCubic(p))
        let dy = (1 - CGFloat(easeOutBack(p))) * 40
        let r = CGRect(x: c0.x - 215, y: 940 + dy, width: 430, height: 430)
        ctx.saveGState()
        ctx.setAlpha(a)
        fillRound(ctx, r, 34, col(0xFFFFFF, 0.055))
        ctx.addPath(roundedPath(r, 34))
        ctx.setStrokeColor(col(KRAFT, 0.22))
        ctx.setLineWidth(2)
        ctx.strokePath()
        ctx.restoreGState()
        _ = drawText(ctx, c0.kicker, size: 40, weight: "STHeitiSC-Medium", color: KRAFT, alpha: a,
                     centerX: c0.x, centerY: r.maxY - 78, maxWidth: 360)
        var ly = r.maxY - 168
        for line in c0.lines {
            _ = drawText(ctx, line, size: 27, weight: "STHeitiSC-Light", color: SUB, alpha: a * 0.95,
                         centerX: c0.x, centerY: ly, maxWidth: 380)
            ly -= 56
        }
    }

    let a3 = CGFloat(easeOutCubic(prog(t, 1.9, 0.8)))
    fillRound(ctx, CGRect(x: 110, y: 700, width: 860, height: 104), 52, col(0xFFFFFF, 0.06 * a3))
    _ = drawText(ctx, "思考强度  OFF · LOW · HIGH · MAX · 默认", size: 33, weight: "STHeitiSC-Medium",
                 color: KRAFT, alpha: a3, centerX: W/2, centerY: 752, maxWidth: 790, kern: 1)
    _ = drawText(ctx, "每一档实际发出的参数，页面上都写给你看", size: 27, weight: "STHeitiSC-Light", color: SUB,
                 alpha: a3 * 0.9, centerX: W/2, centerY: 640, maxWidth: 880)
}

// S7 安全（真截图 + 三条要点）
func s7(_ ctx: CGContext, _ s: SceneCtx) {
    let t = s.lt
    let pw: CGFloat = 480
    let ph = pw * 1600 / 900
    let phone = CGRect(x: (W - pw)/2, y: 700, width: pw, height: ph)
    drawPhoneWithContent(ctx, phone: phone, radius: 44, content: { c, sr in
        if let img = imgPerm {
            drawFilled(c, img, in: sr, zoom: 1.0 + 0.04 * CGFloat(clamp(t / s.dur)))
        } else { fillRound(c, sr, 38, col(0x14110E)) }
    }, enter: prog(t, 0.0, 0.9))

    _ = drawText(ctx, "它只在你手机上跑", size: 52, weight: "STHeitiSC-Medium", color: TXT,
                 alpha: CGFloat(easeOutCubic(prog(t, 0.5, 0.7))), centerX: W/2, centerY: 1700, maxWidth: 900, kern: 1)

    let items = [
        (0.9, "API Key 加密后只存本机，密钥在系统 Keystore 里，不可导出"),
        (1.4, "无广告、无统计、不后台联网"),
        (1.9, "每一步都有日志，出事能一键导出 zip 反馈"),
    ]
    var y: CGFloat = 560
    for (st, text) in items {
        let a = CGFloat(easeOutCubic(prog(t, st, 0.7)))
        if a <= 0.002 { y -= 95; continue }
        ctx.saveGState()
        ctx.setFillColor(col(KRAFT, a * 0.9))
        ctx.addPath(roundedPath(CGRect(x: 92, y: y - 12, width: 24, height: 24), 8))
        ctx.fillPath()
        ctx.restoreGState()
        _ = drawText(ctx, text, size: 30, weight: "STHeitiSC-Light", color: TXT, alpha: a * 0.95,
                     centerX: 140, centerY: y, maxWidth: 840, align: "left")
        y -= 95
    }
}

// S8 三步上手
func s8(_ ctx: CGContext, _ s: SceneCtx) {
    let t = s.lt
    _ = drawText(ctx, "上手只要三步", size: 58, weight: "STHeitiSC-Medium", color: TXT,
                 alpha: CGFloat(easeOutCubic(prog(t, 0.15, 0.7))), centerX: W/2, centerY: 1520, maxWidth: 900, kern: 2)
    let rows = [
        (0.6, "01", "装一个 APK", "允许「安装未知来源应用」"),
        (1.1, "02", "开一次无障碍权限", "系统设置里点一下，长期有效"),
        (1.6, "03", "说一句话", "剩下的它自己动手"),
    ]
    var y: CGFloat = 1290
    for (st, num, title, note) in rows {
        let a = CGFloat(easeOutCubic(prog(t, st, 0.7)))
        _ = drawText(ctx, num, size: 40, weight: "STHeitiSC-Medium", color: KRAFT, alpha: a,
                     centerX: 250, centerY: y, maxWidth: 120)
        _ = drawText(ctx, title, size: 42, weight: "STHeitiSC-Medium", color: TXT, alpha: a,
                     centerX: 330, centerY: y, maxWidth: 560, align: "left")
        _ = drawText(ctx, note, size: 26, weight: "STHeitiSC-Light", color: SUB, alpha: a * 0.9,
                     centerX: 330, centerY: y - 56, maxWidth: 620, align: "left")
        y -= 170
    }
    let a2 = CGFloat(easeOutCubic(prog(t, 2.4, 0.8)))
    fillRound(ctx, CGRect(x: 150, y: 620, width: 780, height: 96), 48, col(0xFFFFFF, 0.06 * a2))
    _ = drawText(ctx, "免费模型开箱即用，不填 Key 也能跑", size: 30, weight: "STHeitiSC-Medium", color: KRAFT,
                 alpha: a2, centerX: W/2, centerY: 668, maxWidth: 700)
}

// S9 收尾
func s9(_ ctx: CGContext, _ s: SceneCtx) {
    let t = s.lt
    let logoSize: CGFloat = 150
    let logoRect = CGRect(x: (W - logoSize)/2, y: 1400, width: logoSize, height: logoSize)
    drawLogo(ctx, rect: logoRect, progress: easeInOutCubic(prog(t, 0.05, 1.0)),
             alpha: CGFloat(easeOutCubic(prog(t, 0.05, 0.5))), lineWidth: 3.4)
    _ = drawText(ctx, "纸盒", size: 64, weight: "STHeitiSC-Medium", color: TXT,
                 alpha: CGFloat(easeOutCubic(prog(t, 0.6, 0.8))), centerX: W/2, centerY: 1300, kern: 6)
    let lines: [(Double, String, CGFloat, UInt32, String)] = [
        (1.0, "装一个 APK，开一次无障碍权限", 46, TXT, "STHeitiSC-Medium"),
        (1.5, "然后就不用管了", 46, KRAFT, "STHeitiSC-Medium"),
        (2.1, "不绑定厂商，不限定机型", 42, KRAFT, "STHeitiSC-Medium"),
        (2.6, "免费模型开箱即用 · 无 root · 无广告 · 无统计", 28, SUB, "STHeitiSC-Light"),
    ]
    var y: CGFloat = 1180
    for l in lines {
        _ = drawText(ctx, l.1, size: l.2, weight: l.4, color: l.3,
                     alpha: CGFloat(easeOutCubic(prog(t, l.0, 0.8))), centerX: W/2, centerY: y,
                     maxWidth: 920, kern: 1)
        y -= (l.2 > 40 ? 84 : 72)
    }
    _ = drawText(ctx, "github.com/panzih/AI-phone-use-bypanzhihe", size: 27, weight: "SFMono-Regular",
                 color: SUB, alpha: CGFloat(easeOutCubic(prog(t, 3.0, 0.9)) * 0.9), centerX: W/2, centerY: 798,
                 maxWidth: 900)
    _ = drawText(ctx, "MIT · By 潘纸盒", size: 26, weight: "STHeitiSC-Light", color: SUB,
                 alpha: CGFloat(easeOutCubic(prog(t, 3.3, 0.9)) * 0.85), centerX: W/2, centerY: 724, kern: 3)
}

let scenes: [Scene] = [
    Scene(start: 0.0,  end: 5.0,  draw: s1),
    Scene(start: 5.0,  end: 11.0, draw: s2),
    Scene(start: 11.0, end: 14.6, draw: s3),
    Scene(start: 14.6, end: 23.0, draw: s4),
    Scene(start: 23.0, end: 32.0, draw: s5),
    Scene(start: 32.0, end: 40.0, draw: s6),
    Scene(start: 40.0, end: 46.6, draw: s7),
    Scene(start: 46.6, end: 52.0, draw: s8),
    Scene(start: 52.0, end: 60.0, draw: s9),
]
let FADE = 0.55

// ---------- 渲染 ----------
let outURL = URL(fileURLWithPath: "\(OUT_DIR)/paperbox-promo-video.mp4")
try? FileManager.default.removeItem(at: outURL)
let writer = try AVAssetWriter(outputURL: outURL, fileType: .mp4)
let vsettings: [String: Any] = [
    AVVideoCodecKey: AVVideoCodecType.h264,
    AVVideoWidthKey: Int(W),
    AVVideoHeightKey: Int(H),
    AVVideoCompressionPropertiesKey: [
        AVVideoAverageBitRateKey: 12_000_000,
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
let startWall = Date()
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
        if sc.start == 0 { a = min(1.0, clamp((sc.end - t) / FADE)) }
        if sc.end == DURATION { a = min(clamp((t - sc.start) / FADE), 1.0) }
        if a <= 0.001 { continue }
        ctx.saveGState()
        ctx.setAlpha(CGFloat(a))
        sc.draw(ctx, SceneCtx(t: t, lt: max(0, t - sc.start), dur: sc.end - sc.start))
        ctx.restoreGState()
    }
    let fadeOut = clamp((DURATION - 0.7 - t) / 0.7)
    if fadeOut < 1 {
        ctx.setFillColor(col(0x000000, CGFloat(1 - fadeOut)))
        ctx.fill(CGRect(x: 0, y: 0, width: W, height: H))
    }
    CVPixelBufferUnlockBaseAddress(pb, [])
    adaptor.append(pb, withPresentationTime: CMTime(value: CMTimeValue(frame), timescale: CMTimeScale(FPS)))
    if frame % 300 == 0 { print("frame \(frame)/\(totalFrames)  \(String(format: "%.1f", Date().timeIntervalSince(startWall)))s") }
}
vinput.markAsFinished()
let sem = DispatchSemaphore(value: 0)
writer.finishWriting { sem.signal() }
sem.wait()
print("video done status:", writer.status.rawValue)
