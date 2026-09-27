using System.Diagnostics;
using System.Drawing.Imaging;

namespace TillRecorder;

static class Program
{
    [STAThread]
    static int Main(string[] args)
    {
        if (args.Any(arg => arg.Equals("--self-test", StringComparison.OrdinalIgnoreCase)))
            return SelfTest.Run();
        var pairFlag = Array.FindIndex(args, arg => arg.Equals("--pair-file", StringComparison.OrdinalIgnoreCase));
        if (pairFlag >= 0 && pairFlag + 1 < args.Length)
            return PairFromFile(args[pairFlag + 1]);
        using var mutex = new Mutex(true, "Local\\TillRecorder", out var created);
        if (!created) return 0;
        ApplicationConfiguration.Initialize();
        Application.Run(new TrayApp());
        return 0;
    }

    static int PairFromFile(string path)
{
    string code;
    try
    {
        code = File.ReadAllText(path).Trim();
        File.Delete(path);
    }
    catch
    {
        Console.WriteLine("pair-failed");
        return 1;
    }
    var settings = AppSettings.Load();
    if (string.IsNullOrWhiteSpace(settings.DeviceId)) settings.DeviceId = Guid.NewGuid().ToString("N");
    if (string.IsNullOrWhiteSpace(settings.DeviceName)) settings.DeviceName = "Windows POS";
    using var shop = new ShopClient();
    var token = shop.ClaimPair(settings.DeviceId, settings.DeviceName, code, CancellationToken.None).GetAwaiter().GetResult();
    if (token == null)
    {
        Console.WriteLine("pair-failed");
        return 1;
    }
    settings.Token = token;
    settings.Armed = false;
    settings.Save();
    var control = shop.Heartbeat(settings, false, CancellationToken.None).GetAwaiter().GetResult();
    Console.WriteLine(control == null ? "paired heartbeat-failed" : "paired heartbeat-ok");
    return control == null ? 1 : 0;
    }
}

static class SelfTest
{
    public static int Run()
    {
        MediaFactoryStartup.Ensure();
        using var desktop = new DesktopCapture();
        if (!desktop.Open())
        {
            Console.WriteLine("screen-failed");
            return 1;
        }
        BgraFrame? first = null;
        var captured = 0;
        var started = Environment.TickCount64;
        while (Environment.TickCount64 - started < 2000)
        {
            var frame = desktop.TryGrab(100);
            if (frame == null) continue;
            first ??= frame;
            captured++;
        }
        var elapsed = Math.Max(1, Environment.TickCount64 - started);
        if (first == null)
        {
            Console.WriteLine($"screen-open {desktop.Detail} frames 0");
            return 1;
        }
        var fitted = FrameScale.Fit(first.Width, first.Height, 960);
        var nv12 = FrameScale.ToNv12(first, fitted.Width, fitted.Height);
        var bmpPath = Path.Combine(Path.GetTempPath(), "till-windows-frame.bmp");
        SaveBmp(first, bmpPath);
        using var encoder = H264Encoder.TryCreate(fitted.Width, fitted.Height, RecorderEngine.ScreenFps, 1_500_000);
        var encoded = 0;
        var encodeStarted = Environment.TickCount64;
        if (encoder != null)
        {
            for (var index = 0; index < 20; index++)
            {
                if (encoder.Encode(nv12, index == 0) != null) encoded++;
            }
        }
        var encodeElapsed = Math.Max(1, Environment.TickCount64 - encodeStarted);
        using var webcam = WebcamCapture.TryOpen();
        var webcamFrames = 0;
        var webcamElapsed = 1L;
        if (webcam != null)
        {
            var webcamStarted = Environment.TickCount64;
            while (Environment.TickCount64 - webcamStarted < 2000)
            {
                if (webcam.TryRead() != null) webcamFrames++;
            }
            webcamElapsed = Math.Max(1, Environment.TickCount64 - webcamStarted);
        }
        using var microphone = Microphone.TryOpen();
        Console.WriteLine(
            $"screen {first.Width}x{first.Height} captured {captured} in {elapsed}ms ({captured * 1000.0 / elapsed:0.0} fps) " +
            $"encoded {fitted.Width}x{fitted.Height} {encoded} in {encodeElapsed}ms ({(encoder == null ? 0 : encoded * 1000.0 / encodeElapsed):0.0} fps) " +
            $"codec {(encoder == null ? "none" : encoder.Codec)} encoder {(encoder == null ? H264Encoder.LastError : "ok")} file {bmpPath}");
        if (webcam == null) Console.WriteLine("webcam none");
        else Console.WriteLine($"webcam {webcam.Name} {webcam.Width}x{webcam.Height} frames {webcamFrames} in {webcamElapsed}ms ({webcamFrames * 1000.0 / webcamElapsed:0.0} fps)");
        Console.WriteLine(microphone == null ? "microphone none" : "microphone open");
        return encoded > 0 || captured > 0 ? 0 : 1;
    }

    static void SaveBmp(BgraFrame frame, string path)
    {
        using var bitmap = new Bitmap(frame.Width, frame.Height, PixelFormat.Format32bppArgb);
        var data = bitmap.LockBits(new Rectangle(0, 0, frame.Width, frame.Height), ImageLockMode.WriteOnly, PixelFormat.Format32bppArgb);
        try
        {
            for (var row = 0; row < frame.Height; row++)
                System.Runtime.InteropServices.Marshal.Copy(frame.Pixels, row * frame.Stride, data.Scan0 + row * data.Stride, frame.Stride);
        }
        finally
        {
            bitmap.UnlockBits(data);
        }
        bitmap.Save(path, ImageFormat.Bmp);
    }
}

sealed class TrayApp : ApplicationContext
{
    readonly NotifyIcon icon;
    readonly RecorderEngine engine = new();
    readonly System.Windows.Forms.Timer timer;
    AppSettings settings = AppSettings.Load();

    public TrayApp()
    {
        icon = new NotifyIcon
        {
            Icon = SystemIcons.Application,
            Visible = true,
            Text = "Till Recorder"
        };
        var menu = new ContextMenuStrip();
        menu.Items.Add("Start", null, (_, _) => Start());
        menu.Items.Add("Stop", null, (_, _) => Stop());
        menu.Items.Add("Pair…", null, (_, _) => Pair());
        menu.Items.Add("Open watch page", null, (_, _) => Process.Start(new ProcessStartInfo(ShopClient.BaseUrl + "/watch") { UseShellExecute = true }));
        menu.Items.Add("Quit", null, (_, _) => ExitThread());
        icon.ContextMenuStrip = menu;
        icon.DoubleClick += (_, _) => { if (settings.Paired && !settings.Armed) Start(); };
        timer = new System.Windows.Forms.Timer { Interval = 1000 };
        timer.Tick += (_, _) => icon.Text = Trim(engine.Status);
        timer.Start();
        if (settings.Paired && settings.Armed) Start();
        else if (!settings.Paired) Pair();
    }

    void Start()
    {
        if (!settings.Paired)
        {
            Pair();
            if (!settings.Paired) return;
        }
        settings.Armed = true;
        settings.Save();
        engine.Start(settings);
        icon.Text = "Starting";
    }

    void Stop()
    {
        settings.Armed = false;
        settings.Save();
        engine.Stop();
        icon.Text = "Stopped";
    }

    void Pair()
    {
        using var form = new PairForm(settings.DeviceName);
        if (form.ShowDialog() != DialogResult.OK) return;
        settings.DeviceName = ShopClient.Clean(form.RegisterName);
        if (string.IsNullOrWhiteSpace(settings.DeviceId)) settings.DeviceId = Guid.NewGuid().ToString("N");
        string? token = null;
        try
        {
            token = Task.Run(() => new ShopClient().ClaimPair(settings.DeviceId, settings.DeviceName, form.PairCode, CancellationToken.None)).GetAwaiter().GetResult();
        }
        catch
        {
            token = null;
        }
        if (token == null)
        {
            MessageBox.Show("That pair code did not work. Create a new one on the watch page.", "Till Recorder");
            return;
        }
        settings.Token = token;
        settings.Save();
        Start();
    }

    static string Trim(string text) => text.Length <= 60 ? text : text[..60];

    protected override void ExitThreadCore()
    {
        timer.Stop();
        engine.Dispose();
        icon.Visible = false;
        icon.Dispose();
        base.ExitThreadCore();
    }
}

sealed class PairForm : Form
{
    readonly TextBox nameBox = new() { Width = 240 };
    readonly TextBox codeBox = new() { Width = 240, MaxLength = 7, CharacterCasing = CharacterCasing.Upper };
    public string RegisterName => nameBox.Text.Trim();
    public string PairCode => new string(codeBox.Text.Where(char.IsLetterOrDigit).ToArray());

    public PairForm(string currentName)
    {
        Text = "Pair this register";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(280, 160);
        nameBox.Text = string.IsNullOrWhiteSpace(currentName) ? Environment.MachineName : currentName;
        var nameLabel = new Label { Text = "Register name", AutoSize = true, Location = new Point(16, 16) };
        nameBox.Location = new Point(16, 36);
        var codeLabel = new Label { Text = "Pair code from the watch page", AutoSize = true, Location = new Point(16, 68) };
        codeBox.Location = new Point(16, 88);
        var pair = new Button { Text = "Pair", DialogResult = DialogResult.OK, Location = new Point(16, 122) };
        var cancel = new Button { Text = "Cancel", DialogResult = DialogResult.Cancel, Location = new Point(100, 122) };
        AcceptButton = pair;
        CancelButton = cancel;
        Controls.AddRange(new Control[] { nameLabel, nameBox, codeLabel, codeBox, pair, cancel });
        pair.Click += (_, _) =>
        {
            if (RegisterName.Length == 0 || PairCode.Length != 6)
            {
                DialogResult = DialogResult.None;
                MessageBox.Show("Enter the register name and the 6-character pair code.", "Till Recorder");
            }
        };
    }
}
