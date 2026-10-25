
import Foundation
import AVFoundation

let videoPath = CommandLine.arguments[1]
let audioPath = CommandLine.arguments[2]
let outPath = CommandLine.arguments[3]
try? FileManager.default.removeItem(atPath: outPath)

let vAsset = AVURLAsset(url: URL(fileURLWithPath: videoPath))
let aAsset = AVURLAsset(url: URL(fileURLWithPath: audioPath))
let comp = AVMutableComposition()
let vTrack = vAsset.tracks(withMediaType: .video)[0]
let aTrack = aAsset.tracks(withMediaType: .audio)[0]
let dur = vAsset.duration

let cv = comp.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid)!
try cv.insertTimeRange(CMTimeRange(start: .zero, duration: dur), of: vTrack, at: .zero)
let ca = comp.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)!
let aDur = min(aAsset.duration, dur)
try ca.insertTimeRange(CMTimeRange(start: .zero, duration: aDur), of: aTrack, at: .zero)

guard let exporter = AVAssetExportSession(asset: comp, presetName: AVAssetExportPresetHighestQuality) else { fatalError("no exporter") }
exporter.outputURL = URL(fileURLWithPath: outPath)
exporter.outputFileType = .mp4
exporter.shouldOptimizeForNetworkUse = true
let sem = DispatchSemaphore(value: 0)
exporter.exportAsynchronously {
    print("status", exporter.status.rawValue, exporter.error ?? "")
    sem.signal()
}
sem.wait()
print("done", outPath)
