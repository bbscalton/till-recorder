namespace TillRecorder;

/// <summary>
/// Pulls an RTSP camera on the store LAN and uploads it as the overhead live stream.
/// The watch page never opens the camera itself.
/// </summary>
sealed class OverheadRelay : IDisposable
{
    string url = "";
    CancellationTokenSource? run;
    Task? task;

    /// <summary>Last relay failure in plain words (no address or password), empty while playing.</summary>
    public static string LastError { get; private set; } = "";

    public void Apply(string next, ShopClient shop, AppSettings settings)
    {
        next ??= "";
        if (next == url && task != null && !task.IsCompleted) return;
        url = next;
        run?.Cancel();
        if (string.IsNullOrWhiteSpace(next))
        {
            task = null;
            return;
        }
        run = new CancellationTokenSource();
        var token = run.Token;
        var target = next;
        task = Task.Run(() => Loop(target, shop, settings, token), token);
    }

    static async Task Loop(string target, ShopClient shop, AppSettings settings, CancellationToken token)
    {
        while (!token.IsCancellationRequested)
        {
            try
            {
                AppLog.Write("overhead connect");
                Pull(target, shop, settings, token);
            }
            catch (CameraException error)
            {
                LastError = error.Message;
                AppLog.Write("overhead " + error.Kind + ": " + error.Message);
            }
            catch (Exception error) when (error is not OperationCanceledException)
            {
                LastError = "Overhead camera stopped (" + error.GetType().Name + ").";
                AppLog.Write("overhead " + error.GetType().Name);
            }
            try { await Task.Delay(15000, token); } catch { return; }
        }
    }

    static void Pull(string target, ShopClient shop, AppSettings settings, CancellationToken token)
    {
        var parts = CameraAddress.Split(target);
        using var client = new RtspClient(parts.Bare, parts.User, parts.Password);
        using var stop = token.Register(() => client.Dispose());
        client.Connect(8000, 10000, token);
        var track = client.Describe();
        client.Setup(track);
        client.Play(track);
        AppLog.Write("overhead playing");
        LastError = "";
        var assembler = new RtpH264Assembler { Sps = track.Sps, Pps = track.Pps };
        Fmp4Muxer? muxer = null;
        var codec = "avc1.42E01E";
        var initBytes = Array.Empty<byte>();
        var sequence = 1;
        var batch = new List<Fmp4Muxer.Sample>();
        var batchStarted = DateTime.MinValue;
        long lastTs = -1;
        var clipStarted = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var clipPath = "";
        FileStream? clip = null;
        var keepAliveEvery = TimeSpan.FromSeconds(Math.Clamp(client.SessionTimeoutSec / 2, 5, 30));
        var lastKeepAlive = DateTime.UtcNow;
        var deadline = DateTime.UtcNow.AddMinutes(10);
        try
        {
            while (!token.IsCancellationRequested && DateTime.UtcNow < deadline)
            {
                if (DateTime.UtcNow - lastKeepAlive > keepAliveEvery)
                {
                    client.KeepAlive();
                    lastKeepAlive = DateTime.UtcNow;
                }
                var packet = client.ReadPacket();
                if (packet == null) break;
                if (packet.Value.Channel != client.VideoChannel) continue;
                foreach (var unit in assembler.Push(packet.Value.Payload))
                {
                    if (muxer == null)
                    {
                        if (assembler.Sps == null || assembler.Pps == null || !unit.Keyframe) continue;
                        muxer = new Fmp4Muxer(1280, 720, 15);
                        codec = Avc.CodecString(assembler.Sps);
                        initBytes = muxer.Start(assembler.Sps, assembler.Pps);
                        _ = shop.UploadStream(settings, "overhead", 0, initBytes, codec, CancellationToken.None);
                        clipStarted = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                        clipPath = Path.Combine(AppSettings.PendingFolder, $"partial-overhead-{clipStarted}.mp4");
                        clip = File.Create(clipPath);
                        clip.Write(initBytes);
                    }
                    var ticks = lastTs < 0 ? 6000 : (int)Math.Clamp((unit.Timestamp - lastTs) & 0xffffffffL, 1, 90000);
                    lastTs = unit.Timestamp;
                    if (batch.Count == 0) batchStarted = DateTime.UtcNow;
                    batch.Add(new Fmp4Muxer.Sample(RtpH264Assembler.Avcc(unit.Nals), unit.Keyframe, ticks));
                    if (batch.Count < 15 && DateTime.UtcNow - batchStarted < TimeSpan.FromMilliseconds(500)) continue;
                    if (clip != null && batch[0].Keyframe && DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - clipStarted > 20000)
                    {
                        var ended = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                        clip.Dispose();
                        var finalPath = Path.Combine(AppSettings.PendingFolder, $"{clipStarted}-{ended}.mp4");
                        File.Move(clipPath, finalPath, true);
                        File.WriteAllText(Path.ChangeExtension(finalPath, ".json"),
                            $"{{\"startedAtMs\":{clipStarted},\"endedAtMs\":{ended},\"kind\":\"overhead\"}}");
                        clipStarted = ended;
                        clipPath = Path.Combine(AppSettings.PendingFolder, $"partial-overhead-{clipStarted}.mp4");
                        clip = File.Create(clipPath);
                        clip.Write(initBytes);
                    }
                    var media = muxer.Media(batch.ToArray());
                    batch.Clear();
                    _ = shop.UploadStream(settings, "overhead", sequence++, media, codec, CancellationToken.None);
                    clip?.Write(media);
                }
            }
        }
        finally
        {
            clip?.Dispose();
            if (!string.IsNullOrEmpty(clipPath) && File.Exists(clipPath)) TryDelete(clipPath);
        }
    }

    static void TryDelete(string path)
    {
        try { File.Delete(path); } catch { }
    }

    public void Dispose()
    {
        run?.Cancel();
        run?.Dispose();
    }
}
