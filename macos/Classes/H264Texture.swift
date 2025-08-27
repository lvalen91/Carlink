//
//  H264Texture.swift
//  quick_usb
//
//  Created by Alexander on 25.01.2024.
//

import Foundation
import VideoToolbox
import AVFoundation

import FlutterMacOS


private typealias VideoPacket = Array<UInt8>

public class H264Texture: NSObject, FlutterTexture {
    
    private let startCode: [UInt8] = [0,0,0,1]

    private var formatDesc: CMVideoFormatDescription?
    private var decompressionSession: VTDecompressionSession?
//    private var videoLayer: AVSampleBufferDisplayLayer?
    
    private var spsSize: Int = 0
    private var ppsSize: Int = 0
    
    private var sps: Array<UInt8>?
    private var pps: Array<UInt8>?

    private var pixelBuffer: Unmanaged<CVPixelBuffer>?
    
    private var packetProcessedCallback: () -> ()
    
    init(packetProcessedCallback: @escaping () -> ()) {
        self.packetProcessedCallback = packetProcessedCallback
    }
    
    
    
    let bufferCap: Int = 512 * 1024
    var streamBuffer = Array<UInt8>()
    
    var fileStream: InputStream?

    
    func decodeFile(_ fileURL: URL) {
        
        openVideoFile(fileURL)
        
        DispatchQueue.global().async { [self] in
            
            while var packet = nextPacket() {
                
                self.processPacket(packet: &packet)
            }
        }
        
    }

    
    private func openVideoFile(_ fileURL: URL) {

        streamBuffer = [UInt8]()
        
        fileStream = InputStream(url: fileURL)
        fileStream?.open()
    }
    
    fileprivate func nextPacket() -> VideoPacket? {
        
        Thread.sleep(forTimeInterval: 0.1)
        
        if streamBuffer.count == 0 && readStremData() == 0{
            return nil
        }
        
        //make sure start with start code
        if streamBuffer.count < 5 || Array(streamBuffer[0...3]) != startCode {
            return nil
        }
        
        //find second start code , so startIndex = 4
        var startIndex = 4
        
        while true {
            
            while ((startIndex + 3) < streamBuffer.count) {
                if Array(streamBuffer[startIndex...startIndex+3]) == startCode {
                    
                    let packet = Array(streamBuffer[0..<startIndex])
                    streamBuffer.removeSubrange(0..<startIndex)
                    
                    return packet
                }
                startIndex += 1
            }
            
            // not found next start code , read more data
            if readStremData() == 0 {
                return nil
            }
        }
    }
    
    fileprivate func readStremData() -> Int{
        
        if let stream = fileStream, stream.hasBytesAvailable{
            
            var tempArray = Array<UInt8>(repeating: 0, count: bufferCap)
            let bytes = stream.read(&tempArray, maxLength: bufferCap)
            
            if bytes > 0 {
                streamBuffer.append(contentsOf: Array(tempArray[0..<bytes]))
            }
            
            return bytes
        }
        
        return 0
    }

    
    public func processData(data: [UInt8]) {
        if data.count < 5 {
            return
        }
        
        var d = data
            processPacket(packet: &d)

        // var pdata = Array<UInt8>()
        // pdata.append(contentsOf: data)
        
        // DispatchQueue.global().async { [self] in
        //     processPacket(packet: &pdata)
        // }
        
        
        //make sure start with start code
//        if data.count < 5 || Array(data[0...3]) != startCode {
//            return
//        }
        
        //find second start code , so startIndex = 4
//        var startIndex = 0
//        var index = 0
//        
//        while ((startIndex + 3) < data.count) {
//            if Array(data[startIndex...startIndex+3]) == startCode {
//                
//                var packet = Array(data[index..<startIndex])
//                
//                DispatchQueue.global().async { [self] in
//                    processPacket(packet: &packet)
//                }
//                
//                index = startIndex
//            }
//            startIndex += 1
//        }
//        
//        if (index+3 < data.count) {
//            var packet = Array(data[index+3..<data.count])
//            
//            DispatchQueue.global().async { [self] in
//                processPacket(packet: &packet)
//            }
//        }

    }
    
    private func processPacket(packet videoPacket: inout VideoPacket) {
        //replace start code with nal size
        var biglen = CFSwapInt32HostToBig(UInt32(videoPacket.count - 4))
        memcpy(&videoPacket, &biglen, 4)
        
        let nalType = videoPacket[4] & 0x1F
        
        switch nalType {
        case 0x05:
//            print("Nal type is IDR frame")
            if createDecompSession() {
                decodeVideoPacket(videoPacket)
            }
        case 0x07:
//            print("Nal type is SPS")
            spsSize = videoPacket.count - 4
            sps = Array(videoPacket[4..<videoPacket.count])
        case 0x08:
//            print("Nal type is PPS")
            ppsSize = videoPacket.count - 4
            pps = Array(videoPacket[4..<videoPacket.count])
        default:
//            print("Nal type is B/P frame")
            decodeVideoPacket(videoPacket)
            break;
        }
        
//        print("Read Nalu size \(videoPacket.count)");
    }
    
    fileprivate func decodeVideoPacket(_ videoPacket: VideoPacket) {
        
        let bufferPointer = UnsafeMutablePointer<UInt8>(mutating: videoPacket)
        
        var blockBuffer: CMBlockBuffer?
        var status = CMBlockBufferCreateWithMemoryBlock(allocator: kCFAllocatorDefault,memoryBlock: bufferPointer, blockLength: videoPacket.count,
                                                        blockAllocator: kCFAllocatorNull,
                                                        customBlockSource: nil, offsetToData: 0, dataLength: videoPacket.count,
                                                        flags: 0, blockBufferOut: &blockBuffer)
        
        if status != kCMBlockBufferNoErr {
            return
        }
        
        var sampleBuffer: CMSampleBuffer?
        let sampleSizeArray = [videoPacket.count]
        
        status = CMSampleBufferCreateReady(allocator: kCFAllocatorDefault,
                                           dataBuffer: blockBuffer,
                                           formatDescription: formatDesc,
                                           sampleCount: 1, sampleTimingEntryCount: 0, sampleTimingArray: nil,
                                           sampleSizeEntryCount: 1, sampleSizeArray: sampleSizeArray,
                                           sampleBufferOut: &sampleBuffer)
        
        if let buffer = sampleBuffer, let session = decompressionSession, status == kCMBlockBufferNoErr {
            
            let attachments:CFArray? = CMSampleBufferGetSampleAttachmentsArray(buffer, createIfNecessary: true)
            if let attachmentArray = attachments {
                let dic = unsafeBitCast(CFArrayGetValueAtIndex(attachmentArray, 0), to: CFMutableDictionary.self)
                
                CFDictionarySetValue(dic,
                                     Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                                     Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
            }
            
            
            //diaplay with AVSampleBufferDisplayLayer
            // self.videoLayer?.enqueue(buffer)
            
            // DispatchQueue.main.async(execute: {
            //     self.videoLayer?.setNeedsDisplay()
            // })
            
            // or decompression to CVPixcelBuffer
            var flagOut = VTDecodeInfoFlags(rawValue: 0)
            var outputBuffer = UnsafeMutablePointer<CVPixelBuffer>.allocate(capacity: 1)
            
            status = VTDecompressionSessionDecodeFrame(session, sampleBuffer: buffer,
                                                       flags: [._EnableAsynchronousDecompression],
                                                       frameRefcon: &outputBuffer, infoFlagsOut: &flagOut)
            
            if status == noErr {
                print("OK")
            }else if(status == kVTInvalidSessionErr) {
                print("IOS8VT: Invalid session, reset decoder session");
            } else if(status == kVTVideoDecoderBadDataErr) {
                print("IOS8VT: decode failed status=\(status)(Bad data)");
            } else if(status != noErr) {
                print("IOS8VT: decode failed status=\(status)");
            }
        }
    }
    
    func createDecompSession() -> Bool{
        formatDesc = nil
        
        if let spsData = sps, let ppsData = pps {
            let pointerSPS = UnsafePointer<UInt8>(spsData)
            let pointerPPS = UnsafePointer<UInt8>(ppsData)
            
            // make pointers array
            let dataParamArray = [pointerSPS, pointerPPS]
            let parameterSetPointers = UnsafePointer<UnsafePointer<UInt8>>(dataParamArray)
            
            // make parameter sizes array
            let sizeParamArray = [spsData.count, ppsData.count]
            let parameterSetSizes = UnsafePointer<Int>(sizeParamArray)
            
            
            let status = CMVideoFormatDescriptionCreateFromH264ParameterSets(allocator: kCFAllocatorDefault, parameterSetCount: 2, parameterSetPointers: parameterSetPointers, parameterSetSizes: parameterSetSizes, nalUnitHeaderLength: 4, formatDescriptionOut: &formatDesc)
            
            if let desc = formatDesc, status == noErr {
                
                if let session = decompressionSession {
                    VTDecompressionSessionInvalidate(session)
                    decompressionSession = nil
                }
                
                var videoSessionM : VTDecompressionSession?
                
                let decoderParameters = NSMutableDictionary()
                let destinationPixelBufferAttributes = NSMutableDictionary()
                destinationPixelBufferAttributes.setValue(NSNumber(value: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange as UInt32), forKey: kCVPixelBufferPixelFormatTypeKey as String)
                
                var outputCallback = VTDecompressionOutputCallbackRecord()
                outputCallback.decompressionOutputCallback = decompressionSessionDecodeFrameCallback
                outputCallback.decompressionOutputRefCon = UnsafeMutableRawPointer(Unmanaged.passUnretained(self).toOpaque())
                
                let status = VTDecompressionSessionCreate(allocator: kCFAllocatorDefault,
                                                          formatDescription: desc, decoderSpecification: decoderParameters,
                                                          imageBufferAttributes: destinationPixelBufferAttributes,outputCallback: &outputCallback,
                                                          decompressionSessionOut: &videoSessionM)
                
                if(status != noErr) {
                    print("\t\t VTD ERROR type: \(status)")
                }
                
                self.decompressionSession = videoSessionM
            }else {
                print("IOS8VT: reset decoder session failed status=\(status)")
            }
        }
        
        return true
    }
    
    func displayDecodedFrame(_ imageBuffer: CVImageBuffer?) {
        if (imageBuffer != nil) {
            pixelBuffer = Unmanaged.passRetained(imageBuffer!)
            
            let size = CVImageBufferGetDisplaySize(imageBuffer!)
            
            print(size)
            
            packetProcessedCallback()
        }
    }
    
    public func copyPixelBuffer() -> Unmanaged<CVPixelBuffer>? {
        return pixelBuffer
    }
    
}

private func decompressionSessionDecodeFrameCallback(_ decompressionOutputRefCon: UnsafeMutableRawPointer?, _ sourceFrameRefCon: UnsafeMutableRawPointer?, _ status: OSStatus, _ infoFlags: VTDecodeInfoFlags, _ imageBuffer: CVImageBuffer?, _ presentationTimeStamp: CMTime, _ presentationDuration: CMTime) -> Void {
    
        let delegate: H264Texture = unsafeBitCast(decompressionOutputRefCon, to: H264Texture.self)
    
        if status == noErr {
            // do something with your resulting CVImageBufferRef that is your decompressed frame
            delegate.displayDecodedFrame(imageBuffer);
        }
}

