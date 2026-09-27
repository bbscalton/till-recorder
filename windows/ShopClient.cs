using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;

namespace TillRecorder;

sealed class RemoteControl
{
    public bool Camera { get; init; }
    public bool Locked { get; init; }
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
        using var response = await http.SendAsync(request, cancel);
        if (!response.IsSuccessStatusCode) return null;
        using var document = JsonDocument.Parse(await response.Content.ReadAsStringAsync(cancel));
        var root = document.RootElement;
        return new RemoteControl
        {
            Camera = root.TryGetProperty("camera", out var camera) && camera.GetBoolean(),
            Locked = root.TryGetProperty("locked", out var locked) && locked.GetBoolean()
        };
    }

    public async Task<bool> UploadClip(AppSettings settings, string kind, long startedAtMs, long endedAtMs, string path, CancellationToken cancel)
    {
        using var form = new MultipartFormDataContent();
        form.Add(new StringContent(settings.DeviceId), "device_id");
        form.Add(new StringContent(Clean(settings.DeviceName)), "device_name");
        form.Add(new StringContent(startedAtMs.ToString()), "started_at_ms");
        form.Add(new StringContent(endedAtMs.ToString()), "ended_at_ms");
        form.Add(new StringContent(DateTimeOffset.FromUnixTimeMilliseconds(startedAtMs).ToLocalTime().ToString("yyyy-MM-dd")), "local_day");
        form.Add(new StringContent(kind == "camera" ? "camera" : "screen"), "kind");
        var bytes = await File.ReadAllBytesAsync(path, cancel);
        var file = new ByteArrayContent(bytes);
        file.Headers.ContentType = new MediaTypeHeaderValue("video/mp4");
        form.Add(file, "file", "segment.mp4");
        using var request = new HttpRequestMessage(HttpMethod.Post, $"{BaseUrl}/api/segments") { Content = form };
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", settings.Token);
        using var response = await http.SendAsync(request, cancel);
        return response.IsSuccessStatusCode;
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
        using var response = await http.SendAsync(request, cancel);
        return response.IsSuccessStatusCode;
    }

    public static string Clean(string name)
    {
        var chars = name.Where(ch => ch >= 0x20 && ch <= 0x7e && ch != '"').ToArray();
        var text = new string(chars).Trim();
        if (text.Length > 80) text = text[..80];
        return string.IsNullOrWhiteSpace(text) ? "Windows POS" : text;
    }
}
