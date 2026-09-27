using System.Text.Json;

namespace TillRecorder;

sealed class AppSettings
{
    public string DeviceId { get; set; } = "";
    public string DeviceName { get; set; } = "";
    public string Token { get; set; } = "";
    public bool Armed { get; set; }

    public bool Paired => DeviceId.Length >= 8 && !string.IsNullOrWhiteSpace(DeviceName) && !string.IsNullOrWhiteSpace(Token);

    public static string Folder
    {
        get
        {
            var path = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "TillRecorder");
            Directory.CreateDirectory(path);
            return path;
        }
    }

    public static string PendingFolder
    {
        get
        {
            var path = Path.Combine(Folder, "pending");
            Directory.CreateDirectory(path);
            return path;
        }
    }

    static string PathName => Path.Combine(Folder, "settings.json");

    public static AppSettings Load()
    {
        try
        {
            if (!File.Exists(PathName)) return new AppSettings();
            return JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(PathName)) ?? new AppSettings();
        }
        catch
        {
            return new AppSettings();
        }
    }

    public void Save()
    {
        if (string.IsNullOrWhiteSpace(DeviceId))
            DeviceId = Guid.NewGuid().ToString("N");
        var json = JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true });
        File.WriteAllText(PathName, json);
    }
}
