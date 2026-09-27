using System.Runtime.InteropServices;
using SharpGen.Runtime;
using Vortice;
using Vortice.MediaFoundation;

namespace TillRecorder;

sealed class EncodedAccessUnit
{
    public required byte[] AnnexB { get; init; }
    public bool Keyframe { get; init; }
}

sealed class H264Encoder : IDisposable
{
    readonly IMFTransform transform;
    readonly int frameRate;
    public int Width { get; }
    public int Height { get; }
    bool providesSamples;
    long timestamp;
    public string Codec { get; private set; } = "avc1.42E01E";
    public byte[]? Sps { get; private set; }
    public byte[]? Pps { get; private set; }

    H264Encoder(IMFTransform transform, int width, int height, int frameRate)
    {
        this.transform = transform;
        Width = width;
        Height = height;
        this.frameRate = Math.Max(1, frameRate);
    }

    public static string LastError { get; private set; } = "";

    public static H264Encoder? TryCreate(int width, int height, int frameRate, int bitrate)
    {
        try
        {
            var activate = FindEncoder();
            if (activate == null)
            {
                LastError = "no-h264-encoder";
                return null;
            }
            var transform = activate.ActivateObject<IMFTransform>();
            var encoder = new H264Encoder(transform, width, height, frameRate);
            encoder.Configure(bitrate);
            activate.Dispose();
            LastError = "";
            return encoder;
        }
        catch (Exception error)
        {
            LastError = error.GetType().Name + " " + error.Message;
            return null;
        }
    }

    static IMFActivate? FindEncoder()
    {
        var input = new RegisterTypeInfo
        {
            GuidMajorType = MediaTypeGuids.Video,
            GuidSubtype = VideoFormatGuids.FromFourCC(new FourCC("NV12"))
        };
        var output = new RegisterTypeInfo
        {
            GuidMajorType = MediaTypeGuids.Video,
            GuidSubtype = VideoFormatGuids.FromFourCC(new FourCC("H264"))
        };
        var flags = (uint)(EnumFlag.EnumFlagSyncmft | EnumFlag.EnumFlagLocalmft | EnumFlag.EnumFlagSortandfilter);
        MediaFactory.MFTEnumEx(TransformCategoryGuids.VideoEncoder, flags, input, output, out var pointer, out var count);
        if (count == 0 || pointer == IntPtr.Zero)
        {
            flags = (uint)(EnumFlag.EnumFlagSyncmft | EnumFlag.EnumFlagHardware | EnumFlag.EnumFlagSortandfilter);
            MediaFactory.MFTEnumEx(TransformCategoryGuids.VideoEncoder, flags, input, output, out pointer, out count);
        }
        if (count == 0 || pointer == IntPtr.Zero) return null;
        var activate = new IMFActivate(Marshal.ReadIntPtr(pointer));
        Marshal.FreeCoTaskMem(pointer);
        return activate;
    }

    void Configure(int bitrate)
    {
        using var encoded = transform.GetOutputAvailableType(0, 0);
        encoded.Set(MediaTypeAttributeKeys.FrameSize, Pack((uint)Width, (uint)Height));
        encoded.Set(MediaTypeAttributeKeys.FrameRate, Pack((uint)frameRate, 1));
        encoded.Set(MediaTypeAttributeKeys.AvgBitrate, (uint)bitrate);
        encoded.Set(MediaTypeAttributeKeys.InterlaceMode, (uint)VideoInterlaceMode.Progressive);
        transform.SetOutputType(0, encoded, 0);

        using var input = transform.GetInputAvailableType(0, 0);
        input.Set(MediaTypeAttributeKeys.FrameSize, Pack((uint)Width, (uint)Height));
        input.Set(MediaTypeAttributeKeys.FrameRate, Pack((uint)frameRate, 1));
        input.Set(MediaTypeAttributeKeys.InterlaceMode, (uint)VideoInterlaceMode.Progressive);
        input.Set(MediaTypeAttributeKeys.PixelAspectRatio, Pack(1, 1));
        transform.SetInputType(0, input, 0);
        var info = transform.GetOutputStreamInfo(0);
        providesSamples = ((OutputStreamInfoFlags)info.Flags & OutputStreamInfoFlags.OutputStreamProvidesSamples) != 0;
        transform.ProcessMessage(TMessageType.MessageNotifyBeginStreaming, UIntPtr.Zero);
        transform.ProcessMessage(TMessageType.MessageNotifyStartOfStream, UIntPtr.Zero);
    }

    public EncodedAccessUnit? Encode(Nv12Frame frame, bool forceKeyframe)
    {
        if (frame.Width != Width || frame.Height != Height) return null;
        using var buffer = MediaFactory.MFCreateMemoryBuffer(frame.Pixels.Length);
        buffer.Lock(out var data, out _, out _);
        Marshal.Copy(frame.Pixels, 0, data, frame.Pixels.Length);
        buffer.Unlock();
        buffer.CurrentLength = frame.Pixels.Length;
        using var sample = MediaFactory.MFCreateSample();
        sample.AddBuffer(buffer);
        var duration = 10_000_000L / frameRate;
        sample.SampleTime = timestamp;
        sample.SampleDuration = duration;
        timestamp += duration;
        if (forceKeyframe) sample.Set(SampleAttributeKeys.CleanPoint, 1u);
        try
        {
            transform.ProcessInput(0, sample, 0);
        }
        catch (SharpGenException)
        {
            return null;
        }
        return Drain();
    }

    EncodedAccessUnit? Drain()
    {
        var output = new OutputDataBuffer { StreamID = 0 };
        IMFSample? owned = null;
        if (!providesSamples)
        {
            owned = MediaFactory.MFCreateSample();
            var buffer = MediaFactory.MFCreateMemoryBuffer(Width * Height);
            owned.AddBuffer(buffer);
            buffer.Dispose();
            output.Sample = owned;
        }
        try
        {
            transform.ProcessOutput(ProcessOutputFlags.None, 1, ref output, out _);
        }
        catch (SharpGenException error) when (error.ResultCode.Code == (int)ResultCode.TransformNeedMoreInput)
        {
            owned?.Dispose();
            return null;
        }
        catch (SharpGenException)
        {
            owned?.Dispose();
            return null;
        }
        var produced = output.Sample ?? owned;
        if (produced == null) return null;
        try
        {
            using var contiguous = produced.ConvertToContiguousBuffer();
            contiguous.Lock(out var data, out _, out var length);
            var annex = new byte[length];
            Marshal.Copy(data, annex, 0, length);
            contiguous.Unlock();
            RememberParameterSets(annex);
            var clean = false;
            try { clean = produced.GetUInt32(SampleAttributeKeys.CleanPoint) == 1; } catch { }
            var key = clean || NalType(annex) == 5;
            return new EncodedAccessUnit { AnnexB = annex, Keyframe = key };
        }
        finally
        {
            if (!providesSamples) owned?.Dispose();
            else produced.Dispose();
        }
    }

    void RememberParameterSets(byte[] annex)
    {
        foreach (var nal in Avc.SplitAnnexB(annex))
        {
            if (nal.Length == 0) continue;
            var type = nal[0] & 0x1f;
            if (type == 7) Sps = nal;
            if (type == 8) Pps = nal;
        }
        if (Sps != null) Codec = Avc.CodecString(Sps);
    }

    static int NalType(byte[] annex)
    {
        foreach (var nal in Avc.SplitAnnexB(annex))
        {
            if (nal.Length == 0) continue;
            var type = nal[0] & 0x1f;
            if (type is 1 or 5) return type;
        }
        return 0;
    }

    static ulong Pack(uint high, uint low) => ((ulong)high << 32) | low;

    public void Dispose()
    {
        try
        {
            transform.ProcessMessage(TMessageType.MessageNotifyEndOfStream, UIntPtr.Zero);
            transform.ProcessMessage(TMessageType.MessageCommandDrain, UIntPtr.Zero);
        }
        catch { }
        transform.Dispose();
    }
}
