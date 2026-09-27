namespace TillRecorder;

/// <summary>
/// Fragmented MP4 matching the Android recorder: ftyp+moov, then moof+mdat.
/// Timescale is 90000. Live fragments start on a keyframe.
/// </summary>
sealed class Fmp4Muxer
{
    public readonly int TicksPerFrame;
    int sequence = 1;
    long decodeTime;

    public Fmp4Muxer(int width, int height, int frameRate)
    {
        Width = width;
        Height = height;
        TicksPerFrame = 90_000 / Math.Max(1, frameRate);
    }

    public int Width { get; }
    public int Height { get; }

    public byte[] Start(byte[] sps, byte[] pps)
    {
        sequence = 1;
        decodeTime = 0;
        var brand = Four("isom").Concat(I32(0x200)).Concat(Four("isom")).Concat(Four("iso6")).Concat(Four("mp41")).ToArray();
        return Box("ftyp", brand).Concat(Movie(sps, pps)).ToArray();
    }

    public byte[] Media(IReadOnlyList<Sample> samples)
    {
        if (samples.Count == 0) return Array.Empty<byte>();
        var durations = samples.Select(sample => sample.DurationTicks > 0 ? sample.DurationTicks : TicksPerFrame).ToArray();
        var sizes = samples.Select(sample => sample.Data.Length).ToArray();
        var flags = samples.Select(sample => sample.Keyframe ? 0x02000000 : 0x01040000).ToArray();
        var tfhd = Box("tfhd", I32(0x00020000).Concat(I32(1)).ToArray());
        var tfdt = Box("tfdt", I32(0x01000000).Concat(I64(decodeTime)).ToArray());
        var mfhd = Box("mfhd", I32(0).Concat(I32(sequence)).ToArray());
        var trunBody = TrunBody(durations, sizes, flags, 0);
        var trun = Box("trun", trunBody);
        var traf = Box("traf", tfhd.Concat(tfdt).Concat(trun).ToArray());
        var moofSize = 8 + mfhd.Length + traf.Length;
        var patched = Box("trun", TrunBody(durations, sizes, flags, moofSize + 8));
        var patchedTraf = Box("traf", tfhd.Concat(tfdt).Concat(patched).ToArray());
        var moof = Box("moof", mfhd.Concat(patchedTraf).ToArray());
        var payload = samples.SelectMany(sample => sample.Data).ToArray();
        var mdat = Box("mdat", payload);
        foreach (var duration in durations) decodeTime += duration;
        sequence += 1;
        return moof.Concat(mdat).ToArray();
    }

    static byte[] TrunBody(int[] durations, int[] sizes, int[] flags, int dataOffset)
    {
        var body = new List<byte>(16 + durations.Length * 12);
        body.AddRange(I32(0x00000701));
        body.AddRange(I32(durations.Length));
        body.AddRange(I32(dataOffset));
        for (var index = 0; index < durations.Length; index++)
        {
            body.AddRange(I32(durations[index]));
            body.AddRange(I32(sizes[index]));
            body.AddRange(I32(flags[index]));
        }
        return body.ToArray();
    }

    byte[] Movie(byte[] sps, byte[] pps)
    {
        var mvhd = Box("mvhd", I32(0).Concat(I32(0)).Concat(I32(0)).Concat(I32(90_000)).Concat(I32(0)).Concat(I32(0x00010000))
            .Concat(I16(0x0100)).Concat(I16(0)).Concat(I32(0)).Concat(I32(0)).Concat(Matrix()).Concat(I32(0)).Concat(I32(0))
            .Concat(I32(0)).Concat(I32(0)).Concat(I32(0)).Concat(I32(0)).Concat(I32(2)).ToArray());
        var tkhd = Box("tkhd", I32(0x00000007).Concat(I32(0)).Concat(I32(0)).Concat(I32(1)).Concat(I32(0)).Concat(I32(0))
            .Concat(I32(0)).Concat(I32(0)).Concat(I16(0)).Concat(I16(0)).Concat(I16(0)).Concat(I16(0)).Concat(Matrix())
            .Concat(I32(Width << 16)).Concat(I32(Height << 16)).ToArray());
        var mdhd = Box("mdhd", I32(0).Concat(I32(0)).Concat(I32(0)).Concat(I32(90_000)).Concat(I32(0)).Concat(I16(0x55C4)).Concat(I16(0)).ToArray());
        var hdlr = Box("hdlr", I32(0).Concat(I32(0)).Concat(Four("vide")).Concat(I32(0)).Concat(I32(0)).Concat(I32(0)).Concat(Cstr("VideoHandler")).ToArray());
        var vmhd = Box("vmhd", I32(0x00000001).Concat(I16(0)).Concat(I16(0)).Concat(I16(0)).Concat(I16(0)).ToArray());
        var url = Box("url ", I32(0x00000001));
        var dref = Box("dref", I32(0).Concat(I32(1)).Concat(url).ToArray());
        var dinf = Box("dinf", dref);
        var avcC = Box("avcC", new byte[]
        {
            1,
            sps.Length > 1 ? sps[1] : (byte)0x42,
            sps.Length > 2 ? sps[2] : (byte)0,
            sps.Length > 3 ? sps[3] : (byte)0x1E,
            0xFF, 0xE1
        }.Concat(I16(sps.Length)).Concat(sps).Concat(new byte[] { 1 }).Concat(I16(pps.Length)).Concat(pps).ToArray());
        var avc1Body = new byte[78];
        avc1Body[7] = 1;
        Put16(avc1Body, 24, Width);
        Put16(avc1Body, 26, Height);
        Put32(avc1Body, 28, 0x00480000);
        Put32(avc1Body, 32, 0x00480000);
        Put16(avc1Body, 40, 1);
        Put16(avc1Body, 74, 0x0018);
        Put16(avc1Body, 76, 0xFFFF);
        var avc1 = Box("avc1", avc1Body.Concat(avcC).ToArray());
        var stsd = Box("stsd", I32(0).Concat(I32(1)).Concat(avc1).ToArray());
        var stts = Box("stts", I32(0).Concat(I32(0)).ToArray());
        var stsc = Box("stsc", I32(0).Concat(I32(0)).ToArray());
        var stsz = Box("stsz", I32(0).Concat(I32(0)).Concat(I32(0)).ToArray());
        var stco = Box("stco", I32(0).Concat(I32(0)).ToArray());
        var stbl = Box("stbl", stsd.Concat(stts).Concat(stsc).Concat(stsz).Concat(stco).ToArray());
        var minf = Box("minf", vmhd.Concat(dinf).Concat(stbl).ToArray());
        var mdia = Box("mdia", mdhd.Concat(hdlr).Concat(minf).ToArray());
        var trak = Box("trak", tkhd.Concat(mdia).ToArray());
        var trex = Box("trex", I32(0).Concat(I32(1)).Concat(I32(1)).Concat(I32(TicksPerFrame)).Concat(I32(0)).Concat(I32(0)).ToArray());
        var mvex = Box("mvex", trex);
        return Box("moov", mvhd.Concat(trak).Concat(mvex).ToArray());
    }

    static byte[] Matrix()
    {
        var values = new[] { 0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000 };
        return values.SelectMany(I32).ToArray();
    }

    public readonly record struct Sample(byte[] Data, bool Keyframe, int DurationTicks);

    static byte[] Box(string type, byte[] body) => I32(8 + body.Length).Concat(Four(type)).Concat(body).ToArray();
    static byte[] Four(string value) => System.Text.Encoding.ASCII.GetBytes(value);
    static byte[] Cstr(string value) => System.Text.Encoding.ASCII.GetBytes(value + "\0");
    static byte[] I16(int value) => new[] { (byte)(value >> 8), (byte)value };
    static byte[] I32(int value) => new[] { (byte)(value >> 24), (byte)(value >> 16), (byte)(value >> 8), (byte)value };
    static byte[] I64(long value) => new[]
    {
        (byte)(value >> 56), (byte)(value >> 48), (byte)(value >> 40), (byte)(value >> 32),
        (byte)(value >> 24), (byte)(value >> 16), (byte)(value >> 8), (byte)value
    };

    static void Put16(byte[] target, int offset, int value)
    {
        target[offset] = (byte)(value >> 8);
        target[offset + 1] = (byte)value;
    }

    static void Put32(byte[] target, int offset, int value)
    {
        target[offset] = (byte)(value >> 24);
        target[offset + 1] = (byte)(value >> 16);
        target[offset + 2] = (byte)(value >> 8);
        target[offset + 3] = (byte)value;
    }
}

static class Avc
{
    public static string CodecString(byte[] sps)
    {
        if (sps.Length < 4) return "avc1.42E01E";
        return $"avc1.{sps[1]:X2}{sps[2]:X2}{sps[3]:X2}";
    }

    public static List<byte[]> SplitAnnexB(byte[] data)
    {
        var starts = new List<int>();
        var index = 0;
        while (index + 3 < data.Length)
        {
            if (data[index] == 0 && data[index + 1] == 0 && data[index + 2] == 1)
            {
                starts.Add(index + 3);
                index += 3;
                continue;
            }
            if (index + 4 < data.Length && data[index] == 0 && data[index + 1] == 0 && data[index + 2] == 0 && data[index + 3] == 1)
            {
                starts.Add(index + 4);
                index += 4;
                continue;
            }
            index++;
        }
        if (starts.Count == 0) return new List<byte[]> { data };
        var parts = new List<byte[]>();
        for (var part = 0; part < starts.Count; part++)
        {
            var begin = starts[part];
            var end = part + 1 < starts.Count ? StartCodeAt(data, starts[part + 1]) : data.Length;
            if (end > begin) parts.Add(data[begin..end]);
        }
        return parts;
    }

    public static byte[] AnnexBToAvcc(byte[] data)
    {
        using var stream = new MemoryStream();
        foreach (var nal in SplitAnnexB(data))
        {
            if (nal.Length == 0) continue;
            var type = nal[0] & 0x1f;
            if (type is 6 or 7 or 8 or 9) continue;
            stream.WriteByte((byte)(nal.Length >> 24));
            stream.WriteByte((byte)(nal.Length >> 16));
            stream.WriteByte((byte)(nal.Length >> 8));
            stream.WriteByte((byte)nal.Length);
            stream.Write(nal);
        }
        return stream.ToArray();
    }

    static int StartCodeAt(byte[] data, int payload)
    {
        if (payload >= 4 && data[payload - 1] == 1 && data[payload - 2] == 0 && data[payload - 3] == 0 && data[payload - 4] == 0)
            return payload - 4;
        if (payload >= 3 && data[payload - 1] == 1 && data[payload - 2] == 0 && data[payload - 3] == 0)
            return payload - 3;
        return payload;
    }
}
