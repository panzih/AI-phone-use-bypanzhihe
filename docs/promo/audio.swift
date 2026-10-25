
import Foundation

let sr = 44100.0
let dur = 60.0
let n = Int(sr * dur)
var L = [Double](repeating: 0, count: n)
var R = [Double](repeating: 0, count: n)

func midi(_ m: Double) -> Double { 440.0 * pow(2.0, (m - 69.0) / 12.0) }

struct Chord { let start: Double; let end: Double; let notes: [Double]; let bass: Double }
let chords: [Chord] = [
    Chord(start: 0.0,  end: 11.5, notes: [57, 60, 64, 69], bass: 45),
    Chord(start: 11.5, end: 23.0, notes: [53, 57, 60, 65], bass: 41),
    Chord(start: 23.0, end: 34.5, notes: [48, 60, 64, 67], bass: 36),
    Chord(start: 34.5, end: 46.0, notes: [55, 59, 62, 67], bass: 43),
    Chord(start: 46.0, end: 60.0, notes: [57, 64, 69, 72], bass: 45),
]

// --- pad ---
for ch in chords {
    let i0 = Int(ch.start * sr), i1 = min(n, Int(ch.end * sr))
    let cross = 1.1
    for i in i0..<i1 {
        let t = (Double(i) - Double(i0)) / sr
        let tEnd = (Double(i1) - Double(i)) / sr
        var env = 1.0
        env *= min(1.0, t / cross)
        env *= min(1.0, tEnd / (cross * 1.3))
        if env <= 0 { continue }
        var sL = 0.0, sR = 0.0
        for (k, m) in ch.notes.enumerated() {
            let f = midi(m)
            let det = 1.0 + 0.0016 * Double(k % 2 == 0 ? 1 : -1)
            let trem = 1.0 + 0.06 * sin(2 * .pi * (0.11 + 0.03 * Double(k)) * t + Double(k))
            let amp = 0.055 * trem
            let a = sin(2 * .pi * f * det * t)
            let b = 0.28 * sin(2 * .pi * f * 2 * t + 0.7)
            let c = 0.10 * sin(2 * .pi * f * 3 * t + 1.3)
            let v = (a + b + c) * amp * env
            let pan = 0.5 + 0.32 * sin(Double(k) * 1.7)
            sL += v * (1.0 - pan * 0.5)
            sR += v * (0.5 + pan * 0.5)
        }
        // bass
        let fb = midi(ch.bass)
        let bv = 0.085 * sin(2 * .pi * fb * t) * env * (0.75 + 0.25 * sin(2 * .pi * 0.17 * t))
        sL += bv; sR += bv
        L[i] += sL; R[i] += sR
    }
}

// --- 柔和的低频心跳 ---
func addKick(_ at: Double, _ gain: Double) {
    let i0 = Int(at * sr)
    let len = Int(0.55 * sr)
    for k in 0..<len {
        let i = i0 + k
        if i < 0 || i >= n { continue }
        let t = Double(k) / sr
        let f = 52.0 + 40.0 * exp(-t * 12)
        let env = exp(-t * 7.5)
        let v = gain * env * sin(2 * .pi * f * t)
        L[i] += v; R[i] += v
    }
}
// --- 风铃点缀（场景切换） ---
func addChime(_ at: Double, _ m: Double, _ gain: Double) {
    let i0 = Int(at * sr)
    let len = Int(2.2 * sr)
    let f = midi(m)
    for k in 0..<len {
        let i = i0 + k
        if i < 0 || i >= n { continue }
        let t = Double(k) / sr
        let env = exp(-t * 2.4) * min(1.0, t / 0.008)
        let v = gain * env * (sin(2 * .pi * f * t) + 0.35 * sin(2 * .pi * f * 2.01 * t) + 0.12 * sin(2 * .pi * f * 3.02 * t))
        L[i] += v * 0.9; R[i] += v * 1.05
    }
}
// --- whoosh：带通扫频噪声 ---
func addWhoosh(_ at: Double, _ gain: Double) {
    let i0 = Int(at * sr)
    let len = Int(0.75 * sr)
    var lp = 0.0, hp = 0.0, prev = 0.0
    var rng = UInt64(0x9E3779B97F4A7C15 &+ UInt64(at * 1000))
    for k in 0..<len {
        let i = i0 + k
        if i < 0 || i >= n { continue }
        let x = Double(k) / Double(len)
        rng = rng &* 6364136223846793005 &+ 1442695040888963407
        let noise = Double(Int64(bitPattern: rng >> 11) % 10_000) / 5000.0 - 1.0
        let cutoff = 0.02 + 0.16 * pow(sin(.pi * min(1.0, x * 1.15)), 1.4)
        lp += cutoff * (noise - lp)
        // 简易高通
        hp = 0.92 * (hp + lp - prev); prev = lp
        let env = pow(sin(.pi * x), 1.6)
        let v = gain * env * hp
        L[i] += v * (1.0 - x * 0.25); R[i] += v * (0.75 + x * 0.25)
    }
}

addKick(0.35, 0.20); addKick(11.0, 0.15); addKick(23.0, 0.15); addKick(32.0, 0.14); addKick(40.0, 0.13); addKick(46.6, 0.15); addKick(52.0, 0.17)
for at in [0.5, 11.0, 14.6, 23.0, 32.0, 40.0, 46.6, 52.0] {
    addWhoosh(at - 0.35, 0.09)
}
let chimeNotes: [(Double, Double)] = [(2.2, 76.0), (11.4, 72.0), (23.4, 74.0), (32.4, 79.0), (40.4, 81.0), (47.0, 79.0), (52.4, 84.0), (57.0, 76.0)]
for (at, m) in chimeNotes { addChime(at, m, 0.085) }

// 母带：轻微软限幅 + 归一化 + 淡入淡出
var peak = 0.0
for i in 0..<n { peak = max(peak, abs(L[i]), abs(R[i])) }
let target = 0.62
let g = peak > 0 ? target / peak : 1.0
for i in 0..<n {
    var l = L[i] * g, r = R[i] * g
    l = tanh(l * 1.15) * 0.92
    r = tanh(r * 1.15) * 0.92
    let t = Double(i) / sr
    var fade = 1.0
    fade *= min(1.0, t / 0.8)
    fade *= min(1.0, (dur - t) / 1.6)
    L[i] = l * fade; R[i] = r * fade
}

// 写 16bit WAV
let outPath = "/tmp/promo.wav"
var data = [Int16]()
data.reserveCapacity(n * 2)
for i in 0..<n {
    for v in [L[i], R[i]] {
        let c = max(-1.0, min(1.0, v))
        data.append(Int16(c * 32767.0))
    }
}
var header = Data()
func le32(_ v: UInt32) -> [UInt8] { [UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF), UInt8((v >> 16) & 0xFF), UInt8((v >> 24) & 0xFF)] }
func le16(_ v: UInt16) -> [UInt8] { [UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF)] }
let byteRate = UInt32(sr) * 2 * 2
header.append(contentsOf: Array("RIFF".utf8))
header.append(contentsOf: le32(UInt32(36 + data.count * 2)))
header.append(contentsOf: Array("WAVEfmt ".utf8))
header.append(contentsOf: le32(16))
header.append(contentsOf: le16(1))
header.append(contentsOf: le16(2))
header.append(contentsOf: le32(UInt32(sr)))
header.append(contentsOf: le32(byteRate))
header.append(contentsOf: le16(4))
header.append(contentsOf: le16(16))
header.append(contentsOf: Array("data".utf8))
header.append(contentsOf: le32(UInt32(data.count * 2)))
data.withUnsafeBufferPointer { bp in
    header.append(UnsafeRawPointer(bp.baseAddress!).assumingMemoryBound(to: UInt8.self), count: bp.count * 2)
}
try! header.write(to: URL(fileURLWithPath: outPath))
print("audio written", outPath, "peak", peak, "gain", g)
