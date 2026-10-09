using System.Text;
using System.Text.RegularExpressions;

namespace TillRecorder;

enum CameraKind { Rtsp, Onvif, Invalid }

sealed record CameraTarget(CameraKind Kind, string Url, string User, string Password, string Error = "");

/// <summary>scheme is lower case, port is -1 when absent, path keeps the query.</summary>
sealed record CameraUrl(string Scheme, string Host, int Port, string Path)
{
    public string HostText => Host.Contains(':') ? "[" + Host + "]" : Host;
    public string HostPort => Port > 0 ? HostText + ":" + Port : HostText;
    public override string ToString() => Scheme + "://" + HostPort + Path;
}

/// <summary>
/// Keeps the camera address and its username and password separate until the register opens the stream.
/// The password is never URL-parsed, so @ / ? # : % in it are fine.
/// </summary>
static class CameraAddress
{
    public const string OnvifPath = "/onvif/device_service";
    public const string BadAddress = "That address was not understood. Use the camera's IP address, an ONVIF address (http://IP/onvif/device_service) or an RTSP address (rtsp://IP:554/path).";
    static readonly int[] RtspPorts = { 554, 8554, 10554 };

    public static (string Bare, string User, string Password) Split(string raw)
    {
        var text = (raw ?? "").Trim();
        var schemeEnd = text.IndexOf("://", StringComparison.Ordinal);
        var prefix = schemeEnd >= 0 ? text[..(schemeEnd + 3)] : "";
        var rest = schemeEnd >= 0 ? text[(schemeEnd + 3)..] : text;
        var at = rest.LastIndexOf('@');
        if (at < 0) return (text, "", "");
        var info = rest[..at];
        var colon = info.IndexOf(':');
        var user = Decode(colon >= 0 ? info[..colon] : info);
        var password = colon >= 0 ? Decode(info[(colon + 1)..]) : "";
        return (prefix + rest[(at + 1)..], user, password);
    }

    public static string Bare(string raw) => Split(raw).Bare;

    public static string Embed(string bare, string user, string password)
    {
        var text = (bare ?? "").Trim();
        if (text.Length == 0 || string.IsNullOrEmpty(user)) return text;
        var schemeEnd = text.IndexOf("://", StringComparison.Ordinal);
        if (schemeEnd < 0) return text;
        var clean = Split(text).Bare;
        return clean[..(schemeEnd + 3)] + Encode(user) + ":" + Encode(password ?? "") + "@" + clean[(schemeEnd + 3)..];
    }

    public static string Label(string bare)
    {
        var host = Parse(Split(bare ?? "").Bare)?.Host ?? "";
        return host.Length <= 60 ? host : host[..60];
    }

    public static string Pull(AppSettings settings)
    {
        var split = Split(settings.Overhead ?? "");
        var user = string.IsNullOrEmpty(split.User) ? settings.OverheadUser ?? "" : split.User;
        var password = string.IsNullOrEmpty(split.Password) ? settings.OverheadPassword ?? "" : split.Password;
        return Embed(split.Bare, user, password);
    }

    public static CameraUrl? Parse(string text)
    {
        var trimmed = (text ?? "").Trim();
        var schemeEnd = trimmed.IndexOf("://", StringComparison.Ordinal);
        if (schemeEnd <= 0) return null;
        var scheme = trimmed[..schemeEnd].ToLowerInvariant();
        if (!scheme.All(c => char.IsLetterOrDigit(c) || c is '+' or '-' or '.')) return null;
        var rest = trimmed[(schemeEnd + 3)..];
        var hash = rest.IndexOf('#');
        if (hash >= 0) rest = rest[..hash];
        var pathStart = rest.IndexOfAny(new[] { '/', '?' });
        var authority = pathStart >= 0 ? rest[..pathStart] : rest;
        var path = pathStart >= 0 ? rest[pathStart..] : "";
        if (path.StartsWith('?')) path = "/" + path;
        if (authority.Contains('@')) return null;
        string host;
        var portText = "";
        if (authority.StartsWith('['))
        {
            var close = authority.IndexOf(']');
            if (close < 0) return null;
            host = authority[1..close];
            var after = authority[(close + 1)..];
            if (after.Length > 0)
            {
                if (!after.StartsWith(':')) return null;
                portText = after[1..];
            }
            if (host.Length == 0 || !host.All(c => char.IsAsciiLetterOrDigit(c) || c is ':' or '.' or '%')) return null;
        }
        else
        {
            var colon = authority.LastIndexOf(':');
            host = colon >= 0 ? authority[..colon] : authority;
            if (colon >= 0) portText = authority[(colon + 1)..];
            if (host.Length == 0 || !host.All(c => char.IsAsciiLetterOrDigit(c) || c is '.' or '-' or '_')) return null;
        }
        var port = -1;
        if (portText.Length > 0)
        {
            if (!int.TryParse(portText, System.Globalization.NumberStyles.None, null, out port) || port < 1 || port > 65535) return null;
        }
        return new CameraUrl(scheme, host, port, path);
    }

    /// <summary>
    /// Bare IP / host[:port] -> ONVIF device service (or RTSP for ports 554/8554), http(s) without a path -> /onvif/device_service,
    /// rtsp://[user:pass@]host[:port]/path?query -> RTSP. Username/password fields win over credentials in the address.
    /// </summary>
    public static CameraTarget Resolve(string raw, string fieldUser, string fieldPassword)
    {
        var parts = Split((raw ?? "").Trim());
        var user = string.IsNullOrEmpty(fieldUser?.Trim()) ? parts.User : fieldUser!.Trim();
        var password = string.IsNullOrEmpty(fieldPassword) ? parts.Password : fieldPassword;
        var text = parts.Bare.Trim().Replace("&amp;", "&");
        if (text.Length == 0) return Invalid("Enter the camera address.", user, password);
        if (!text.Contains("://"))
        {
            var guess = Parse("x://" + text);
            if (guess == null) return Invalid(BadAddress, user, password);
            text = RtspPorts.Contains(guess.Port)
                ? "rtsp://" + guess.HostPort + (guess.Path.Length == 0 ? "/" : guess.Path)
                : "http://" + guess.HostPort + (guess.Path.Length == 0 ? OnvifPath : guess.Path);
        }
        var url = Parse(text);
        if (url == null) return Invalid(BadAddress, user, password);
        switch (url.Scheme)
        {
            case "rtsp":
                return new CameraTarget(CameraKind.Rtsp, (url with { Path = url.Path.Length == 0 ? "/" : url.Path }).ToString(), user, password);
            case "rtsps":
                return Invalid("rtsps:// (encrypted RTSP) is not supported. Use the camera's rtsp:// address.", user, password);
            case "http":
            case "https":
                var path = url.Path.Length == 0 || url.Path == "/" ? OnvifPath : url.Path;
                return new CameraTarget(CameraKind.Onvif, (url with { Path = path }).ToString(), user, password);
            default:
                return Invalid(BadAddress, user, password);
        }
    }

    /// <summary>Sub stream address for the common Dahua and Hikvision path forms, or null.</summary>
    public static string? SubStream(string url)
    {
        var dahua = new Regex("([?&]subtype=)0(?=&|$)", RegexOptions.IgnoreCase);
        if (dahua.IsMatch(url)) return dahua.Replace(url, m => m.Groups[1].Value + "1");
        var hik = new Regex(@"(/Streaming/Channels/\d*?)01(?=[/?]|$)", RegexOptions.IgnoreCase);
        if (hik.IsMatch(url)) return hik.Replace(url, m => m.Groups[1].Value + "02");
        return null;
    }

    static CameraTarget Invalid(string message, string user, string password) => new(CameraKind.Invalid, "", user, password, message);

    public static string Encode(string value)
    {
        var sb = new StringBuilder();
        foreach (var b in Encoding.UTF8.GetBytes(value ?? ""))
        {
            var c = (char)b;
            if (char.IsAsciiLetterOrDigit(c) || c is '-' or '.' or '_' or '~') sb.Append(c);
            else sb.Append('%').Append(b.ToString("X2"));
        }
        return sb.ToString();
    }

    /// <summary>Decodes %XX sequences only; a lone % or + stays as typed.</summary>
    public static string Decode(string value)
    {
        if (!value.Contains('%')) return value;
        var bytes = new List<byte>();
        for (var i = 0; i < value.Length; i++)
        {
            if (value[i] == '%' && i + 2 < value.Length && Uri.IsHexDigit(value[i + 1]) && Uri.IsHexDigit(value[i + 2]))
            {
                bytes.Add(Convert.ToByte(value.Substring(i + 1, 2), 16));
                i += 2;
            }
            else
            {
                bytes.AddRange(Encoding.UTF8.GetBytes(value[i].ToString()));
            }
        }
        return Encoding.UTF8.GetString(bytes.ToArray());
    }
}
