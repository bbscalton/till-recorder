using System.Net.Sockets;
using System.Text;
using System.Text.RegularExpressions;

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
                await Pull(target, shop, settings, token);
            }
            catch (Exception error) when (error is not OperationCanceledException)
            {
                AppLog.Write("overhead " + error.GetType().Name);
            }
            try { await Task.Delay(15000, token); } catch { return; }
        }
    }

    static async Task Pull(string target, ShopClient shop, AppSettings settings, CancellationToken token)
    {
        if (!Uri.TryCreate(target, UriKind.Absolute, out var uri) || uri.Scheme is not ("rtsp" or "rtsps"))
            throw new InvalidOperationException("url");
        var port = uri.Port > 0 ? uri.Port : 554;
        using var tcp = new TcpClient();
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(token);
        linked.CancelAfter(TimeSpan.FromSeconds(12));
        await tcp.ConnectAsync(uri.Host, port, linked.Token);
        tcp.ReceiveTimeout = 8000;
        tcp.SendTimeout = 8000;
        using var stream = tcp.GetStream();
        var cseq = 1;
        var describe = await Request(stream, $"DESCRIBE {target} RTSP/1.0\r\nCSeq: {cseq++}\r\nAccept: application/sdp\r\n\r\n", token);
        if (!describe.Contains("200", StringComparison.Ordinal)) throw new InvalidOperationException("describe");
        var control = ControlUrl(target, describe);
        var setup = await Request(stream, $"SETUP {control} RTSP/1.0\r\nCSeq: {cseq++}\r\nTransport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n\r\n", token);
        if (!setup.Contains("200", StringComparison.Ordinal)) throw new InvalidOperationException("setup");
        var session = Header(setup, "Session").Split(';')[0].Trim();
        if (session.Length == 0) throw new InvalidOperationException("session");
        var play = await Request(stream, $"PLAY {target} RTSP/1.0\r\nCSeq: {cseq++}\r\nSession: {session}\r\n\r\n", token);
        if (!play.Contains("200", StringComparison.Ordinal)) throw new InvalidOperationException("play");
        AppLog.Write("overhead playing");
        var depay = new H264Depay();
        var muxer = (Fmp4Muxer?)null;
        var initBytes = Array.Empty<byte>();
        var sequence = 1;
        var clipStarted = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var clipPath = "";
        FileStream? clip = null;
        var deadline = DateTime.UtcNow.AddMinutes(2);
        while (!token.IsCancellationRequested && DateTime.UtcNow < deadline)
        {
            var packet = ReadInterleaved(stream);
            if (packet == null) break;
            if (packet.Value.Channel != 0) continue;
            foreach (var nal in depay.Push(packet.Value.Payload))
            {
                var type = nal[0] & 0x1f;
                if (type == 7) depay.Sps = nal;
                if (type == 8) depay.Pps = nal;
                if (muxer == null && depay.Sps != null && depay.Pps != null)
                {
                    muxer = new Fmp4Muxer(1280, 720, 15);
                    initBytes = muxer.Start(depay.Sps, depay.Pps);
                    _ = shop.UploadStream(settings, "overhead", 0, initBytes, "avc1.42E01E", CancellationToken.None);
                    clipStarted = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                    clipPath = Path.Combine(AppSettings.PendingFolder, $"partial-overhead-{clipStarted}.mp4");
                    clip = File.Create(clipPath);
                    clip.Write(initBytes);
                }
                if (muxer == null || type == 7 || type == 8 || type == 6) continue;
                var avcc = Avc.AnnexBToAvcc(new byte[] { 0, 0, 0, 1 }.Concat(nal).ToArray());
                if (avcc.Length == 0) continue;
                var media = muxer.Media(new[] { new Fmp4Muxer.Sample(avcc, type == 5, 0) });
                var seq = sequence++;
                _ = shop.UploadStream(settings, "overhead", seq, media, "avc1.42E01E", CancellationToken.None);
                clip?.Write(media);
                if (clip != null && DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - clipStarted > 20000)
                {
                    var ended = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                    clip.Dispose();
                    var finalPath = Path.Combine(AppSettings.PendingFolder, $"{clipStarted}-{ended}.mp4");
                    File.Move(clipPath, finalPath, true);
                    File.WriteAllText(Path.ChangeExtension(finalPath, ".json"),
                        $"{{\"startedAtMs\":{clipStarted},\"endedAtMs\":{ended},\"kind\":\"overhead\"}}");
                    clip = null;
                    clipPath = "";
                    clipStarted = ended;
                    clipPath = Path.Combine(AppSettings.PendingFolder, $"partial-overhead-{clipStarted}.mp4");
                    clip = File.Create(clipPath);
                    clip.Write(initBytes);
                }
            }
        }
        clip?.Dispose();
        if (!string.IsNullOrEmpty(clipPath) && File.Exists(clipPath)) TryDelete(clipPath);
    }

    static string ControlUrl(string baseUrl, string describe)
    {
        var match = Regex.Match(describe, @"a=control:(\S+)");
        if (!match.Success) return baseUrl;
        var value = match.Groups[1].Value.Trim();
        if (value.StartsWith("rtsp", StringComparison.OrdinalIgnoreCase)) return value;
        return baseUrl.TrimEnd('/') + "/" + value.TrimStart('/');
    }

    static async Task<string> Request(NetworkStream stream, string text, CancellationToken token)
    {
        var bytes = Encoding.ASCII.GetBytes(text);
        await stream.WriteAsync(bytes, token);
        var buffer = new byte[8192];
        var read = await stream.ReadAsync(buffer, token);
        return Encoding.ASCII.GetString(buffer, 0, read);
    }

    static string Header(string message, string name)
    {
        foreach (var line in message.Split("\r\n"))
        {
            if (line.StartsWith(name + ":", StringComparison.OrdinalIgnoreCase))
                return line[(name.Length + 1)..].Trim();
        }
        return "";
    }

    static (int Channel, byte[] Payload)? ReadInterleaved(NetworkStream stream)
    {
        var lead = stream.ReadByte();
        if (lead < 0) return null;
        if (lead != 0x24)
        {
            while (lead >= 0 && lead != 0x24) lead = stream.ReadByte();
            if (lead < 0) return null;
        }
        var channel = stream.ReadByte();
        var hi = stream.ReadByte();
        var lo = stream.ReadByte();
        if (channel < 0 || hi < 0 || lo < 0) return null;
        var length = (hi << 8) | lo;
        if (length <= 0 || length > 1_000_000) return null;
        var payload = new byte[length];
        var filled = 0;
        while (filled < length)
        {
            var n = stream.Read(payload, filled, length - filled);
            if (n <= 0) return null;
            filled += n;
        }
        return (channel, payload);
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

sealed class H264Depay
{
    readonly MemoryStream fragment = new();
    public byte[]? Sps { get; set; }
    public byte[]? Pps { get; set; }

    public IEnumerable<byte[]> Push(byte[] rtp)
    {
        if (rtp.Length < 13) yield break;
        var nalType = rtp[12] & 0x1f;
        if (nalType >= 1 && nalType <= 23)
        {
            yield return rtp[12..];
            yield break;
        }
        if (nalType != 28 || rtp.Length < 15) yield break;
        var start = (rtp[13] & 0x80) != 0;
        var end = (rtp[13] & 0x40) != 0;
        var header = (byte)((rtp[12] & 0xe0) | (rtp[13] & 0x1f));
        if (start)
        {
            fragment.SetLength(0);
            fragment.WriteByte(header);
        }
        fragment.Write(rtp, 14, rtp.Length - 14);
        if (end && fragment.Length > 0)
        {
            yield return fragment.ToArray();
            fragment.SetLength(0);
        }
    }
}

static class OnvifDiscovery
{
    public static List<(string Name, string Host, string Url)> Probe()
    {
        var found = new List<(string Name, string Host, string Url)>();
        try
        {
            using var udp = new System.Net.Sockets.UdpClient();
            udp.Client.ReceiveTimeout = 1500;
            var probe = """
                <?xml version="1.0" encoding="UTF-8"?>
                <e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery">
                <e:Header><w:MessageID>urn:uuid:11111111-1111-1111-1111-111111111111</w:MessageID><w:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header>
                <e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>
                """;
            var bytes = Encoding.UTF8.GetBytes(probe);
            udp.Send(bytes, bytes.Length, "239.255.255.250", 3702);
            var until = DateTime.UtcNow.AddSeconds(2);
            while (DateTime.UtcNow < until)
            {
                try
                {
                    var remote = new System.Net.IPEndPoint(System.Net.IPAddress.Any, 0);
                    var data = udp.Receive(ref remote);
                    var text = Encoding.UTF8.GetString(data);
                    var host = Regex.Match(text, @"XAddrs>([^<]+)");
                    var address = host.Success ? host.Groups[1].Value.Split(' ')[0] : remote.Address.ToString();
                    var ipMatch = Regex.Match(address, @"^(?:https?://)?([^/:]+)");
                    var ip = ipMatch.Success ? ipMatch.Groups[1].Value : remote.Address.ToString();
                    if (found.All(item => item.Host != ip))
                    {
                        var service = address.StartsWith("http", StringComparison.OrdinalIgnoreCase)
                            ? address
                            : "rtsp://" + ip + ":554/";
                        found.Add(("Camera", ip, service));
                    }
                }
                catch { break; }
            }
        }
        catch (Exception error)
        {
            AppLog.Write("onvif " + error.GetType().Name);
        }
        return found;
    }

    public static string? TryStream(string xaddr, string user, string password)
    {
        try
        {
            if (!Uri.TryCreate(xaddr, UriKind.Absolute, out var uri) || uri.Scheme is not ("http" or "https")) return null;
            using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(8) };
            var profiles = Post(http, xaddr, Envelope(user, password, "<GetProfiles xmlns=\"http://www.onvif.org/ver10/media/wsdl\"/>"));
            var token = Regex.Match(profiles, "token=\"([^\"]+)\"").Groups[1].Value;
            var media = xaddr;
            if (token.Length == 0)
            {
                var caps = Post(http, xaddr, Envelope(user, password, "<GetCapabilities xmlns=\"http://www.onvif.org/ver10/device/wsdl\"><Category>Media</Category></GetCapabilities>"));
                var mediaAddr = Regex.Match(caps, "XAddr>([^<]+)").Groups[1].Value.Trim();
                if (mediaAddr.Length == 0) return null;
                media = mediaAddr.Split(' ')[0];
                profiles = Post(http, media, Envelope(user, password, "<GetProfiles xmlns=\"http://www.onvif.org/ver10/media/wsdl\"/>"));
                token = Regex.Match(profiles, "token=\"([^\"]+)\"").Groups[1].Value;
            }
            if (token.Length == 0) return null;
            var body = "<GetStreamUri xmlns=\"http://www.onvif.org/ver10/media/wsdl\"><StreamSetup><Stream xmlns=\"http://www.onvif.org/ver10/schema\">RTP-Unicast</Stream><Transport xmlns=\"http://www.onvif.org/ver10/schema\"><Protocol>RTSP</Protocol></Transport></StreamSetup><ProfileToken>" + System.Security.SecurityElement.Escape(token) + "</ProfileToken></GetStreamUri>";
            var stream = Post(http, media, Envelope(user, password, body));
            var match = Regex.Match(stream, "<(?:[\\w]+:)?Uri>([^<]+)</");
            if (!match.Success) return null;
            return CameraAddress.Bare(match.Groups[1].Value.Trim());
        }
        catch (Exception error)
        {
            AppLog.Write("onvif " + error.GetType().Name);
            return null;
        }
    }

    static string Envelope(string user, string password, string body)
    {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\"><s:Header>" + Security(user, password) + "</s:Header><s:Body>" + body + "</s:Body></s:Envelope>";
    }

    static string Security(string user, string password)
    {
        if (string.IsNullOrEmpty(user)) return "";
        var nonce = System.Security.Cryptography.RandomNumberGenerator.GetBytes(16);
        var created = DateTime.UtcNow.ToString("yyyy-MM-ddTHH:mm:ss.fffZ");
        var createdBytes = Encoding.UTF8.GetBytes(created);
        var passBytes = Encoding.UTF8.GetBytes(password ?? "");
        var mix = new byte[nonce.Length + createdBytes.Length + passBytes.Length];
        Buffer.BlockCopy(nonce, 0, mix, 0, nonce.Length);
        Buffer.BlockCopy(createdBytes, 0, mix, nonce.Length, createdBytes.Length);
        Buffer.BlockCopy(passBytes, 0, mix, nonce.Length + createdBytes.Length, passBytes.Length);
        var digest = Convert.ToBase64String(System.Security.Cryptography.SHA1.HashData(mix));
        var nonceText = Convert.ToBase64String(nonce);
        var name = System.Security.SecurityElement.Escape(user);
        return "<Security s:mustUnderstand=\"1\" xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\"><UsernameToken><Username>" + name + "</Username><Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">" + digest + "</Password><Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary\">" + nonceText + "</Nonce><Created xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">" + created + "</Created></UsernameToken></Security>";
    }

    static string Post(HttpClient http, string address, string xml)
    {
        using var content = new StringContent(xml, Encoding.UTF8, "application/soap+xml");
        using var response = http.PostAsync(address, content).GetAwaiter().GetResult();
        return response.Content.ReadAsStringAsync().GetAwaiter().GetResult();
    }
}

static class CameraAddress
{
    public static (string Bare, string User, string Password) Split(string raw)
    {
        var text = (raw ?? "").Trim();
        if (!Uri.TryCreate(text, UriKind.Absolute, out var uri) || string.IsNullOrEmpty(uri.UserInfo)) return (text, "", "");
        var colon = uri.UserInfo.IndexOf(':');
        var user = Uri.UnescapeDataString(colon >= 0 ? uri.UserInfo[..colon] : uri.UserInfo);
        var password = colon >= 0 ? Uri.UnescapeDataString(uri.UserInfo[(colon + 1)..]) : "";
        return (Bare(text), user, password);
    }

    public static string Bare(string raw)
    {
        if (!Uri.TryCreate((raw ?? "").Trim(), UriKind.Absolute, out var uri)) return (raw ?? "").Trim();
        var builder = new UriBuilder(uri) { UserName = "", Password = "" };
        return builder.Uri.GetComponents(UriComponents.AbsoluteUri & ~UriComponents.UserInfo, UriFormat.UriEscaped);
    }

    public static string Embed(string bare, string user, string password)
    {
        var text = (bare ?? "").Trim();
        if (text.Length == 0 || string.IsNullOrEmpty(user)) return text;
        if (!Uri.TryCreate(text, UriKind.Absolute, out var uri)) return text;
        var builder = new UriBuilder(uri) { UserName = user, Password = password ?? "" };
        return builder.Uri.AbsoluteUri;
    }

    public static string Label(string bare)
    {
        if (!Uri.TryCreate((bare ?? "").Trim(), UriKind.Absolute, out var uri) || string.IsNullOrWhiteSpace(uri.Host)) return "";
        return uri.Host;
    }

    public static string Pull(AppSettings settings)
    {
        var split = Split(settings.Overhead ?? "");
        var user = string.IsNullOrEmpty(split.User) ? settings.OverheadUser ?? "" : split.User;
        var password = string.IsNullOrEmpty(split.Password) ? settings.OverheadPassword ?? "" : split.Password;
        return Embed(split.Bare, user, password);
    }
}
