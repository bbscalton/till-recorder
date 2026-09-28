using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using SharpGen.Runtime;
using Vortice.Direct3D;
using Vortice.Direct3D11;
using Vortice.DXGI;
using Vortice.MediaFoundation;

namespace TillRecorder;

sealed class BgraFrame
{
    public required byte[] Pixels { get; init; }
    public int Width { get; init; }
    public int Height { get; init; }
    public int Stride { get; init; }
}

sealed class Nv12Frame
{
    public required byte[] Pixels { get; init; }
    public int Width { get; init; }
    public int Height { get; init; }
}

static class FrameScale
{
    public static (int Width, int Height) Fit(int width, int height, int longEdge)
    {
        if (width < 2 || height < 2) return (0, 0);
        var scale = Math.Min(1.0, longEdge / (double)Math.Max(width, height));
        var fittedWidth = Math.Max(2, ((int)Math.Round(width * scale)) & ~1);
        var fittedHeight = Math.Max(2, ((int)Math.Round(height * scale)) & ~1);
        return (fittedWidth, fittedHeight);
    }

    public static Nv12Frame ToNv12(BgraFrame source, int width, int height)
    {
        var nv12 = new byte[width * height * 3 / 2];
        for (var y = 0; y < height; y++)
        {
            var srcY = Math.Min(source.Height - 1, y * source.Height / height);
            for (var x = 0; x < width; x++)
            {
                var srcX = Math.Min(source.Width - 1, x * source.Width / width);
                var offset = srcY * source.Stride + srcX * 4;
                var b = source.Pixels[offset];
                var g = source.Pixels[offset + 1];
                var r = source.Pixels[offset + 2];
                nv12[y * width + x] = (byte)Math.Clamp(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16, 0, 255);
            }
        }
        var chroma = width * height;
        for (var y = 0; y < height; y += 2)
        {
            var srcY = Math.Min(source.Height - 1, y * source.Height / height);
            for (var x = 0; x < width; x += 2)
            {
                var srcX = Math.Min(source.Width - 1, x * source.Width / width);
                var offset = srcY * source.Stride + srcX * 4;
                var b = source.Pixels[offset];
                var g = source.Pixels[offset + 1];
                var r = source.Pixels[offset + 2];
                var index = chroma + (y / 2) * width + x;
                nv12[index] = (byte)Math.Clamp(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128, 0, 255);
                nv12[index + 1] = (byte)Math.Clamp(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128, 0, 255);
            }
        }
        return new Nv12Frame { Pixels = nv12, Width = width, Height = height };
    }

    public static byte[] MotionGrid(Nv12Frame frame, int columns = 40, int rows = 22)
    {
        var grid = new byte[columns * rows];
        for (var y = 0; y < rows; y++)
        {
            var srcY = Math.Min(frame.Height - 1, y * frame.Height / rows);
            for (var x = 0; x < columns; x++)
            {
                var srcX = Math.Min(frame.Width - 1, x * frame.Width / columns);
                grid[y * columns + x] = frame.Pixels[srcY * frame.Width + srcX];
            }
        }
        return grid;
    }

    public static float ChangedFraction(byte[] previous, byte[] current, int delta = 18)
    {
        var count = Math.Min(previous.Length, current.Length);
        if (count == 0) return 0;
        var changed = 0;
        for (var index = 0; index < count; index++)
        {
            if (Math.Abs(previous[index] - current[index]) >= delta) changed++;
        }
        return changed / (float)count;
    }
}

sealed class DesktopCapture : IDisposable
{
    IDXGIFactory1? factory;
    IDXGIAdapter? adapter;
    IDXGIOutput1? output;
    ID3D11Device? device;
    ID3D11DeviceContext? context;
    IDXGIOutputDuplication? duplication;
    ID3D11Texture2D? staging;
    int stageWidth;
    int stageHeight;
    public int Width { get; private set; }
    public int Height { get; private set; }
    public string Detail { get; private set; } = "";

    public bool Open()
    {
        Close();
        try
        {
            factory = DXGI.CreateDXGIFactory1<IDXGIFactory1>();
            factory.EnumAdapters(0, out var enumerated);
            adapter = enumerated ?? throw new InvalidOperationException("No display adapter");
            adapter.EnumOutputs(0, out var first);
            var description = first.Description;
            output = first.QueryInterface<IDXGIOutput1>();
            Width = description.DesktopCoordinates.Right - description.DesktopCoordinates.Left;
            Height = description.DesktopCoordinates.Bottom - description.DesktopCoordinates.Top;
            var levels = new[] { FeatureLevel.Level_11_0, FeatureLevel.Level_10_0 };
            device = D3D11.D3D11CreateDevice(DriverType.Hardware, DeviceCreationFlags.BgraSupport, levels);
            context = device.ImmediateContext;
            duplication = output.DuplicateOutput(device);
            Detail = $"{Width}x{Height}";
            return Width >= 2 && Height >= 2 && duplication != null;
        }
        catch (Exception error)
        {
            Detail = error.Message;
            Close();
            return false;
        }
    }

    public BgraFrame? TryGrab(int timeoutMs)
    {
        if (duplication == null || device == null || context == null) return null;
        IDXGIResource? resource = null;
        var held = false;
        try
        {
            duplication.AcquireNextFrame((uint)timeoutMs, out var info, out resource);
            held = true;
            if (info.AccumulatedFrames == 0 && info.LastPresentTime == 0) return null;
            using var texture = resource.QueryInterface<ID3D11Texture2D>();
            var desc = texture.Description;
            EnsureStaging((int)desc.Width, (int)desc.Height, desc.Format);
            context.CopyResource(staging!, texture);
            var mapped = context.Map(staging!, 0, MapMode.Read, Vortice.Direct3D11.MapFlags.None);
            try
            {
                var width = (int)desc.Width;
                var height = (int)desc.Height;
                var stride = width * 4;
                var pixels = new byte[stride * height];
                for (var row = 0; row < height; row++)
                    Marshal.Copy(mapped.DataPointer + row * (int)mapped.RowPitch, pixels, row * stride, stride);
                return new BgraFrame { Pixels = pixels, Width = width, Height = height, Stride = stride };
            }
            finally
            {
                context.Unmap(staging!, 0);
            }
        }
        catch (SharpGenException error) when (IsWait(error) || IsLost(error))
        {
            if (IsLost(error)) Open();
            return null;
        }
        catch (SharpGenException)
        {
            return null;
        }
        finally
        {
            if (held)
            {
                try { duplication?.ReleaseFrame(); } catch { }
            }
            resource?.Dispose();
        }
    }

    public BgraFrame? GrabStill()
    {
        try
        {
            var bounds = System.Windows.Forms.Screen.PrimaryScreen?.Bounds
                ?? new Rectangle(0, 0, Math.Max(Width, 2), Math.Max(Height, 2));
            if (bounds.Width < 2 || bounds.Height < 2) return null;
            using var bitmap = new Bitmap(bounds.Width, bounds.Height, PixelFormat.Format32bppArgb);
            using (var graphics = Graphics.FromImage(bitmap))
                graphics.CopyFromScreen(bounds.Left, bounds.Top, 0, 0, bounds.Size, CopyPixelOperation.SourceCopy);
            var data = bitmap.LockBits(new Rectangle(0, 0, bitmap.Width, bitmap.Height), ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
            try
            {
                var stride = bitmap.Width * 4;
                var pixels = new byte[stride * bitmap.Height];
                for (var row = 0; row < bitmap.Height; row++)
                    Marshal.Copy(data.Scan0 + row * data.Stride, pixels, row * stride, stride);
                Width = bitmap.Width;
                Height = bitmap.Height;
                return new BgraFrame { Pixels = pixels, Width = bitmap.Width, Height = bitmap.Height, Stride = stride };
            }
            finally
            {
                bitmap.UnlockBits(data);
            }
        }
        catch (Exception error)
        {
            Detail = error.Message;
            return null;
        }
    }

    void EnsureStaging(int width, int height, Format format)
    {
        if (staging != null && stageWidth == width && stageHeight == height) return;
        staging?.Dispose();
        staging = device!.CreateTexture2D(new Texture2DDescription
        {
            Width = (uint)width,
            Height = (uint)height,
            MipLevels = 1,
            ArraySize = 1,
            Format = format,
            SampleDescription = new SampleDescription(1, 0),
            Usage = ResourceUsage.Staging,
            BindFlags = BindFlags.None,
            CPUAccessFlags = CpuAccessFlags.Read,
            MiscFlags = ResourceOptionFlags.None
        });
        stageWidth = width;
        stageHeight = height;
    }

    static bool IsWait(SharpGenException error) => error.ResultCode.Code == unchecked((int)0x887A0027);
    static bool IsLost(SharpGenException error) => error.ResultCode.Code == unchecked((int)0x887A0026);

    public void Close()
    {
        staging?.Dispose();
        staging = null;
        duplication?.Dispose();
        duplication = null;
        context?.Dispose();
        context = null;
        device?.Dispose();
        device = null;
        output?.Dispose();
        output = null;
        adapter?.Dispose();
        adapter = null;
        factory?.Dispose();
        factory = null;
    }

    public void Dispose() => Close();
}

static class MediaDevices
{
    public static IMFMediaSource? OpenSource(Guid sourceType, out string name)
    {
        name = "";
        using var attributes = MediaFactory.MFCreateAttributes(1);
        attributes.Set(CaptureDeviceAttributeKeys.SourceType, sourceType);
        using var devices = MediaFactory.MFEnumDeviceSources(attributes);
        foreach (var device in devices)
        {
            name = NameOf(device);
            return device.ActivateObject<IMFMediaSource>();
        }
        return null;
    }

    public static string NameOf(IMFActivate activate)
    {
        try
        {
            return activate.GetString(CaptureDeviceAttributeKeys.FriendlyName) ?? "Device";
        }
        catch
        {
            return "Device";
        }
    }
}
