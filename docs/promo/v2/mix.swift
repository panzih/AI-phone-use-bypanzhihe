//
//  纸盒 · 宣传片 v2 音频合成：解说（say 生成）+ 算出来的配乐 + 自动压低
//
//      swiftc -O -o /tmp/promomix mix.swift && /tmp/promomix
//  产物：/tmp/promo2.wav（44.1kHz 立体声 16bit），再交给 mux2 合成进画面。
//
//  配乐不依赖任何音频素材：pad 和弦 + 低频 + 场景切换的风铃/whoosh，
//  全部按采样点算出来；解说一开口，pad 自动让路（ducking）。
//

import Foundation
import AVFoundation

let BASE = "/Users/panzhihe/DSH/AI手机/docs/promo/v2"
let SR = 44100.0

// ---------- 时间线 ----------
let tlData = try! Data(contentsOf: URL(fileURLWithPath: BASE + "/timeline.json"))
let tl = try! JSONSerialization.jsonObject(with: tlData) as! [String: Any]
let DUR: Double = tl["total"] as! Double
let scenes = tl["scenes"] as! [[String: Any]]

let n = Int(DUR * SR) + Int(SR)
var L = [Double](repeating: 0, count: n)
var R = [Double](repeating: 0, count: n)

func midi(_ m: Double) -> Double { 440.0 * pow(2.0, (m - 69.0) / 12.0) }

// ---------- 1. 解说 ----------
var voice = [Double](repeating: 0, count: n)
for sc in scenes {
    let id = sc["id"] as! String
    let at = sc["audio"] as! Double
    let url = URL(fileURLWithPath: "\(BASE)/audio/\(id).aiff")
    guard let f = try? AVAudioFile(forReading: url) else {
        FileHandle.standardError.write("!! 读不到解说 \(id)\n".data(using: .utf8)!)
        continue
    }
    guard let buf = AVAudioPCMBuffer(pcmFormat: f.processingFormat, frameCapacity: AVAudioFrameCount(f.length)),
          (try? f.read(into: buf)) != nil else { continue }
    let src = buf.floatChannelData![0]
    let srcRate = f.processingFormat.sampleRate
    let dstStart = Int(at * SR)
    let dstLen = Int(Double(buf.frameLength) / srcRate * SR)
    for i in 0..<dstLen {
        let srcIdx = Double(i) * srcRate / SR
        let i0 = Int(srcIdx)
        let frac = srcIdx - Double(i0)
        let a = i0 < Int(buf.frameLength) ? Double(src[i0]) : 0
        let b = i0 + 1 < Int(buf.frameLength) ? Double(src[i0 + 1]) : a
        let v = a + (b - a) * frac
        let idx = dstStart + i
        if idx >= 0 && idx < n { voice[idx] += v }
    }
}

// ---------- 2. 配乐 ----------
// 每一场换一次和弦（Am – F – C – G 循环），场景切换叠一记 whoosh
let progNotes: [[Double]] = [
    [57, 60, 64, 69], [53, 57, 60, 65], [48, 55, 60, 64], [55, 59, 62, 67],
]
let bassNotes: [Double] = [45, 41, 36, 43]

for (i, sc) in scenes.enumerated() {
    let s0 = sc["start"] as! Double
    let s1 = sc["end"] as! Double
    let chord = progNotes[i % progNotes.count]
    let bass = bassNotes[i % bassNotes.count]
    let i0 = Int(s0 * SR), i1 = min(n, Int(s1 * SR) + Int(1.2 * SR))
    let cross = 1.4
    for k in i0..<max(i0, i1) {
        if k >= n { break }
        let t = Double(k) / SR - s0
        let tEnd = (Double(i1) / SR) - Double(k) / SR
        var env = min(1.0, t / cross) * min(1.0, tEnd / (cross * 1.4))
        // 段内缓慢起伏，别一直一个音量
        env *= 0.85 + 0.15 * sin(2 * .pi * 0.07 * t + Double(i))
        if env <= 0 { continue }
        var sL = 0.0, sR = 0.0
        for (j, m) in chord.enumerated() {
            let f = midi(m)
            let det = 1.0 + 0.0015 * Double(j % 2 == 0 ? 1 : -1)
            let trem = 1.0 + 0.05 * sin(2 * .pi * (0.10 + 0.025 * Double(j)) * t + Double(j))
            let amp = 0.030 * trem
            let a = sin(2 * .pi * f * det * t)
            let b = 0.26 * sin(2 * .pi * f * 2 * t + 0.7)
            let v = (a + b) * amp * env
            let pan = 0.5 + 0.30 * sin(Double(j) * 1.9)
            sL += v * (1.0 - pan * 0.5)
            sR += v * (0.5 + pan * 0.5)
        }
        let bv = 0.045 * sin(2 * .pi * midi(bass) * t) * env * (0.8 + 0.2 * sin(2 * .pi * 0.13 * t))
        L[k] += sL + bv
        R[k] += sR + bv
    }
    // 场景切换：低频 whoosh + 一声很轻的风铃
    if i > 0 {
        let at = s0
        let len = Int(0.9 * SR)
        for q in 0..<len {
            let idx = Int(at * SR) + q
            if idx >= n { break }
            let tt = Double(q) / SR
            let env = exp(-tt * 4.5) * min(1.0, tt / 0.02)
            let noise = Double.random(in: -1...1) * 0.05
            let sweep = sin(2 * .pi * (140 + 420 * exp(-tt * 3)) * tt) * 0.05
            L[idx] += (noise + sweep) * env
            R[idx] += (noise * 0.8 + sweep) * env
        }
        let bellLen = Int(1.6 * SR)
        for q in 0..<bellLen {
            let idx = Int(at * SR) + q
            if idx >= n { break }
            let tt = Double(q) / SR
            let env = exp(-tt * 2.6) * 0.035
            let f = midi([72.0, 76.0, 79.0, 84.0][i % 4])
            L[idx] += (sin(2 * .pi * f * tt) + 0.3 * sin(2 * .pi * f * 2.01 * tt)) * env
            R[idx] += (sin(2 * .pi * f * 1.002 * tt) + 0.3 * sin(2 * .pi * f * 2.01 * tt)) * env * 0.9
        }
    }
}

// ---------- 3. 解说一开口，配乐让路 ----------
var envVoice = [Double](repeating: 0, count: n)
let win = Int(0.02 * SR)
var i = 0
while i < n {
    var peak = 0.0
    for k in i..<min(i + win, n) { peak = max(peak, abs(voice[k])) }
    for k in i..<min(i + win, n) { envVoice[k] = peak }
    i += win
}
// 平滑（前后各 120ms）
var smooth = envVoice
let sm = Int(0.12 * SR)
for k in 0..<n {
    var acc = 0.0, cnt = 0
    var j = max(0, k - sm)
    while j < min(n, k + sm) {
        acc += envVoice[j]; cnt += 1; j += 8
    }
    smooth[k] = acc / Double(max(cnt, 1))
}
let voiceMax = voice.map { abs($0) }.max() ?? 1
let norm = voiceMax > 1.0 ? 1.0 / voiceMax : 1.0

for k in 0..<n {
    let v = voice[k] * norm
    let duck = 1.0 - 0.62 * min(1.0, smooth[k] / max(voiceMax * 0.5, 0.0001))
    L[k] = L[k] * duck + v
    R[k] = R[k] * duck + v
}

// ---------- 4. 首尾淡入淡出 + 归一 ----------
let fadeIn = Int(0.6 * SR), fadeOut = Int(1.2 * SR)
for k in 0..<min(fadeIn, n) { let g = Double(k) / Double(fadeIn); L[k] *= g; R[k] *= g }
for k in 0..<min(fadeOut, n) {
    let g = Double(k) / Double(fadeOut)
    L[n - 1 - k] *= g; R[n - 1 - k] *= g
}
let peak = max(L.map { abs($0) }.max() ?? 1, R.map { abs($0) }.max() ?? 1)
let g = 0.89 / max(peak, 0.0001)
print(String(format: "解说峰值 %.3f  配乐混音峰值 %.3f  增益 %.2f", voiceMax, peak, g))

// ---------- 5. 写 WAV ----------
// 注意：AVAudioFile 的 WAV 头（data 长度）是在**对象释放时**才回填的。
// 所以这段必须放在函数里，出函数即释放 —— 否则顶层变量活到进程结束，
// 出来的 wav 会带着「0 字节 / 0 秒」的头，afconvert 与 AVFoundation 都读不进去。
func writeWav(_ L: [Double], _ R: [Double], _ n: Int, gain: Double) throws {
    let outURL = URL(fileURLWithPath: "/tmp/promo2.wav")
    try? FileManager.default.removeItem(at: outURL)
    let fmt = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: SR, channels: 2, interleaved: false)!
    let file = try AVAudioFile(forWriting: outURL, settings: [
        AVFormatIDKey: kAudioFormatLinearPCM,
        AVSampleRateKey: SR,
        AVNumberOfChannelsKey: 2,
        AVLinearPCMBitDepthKey: 16,
        AVLinearPCMIsFloatKey: false,
        AVLinearPCMIsBigEndianKey: false,
    ], commonFormat: .pcmFormatFloat32, interleaved: false)

    var written = 0
    let chunk = 44100
    while written < n {
        let len = min(chunk, n - written)
        guard let buf = AVAudioPCMBuffer(pcmFormat: fmt, frameCapacity: AVAudioFrameCount(len)) else { break }
        buf.frameLength = AVAudioFrameCount(len)
        for k in 0..<len {
            buf.floatChannelData![0][k] = Float(L[written + k] * gain)
            buf.floatChannelData![1][k] = Float(R[written + k] * gain)
        }
        try file.write(from: buf)
        written += len
    }
}

try writeWav(L, R, n, gain: g)
print("音频写好：/tmp/promo2.wav  时长 \(String(format: "%.2f", DUR))s")
