using System.Runtime.InteropServices;
using Vortice;
using Vortice.MediaFoundation;

namespace TillRecorder;

sealed class WebcamCapture : IDisposable
{
    IMFMediaSource? source;
    IMFSourceReader? reader;
    public int Width { get; private set; }
    public int Height { get; private set; }
    public string Name { get; private set; } = "";
    public bool Opened { get; private set; }

    public static WebcamCapture? TryOpen()
    {
        var camera = new WebcamCapture();
        return camera.Open() ? camera : null;
    }

    bool Open()
    {
        try
        {
            source = MediaDevices.OpenSource(CaptureDeviceAttributeKeys.SourceTypeVidcap, out var name);
            if (source == null) return false;
            Name = name;
            reader = MediaFactory.MFCreateSourceReaderFromMediaSource(source, null!);
            ChooseWebcamType(reader);
            using var current = reader.GetCurrentMediaType(SourceReaderIndex.FirstVideoStream);
            var size = current.GetUInt64(MediaTypeAttributeKeys.FrameSize);
            Width = (int)(size >> 32);
            Height = (int)(size & 0xffffffff);
            if (Width < 2 || Height < 2) return false;
            Opened = true;
            return true;
        }
        catch
        {
            Dispose();
            return false;
        }
    }

    static void ChooseWebcamType(IMFSourceReader reader)
    {
        IMFMediaType? best = null;
        var bestScore = int.MinValue;
        for (var index = 0; index < 40; index++)
        {
            IMFMediaType native;
            try { native = reader.GetNativeMediaType(SourceReaderIndex.FirstVideoStream, index); }
            catch { break; }
            var size = native.GetUInt64(MediaTypeAttributeKeys.FrameSize);
            var rate = native.GetUInt64(MediaTypeAttributeKeys.FrameRate);
            var width = (int)(size >> 32);
            var height = (int)(size & 0xffffffff);
            var fps = (int)(rate >> 32);
            var denom = (int)(rate & 0xffffffff);
            if (denom > 1) fps /= denom;
            var score = fps * 1000 - Math.Abs(width - 640);
            if (width >= 320 && width <= 1280 && fps >= 15 && score > bestScore)
            {
                best?.Dispose();
                best = native;
                bestScore = score;
            }
            else native.Dispose();
        }
        if (best != null)
        {
            try { reader.SetCurrentMediaType(SourceReaderIndex.FirstVideoStream, best); } catch { }
            best.Dispose();
        }
        using var preferred = MediaFactory.MFCreateMediaType();
        preferred.Set(MediaTypeAttributeKeys.MajorType, MediaTypeGuids.Video);
        preferred.Set(MediaTypeAttributeKeys.Subtype, VideoFormatGuids.FromFourCC(new FourCC("NV12")));
        try { reader.SetCurrentMediaType(SourceReaderIndex.FirstVideoStream, preferred); } catch { }
    }

    public Nv12Frame? TryRead()
    {
        if (reader == null) return null;
        try
        {
            using var sample = reader.ReadSample(SourceReaderIndex.FirstVideoStream, SourceReaderControlFlag.None, out _, out var flags, out _);
            if (sample == null || (flags & SourceReaderFlag.StreamTick) != 0 && sample == null) return null;
            using var buffer = sample.ConvertToContiguousBuffer();
            buffer.Lock(out var data, out _, out var length);
            var pixels = new byte[Math.Max(0, length)];
            if (length > 0) Marshal.Copy(data, pixels, 0, length);
            buffer.Unlock();
            if (pixels.Length < Width * Height * 3 / 2) return null;
            return new Nv12Frame { Pixels = pixels, Width = Width, Height = Height };
        }
        catch
        {
            return null;
        }
    }

    public void Dispose()
    {
        reader?.Dispose();
        reader = null;
        try { source?.Shutdown(); } catch { }
        source?.Dispose();
        source = null;
        Opened = false;
    }
}

sealed class Microphone : IDisposable
{
    IMFMediaSource? source;
    IMFSourceReader? reader;
    public bool Opened { get; private set; }
    public string Name { get; private set; } = "";

    public static Microphone? TryOpen()
    {
        var mic = new Microphone();
        return mic.Open() ? mic : null;
    }

    bool Open()
    {
        try
        {
            source = MediaDevices.OpenSource(CaptureDeviceAttributeKeys.SourceTypeAudcap, out var name);
            if (source == null) return false;
            Name = name;
            reader = MediaFactory.MFCreateSourceReaderFromMediaSource(source, null!);
            using var preferred = MediaFactory.MFCreateMediaType();
            preferred.Set(MediaTypeAttributeKeys.MajorType, MediaTypeGuids.Audio);
            preferred.Set(MediaTypeAttributeKeys.Subtype, AudioFormatGuids.Pcm);
            preferred.Set(MediaTypeAttributeKeys.AudioSamplesPerSecond, 44100u);
            preferred.Set(MediaTypeAttributeKeys.AudioNumChannels, 1u);
            preferred.Set(MediaTypeAttributeKeys.AudioBitsPerSample, 16u);
            preferred.Set(MediaTypeAttributeKeys.AudioBlockAlignment, 2u);
            try { reader.SetCurrentMediaType(SourceReaderIndex.FirstAudioStream, preferred); } catch { }
            Opened = true;
            return true;
        }
        catch
        {
            Dispose();
            return false;
        }
    }

    public byte[]? TryRead()
    {
        if (reader == null) return null;
        try
        {
            using var sample = reader.ReadSample(SourceReaderIndex.FirstAudioStream, SourceReaderControlFlag.None, out _, out _, out _);
            if (sample == null) return null;
            using var buffer = sample.ConvertToContiguousBuffer();
            buffer.Lock(out var data, out _, out var length);
            var pcm = new byte[Math.Max(0, length)];
            if (length > 0) Marshal.Copy(data, pcm, 0, length);
            buffer.Unlock();
            return pcm.Length == 0 ? null : pcm;
        }
        catch
        {
            return null;
        }
    }

    volatile bool drain;

    public void StartDrain()
    {
        drain = true;
        var thread = new Thread(() =>
        {
            while (drain) TryRead();
        })
        { IsBackground = true, Name = "TillMic" };
        thread.Start();
    }

    public void Dispose()
    {
        drain = false;
        reader?.Dispose();
        reader = null;
        try { source?.Shutdown(); } catch { }
        source?.Dispose();
        source = null;
        Opened = false;
    }
}

sealed class WebcamPump : IDisposable
{
    readonly object gate = new();
    WebcamCapture? camera;
    Nv12Frame? latest;
    Thread? thread;
    volatile bool run;

    public bool Running => run;
    public bool Opened { get; private set; }
    public string Name { get; private set; } = "";
    public int Width { get; private set; }
    public int Height { get; private set; }

    public void Start()
    {
        Stop();
        camera = WebcamCapture.TryOpen();
        Opened = camera != null;
        Name = camera?.Name ?? "";
        Width = camera?.Width ?? 0;
        Height = camera?.Height ?? 0;
        if (camera == null) return;
        run = true;
        var source = camera;
        thread = new Thread(() =>
        {
            while (run)
            {
                var frame = source.TryRead();
                if (frame != null)
                {
                    lock (gate) latest = frame;
                }
            }
        })
        { IsBackground = true, Name = "TillWebcam" };
        thread.Start();
    }

    public Nv12Frame? Latest()
    {
        lock (gate) return latest;
    }

    public void Stop()
    {
        run = false;
        try { thread?.Join(800); } catch { }
        thread = null;
        camera?.Dispose();
        camera = null;
        Opened = false;
        lock (gate) latest = null;
    }

    public void Dispose() => Stop();
}
