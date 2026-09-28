using System.Runtime.InteropServices;
using System.Text;

namespace TillRecorder;

/// <summary>
/// Records finished text typed on this register while Till Recorder is watching.
/// The hook lives on the tray message loop and is removed when watching stops.
/// </summary>
static class TypedInput
{
    const int WhKeyboardLl = 13;
    const int WmKeyDown = 0x0100;
    const int WmSysKeyDown = 0x0104;
    const int VkBack = 0x08;
    const int VkTab = 0x09;
    const int VkReturn = 0x0D;
    const int VkShift = 0x10;
    const int VkControl = 0x11;
    const int VkMenu = 0x12;
    const int VkCapital = 0x14;
    const int VkEscape = 0x1B;
    const int VkLwin = 0x5B;
    const int VkRwin = 0x5C;

    delegate IntPtr HookProc(int nCode, IntPtr wParam, IntPtr lParam);

    static readonly object Gate = new();
    static readonly List<(long At, string Text)> Pending = new();
    static HookProc? callback;
    static IntPtr hook;
    static string buffer = "";
    static long bufferAt;
    static long changedAt;
    static int inside;
    static bool paused;

    public static void Install()
    {
        if (hook != IntPtr.Zero) return;
        callback = OnKey;
        hook = SetWindowsHookEx(WhKeyboardLl, callback, GetModuleHandle(null), 0);
    }

    public static void Remove()
    {
        if (hook != IntPtr.Zero)
        {
            UnhookWindowsHookEx(hook);
            hook = IntPtr.Zero;
        }
        lock (Gate) CommitLocked();
    }

    public static void Pause()
    {
        lock (Gate)
        {
            paused = true;
            buffer = "";
        }
    }

    public static void Resume()
    {
        lock (Gate) paused = false;
    }

    public static void Tick()
    {
        lock (Gate)
        {
            if (buffer.Length > 0 && Environment.TickCount64 - changedAt >= 700) CommitLocked();
        }
    }

    public static List<(long At, string Text)> Drain()
    {
        lock (Gate)
        {
            if (buffer.Length > 0 && Environment.TickCount64 - changedAt >= 700) CommitLocked();
            var copy = Pending.ToList();
            Pending.Clear();
            return copy;
        }
    }

    public static void Restore(IReadOnlyList<(long At, string Text)> entries)
    {
        if (entries.Count == 0) return;
        lock (Gate) Pending.InsertRange(0, entries);
    }

    static IntPtr OnKey(int nCode, IntPtr wParam, IntPtr lParam)
    {
        if (nCode >= 0 && Interlocked.Exchange(ref inside, 1) == 0)
        {
            try
            {
                var message = wParam.ToInt32();
                if (message == WmKeyDown || message == WmSysKeyDown)
                    Handle(Marshal.PtrToStructure<KbdLlHook>(lParam));
            }
            catch (Exception error)
            {
                AppLog.Write("input " + error.GetType().Name);
            }
            finally
            {
                inside = 0;
            }
        }
        return CallNextHookEx(hook, nCode, wParam, lParam);
    }

    static void Handle(KbdLlHook key)
    {
        lock (Gate)
        {
            if (paused) return;
            var vk = key.vkCode;
            if (vk is VkShift or VkControl or VkMenu or VkCapital or VkLwin or VkRwin) return;
            if (ModifierDown(VkControl) || ModifierDown(VkMenu) || ModifierDown(VkLwin) || ModifierDown(VkRwin)) return;
            if (vk == VkEscape)
            {
                buffer = "";
                return;
            }
            if (vk is VkReturn or VkTab)
            {
                CommitLocked();
                return;
            }
            if (vk == VkBack)
            {
                if (buffer.Length > 0) buffer = buffer[..^1];
                changedAt = Environment.TickCount64;
                return;
            }
            var ch = Character(key);
            if (ch == null || char.IsControl(ch.Value)) return;
            if (buffer.Length == 0) bufferAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            buffer += ch.Value;
            changedAt = Environment.TickCount64;
            if (buffer.Length >= 200) CommitLocked();
        }
    }

    static void CommitLocked()
    {
        var text = buffer.Trim();
        buffer = "";
        if (text.Length == 0) return;
        if (text.Length > 200) text = text[..200];
        Pending.Add((bufferAt > 0 ? bufferAt : DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), text));
        bufferAt = 0;
    }

    static char? Character(KbdLlHook key)
    {
        var state = new byte[256];
        GetKeyboardState(state);
        if (ModifierDown(VkShift)) state[VkShift] |= 0x80;
        else state[VkShift] = 0;
        if (key.vkCode < 256) state[key.vkCode] |= 0x80;
        var buffer = new StringBuilder(8);
        var written = ToUnicode(key.vkCode, key.scanCode, state, buffer, buffer.Capacity, 0);
        if (written <= 0 || buffer.Length == 0) return null;
        return buffer[0];
    }

    static bool ModifierDown(int vk)
    {
        return (GetAsyncKeyState(vk) & 0x8000) != 0;
    }

    [StructLayout(LayoutKind.Sequential)]
    struct KbdLlHook
    {
        public uint vkCode;
        public uint scanCode;
        public uint flags;
        public uint time;
        public IntPtr extra;
    }

    [DllImport("user32.dll", SetLastError = true)]
    static extern IntPtr SetWindowsHookEx(int idHook, HookProc lpfn, IntPtr hMod, uint dwThreadId);

    [DllImport("user32.dll")]
    static extern bool UnhookWindowsHookEx(IntPtr hhk);

    [DllImport("user32.dll")]
    static extern IntPtr CallNextHookEx(IntPtr hhk, int nCode, IntPtr wParam, IntPtr lParam);

    [DllImport("user32.dll")]
    static extern bool GetKeyboardState(byte[] lpKeyState);

    [DllImport("user32.dll")]
    static extern short GetAsyncKeyState(int vKey);

    [DllImport("user32.dll")]
    static extern int ToUnicode(uint wVirtKey, uint wScanCode, byte[] lpKeyState, StringBuilder pwszBuff, int cchBuff, uint wFlags);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    static extern IntPtr GetModuleHandle(string? lpModuleName);
}
