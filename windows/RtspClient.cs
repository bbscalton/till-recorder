using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

namespace TillRecorder;

enum CameraFailure { Unreachable, Auth, NotFound, Codec, Protocol }

/// <summary>What went wrong talking to the camera, in words a store owner can act on. Never contains the password.</summary>
sealed class CameraException : Exception
{
    public CameraFailure Kind { get; }
    public CameraException(CameraFailure kind, string message) : base(message) { Kind = kind; }
}

sealed record RtspTrack(string Codec, int PayloadType, string Control, string PlayUrl, byte[]? Sps, byte[]? Pps);

/// <summary>
/// Minimal RTSP client: DESCRIBE / SETUP (RTP over TCP interleaved) / PLAY with Basic or Digest login.
/// Request lines never carry the username or password.
/// </summary>
sealed class RtspClient : IDisposable
{
    sealed record Response(int Status, string Reason, List<(string Name, string Value)> Headers, string Body)
    {
        public string Header(string name) => Headers.FirstOrDefault(h => h.Name.Equals(name, StringComparison.OrdinalIgnoreCase)).Value ?? "";
        public List<string> All(string name) => Headers.Where(h => h.Name.Equals(name, StringComparison.OrdinalIgnoreCase)).Select(h => h.Value).ToList();
    }

    readonly string user;
    readonly string password;
    readonly CameraUrl parsed;
    readonly TcpClient tcp = new();
    Stream input = Stream.Null;
    Stream output = Stream.Null;
    int cseq = 1;
    Dictionary<string, string>? challenge;
    int nonceCount;
    string session = "";

    public string Url { get; }
    public string Where { get; }
    public int SessionTimeoutSec { get; private set; } = 60;
    public int VideoChannel { get; private set; }

    public RtspClient(string bareUrl, string user, string password)
    {
        Url = (bareUrl ?? "").Trim().Replace("&amp;", "&");
        this.user = user ?? "";
        this.password = password ?? "";
        parsed = CameraAddress.Parse(Url) ?? throw new CameraException(CameraFailure.Protocol, CameraAddress.BadAddress);
        if (parsed.Scheme != "rtsp") throw new CameraException(CameraFailure.Protocol, "Only rtsp:// addresses can be played.");
        Where = parsed.HostText + ":" + (parsed.Port > 0 ? parsed.Port : 554);
    }

    public void Connect(int connectMs = 8000, int readMs = 10000, CancellationToken token = default)
    {
        try
        {
            using var linked = CancellationTokenSource.CreateLinkedTokenSource(token);
            linked.CancelAfter(connectMs);
            tcp.ConnectAsync(parsed.Host, parsed.Port > 0 ? parsed.Port : 554, linked.Token).AsTask().GetAwaiter().GetResult();
        }
        catch (OperationCanceledException) when (!token.IsCancellationRequested)
        {
            throw new CameraException(CameraFailure.Unreachable, $"The camera at {Where} did not answer (timed out). Check the IP address and that the camera is on this network.");
        }
        catch (SocketException error) when (error.SocketErrorCode == SocketError.HostNotFound || error.SocketErrorCode == SocketError.NoData)
        {
            throw new CameraException(CameraFailure.Unreachable, $"The camera name {parsed.Host} could not be found on this network.");
        }
        catch (SocketException error) when (error.SocketErrorCode == SocketError.ConnectionRefused)
        {
            throw new CameraException(CameraFailure.Unreachable, $"The camera at {Where} refused the RTSP connection. Check the RTSP port (usually 554) and that RTSP is turned on in the camera.");
        }
        catch (SocketException)
        {
            throw new CameraException(CameraFailure.Unreachable, $"Could not connect to the camera at {Where}.");
        }
        tcp.ReceiveTimeout = readMs;
        tcp.SendTimeout = readMs;
        tcp.NoDelay = true;
        var stream = tcp.GetStream();
        input = new BufferedStream(stream, 64 * 1024);
        output = stream;
    }

    public RtspTrack Describe(bool anyCodec = false)
    {
        var response = Request("DESCRIBE", Url, new() { ("Accept", "application/sdp") });
        if (response.Status == 404)
            throw new CameraException(CameraFailure.NotFound, $"The camera at {Where} has no stream at that path (RTSP 404). Check the path after the port.");
        ExpectOk(response, "DESCRIBE");
        var baseUrl = response.Header("Content-Base");
        if (baseUrl.Length == 0) baseUrl = response.Header("Content-Location");
        if (baseUrl.Length == 0) baseUrl = Url;
        var track = ParseSdp(response.Body, baseUrl);
        if (track.Codec == "H264" || anyCodec) return track;
        var name = track.Codec switch { "H265" or "HEVC" => "H.265", "JPEG" => "MJPEG", _ => track.Codec };
        throw new CameraException(CameraFailure.Codec,
            $"The camera at {Where} sends {name} video, which the watch page cannot play. In the camera's video settings set this stream (or the sub stream) to H.264, or use the sub stream address.");
    }

    public void Setup(RtspTrack track)
    {
        var response = Request("SETUP", track.Control, new() { ("Transport", "RTP/AVP/TCP;unicast;interleaved=0-1") });
        if (response.Status == 461)
            throw new CameraException(CameraFailure.Protocol, $"The camera at {Where} refused RTP over TCP (RTSP 461). Turn on RTSP over TCP in the camera, or use another stream.");
        ExpectOk(response, "SETUP");
        var sessionHeader = response.Header("Session");
        session = sessionHeader.Split(';')[0].Trim();
        var timeout = Regex.Match(sessionHeader, @"timeout=(\d+)");
        if (timeout.Success && int.TryParse(timeout.Groups[1].Value, out var seconds) && seconds is >= 5 and <= 3600) SessionTimeoutSec = seconds;
        if (session.Length == 0) throw new CameraException(CameraFailure.Protocol, $"The camera at {Where} did not start an RTSP session.");
        var channel = Regex.Match(response.Header("Transport"), @"interleaved=(\d+)");
        if (channel.Success) VideoChannel = int.Parse(channel.Groups[1].Value);
    }

    public void Play(RtspTrack track)
    {
        var response = Request("PLAY", track.PlayUrl, new() { ("Session", session), ("Range", "npt=0.000-") });
        ExpectOk(response, "PLAY");
    }

    /// <summary>Sends a keep-alive without waiting; the reply is skipped by ReadPacket.</summary>
    public void KeepAlive() => Send("OPTIONS", Url, new() { ("Session", session) });

    public (int Channel, byte[] Payload)? ReadPacket()
    {
        while (true)
        {
            var lead = input.ReadByte();
            if (lead < 0) return null;
            if (lead == 'R')
            {
                ReadResponseRest("R");
                continue;
            }
            if (lead != 0x24) continue;
            var channel = input.ReadByte();
            var hi = input.ReadByte();
            var lo = input.ReadByte();
            if (channel < 0 || hi < 0 || lo < 0) return null;
            var length = (hi << 8) | lo;
            var payload = new byte[length];
            var filled = 0;
            while (filled < length)
            {
                var n = input.Read(payload, filled, length - filled);
                if (n <= 0) return null;
                filled += n;
            }
            return (channel, payload);
        }
    }

    public void Dispose()
    {
        try { if (session.Length > 0) Send("TEARDOWN", Url, new() { ("Session", session) }); } catch { }
        try { tcp.Dispose(); } catch { }
    }

    void ExpectOk(Response response, string step)
    {
        if (response.Status is >= 200 and <= 299) return;
        throw new CameraException(CameraFailure.Protocol, $"The camera at {Where} answered {step} with RTSP {response.Status} {response.Reason}".Trim() + ".");
    }

    Response Request(string method, string target, List<(string, string)> extra)
    {
        var response = Exchange(method, target, extra);
        var tries = 0;
        while (response.Status == 401 && tries < 2)
        {
            if (user.Length == 0) throw new CameraException(CameraFailure.Auth, $"The camera at {Where} needs a username and password.");
            var previous = challenge;
            var next = PickChallenge(response.All("WWW-Authenticate"))
                ?? throw new CameraException(CameraFailure.Auth, $"The camera at {Where} asked for a login type this app does not support.");
            var stale = next.TryGetValue("stale", out var s) && s.Equals("true", StringComparison.OrdinalIgnoreCase);
            if (previous != null && !stale && tries > 0) break;
            challenge = next;
            nonceCount = 0;
            tries++;
            response = Exchange(method, target, extra);
        }
        if (response.Status == 401) throw new CameraException(CameraFailure.Auth, $"The camera at {Where} rejected the username or password.");
        if (response.Status == 403) throw new CameraException(CameraFailure.Auth, $"The camera at {Where} refused access for that user (RTSP 403).");
        return response;
    }

    Response Exchange(string method, string target, List<(string, string)> extra)
    {
        var sent = Send(method, target, extra);
        while (true)
        {
            Response response;
            try { response = ReadResponseRest(""); }
            catch (IOException) { throw new CameraException(CameraFailure.Protocol, $"The camera at {Where} stopped answering RTSP requests (timed out). Is {Where} really an RTSP port?"); }
            if (!int.TryParse(response.Header("CSeq").Trim(), out var reply) || reply >= sent) return response;
        }
    }

    int Send(string method, string target, List<(string Name, string Value)> extra)
    {
        var number = cseq++;
        var sb = new StringBuilder();
        sb.Append(method).Append(' ').Append(target).Append(" RTSP/1.0\r\n");
        sb.Append("CSeq: ").Append(number).Append("\r\n");
        sb.Append("User-Agent: TillRecorder\r\n");
        var auth = Authorization(method, target);
        if (auth != null) sb.Append("Authorization: ").Append(auth).Append("\r\n");
        foreach (var (name, value) in extra) if (!string.IsNullOrEmpty(value)) sb.Append(name).Append(": ").Append(value).Append("\r\n");
        sb.Append("\r\n");
        try
        {
            var bytes = Encoding.UTF8.GetBytes(sb.ToString());
            output.Write(bytes, 0, bytes.Length);
            output.Flush();
        }
        catch (IOException)
        {
            throw new CameraException(CameraFailure.Unreachable, $"The camera at {Where} closed the connection.");
        }
        return number;
    }

    string? Authorization(string method, string target)
    {
        var c = challenge;
        if (c == null) return null;
        if (c["#scheme"] == "basic") return "Basic " + Convert.ToBase64String(Encoding.UTF8.GetBytes(user + ":" + password));
        nonceCount++;
        return DigestHeader(c, user, password, method, target, nonceCount, Convert.ToHexString(RandomNumberGenerator.GetBytes(8)).ToLowerInvariant());
    }

    Response ReadResponseRest(string prefix)
    {
        var lines = new List<string>();
        var line = new StringBuilder(prefix);
        while (true)
        {
            var b = input.ReadByte();
            if (b < 0)
            {
                if (lines.Count == 0 && line.Length == 0)
                    throw new CameraException(CameraFailure.Protocol, $"The camera at {Where} closed the RTSP connection.");
                break;
            }
            if (b == '\n')
            {
                var done = line.ToString().TrimEnd('\r');
                line.Clear();
                if (done.Length == 0)
                {
                    if (lines.Count == 0) continue;
                    break;
                }
                lines.Add(done);
                if (lines.Count > 200) break;
                continue;
            }
            line.Append((char)b);
            if (line.Length > 16384) break;
        }
        var status = lines.FirstOrDefault() ?? "";
        if (!status.StartsWith("RTSP/", StringComparison.Ordinal))
            throw new CameraException(CameraFailure.Protocol, $"The service at {Where} is not an RTSP camera stream.");
        var pieces = status.Split(' ', 3);
        var code = pieces.Length > 1 && int.TryParse(pieces[1], out var c) ? c : 0;
        var reason = pieces.Length > 2 ? pieces[2] : "";
        var headers = new List<(string, string)>();
        foreach (var l in lines.Skip(1))
        {
            var colon = l.IndexOf(':');
            if (colon > 0) headers.Add((l[..colon].Trim(), l[(colon + 1)..].Trim()));
        }
        var response = new Response(code, reason, headers, "");
        _ = int.TryParse(response.Header("Content-Length"), out var length);
        var body = new byte[Math.Clamp(length, 0, 1_000_000)];
        var filled = 0;
        while (filled < body.Length)
        {
            var n = input.Read(body, filled, body.Length - filled);
            if (n <= 0) break;
            filled += n;
        }
        return response with { Body = Encoding.UTF8.GetString(body, 0, filled) };
    }

    RtspTrack ParseSdp(string sdp, string baseUrl) => ParseSdpText(sdp, baseUrl);

    public static string Md5(string text) => Convert.ToHexString(MD5.HashData(Encoding.UTF8.GetBytes(text))).ToLowerInvariant();

    /// <summary>Picks Digest over Basic from one or more WWW-Authenticate headers. Keys are lower case; "#scheme" holds the scheme.</summary>
    public static Dictionary<string, string>? PickChallenge(IEnumerable<string> values)
    {
        var parsed = values.Select(ParseChallenge).Where(p => p != null).Select(p => p!).ToList();
        return parsed.FirstOrDefault(p => p["#scheme"] == "digest") ?? parsed.FirstOrDefault(p => p["#scheme"] == "basic");
    }

    public static Dictionary<string, string>? ParseChallenge(string value)
    {
        var text = (value ?? "").Trim();
        var space = text.IndexOf(' ');
        var scheme = (space < 0 ? text : text[..space]).ToLowerInvariant();
        if (scheme is not ("digest" or "basic")) return null;
        var map = new Dictionary<string, string> { ["#scheme"] = scheme };
        if (space < 0) return map;
        var p = text[(space + 1)..];
        var i = 0;
        while (i < p.Length)
        {
            while (i < p.Length && (p[i] == ',' || p[i] == ' ')) i++;
            var eq = p.IndexOf('=', i);
            if (eq < 0) break;
            var key = p[i..eq].Trim().ToLowerInvariant();
            i = eq + 1;
            string v;
            if (i < p.Length && p[i] == '"')
            {
                var sb = new StringBuilder();
                i++;
                while (i < p.Length && p[i] != '"')
                {
                    if (p[i] == '\\' && i + 1 < p.Length) i++;
                    sb.Append(p[i]);
                    i++;
                }
                i++;
                v = sb.ToString();
            }
            else
            {
                var end = p.IndexOf(',', i);
                if (end < 0) end = p.Length;
                v = p[i..end].Trim();
                i = end;
            }
            map[key] = v;
        }
        return map;
    }

    public static string DigestHeader(Dictionary<string, string> c, string user, string password, string method, string uri, int nc, string cnonce)
    {
        var realm = c.GetValueOrDefault("realm", "");
        var nonce = c.GetValueOrDefault("nonce", "");
        var algorithm = c.GetValueOrDefault("algorithm", "");
        var qop = c.GetValueOrDefault("qop", "").Split(',').Select(q => q.Trim()).FirstOrDefault(q => q.Equals("auth", StringComparison.OrdinalIgnoreCase));
        var ha1 = Md5($"{user}:{realm}:{password}");
        if (algorithm.Equals("MD5-sess", StringComparison.OrdinalIgnoreCase)) ha1 = Md5($"{ha1}:{nonce}:{cnonce}");
        var ha2 = Md5($"{method}:{uri}");
        var ncText = nc.ToString("x8");
        var response = qop != null ? Md5($"{ha1}:{nonce}:{ncText}:{cnonce}:auth:{ha2}") : Md5($"{ha1}:{nonce}:{ha2}");
        var sb = new StringBuilder("Digest ");
        sb.Append($"username=\"{Quote(user)}\", realm=\"{Quote(realm)}\", nonce=\"{Quote(nonce)}\", uri=\"{Quote(uri)}\", response=\"{response}\"");
        if (algorithm.Length > 0) sb.Append(", algorithm=").Append(algorithm);
        if (c.TryGetValue("opaque", out var opaque)) sb.Append($", opaque=\"{Quote(opaque)}\"");
        if (qop != null) sb.Append($", qop=auth, nc={ncText}, cnonce=\"{cnonce}\"");
        return sb.ToString();
    }

    static string Quote(string value) => value.Replace("\\", "\\\\").Replace("\"", "\\\"");

    public static RtspTrack ParseSdpText(string sdp, string baseUrl)
    {
        var inVideo = false;
        var seenVideo = false;
        var sessionControl = "";
        var payloadType = -1;
        var codec = "";
        var control = "";
        var sprop = "";
        foreach (var raw in (sdp ?? "").Split('\n'))
        {
            var line = raw.Trim();
            if (line.StartsWith("m=", StringComparison.Ordinal))
            {
                if (seenVideo) break;
                inVideo = line.StartsWith("m=video", StringComparison.Ordinal);
                if (inVideo)
                {
                    seenVideo = true;
                    var parts = line.Split(' ');
                    payloadType = parts.Length > 3 && int.TryParse(parts[3], out var pt) ? pt : -1;
                }
                continue;
            }
            if (!seenVideo && line.StartsWith("a=control:", StringComparison.Ordinal)) sessionControl = line["a=control:".Length..].Trim();
            if (!inVideo) continue;
            if (line.StartsWith("a=control:", StringComparison.Ordinal)) control = line["a=control:".Length..].Trim();
            else if (line.StartsWith("a=rtpmap:", StringComparison.Ordinal))
            {
                var rest = line["a=rtpmap:".Length..];
                var space = rest.IndexOf(' ');
                if (space > 0 && (int.TryParse(rest[..space], out var pt) && pt == payloadType || codec.Length == 0))
                    codec = rest[(space + 1)..].Split('/')[0].Trim().ToUpperInvariant();
            }
            else if (line.StartsWith("a=fmtp:", StringComparison.Ordinal))
            {
                var m = Regex.Match(line, @"sprop-parameter-sets=([^;\s]+)");
                if (m.Success) sprop = m.Groups[1].Value;
            }
        }
        if (!seenVideo) throw new CameraException(CameraFailure.Protocol, "The camera stream has no video track.");
        if (codec.Length == 0) codec = payloadType == 26 ? "JPEG" : "H264";
        byte[]? sps = null, pps = null;
        foreach (var part in sprop.Split(',', StringSplitOptions.RemoveEmptyEntries))
        {
            try
            {
                var bytes = Convert.FromBase64String(part.Trim());
                if (bytes.Length == 0) continue;
                if ((bytes[0] & 0x1f) == 7) sps = bytes;
                if ((bytes[0] & 0x1f) == 8) pps = bytes;
            }
            catch (FormatException) { }
        }
        var playUrl = sessionControl.StartsWith("rtsp://", StringComparison.OrdinalIgnoreCase) ? sessionControl : baseUrl;
        return new RtspTrack(codec, payloadType, JoinControl(baseUrl, control), playUrl, sps, pps);
    }

    public static string JoinControl(string baseUrl, string control)
    {
        if (control.Length == 0 || control == "*") return baseUrl;
        if (control.StartsWith("rtsp://", StringComparison.OrdinalIgnoreCase) || control.StartsWith("rtsps://", StringComparison.OrdinalIgnoreCase)) return control;
        return baseUrl.TrimEnd('/') + "/" + control.TrimStart('/');
    }

    /// <summary>Checks the address answers DESCRIBE with H.264 video. Null when fine, otherwise what failed.</summary>
    public static CameraException? Probe(string bareUrl, string user, string password)
    {
        try
        {
            using var client = new RtspClient(bareUrl, user, password);
            client.Connect(6000, 8000);
            client.Describe();
            return null;
        }
        catch (CameraException error)
        {
            return error;
        }
        catch (Exception error)
        {
            return new CameraException(CameraFailure.Protocol, $"The camera stream could not be opened ({error.GetType().Name}).");
        }
    }
}

/// <summary>RTP H.264 depacketizer (single NAL, STAP-A, FU-A) that groups NAL units into access units.</summary>
sealed class RtpH264Assembler
{
    public sealed record AccessUnit(List<byte[]> Nals, bool Keyframe, long Timestamp);

    public byte[]? Sps { get; set; }
    public byte[]? Pps { get; set; }
    readonly List<byte[]> pending = new();
    long pendingTs = -1;
    MemoryStream? fragment;

    public List<AccessUnit> Push(byte[] rtp)
    {
        var output = new List<AccessUnit>();
        if (rtp.Length < 12 || (rtp[0] & 0xc0) != 0x80) return output;
        var padding = (rtp[0] & 0x20) != 0;
        var extension = (rtp[0] & 0x10) != 0;
        var csrc = rtp[0] & 0x0f;
        var marker = (rtp[1] & 0x80) != 0;
        long ts = ((long)rtp[4] << 24) | ((long)rtp[5] << 16) | ((long)rtp[6] << 8) | rtp[7];
        var offset = 12 + csrc * 4;
        if (extension)
        {
            if (rtp.Length < offset + 4) return output;
            var words = (rtp[offset + 2] << 8) | rtp[offset + 3];
            offset += 4 + words * 4;
        }
        var end = rtp.Length;
        if (padding && end > offset) end -= rtp[end - 1];
        if (end <= offset) return output;
        if (pendingTs >= 0 && ts != pendingTs && pending.Count > 0)
        {
            var unit = Flush();
            if (unit != null) output.Add(unit);
        }
        pendingTs = ts;
        var type = rtp[offset] & 0x1f;
        if (type is >= 1 and <= 23)
        {
            Add(rtp[offset..end]);
        }
        else if (type == 24)
        {
            var i = offset + 1;
            while (i + 2 <= end)
            {
                var size = (rtp[i] << 8) | rtp[i + 1];
                i += 2;
                if (size <= 0 || i + size > end) break;
                Add(rtp[i..(i + size)]);
                i += size;
            }
        }
        else if (type == 28 && end - offset >= 2)
        {
            var fu = rtp[offset + 1];
            if ((fu & 0x80) != 0)
            {
                fragment = new MemoryStream();
                fragment.WriteByte((byte)((rtp[offset] & 0xe0) | (fu & 0x1f)));
            }
            if (fragment != null)
            {
                fragment.Write(rtp, offset + 2, end - offset - 2);
                if ((fu & 0x40) != 0)
                {
                    Add(fragment.ToArray());
                    fragment = null;
                }
            }
        }
        if (marker)
        {
            var unit = Flush();
            if (unit != null) output.Add(unit);
        }
        return output;
    }

    void Add(byte[] nal)
    {
        if (nal.Length == 0) return;
        var t = nal[0] & 0x1f;
        if (t == 7) Sps = nal;
        if (t == 8) Pps = nal;
        pending.Add(nal);
    }

    AccessUnit? Flush()
    {
        if (pending.Count == 0) return null;
        var nals = pending.Where(n => (n[0] & 0x1f) is not (6 or 7 or 8 or 9)).ToList();
        var key = pending.Any(n => (n[0] & 0x1f) == 5);
        pending.Clear();
        return nals.Count == 0 ? null : new AccessUnit(nals, key, pendingTs);
    }

    public static byte[] Avcc(List<byte[]> nals)
    {
        using var ms = new MemoryStream();
        foreach (var nal in nals)
        {
            ms.WriteByte((byte)(nal.Length >> 24)); ms.WriteByte((byte)(nal.Length >> 16));
            ms.WriteByte((byte)(nal.Length >> 8)); ms.WriteByte((byte)nal.Length);
            ms.Write(nal);
        }
        return ms.ToArray();
    }
}
