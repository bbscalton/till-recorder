using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;

namespace TillRecorder;

sealed class RemoteControl
{
    public bool Camera { get; init; }
    public bool Locked { get; init; }
    public string Overhead { get; init; } = "";
    public bool Scan { get; init; }
    public bool Record { get; init; } = true;
    public bool Removed { get; init; }
}

sealed class ShopClient : IDisposable
{
    public const string BaseUrl = "https://till-recorder.neuereatec.workers.dev";
    readonly HttpClient http = new() { Timeout = TimeSpan.FromMinutes(2) };

    public void Dispose() => http.Dispose();

    public async Task<string?> ClaimPair(string deviceId, string deviceName, string code, CancellationToken cancel)
    {
        var payload = JsonSerializer.Serialize(new
        {
            device_id = deviceId,
            device_name = Clean(deviceName),
            code
        });
        using var response = await http.PostAsync($"{BaseUrl}/api/pair", new StringContent(payload, Encoding.UTF8, "application/json"), cancel);
        if (!response.IsSuccessStatusCode) return null;
        using var document = JsonDocument.Parse(await response.Content.ReadAsStringAsync(cancel));
        var token = document.RootElement.TryGetProperty("token", out var value) ? value.GetString() : null;
        return string.IsNullOrWhiteSpace(token) ? null : token;
    }

    public async Task<bool> CheckToken(string token, CancellationToken cancel)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, $"{BaseUrl}/api/check-token");
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
        using var response = await http.SendAsync(request, cancel);
        return response.IsSuccessStatusCode;
    }

    public async Task<RemoteControl?> Heartbeat(AppSettings settings, bool recording, CancellationToken cancel)
    {
        try
        {
            var payload = JsonSerializer.Serialize(new
            {
                device_id = settings.DeviceId,
                device_name = Clean(settings.DeviceName),
                recording
            });
            using var request = new HttpRequestMessage(HttpMethod.Post, $"{BaseUrl}/api/heartbeat")
            {
                Content = new StringContent(payload, Encoding.UTF8, "application/json")
            };
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", settings.Token);
            using var limit = CancellationTokenSource.CreateLinkedTokenSource(cancel);
            limit.CancelAfter(TimeSpan.FromSeconds(20));
            using var response = await http.SendAsync(request, limit.Token);
            if (!response.IsSuccessStatusCode)
            {
                AppLog.Write("heartbeat http " + (int)response.StatusCode);
                return null;
            }
            using var document = JsonDocument.Parse(await response.Content.ReadAsStringAsync(limit.Token));
            var root = document.RootElement;
            return new RemoteControl
            {
                Camera = root.TryGetProperty("camera", out var camera) && camera.GetBoolean(),
                Locked = root.TryGetProperty("locked", out var locked) && locked.GetBoolean(),
                Overhead = root.TryGetProperty("overhead", out var overhead) ? overhead.GetString() ?? "" : "",
                Scan = root.TryGetProperty("scan", out var scan) && scan.GetBoolean(),
                Record = !root.TryGetProperty("record", out var record) || record.ValueKind != JsonValueKind.False,
                Removed = root.TryGetProperty("removed", out var removed) && removed.ValueKind == JsonValueKind.True
            };
        }
        catch (Exception error) when (!cancel.IsCancellationRequested)
        {
            AppLog.Write("heartbeat " + error.GetType().Name);
            return null;
        }
    }

    public async Task<bool> UploadClip(AppSettings settings, string kind, long startedAtMs, long endedAtMs, string path, CancellationToken cancel)
    {
        var bytes = await File.ReadAllBytesAsync(path, cancel);
        var boundary = "TillRecorder" + Guid.NewGuid().ToString("N");
        var body = Multipart(boundary, new (string, string?, byte[])[]
        {
            ("device_id", null, Encoding.UTF8.GetBytes(settings.DeviceId)),
            ("device_name", null, Encoding.UTF8.GetBytes(Clean(settings.DeviceName))),
            ("started_at_ms", null, Encoding.UTF8.GetBytes(startedAtMs.ToString())),
            ("ended_at_ms", null, Encoding.UTF8.GetBytes(endedAtMs.ToString())),
            ("local_day", null, Encoding.UTF8.GetBytes(DateTimeOffset.FromUnixTimeMilliseconds(startedAtMs).ToLocalTime().ToString("yyyy-MM-dd"))),
            ("kind", null, Encoding.UTF8.GetBytes(kind == "camera" || kind == "overhead" ? kind : "screen")),
            ("file", "segment.mp4", bytes)
        });
        using var content = new ByteArrayContent(body);
        content.Headers.ContentType = MediaTypeHeaderValue.Parse($"multipart/form-data; boundary={boundary}");
        using var request = new HttpRequestMessage(HttpMethod.Post, $"{BaseUrl}/api/segments") { Content = content };
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", settings.Token);
        try
        {
            using var response = await http.SendAsync(request, cancel);
            if (!response.IsSuccessStatusCode)
            {
                var detail = await response.Content.ReadAsStringAsync(cancel);
                if (detail.Length > 80) detail = detail[..80];
                AppLog.Write($"clip {kind} http {(int)response.StatusCode} {detail}");
            }
            else AppLog.Write($"clip {kind} stored {bytes.Length}");
            return response.IsSuccessStatusCode;
        }
        catch (Exception error) when (!cancel.IsCancellationRequested)
        {
            AppLog.Write($"clip {kind} {error.GetType().Name}");
            return false;
        }
    }

    public async Task<bool> UploadInputs(AppSettings settings, IReadOnlyList<(long At, string Text)> entries, CancellationToken cancel)
    {
        if (entries.Count == 0) return true;
        var payload = JsonSerializer.Serialize(new
        {
            device_id = settings.DeviceId,
            device_name = Clean(settings.DeviceName),
            local_day = DateTime.Now.ToString("yyyy-MM-dd"),
            entries = entries.Select(entry => new { at = entry.At, text = entry.Text })
        });
        using var request = new HttpRequestMessage(HttpMethod.Post, $"{BaseUrl}/api/inputs")
        {
            Content = new StringContent(payload, Encoding.UTF8, "application/json")
        };
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", settings.Token);
        try
        {
            using var response = await http.SendAsync(request, cancel);
            if (!response.IsSuccessStatusCode) AppLog.Write("inputs http " + (int)response.StatusCode);
            else AppLog.Write("inputs stored " + entries.Count);
            return response.IsSuccessStatusCode;
        }
        catch (Exception error) when (!cancel.IsCancellationRequested)
        {
            AppLog.Write("inputs " + error.GetType().Name);
            return false;
        }
    }

    public async Task<bool> PublishOverhead(AppSettings settings, bool attached, string label, CancellationToken cancel)
    {
        var payload = JsonSerializer.Serialize(new
        {
            device_id = settings.DeviceId,
            attached,
            label = label ?? ""
        });
        using var request = new HttpRequestMessage(HttpMethod.Post, $"{BaseUrl}/api/overhead")
        {
            Content = new StringContent(payload, Encoding.UTF8, "application/json")
        };
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", settings.Token);
        try
        {
            using var response = await http.SendAsync(request, cancel);
            if (!response.IsSuccessStatusCode) AppLog.Write("overhead save http " + (int)response.StatusCode);
            return response.IsSuccessStatusCode;
        }
        catch (Exception error) when (!cancel.IsCancellationRequested)
        {
            AppLog.Write("overhead save " + error.GetType().Name);
            return false;
        }
    }

    public async Task ReportCameras(AppSettings settings, IReadOnlyList<(string Name, string Host, string Url)> cameras, CancellationToken cancel)
    {
        var payload = JsonSerializer.Serialize(new
        {
            device_id = settings.DeviceId,
            cameras = cameras.Select(item => new { name = item.Name, host = item.Host, url = item.Url })
        });
        using var request = new HttpRequestMessage(HttpMethod.Post, $"{BaseUrl}/api/overhead-found")
        {
            Content = new StringContent(payload, Encoding.UTF8, "application/json")
        };
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", settings.Token);
        try
        {
            using var response = await http.SendAsync(request, cancel);
            if (!response.IsSuccessStatusCode) AppLog.Write("overhead found http " + (int)response.StatusCode);
        }
        catch (Exception error) when (!cancel.IsCancellationRequested)
        {
            AppLog.Write("overhead found " + error.GetType().Name);
        }
    }

    public async Task<bool> UploadStream(AppSettings settings, string kind, int sequence, byte[] bytes, string codec, CancellationToken cancel)
    {
        using var request = new HttpRequestMessage(HttpMethod.Post, $"{BaseUrl}/api/stream")
        {
            Content = new ByteArrayContent(bytes)
        };
        request.Content.Headers.ContentType = new MediaTypeHeaderValue("video/mp4");
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", settings.Token);
        request.Headers.TryAddWithoutValidation("X-Device-Id", settings.DeviceId);
        request.Headers.TryAddWithoutValidation("X-Stream-Kind", kind);
        request.Headers.TryAddWithoutValidation("X-Stream-Seq", sequence.ToString());
        request.Headers.TryAddWithoutValidation("X-Stream-Codec", codec);
        request.Headers.ConnectionClose = false;
        try
        {
            using var response = await http.SendAsync(request, cancel);
            if (sequence <= 1 || !response.IsSuccessStatusCode)
                AppLog.Write($"live {kind} seq {sequence} http {(int)response.StatusCode} bytes {bytes.Length}");
            return response.IsSuccessStatusCode;
        }
        catch (Exception error) when (error is not OperationCanceledException || !cancel.IsCancellationRequested)
        {
            AppLog.Write($"live {kind} seq {sequence} {error.GetType().Name}");
            return false;
        }
    }

    static byte[] Multipart(string boundary, IReadOnlyList<(string Name, string? FileName, byte[] Body)> parts)
    {
        using var stream = new MemoryStream();
        foreach (var part in parts)
        {
            var header = part.FileName == null
                ? $"--{boundary}\r\nContent-Disposition: form-data; name=\"{part.Name}\"\r\n\r\n"
                : $"--{boundary}\r\nContent-Disposition: form-data; name=\"{part.Name}\"; filename=\"{part.FileName}\"\r\nContent-Type: video/mp4\r\n\r\n";
            stream.Write(Encoding.UTF8.GetBytes(header));
            stream.Write(part.Body);
            stream.Write(Encoding.UTF8.GetBytes("\r\n"));
        }
        stream.Write(Encoding.UTF8.GetBytes($"--{boundary}--\r\n"));
        return stream.ToArray();
    }

    public static string Clean(string name)
    {
        var chars = name.Where(ch => ch >= 0x20 && ch <= 0x7e && ch != '"').ToArray();
        var text = new string(chars).Trim();
        if (text.Length > 80) text = text[..80];
        return string.IsNullOrWhiteSpace(text) ? "Windows POS" : text;
    }
}
