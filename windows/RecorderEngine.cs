using Vortice.MediaFoundation;

namespace TillRecorder;

sealed class RecorderEngine : IDisposable
{
    public const int ScreenFps = 30;
    public const int CameraFps = 15;
    const int QuietMs = 20_000;
    const int SegmentMs = 60_000;
    const float MotionFraction = 0.02f;

    readonly ShopClient shop = new();
    readonly object gate = new();
    CancellationTokenSource? cancel;
    Task? loop;
    public string Status { get; private set; } = "Stopped";
    int removedFlag;
    public bool TakeRemoved() => Interlocked.Exchange(ref removedFlag, 0) == 1;
    public double ScreenEncodeFps { get; private set; }
    public double CameraEncodeFps { get; private set; }
    public bool WebcamOpen { get; private set; }
    public bool MicrophoneOpen { get; private set; }

    public void Start(AppSettings settings)
    {
        Stop();
        cancel = new CancellationTokenSource();
        var token = cancel.Token;
        loop = Task.Run(async () =>
        {
            while (!token.IsCancellationRequested)
            {
                try
                {
                    await Run(settings, token);
                }
                catch (Exception error) when (!token.IsCancellationRequested)
                {
                    AppLog.Write("loop " + error.GetType().Name + " " + error.Message);
                }
                if (token.IsCancellationRequested) break;
                Status = "Reconnecting";
                AppLog.Write("capture retry");
                try { await Task.Delay(2000, token); } catch { break; }
            }
        }, token);
    }

    public void Stop()
    {
        cancel?.Cancel();
        try { loop?.Wait(TimeSpan.FromSeconds(3)); } catch { }
        cancel?.Dispose();
        cancel = null;
        loop = null;
        Status = "Stopped";
    }

    async Task Run(AppSettings settings, CancellationToken token)
    {
        MediaFactoryStartup.Ensure();
        using var desktop = new DesktopCapture();
        var desktopOpen = desktop.Open();
        if (!desktopOpen) AppLog.Write("desktop duplication unavailable");
        var seeded = desktop.GrabStill();
        if (seeded == null && !desktopOpen)
        {
            Status = "The desktop could not be captured";
            AppLog.Write("desktop capture failed");
            return;
        }
        var screenSize = FrameScale.Fit(seeded?.Width ?? desktop.Width, seeded?.Height ?? desktop.Height, 960);
        using var screenEncoder = H264Encoder.TryCreate(screenSize.Width, screenSize.Height, ScreenFps, 1_500_000);
        if (screenEncoder == null)
        {
            Status = "The H.264 encoder did not start";
            return;
        }
        var screenLive = new LivePublisher(shop, settings, "screen");
        var cameraLive = new LivePublisher(shop, settings, "camera");
        var screenClip = new ClipWriter("screen");
        var cameraClip = new ClipWriter("camera");
        byte[]? screenGrid = null;
        byte[]? cameraGrid = null;
        long screenMotionAt = 0;
        long cameraMotionAt = 0;
        var cameraWanted = false;
        using var webcam = new WebcamPump();
        H264Encoder? cameraEncoder = null;
        Nv12Frame? cameraSize = null;
        using var microphone = Microphone.TryOpen();
        MicrophoneOpen = microphone != null;
        AppLog.Write(microphone == null ? "microphone none" : "microphone open");
        using var overheadRelay = new OverheadRelay();
        var screenCount = 0;
        var screenInputs = 0;
        var cameraCount = 0;
        var screenWindow = Environment.TickCount64;
        var cameraWindow = screenWindow;
        var nextHeartbeat = 0L;
        var heartbeatGap = 5_000L;
        var recordWanted = true;
        var nextScreen = 0L;
        var nextCamera = 0L;
        BgraFrame? latestDesktop = seeded;
        var loggedGrab = false;
        var loggedEncodeWait = false;
        Status = "Watching the desktop";
        AppLog.Write("capture started " + screenSize.Width + "x" + screenSize.Height);

        while (!token.IsCancellationRequested)
        {
            try
            {
            var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            if (!recordWanted)
            {
                if (screenClip.IsOpen) screenClip.Close(now);
                if (cameraClip.IsOpen) cameraClip.Close(now);
                if (webcam.Running)
                {
                    webcam.Stop();
                    cameraEncoder?.Dispose();
                    cameraEncoder = null;
                    WebcamOpen = false;
                    cameraLive.Reset();
                }
                screenLive.Reset();
                overheadRelay.Apply("", shop, settings);
                Status = "Not recording";
            }
            if (now >= nextHeartbeat)
            {
                var control = await shop.Heartbeat(settings, recordWanted, token);
                heartbeatGap = control == null ? Math.Min(30_000, heartbeatGap * 2) : 5_000;
                nextHeartbeat = now + heartbeatGap;
                if (control != null)
                {
                    if (control.Removed)
                    {
                        settings.Token = "";
                        settings.Armed = false;
                        settings.Save();
                        AppLog.Write("watch page removed this register");
                        Interlocked.Exchange(ref removedFlag, 1);
                        try { cancel?.Cancel(); } catch { }
                        break;
                    }
                    if (control.Record != recordWanted)
                        AppLog.Write(control.Record ? "record on" : "record off");
                    recordWanted = control.Record;
                    if (recordWanted)
                    {
                        if (control.Camera != cameraWanted)
                            AppLog.Write(control.Camera ? "camera on" : "camera off");
                        cameraWanted = control.Camera;
                        overheadRelay.Apply(CameraAddress.Pull(settings), shop, settings);
                        var label = CameraAddress.Label(settings.Overhead ?? "");
                        if (!string.Equals(label, control.Overhead ?? "", StringComparison.Ordinal))
                            _ = shop.PublishOverhead(settings, label.Length > 0, label, CancellationToken.None);
                        await UploadPending(settings, token);
                    }
                    else
                    {
                        cameraWanted = false;
                    }
                }
                var typed = TypedInput.Drain();
                if (typed.Count > 0 && !await shop.UploadInputs(settings, typed, token))
                    TypedInput.Restore(typed);
            }
            if (!recordWanted)
            {
                Thread.Sleep(200);
                continue;
            }

            var grabbed = desktopOpen ? desktop.TryGrab(8) : null;
            if (grabbed == null && latestDesktop == null) grabbed = desktop.GrabStill();
            if (grabbed != null) latestDesktop = grabbed;
            if (!loggedGrab && latestDesktop != null)
            {
                loggedGrab = true;
                AppLog.Write("screen frame " + latestDesktop.Width + "x" + latestDesktop.Height);
            }
            if (latestDesktop != null && Environment.TickCount64 >= nextScreen)
            {
                nextScreen = Environment.TickCount64 + 1000 / ScreenFps;
                var nv12 = FrameScale.ToNv12(latestDesktop, screenSize.Width, screenSize.Height);
                var grid = FrameScale.MotionGrid(nv12);
                if (screenGrid != null && FrameScale.ChangedFraction(screenGrid, grid) >= MotionFraction)
                    screenMotionAt = now;
                screenGrid = grid;
                var encoded = screenEncoder.Encode(nv12, screenInputs == 0 || (screenLive.NeedsKeyframe && screenInputs % 30 == 0));
                screenInputs++;
                if (encoded == null && !loggedEncodeWait)
                {
                    loggedEncodeWait = true;
                    AppLog.Write("screen encode waiting");
                }
                if (encoded != null && encoded.AnnexB.Length > 0)
                {
                    if (screenCount == 0) AppLog.Write("screen encode " + encoded.AnnexB.Length + " key " + encoded.Keyframe);
                    screenCount++;
                    Note(encoded, screenEncoder, screenLive, screenClip, "screen", now);
                }
            }

            if (cameraWanted && !webcam.Running)
            {
                webcam.Start();
                WebcamOpen = webcam.Opened;
                AppLog.Write(webcam.Opened ? "webcam open " + webcam.Name + " " + webcam.Width + "x" + webcam.Height : "webcam none");
                if (webcam.Opened)
                {
                    var fitted = FrameScale.Fit(webcam.Width, webcam.Height, 960);
                    cameraEncoder = H264Encoder.TryCreate(fitted.Width, fitted.Height, CameraFps, 900_000);
                    cameraSize = null;
                    if (cameraEncoder == null) AppLog.Write("camera encoder failed");
                }
            }
            if (!cameraWanted && webcam.Running)
            {
                cameraClip.Close(now);
                webcam.Stop();
                cameraEncoder?.Dispose();
                cameraEncoder = null;
                WebcamOpen = false;
                cameraLive.Reset();
            }
            if (webcam.Opened && cameraEncoder != null && Environment.TickCount64 >= nextCamera)
            {
                nextCamera = Environment.TickCount64 + 1000 / CameraFps;
                var raw = webcam.Latest();
                if (raw != null)
                {
                    var fitted = FrameScale.Fit(raw.Width, raw.Height, 960);
                    Nv12Frame nv12;
                    if (raw.Width == fitted.Width && raw.Height == fitted.Height) nv12 = raw;
                    else
                    {
                        var bgra = Nv12ToBgra(raw);
                        nv12 = FrameScale.ToNv12(bgra, fitted.Width, fitted.Height);
                    }
                    if (cameraSize == null || cameraSize.Width != nv12.Width || cameraSize.Height != nv12.Height)
                    {
                        cameraEncoder.Dispose();
                        cameraEncoder = H264Encoder.TryCreate(nv12.Width, nv12.Height, CameraFps, 900_000);
                        cameraSize = nv12;
                        cameraLive.Reset();
                    }
                    var grid = FrameScale.MotionGrid(nv12);
                    if (cameraGrid != null && FrameScale.ChangedFraction(cameraGrid, grid) >= MotionFraction)
                        cameraMotionAt = now;
                    cameraGrid = grid;
                    var encoded = cameraEncoder?.Encode(nv12, cameraLive.NeedsKeyframe);
                    if (encoded != null)
                    {
                        cameraCount++;
                        Note(encoded, cameraEncoder!, cameraLive, cameraClip, "camera", now);
                    }
                }
            }

            var screenHot = now - screenMotionAt < QuietMs && screenMotionAt > 0;
            var cameraHot = cameraWanted && now - cameraMotionAt < QuietMs && cameraMotionAt > 0;
            Consider(screenClip, screenHot, now, screenEncoder);
            Consider(cameraClip, cameraHot, now, cameraEncoder);
            if (screenClip.IsOpen) Status = "Recording the screen";
            else if (cameraClip.IsOpen) Status = "Recording the webcam";
            else Status = cameraWanted ? "Watching the desktop and webcam" : "Watching the desktop";

            var tick = Environment.TickCount64;
            if (tick - screenWindow >= 5000)
            {
                ScreenEncodeFps = screenCount * 1000.0 / (tick - screenWindow);
                AppLog.Write("alive screen " + screenCount + " camera " + cameraCount + (WebcamOpen ? " webcam-open" : " webcam-off"));
                screenCount = 0;
                screenWindow = tick;
            }
            if (tick - cameraWindow >= 5000 && cameraWanted)
            {
                CameraEncodeFps = cameraCount * 1000.0 / (tick - cameraWindow);
                cameraCount = 0;
                cameraWindow = tick;
            }
            else if (!cameraWanted)
            {
                CameraEncodeFps = 0;
            }
            Thread.Sleep(5);
            }
            catch (Exception error) when (!token.IsCancellationRequested)
            {
                AppLog.Write("capture " + error.GetType().Name + " " + error.Message);
                Status = "Recorder hit an error and is retrying";
                Thread.Sleep(500);
            }
        }

        var ended = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        screenClip.Close(ended);
        cameraClip.Close(ended);
        webcam?.Dispose();
        cameraEncoder?.Dispose();
        await UploadPending(settings, CancellationToken.None);
    }

    static void Note(EncodedAccessUnit encoded, H264Encoder encoder, LivePublisher live, ClipWriter clip, string kind, long now)
    {
        if (encoder.Sps == null || encoder.Pps == null) return;
        var avcc = Avc.AnnexBToAvcc(encoded.AnnexB);
        if (avcc.Length == 0) return;
        live.Accept(encoder, encoded.Keyframe, avcc);
        if (clip.IsOpen) clip.Accept(encoder, encoded.Keyframe, avcc, now);
        _ = kind;
    }

    static void Consider(ClipWriter clip, bool hot, long now, H264Encoder? encoder)
    {
        if (encoder == null) return;
        if (hot && !clip.IsOpen) clip.Open(now, encoder);
        else if (!hot && clip.IsOpen && now - clip.Started >= 8000) clip.Close(now);
        else if (clip.IsOpen && now - clip.Started >= SegmentMs)
        {
            clip.Close(now);
            if (hot) clip.Open(now, encoder);
        }
    }

    async Task UploadPending(AppSettings settings, CancellationToken token)
    {
        foreach (var file in Directory.GetFiles(AppSettings.PendingFolder, "*.mp4"))
        {
            var jsonPath = Path.ChangeExtension(file, ".json");
            if (!File.Exists(jsonPath)) continue;
            try
            {
                using var document = System.Text.Json.JsonDocument.Parse(await File.ReadAllTextAsync(jsonPath, token));
                var root = document.RootElement;
                var started = root.GetProperty("startedAtMs").GetInt64();
                var ended = root.GetProperty("endedAtMs").GetInt64();
                var kind = root.GetProperty("kind").GetString() ?? "screen";
                if (await shop.UploadClip(settings, kind, started, ended, file, token))
                {
                    File.Delete(file);
                    File.Delete(jsonPath);
                }
            }
            catch (Exception error) when (!token.IsCancellationRequested)
            {
                AppLog.Write("clip file " + error.GetType().Name);
            }
        }
    }

    static BgraFrame Nv12ToBgra(Nv12Frame frame)
    {
        var stride = frame.Width * 4;
        var pixels = new byte[stride * frame.Height];
        var chroma = frame.Width * frame.Height;
        for (var y = 0; y < frame.Height; y++)
        {
            for (var x = 0; x < frame.Width; x++)
            {
                var yValue = frame.Pixels[y * frame.Width + x];
                var uv = chroma + (y / 2) * frame.Width + (x & ~1);
                var u = frame.Pixels[uv] - 128;
                var v = frame.Pixels[uv + 1] - 128;
                var c = yValue - 16;
                var r = Math.Clamp((298 * c + 409 * v + 128) >> 8, 0, 255);
                var g = Math.Clamp((298 * c - 100 * u - 208 * v + 128) >> 8, 0, 255);
                var b = Math.Clamp((298 * c + 516 * u + 128) >> 8, 0, 255);
                var offset = y * stride + x * 4;
                pixels[offset] = (byte)b;
                pixels[offset + 1] = (byte)g;
                pixels[offset + 2] = (byte)r;
                pixels[offset + 3] = 255;
            }
        }
        return new BgraFrame { Pixels = pixels, Width = frame.Width, Height = frame.Height, Stride = stride };
    }

    public void Dispose() => Stop();
}

static class MediaFactoryStartup
{
    static int started;

    public static void Ensure()
    {
        if (Interlocked.Exchange(ref started, 1) == 1) return;
        MediaFactory.MFStartup();
    }
}

sealed class LivePublisher
{
    readonly ShopClient shop;
    readonly AppSettings settings;
    readonly string kind;
    readonly object gate = new();
    readonly List<Fmp4Muxer.Sample> batch = new();
    Fmp4Muxer? muxer;
    int sequence = 1;
    long batchStarted;
    public bool NeedsKeyframe { get; private set; } = true;

    public LivePublisher(ShopClient shop, AppSettings settings, string kind)
    {
        this.shop = shop;
        this.settings = settings;
        this.kind = kind;
    }

    public void Reset()
    {
        lock (gate)
        {
            muxer = null;
            sequence = 1;
            batch.Clear();
            NeedsKeyframe = true;
        }
    }

    public void Accept(H264Encoder encoder, bool keyframe, byte[] avcc)
    {
        if (encoder.Sps == null || encoder.Pps == null) return;
        byte[]? outbound = null;
        var outboundSeq = 0;
        lock (gate)
        {
            if (muxer == null)
            {
                if (!keyframe) return;
                muxer = new Fmp4Muxer(encoder.Width, encoder.Height, kind == "camera" ? RecorderEngine.CameraFps : RecorderEngine.ScreenFps);
                outbound = muxer.Start(encoder.Sps, encoder.Pps);
                outboundSeq = 0;
                sequence = 1;
                NeedsKeyframe = false;
            }
            if (keyframe) NeedsKeyframe = false;
            batch.Add(new Fmp4Muxer.Sample(avcc, keyframe, 0));
            var now = Environment.TickCount64;
            if (batchStarted == 0) batchStarted = now;
            if (outbound == null && (batch.Count >= 6 || now - batchStarted >= 200))
            {
                var bytes = muxer!.Media(batch);
                batch.Clear();
                batchStarted = now;
                if (bytes.Length > 0)
                {
                    outbound = bytes;
                    outboundSeq = sequence++;
                }
                if (now % 1000 < 50) NeedsKeyframe = true;
            }
        }
        if (outbound != null) Send(outboundSeq, outbound, encoder.Codec);
    }

    void Send(int sequence, byte[] bytes, string codec)
    {
        _ = shop.UploadStream(settings, kind, sequence, bytes, codec, CancellationToken.None).ContinueWith(task =>
        {
            var stored = task.Status == TaskStatus.RanToCompletion && task.Result;
            if (!stored) Reset();
        });
    }
}

sealed class ClipWriter
{
    readonly string kind;
    FileStream? stream;
    Fmp4Muxer? muxer;
    string? partialPath;
    public long Started { get; private set; }
    public bool IsOpen => stream != null;
    int samples;

    public ClipWriter(string kind) => this.kind = kind;

    public void Open(long now, H264Encoder encoder)
    {
        if (IsOpen || encoder.Sps == null || encoder.Pps == null) return;
        Started = now;
        partialPath = Path.Combine(AppSettings.PendingFolder, $"partial-{kind}-{now}.mp4");
        stream = File.Create(partialPath);
        muxer = new Fmp4Muxer(encoder.Width, encoder.Height, kind == "camera" ? RecorderEngine.CameraFps : RecorderEngine.ScreenFps);
        var init = muxer.Start(encoder.Sps, encoder.Pps);
        stream.Write(init);
        samples = 0;
    }

    public void Accept(H264Encoder encoder, bool keyframe, byte[] avcc, long now)
    {
        if (stream == null || muxer == null) return;
        if (samples == 0 && !keyframe) return;
        var bytes = muxer.Media(new[] { new Fmp4Muxer.Sample(avcc, keyframe, 0) });
        stream.Write(bytes);
        samples++;
        _ = now;
    }

    public void Close(long ended)
    {
        if (stream == null || partialPath == null) return;
        stream.Dispose();
        stream = null;
        var info = new FileInfo(partialPath);
        var longEnough = ended - Started >= 8000 && samples >= 8 && info.Exists && info.Length > 1024;
        if (!longEnough)
        {
            TryDelete(partialPath);
            partialPath = null;
            muxer = null;
            return;
        }
        var finalPath = Path.Combine(AppSettings.PendingFolder, $"{Started}-{ended}.mp4");
        File.Move(partialPath, finalPath, true);
        File.WriteAllText(Path.ChangeExtension(finalPath, ".json"),
            $"{{\"startedAtMs\":{Started},\"endedAtMs\":{ended},\"kind\":\"{kind}\"}}");
        partialPath = null;
        muxer = null;
    }

    static void TryDelete(string path)
    {
        try { File.Delete(path); } catch { }
    }
}
