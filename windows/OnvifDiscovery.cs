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

    /// <summary>When the WS-Discovery probe is (re)sent, in ms from the start, and how long replies are collected.</summary>
    static readonly long[] ProbeRounds = { 0, 800, 1800 };
    const long ListenMs = 4000;

    /// <summary>
    /// WS-Discovery Probe to 239.255.255.250:3702, sent from every active IPv4 interface (three rounds, same MessageID),
    /// collecting every ProbeMatch for four seconds. Multicast stays on each local subnet: cameras behind a router
    /// on another subnet do not hear it, and are added by typing their IP address.
    /// </summary>
    public static List<(string Name, string Host, string Url)> Probe()
    {
        var found = new DiscoveryResults();
        var sockets = new List<System.Net.Sockets.UdpClient>();
        try
        {
            foreach (var local in MulticastAddresses())
            {
                try
                {
                    var udp = new System.Net.Sockets.UdpClient(new IPEndPoint(local, 0));
                    udp.Client.SetSocketOption(System.Net.Sockets.SocketOptionLevel.IP, System.Net.Sockets.SocketOptionName.MulticastInterface, local.GetAddressBytes());
                    sockets.Add(udp);
                }
                catch (Exception error)
                {
                    AppLog.Write("onvif bind " + error.GetType().Name);
                }
            }
            if (sockets.Count == 0) sockets.Add(new System.Net.Sockets.UdpClient());
            var bytes = Encoding.UTF8.GetBytes(ProbeMessage());
            var group = new IPEndPoint(IPAddress.Parse("239.255.255.250"), 3702);
            var started = Environment.TickCount64;
            var round = 0;
            while (Environment.TickCount64 - started < ListenMs)
            {
                if (round < ProbeRounds.Length && Environment.TickCount64 - started >= ProbeRounds[round])
                {
                    foreach (var udp in sockets)
                    {
                        try { udp.Send(bytes, bytes.Length, group); }
                        catch (Exception error) { AppLog.Write("onvif send " + error.GetType().Name); }
                    }
                    round++;
                }
                var idle = true;
                foreach (var udp in sockets)
                {
                    try
                    {
                        while (udp.Available > 0)
                        {
                            idle = false;
                            var remote = new IPEndPoint(IPAddress.Any, 0);
                            var data = udp.Receive(ref remote);
                            found.Add(Encoding.UTF8.GetString(data), remote.Address.ToString());
                        }
                    }
                    catch (Exception error)
                    {
                        AppLog.Write("onvif receive " + error.GetType().Name);
                    }
                }
                if (idle) Thread.Sleep(40);
            }
            AppLog.Write($"onvif probe interfaces {sockets.Count} cameras {found.Cameras.Count}");
        }
        catch (Exception error)
        {
            AppLog.Write("onvif " + error.GetType().Name);
        }
        finally
        {
            foreach (var udp in sockets) udp.Dispose();
        }
        return found.Cameras;
    }

    /// <summary>IPv4 addresses of interfaces that are up and can multicast (no loopback, no 169.254 link-local).</summary>
    public static List<IPAddress> MulticastAddresses()
    {
        var result = new List<IPAddress>();
        try
        {
            foreach (var nic in System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces())
            {
                if (nic.OperationalStatus != System.Net.NetworkInformation.OperationalStatus.Up) continue;
                if (nic.NetworkInterfaceType == System.Net.NetworkInformation.NetworkInterfaceType.Loopback) continue;
                if (!nic.SupportsMulticast) continue;
                foreach (var unicast in nic.GetIPProperties().UnicastAddresses)
                {
                    var address = unicast.Address;
                    if (address.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork) continue;
                    var b = address.GetAddressBytes();
                    if (b[0] == 127 || (b[0] == 169 && b[1] == 254)) continue;
                    if (!result.Contains(address)) result.Add(address);
                }
            }
        }
        catch (Exception error)
        {
            AppLog.Write("onvif interfaces " + error.GetType().Name);
        }
        return result;
    }

    /// <summary>Model (or name) from the ONVIF scopes in a ProbeMatch, e.g. onvif://www.onvif.org/hardware/IPC-HDW1230S.</summary>
    public static string ModelFromReply(string text)
    {
        var scopes = Regex.Match(text ?? "", @"Scopes[^>]*>([^<]+)<").Groups[1].Value
            .Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);
        string Scope(string kind)
        {
            var prefix = "onvif://www.onvif.org/" + kind + "/";
            var hit = scopes.FirstOrDefault(s => s.StartsWith(prefix, StringComparison.OrdinalIgnoreCase));
            return hit == null ? "" : Uri.UnescapeDataString(hit[prefix.Length..]).Replace('_', ' ').Trim();
        }
        var hardware = Scope("hardware");
        var name = Scope("name");
        if (hardware.Length > 0 && name.Length > 0 && !name.Equals(hardware, StringComparison.OrdinalIgnoreCase))
            return name + " " + hardware;
        return hardware.Length > 0 ? hardware : name;
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
        if (host == null) return null;
        var model = ModelFromReply(text);
        return (model.Length > 0 ? model : "Camera", host, chosen);
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
/// Every camera that answered the probe, once each, keyed by the device's own address (its XAddr host).
/// Repeated probes and replies on several interfaces collapse; different cameras never do, even when cloned
/// firmware reports the same WS-Discovery endpoint id.
/// </summary>
sealed class DiscoveryResults
{
    readonly HashSet<string> keys = new(StringComparer.OrdinalIgnoreCase);
    public List<(string Name, string Host, string Url)> Cameras { get; } = new();

    public bool Add(string reply, string sender)
    {
        var camera = OnvifDiscovery.CameraFromReply(reply, sender);
        if (camera == null) return false;
        if (!keys.Add(camera.Value.Host)) return false;
        Cameras.Add(camera.Value);
        Cameras.Sort((a, b) => CompareHosts(a.Host, b.Host));
        return true;
    }

    static int CompareHosts(string a, string b)
    {
        if (IPAddress.TryParse(a, out var x) && IPAddress.TryParse(b, out var y))
        {
            var xb = x.GetAddressBytes();
            var yb = y.GetAddressBytes();
            if (xb.Length == yb.Length)
                for (var i = 0; i < xb.Length; i++)
                    if (xb[i] != yb[i]) return xb[i].CompareTo(yb[i]);
        }
        return string.CompareOrdinal(a, b);
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
