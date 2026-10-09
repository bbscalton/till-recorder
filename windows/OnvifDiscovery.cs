using System.Net;
using System.Text;
using System.Text.RegularExpressions;

namespace TillRecorder;

sealed record OnvifProfile(string Token, string Encoding, int Width, int Height);

/// <summary>WS-Discovery for the Find cameras button, and ONVIF stream lookup.</summary>
static class OnvifDiscovery
{
    public static string ProbeMessage() =>
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<e:Envelope xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:w=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\" xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\" xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\">" +
        "<e:Header><w:MessageID>uuid:" + Guid.NewGuid() + "</w:MessageID><w:To e:mustUnderstand=\"true\">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action e:mustUnderstand=\"true\">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header>" +
        "<e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>";

    public static List<(string Name, string Host, string Url)> Probe()
    {
        var found = new List<(string Name, string Host, string Url)>();
        try
        {
            using var udp = new System.Net.Sockets.UdpClient();
            udp.Client.ReceiveTimeout = 1500;
            var bytes = Encoding.UTF8.GetBytes(ProbeMessage());
            udp.Send(bytes, bytes.Length, "239.255.255.250", 3702);
            var until = DateTime.UtcNow.AddSeconds(3);
            while (DateTime.UtcNow < until)
            {
                try
                {
                    var remote = new IPEndPoint(IPAddress.Any, 0);
                    var data = udp.Receive(ref remote);
                    var camera = CameraFromReply(Encoding.UTF8.GetString(data), remote.Address.ToString());
                    if (camera != null && found.All(item => item.Host != camera.Value.Host)) found.Add(camera.Value);
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

    /// <summary>Picks the IPv4 http XAddr from a ProbeMatch (XAddrs is a space separated list that may start with IPv6).</summary>
    public static (string Name, string Host, string Url)? CameraFromReply(string text, string sender)
    {
        if (!text.Contains("ProbeMatch", StringComparison.Ordinal)) return null;
        var m = Regex.Match(text, "XAddrs>([^<]+)<");
        var xaddrs = (m.Success ? m.Groups[1].Value : "").Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries)
            .Where(x => x.StartsWith("http://", StringComparison.OrdinalIgnoreCase) || x.StartsWith("https://", StringComparison.OrdinalIgnoreCase)).ToList();
        var chosen = xaddrs.FirstOrDefault(x => IsIpv4(CameraAddress.Parse(x)?.Host ?? ""))
            ?? xaddrs.FirstOrDefault(x => CameraAddress.Parse(x)?.Host == sender);
        if (chosen == null)
        {
            if (!IsIpv4(sender)) return null;
            chosen = "http://" + sender + CameraAddress.OnvifPath;
        }
        var host = CameraAddress.Parse(chosen)?.Host;
        return host == null ? null : ("Camera", host, chosen);
    }

    static bool IsIpv4(string host) => Regex.IsMatch(host, @"^\d{1,3}(\.\d{1,3}){3}$");

    /// <summary>
    /// Asks the ONVIF device for RTSP stream addresses: H.264 profiles first, then the sub stream, then the rest.
    /// Returns the addresses without credentials, or throws CameraException saying what failed.
    /// </summary>
    public static List<string> Streams(string device, string user, string password)
    {
        var where = CameraAddress.Parse(device)?.HostPort ?? device;
        var handler = new HttpClientHandler { AllowAutoRedirect = true };
        if (!string.IsNullOrEmpty(user)) handler.Credentials = new NetworkCredential(user, password ?? "");
        using var http = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(8) };
        var offset = ClockOffset(http, device, where);
        var caps = Post(http, device, Envelope(user, password, offset, "<GetCapabilities xmlns=\"http://www.onvif.org/ver10/device/wsdl\"><Category>Media</Category></GetCapabilities>"), where);
        if (caps.Status == 401 || IsNotAuthorized(caps.Text)) throw AuthError(where, user);
        if (caps.Status == 404)
            throw new CameraException(CameraFailure.NotFound, $"No ONVIF service at {where} (HTTP 404). Check the ONVIF port (often 80, 8000, 8080 or 8899) and that ONVIF is turned on in the camera.");
        var media = SameHost(caps.Ok ? MediaXAddr(caps.Text) ?? device : device, device);
        var profilesBody = "<GetProfiles xmlns=\"http://www.onvif.org/ver10/media/wsdl\"/>";
        var profilesReply = Post(http, media, Envelope(user, password, offset, profilesBody), where);
        if (!profilesReply.Ok && media != device)
        {
            media = device;
            profilesReply = Post(http, media, Envelope(user, password, offset, profilesBody), where);
        }
        if (profilesReply.Status == 401 || IsNotAuthorized(profilesReply.Text)) throw AuthError(where, user);
        var ordered = Order(Profiles(profilesReply.Text));
        if (ordered.Count == 0)
            throw new CameraException(CameraFailure.Protocol, $"The camera at {where} answered ONVIF but listed no video profiles (HTTP {profilesReply.Status}).");
        var urls = new List<string>();
        foreach (var profile in ordered)
        {
            var body = "<GetStreamUri xmlns=\"http://www.onvif.org/ver10/media/wsdl\"><StreamSetup><Stream xmlns=\"http://www.onvif.org/ver10/schema\">RTP-Unicast</Stream><Transport xmlns=\"http://www.onvif.org/ver10/schema\"><Protocol>RTSP</Protocol></Transport></StreamSetup><ProfileToken>" + System.Security.SecurityElement.Escape(profile.Token) + "</ProfileToken></GetStreamUri>";
            var reply = Post(http, media, Envelope(user, password, offset, body), where);
            if (reply.Status == 401 || IsNotAuthorized(reply.Text)) throw AuthError(where, user);
            var uri = StreamUri(reply.Text);
            if (uri == null) continue;
            var bare = SameHost(CameraAddress.Bare(uri), device);
            if (!urls.Contains(bare)) urls.Add(bare);
        }
        if (urls.Count == 0) throw new CameraException(CameraFailure.Protocol, $"The camera at {where} did not give an RTSP stream address over ONVIF.");
        return urls;
    }

    static CameraException AuthError(string where, string user) => new(CameraFailure.Auth,
        string.IsNullOrEmpty(user)
            ? $"The camera at {where} needs a username and password for ONVIF."
            : $"The camera at {where} rejected the username or password (ONVIF). Check them, and that the camera clock is right.");

    /// <summary>Cameras sometimes report 0.0.0.0 or localhost; keep the host the user reached.</summary>
    public static string SameHost(string address, string device)
    {
        var reported = CameraAddress.Parse(address);
        var reached = CameraAddress.Parse(device);
        if (reported == null || reached == null) return address;
        var bogus = reported.Host is "0.0.0.0" or "127.0.0.1" || reported.Host.Equals("localhost", StringComparison.OrdinalIgnoreCase);
        return bogus ? (reported with { Host = reached.Host }).ToString() : address;
    }

    public static List<OnvifProfile> Profiles(string xml)
    {
        var result = new List<OnvifProfile>();
        var starts = Regex.Matches(xml ?? "", @"<(?:[\w-]+:)?Profiles\b([^>]*)>");
        for (var i = 0; i < starts.Count; i++)
        {
            var token = Regex.Match(starts[i].Groups[1].Value, "token=\"([^\"]+)\"");
            if (!token.Success) continue;
            var from = starts[i].Index + starts[i].Length;
            var to = i + 1 < starts.Count ? starts[i + 1].Index : xml!.Length;
            var chunk = xml!.Substring(from, to - from);
            var encoder = Regex.Match(chunk, @"<(?:[\w-]+:)?VideoEncoderConfiguration\b.*?</(?:[\w-]+:)?VideoEncoderConfiguration>", RegexOptions.Singleline).Value;
            var encoding = Regex.Match(encoder, @"<(?:[\w-]+:)?Encoding>\s*([^<\s]+)\s*<").Groups[1].Value.ToUpperInvariant();
            _ = int.TryParse(Regex.Match(encoder, @"<(?:[\w-]+:)?Width>(\d+)<").Groups[1].Value, out var width);
            _ = int.TryParse(Regex.Match(encoder, @"<(?:[\w-]+:)?Height>(\d+)<").Groups[1].Value, out var height);
            result.Add(new OnvifProfile(Unescape(token.Groups[1].Value), encoding, width, height));
        }
        return result;
    }

    /// <summary>H.264 first (main before sub), then the sub stream, then the rest.</summary>
    public static List<OnvifProfile> Order(List<OnvifProfile> profiles)
    {
        var h264 = profiles.Where(p => p.Encoding == "H264");
        var unknown = profiles.Where(p => p.Encoding.Length == 0);
        var rest = profiles.Where(p => p.Encoding.Length > 0 && p.Encoding != "H264").ToList();
        return h264.Concat(unknown).Concat(rest.Skip(1).Take(1)).Concat(rest).Distinct().ToList();
    }

    public static string? StreamUri(string xml)
    {
        var m = Regex.Match(xml ?? "", @"<(?:[\w-]+:)?Uri>\s*([^<]+?)\s*</");
        if (!m.Success) return null;
        var uri = Unescape(m.Groups[1].Value);
        return uri.StartsWith("rtsp://", StringComparison.OrdinalIgnoreCase) ? uri : null;
    }

    public static string? MediaXAddr(string xml)
    {
        var m = Regex.Match(xml ?? "", @"<(?:[\w-]+:)?Media>.*?<(?:[\w-]+:)?XAddr>\s*([^<\s]+)\s*<", RegexOptions.Singleline);
        return m.Success ? Unescape(m.Groups[1].Value) : null;
    }

    public static bool IsNotAuthorized(string xml) =>
        (xml ?? "").Contains("NotAuthorized", StringComparison.OrdinalIgnoreCase) ||
        (xml ?? "").Contains("FailedAuthentication", StringComparison.OrdinalIgnoreCase) ||
        (xml ?? "").Contains("Sender not Authorized", StringComparison.OrdinalIgnoreCase);

    public static DateTime? CameraUtc(string xml)
    {
        var utc = Regex.Match(xml ?? "", @"<(?:[\w-]+:)?UTCDateTime>(.*?)</(?:[\w-]+:)?UTCDateTime>", RegexOptions.Singleline);
        if (!utc.Success) return null;
        int Field(string name, int fallback)
        {
            var m = Regex.Match(utc.Groups[1].Value, $@"<(?:[\w-]+:)?{name}>(\d+)<");
            return m.Success ? int.Parse(m.Groups[1].Value) : fallback;
        }
        var year = Field("Year", 0);
        if (year < 2000) return null;
        try { return new DateTime(year, Field("Month", 1), Field("Day", 1), Field("Hour", 0), Field("Minute", 0), Field("Second", 0), DateTimeKind.Utc); }
        catch { return null; }
    }

    public static string Unescape(string value) => value.Replace("&lt;", "<").Replace("&gt;", ">").Replace("&quot;", "\"")
        .Replace("&apos;", "'").Replace("&#38;", "&").Replace("&amp;", "&");

    static TimeSpan ClockOffset(HttpClient http, string device, string where)
    {
        var reply = Post(http, device, Envelope("", "", TimeSpan.Zero, "<GetSystemDateAndTime xmlns=\"http://www.onvif.org/ver10/device/wsdl\"/>"), where);
        var camera = CameraUtc(reply.Text);
        if (camera == null) return TimeSpan.Zero;
        var offset = camera.Value - DateTime.UtcNow;
        return Math.Abs(offset.TotalSeconds) > 5 ? offset : TimeSpan.Zero;
    }

    static string Envelope(string user, string? password, TimeSpan offset, string body)
    {
        var nonce = System.Security.Cryptography.RandomNumberGenerator.GetBytes(16);
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\"><s:Header>" +
            Security(user, password, DateTime.UtcNow + offset, nonce) + "</s:Header><s:Body>" + body + "</s:Body></s:Envelope>";
    }

    public static string Security(string user, string? password, DateTime nowUtc, byte[] nonce)
    {
        if (string.IsNullOrEmpty(user)) return "";
        var created = nowUtc.ToString("yyyy-MM-ddTHH:mm:ssZ", System.Globalization.CultureInfo.InvariantCulture);
        var digest = PasswordDigest(nonce, created, password ?? "");
        var name = System.Security.SecurityElement.Escape(user);
        return "<Security s:mustUnderstand=\"1\" xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\"><UsernameToken><Username>" + name + "</Username><Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">" + digest + "</Password><Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary\">" + Convert.ToBase64String(nonce) + "</Nonce><Created xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">" + created + "</Created></UsernameToken></Security>";
    }

    /// <summary>WS-Security UsernameToken digest: Base64(SHA1(nonce + created + password)).</summary>
    public static string PasswordDigest(byte[] nonce, string created, string password)
    {
        var createdBytes = Encoding.UTF8.GetBytes(created);
        var passBytes = Encoding.UTF8.GetBytes(password);
        var mix = new byte[nonce.Length + createdBytes.Length + passBytes.Length];
        Buffer.BlockCopy(nonce, 0, mix, 0, nonce.Length);
        Buffer.BlockCopy(createdBytes, 0, mix, nonce.Length, createdBytes.Length);
        Buffer.BlockCopy(passBytes, 0, mix, nonce.Length + createdBytes.Length, passBytes.Length);
        return Convert.ToBase64String(System.Security.Cryptography.SHA1.HashData(mix));
    }

    readonly record struct Reply(int Status, string Text)
    {
        public bool Ok => Status is >= 200 and <= 299;
    }

    static Reply Post(HttpClient http, string address, string xml, string where)
    {
        try
        {
            using var content = new StringContent(xml, Encoding.UTF8, "application/soap+xml");
            using var response = http.PostAsync(address, content).GetAwaiter().GetResult();
            return new Reply((int)response.StatusCode, response.Content.ReadAsStringAsync().GetAwaiter().GetResult());
        }
        catch (TaskCanceledException)
        {
            throw new CameraException(CameraFailure.Unreachable, $"The camera at {where} did not answer ONVIF (timed out). Check the IP address and ONVIF port.");
        }
        catch (HttpRequestException error) when (error.InnerException is System.Net.Sockets.SocketException socket)
        {
            throw new CameraException(CameraFailure.Unreachable, socket.SocketErrorCode switch
            {
                System.Net.Sockets.SocketError.ConnectionRefused => $"The camera at {where} refused the ONVIF connection. Check the ONVIF port (often 80, 8000, 8080 or 8899).",
                System.Net.Sockets.SocketError.HostNotFound => $"The camera name {where} could not be found on this network.",
                _ => $"Could not reach the camera's ONVIF service at {where}.",
            });
        }
        catch (HttpRequestException)
        {
            throw new CameraException(CameraFailure.Unreachable, $"Could not reach the camera's ONVIF service at {where}.");
        }
    }
}

/// <summary>
/// Checks a camera before it is saved: ONVIF addresses become an RTSP stream (H.264 profile first, then the sub stream),
/// and the RTSP stream must answer DESCRIBE with H.264 video.
/// </summary>
static class CameraSetup
{
    public static (string Url, string? Error) Check(CameraTarget target)
    {
        switch (target.Kind)
        {
            case CameraKind.Invalid:
                return ("", target.Error);
            case CameraKind.Rtsp:
            {
                var error = RtspClient.Probe(target.Url, target.User, target.Password);
                if (error == null) return (target.Url, null);
                if (error.Kind == CameraFailure.Codec)
                {
                    var sub = CameraAddress.SubStream(target.Url);
                    if (sub != null && RtspClient.Probe(sub, target.User, target.Password) == null) return (sub, null);
                }
                return ("", error.Message);
            }
            default:
            {
                List<string> urls;
                try { urls = OnvifDiscovery.Streams(target.Url, target.User, target.Password); }
                catch (CameraException error) { return ("", error.Message); }
                catch (Exception error) { return ("", $"ONVIF lookup failed ({error.GetType().Name})."); }
                CameraException? first = null;
                foreach (var url in urls)
                {
                    var error = RtspClient.Probe(url, target.User, target.Password);
                    if (error == null) return (url, null);
                    if (first == null || (error.Kind == CameraFailure.Codec && first.Kind != CameraFailure.Codec)) first = error;
                    // A rejected login will not get better on the next profile; stop to avoid locking the account.
                    if (error.Kind is CameraFailure.Auth or CameraFailure.Unreachable) break;
                }
                return ("", first?.Message ?? "The camera did not give a playable stream.");
            }
        }
    }
}
