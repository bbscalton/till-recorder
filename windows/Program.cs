using System.Diagnostics;
using System.Drawing.Imaging;
using Microsoft.Win32;

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
    long nextIdleBeat;
    int idleRemoved;
    bool pairing;

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
        menu.Items.Add("Add camera…", null, (_, _) => AddCamera());
        menu.Items.Add("Open watch page", null, (_, _) => Process.Start(new ProcessStartInfo(ShopClient.BaseUrl + "/watch") { UseShellExecute = true }));
        menu.Items.Add("Quit", null, (_, _) => ExitThread());
        icon.ContextMenuStrip = menu;
        icon.DoubleClick += (_, _) => { if (settings.Paired && !settings.Armed) Start(); };
        timer = new System.Windows.Forms.Timer { Interval = 1000 };
        timer.Tick += (_, _) =>
        {
            TypedInput.Tick();
            icon.Text = Trim(engine.Status);
            if (engine.TakeRemoved() || Interlocked.Exchange(ref idleRemoved, 0) == 1) ShowPairAfterRemoval();
            else PollRemovalWhileStopped();
        };
        timer.Start();
        if (settings.Paired) RegisterStartup();
        if (settings.Paired && settings.Armed) Start();
        else if (settings.Paired)
        {
            RemoteControl? control = null;
            try { control = Task.Run(() => new ShopClient().Heartbeat(settings, false, CancellationToken.None)).GetAwaiter().GetResult(); }
            catch { }
            if (control?.Removed == true) ShowPairAfterRemoval();
        }
        else if (!settings.Paired) Pair();
    }

    static void RegisterStartup()
    {
        try
        {
            var exe = Environment.ProcessPath;
            if (string.IsNullOrWhiteSpace(exe)) return;
            using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run", true);
            key?.SetValue("TillRecorder", "\"" + exe + "\"");
        }
        catch (Exception error)
        {
            AppLog.Write("startup " + error.GetType().Name);
        }
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
        RegisterStartup();
        TypedInput.Install();
        engine.Start(settings);
        icon.Text = "Starting";
    }

    void Stop()
    {
        settings.Armed = false;
        settings.Save();
        TypedInput.Remove();
        var typed = TypedInput.Drain();
        if (typed.Count > 0)
        {
            try
            {
                var stored = Task.Run(() => new ShopClient().UploadInputs(settings, typed, CancellationToken.None)).GetAwaiter().GetResult();
                if (!stored) TypedInput.Restore(typed);
            }
            catch
            {
                TypedInput.Restore(typed);
            }
        }
        engine.Stop();
        RemoteControl? stopped = null;
        try { stopped = Task.Run(() => new ShopClient().Heartbeat(settings, false, CancellationToken.None)).GetAwaiter().GetResult(); }
        catch { }
        if (stopped?.Removed == true) ShowPairAfterRemoval();
        icon.Text = "Stopped";
    }

    void PollRemovalWhileStopped()
    {
        if (pairing || settings.Armed || !settings.Paired) return;
        var now = Environment.TickCount64;
        if (now < nextIdleBeat) return;
        nextIdleBeat = now + 5000;
        var snapshot = settings;
        Task.Run(() =>
        {
            try
            {
                using var shop = new ShopClient();
                var control = shop.Heartbeat(snapshot, false, CancellationToken.None).GetAwaiter().GetResult();
                if (control?.Removed == true) Interlocked.Exchange(ref idleRemoved, 1);
            }
            catch { }
        });
    }

    void ShowPairAfterRemoval()
    {
        if (pairing) return;
        settings.Token = "";
        settings.Armed = false;
        settings.Save();
        engine.Stop();
        Pair();
    }

    void Pair()
    {
        if (pairing) return;
        pairing = true;
        try
        {
            TypedInput.Pause();
            DialogResult result;
            string registerName;
            string pairCode;
            using (var form = new PairForm(settings.DeviceName))
            {
                result = form.ShowDialog();
                registerName = form.RegisterName;
                pairCode = form.PairCode;
            }
            TypedInput.Resume();
            if (result != DialogResult.OK) return;
            settings.DeviceName = ShopClient.Clean(registerName);
            if (string.IsNullOrWhiteSpace(settings.DeviceId)) settings.DeviceId = Guid.NewGuid().ToString("N");
            string? token = null;
            try
            {
                token = Task.Run(() => new ShopClient().ClaimPair(settings.DeviceId, settings.DeviceName, pairCode, CancellationToken.None)).GetAwaiter().GetResult();
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
        finally
        {
            pairing = false;
        }
    }

    void AddCamera()
    {
        TypedInput.Pause();
        DialogResult result;
        string url;
        string user;
        string password;
        var existing = CameraAddress.Split(settings.Overhead ?? "");
        var currentUser = string.IsNullOrEmpty(settings.OverheadUser) ? existing.User : settings.OverheadUser;
        var currentPassword = string.IsNullOrEmpty(settings.OverheadPassword) ? existing.Password : settings.OverheadPassword;
        using (var form = new CameraForm(existing.Bare, currentUser ?? "", currentPassword ?? ""))
        {
            result = form.ShowDialog();
            url = form.CameraUrl;
            user = form.CameraUser;
            password = form.CameraPassword;
        }
        TypedInput.Resume();
        if (result != DialogResult.OK) return;
        settings.Overhead = url;
        settings.OverheadUser = user;
        settings.OverheadPassword = password;
        settings.Save();
        var label = CameraAddress.Label(url);
        var synced = false;
        try
        {
            synced = Task.Run(() => new ShopClient().PublishOverhead(settings, label.Length > 0, label, CancellationToken.None)).GetAwaiter().GetResult();
        }
        catch
        {
            synced = false;
        }
        var message = url.Length == 0
            ? "Cleared the overhead camera."
            : synced
                ? "Saved. This register will pull that camera from the store network."
                : "Saved on this computer. It will sync when the register can reach the watch page.";
        MessageBox.Show(message, "Till Recorder");
    }

    static string Trim(string text) => text.Length <= 60 ? text : text[..60];

    protected override void ExitThreadCore()
    {
        timer.Stop();
        TypedInput.Remove();
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

sealed class CameraForm : Form
{
    readonly TextBox urlBox = new() { Width = 400 };
    readonly TextBox userBox = new() { Width = 400 };
    readonly TextBox passwordBox = new() { Width = 400, UseSystemPasswordChar = true };
    readonly ListBox found = new() { Width = 400, Height = 90 };
    readonly Label status = new() { AutoSize = true, MaximumSize = new Size(400, 0) };
    readonly Button find = new() { Text = "Find cameras", Width = 120 };
    readonly List<string> addresses = new();
    public string CameraUrl { get; private set; } = "";
    public string CameraUser { get; private set; } = "";
    public string CameraPassword { get; private set; } = "";

    public CameraForm(string current, string user, string password)
    {
        Text = "Add overhead camera";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(440, 430);
        urlBox.Text = current ?? "";
        userBox.Text = user ?? "";
        passwordBox.Text = password ?? "";
        var intro = new Label
        {
            Text = "Paste an RTSP or ONVIF address, then the camera username and password. Find cameras looks on this computer's network. Enter the username and password before you save. The watch page does not receive the password.",
            AutoSize = true,
            MaximumSize = new Size(400, 0),
            Location = new Point(16, 12)
        };
        var urlLabel = new Label { Text = "RTSP or ONVIF address", AutoSize = true, Location = new Point(16, 78) };
        urlBox.Location = new Point(16, 98);
        var userLabel = new Label { Text = "Username", AutoSize = true, Location = new Point(16, 128) };
        userBox.Location = new Point(16, 148);
        var passwordLabel = new Label { Text = "Password", AutoSize = true, Location = new Point(16, 178) };
        passwordBox.Location = new Point(16, 198);
        find.Location = new Point(16, 234);
        found.Location = new Point(16, 268);
        status.Location = new Point(16, 364);
        var save = new Button { Text = "Save", Location = new Point(150, 234), Width = 80 };
        var clear = new Button { Text = "Clear", Location = new Point(236, 234), Width = 80 };
        var cancel = new Button { Text = "Cancel", DialogResult = DialogResult.Cancel, Location = new Point(322, 234), Width = 80 };
        CancelButton = cancel;
        // Only a deliberate pick (click on an item, or Enter/Space on the list) fills the address box.
        // SelectedIndexChanged also fires when the list is refilled or when a click lands below the last item,
        // and must never replace what was typed.
        found.MouseClick += (_, e) =>
        {
            var index = found.IndexFromPoint(e.Location);
            if (index != ListBox.NoMatches) Pick(index);
        };
        found.KeyDown += (_, e) =>
        {
            if (e.KeyCode is Keys.Enter or Keys.Space && found.SelectedIndex >= 0)
            {
                Pick(found.SelectedIndex);
                e.Handled = true;
                e.SuppressKeyPress = true;
            }
        };
        find.Click += (_, _) =>
        {
            find.Enabled = false;
            status.Text = "Looking for cameras on this network for a few seconds. Enter the username and password before you save.";
            found.Items.Clear();
            addresses.Clear();
            Task.Run(() =>
            {
                List<(string Name, string Host, string Url)> cameras;
                try { cameras = OnvifDiscovery.Probe(); }
                catch { cameras = new(); }
                if (IsDisposed || Disposing) return;
                try
                {
                    BeginInvoke(() =>
                    {
                        find.Enabled = true;
                        foreach (var camera in cameras)
                        {
                            found.Items.Add(camera.Name is "" or "Camera" ? camera.Host : camera.Host + "   " + camera.Name);
                            addresses.Add(camera.Url);
                        }
                        status.Text = cameras.Count switch
                        {
                            0 => "No camera answered. Type the camera's IP address (or its RTSP or ONVIF address) instead.",
                            1 => "Found 1 camera. Click it, enter its username and password, then save. Cameras on another subnet do not answer; type their IP address.",
                            _ => $"Found {cameras.Count} cameras. Click one, enter its username and password, then save."
                        };
                    });
                }
                catch (InvalidOperationException) { }
            });
        };
        save.Click += (_, _) =>
        {
            var address = urlBox.Text.Trim();
            var userName = userBox.Text.Trim();
            var secret = passwordBox.Text;
            if (address.Length == 0)
            {
                status.Text = "Paste an RTSP or ONVIF address, or clear the camera.";
                return;
            }
            var target = CameraAddress.Resolve(address, userName, secret);
            if (target.Kind == CameraKind.Invalid)
            {
                status.Text = target.Error;
                return;
            }
            save.Enabled = false;
            status.Text = "Checking the camera...";
            Task.Run(() =>
            {
                string bare;
                string? problem;
                try { (bare, problem) = CameraSetup.Check(target); }
                catch (Exception error) { (bare, problem) = ("", $"Checking the camera failed ({error.GetType().Name})."); }
                if (IsDisposed || Disposing) return;
                try
                {
                BeginInvoke(() =>
                {
                    // The address, username and password boxes are left exactly as typed; only the status changes.
                    save.Enabled = true;
                    if (problem != null || string.IsNullOrWhiteSpace(bare))
                    {
                        status.Text = problem ?? "The camera did not give a playable stream.";
                        return;
                    }
                    CameraUrl = bare;
                    CameraUser = target.User;
                    CameraPassword = target.Password;
                    DialogResult = DialogResult.OK;
                    Close();
                });
                }
                catch (InvalidOperationException) { }
            });
        };
        clear.Click += (_, _) =>
        {
            CameraUrl = "";
            CameraUser = "";
            CameraPassword = "";
            DialogResult = DialogResult.OK;
            Close();
        };
        Controls.AddRange(new Control[] { intro, urlLabel, urlBox, userLabel, userBox, passwordLabel, passwordBox, find, save, clear, cancel, found, status });
    }

    void Pick(int index)
    {
        if (index < 0 || index >= addresses.Count) return;
        urlBox.Text = addresses[index];
        if (userBox.Text.Trim().Length == 0)
            status.Text = "Enter the camera's username and password, then save.";
    }
}
