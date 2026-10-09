using System.Net;
using System.Net.Sockets;
using System.Text;

namespace TillRecorder;

static class SelfTest
{
    static int failed;
    static int passed;

    static void Eq<T>(T expected, T actual, string name)
    {
        if (EqualityComparer<T>.Default.Equals(expected, actual)) { passed++; return; }
        failed++;
        Console.WriteLine($"FAIL {name}: expected [{expected}] got [{actual}]");
    }

    static void True(bool value, string name) => Eq(true, value, name);

    static int Main(string[] args)
    {
        if (args.Length >= 1 && args[0] == "live") return Live(args);
        AddressChecks();
        RtspChecks();
        OnvifChecks();
        MockCameraChecks();
        Console.WriteLine($"{passed} passed, {failed} failed");
        return failed == 0 ? 0 : 1;
    }

    static void AddressChecks()
    {
        var t = CameraAddress.Resolve("192.168.1.111", "admin", "x");
        Eq(CameraKind.Onvif, t.Kind, "bare ip kind");
        Eq("http://192.168.1.111/onvif/device_service", t.Url, "bare ip url");
        Eq("http://cam.local:8000/onvif/device_service", CameraAddress.Resolve("cam.local:8000", "", "").Url, "host:port onvif");
        var r = CameraAddress.Resolve("192.168.1.111:554/cam/realmonitor?channel=1&subtype=1", "", "");
        Eq(CameraKind.Rtsp, r.Kind, "host:554 rtsp");
        Eq("rtsp://192.168.1.111:554/cam/realmonitor?channel=1&subtype=1", r.Url, "host:554 url");
        Eq("http://10.0.0.5:8080/onvif/device_service", CameraAddress.Resolve("HTTP://10.0.0.5:8080/", "", "").Url, "http no path");
        Eq("https://10.0.0.5/onvif/device_service", CameraAddress.Resolve("https://10.0.0.5/onvif/device_service", "", "").Url, "https full");
        var c = CameraAddress.Resolve("rtsp://admin:p%40ss@192.168.1.111:554/cam/realmonitor?channel=1&subtype=0", "", "");
        Eq("rtsp://192.168.1.111:554/cam/realmonitor?channel=1&subtype=0", c.Url, "rtsp creds url");
        Eq("admin", c.User, "rtsp creds user");
        Eq("p@ss", c.Password, "rtsp creds pw");
        var raw = CameraAddress.Resolve("rtsp://admin:a#b/c?d@e:f@192.168.1.111/Streaming/Channels/101", "", "");
        Eq("rtsp://192.168.1.111/Streaming/Channels/101", raw.Url, "raw special url");
        Eq("a#b/c?d@e:f", raw.Password, "raw special pw");
        var f = CameraAddress.Resolve("rtsp://old:old@1.2.3.4/live", "admin", "new");
        Eq("admin", f.User, "fields win user");
        Eq("new", f.Password, "fields win pw");
        var bare = "rtsp://192.168.1.111:554/cam/realmonitor?channel=1&subtype=0";
        var pw = "p@ss:w/rd#%?&";
        var embedded = CameraAddress.Embed(bare, "ad min", pw);
        True(embedded.StartsWith("rtsp://ad%20min:p%40ss%3Aw%2Frd%23%25%3F%26@192.168.1.111:554/"), "embed encodes");
        var split = CameraAddress.Split(embedded);
        Eq(bare, split.Bare, "roundtrip bare");
        Eq("ad min", split.User, "roundtrip user");
        Eq(pw, split.Password, "roundtrip pw");
        Eq("192.168.1.111", CameraAddress.Label(embedded), "label");
        Eq("rtsp://1.2.3.4:554/cam/realmonitor?channel=1&subtype=0", CameraAddress.Resolve("rtsp://1.2.3.4:554/cam/realmonitor?channel=1&amp;subtype=0", "", "").Url, "amp repaired");
        Eq(CameraKind.Invalid, CameraAddress.Resolve("", "", "").Kind, "empty invalid");
        Eq(CameraKind.Invalid, CameraAddress.Resolve("ftp://1.2.3.4/", "", "").Kind, "ftp invalid");
        Eq(CameraKind.Invalid, CameraAddress.Resolve("rtsp://1.2.3.4:99999/", "", "").Kind, "bad port invalid");
        True(CameraAddress.Resolve("rtsps://1.2.3.4/live", "", "").Error.Contains("rtsps"), "rtsps message");
        Eq("fe80::1", CameraAddress.Parse(CameraAddress.Resolve("rtsp://[fe80::1]:554/live", "", "").Url)?.Host, "ipv6");
        Eq("rtsp://h:554/cam/realmonitor?channel=1&subtype=1", CameraAddress.SubStream("rtsp://h:554/cam/realmonitor?channel=1&subtype=0"), "dahua sub");
        Eq("rtsp://h/Streaming/Channels/102", CameraAddress.SubStream("rtsp://h/Streaming/Channels/101"), "hik sub");
        Eq(null, CameraAddress.SubStream("rtsp://h/live"), "no sub");
    }

    static void RtspChecks()
    {
        var c = RtspClient.ParseChallenge("Digest realm=\"testrealm@host.com\", qop=\"auth,auth-int\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"")!;
        var header = RtspClient.DigestHeader(c, "Mufasa", "Circle Of Life", "GET", "/dir/index.html", 1, "0a4f113b");
        True(header.Contains("response=\"6629fae49393a05397450978507c4ef1\""), "rfc2617 digest");
        Eq("digest", RtspClient.PickChallenge(new[] { "Basic realm=\"x\"", "Digest realm=\"Login to 2a1b\", nonce=\"abc\"" })!["#scheme"], "prefer digest");
        var sdp = "v=0\r\ns=Media Server\r\na=control:*\r\nt=0 0\r\nm=video 0 RTP/AVP 96\r\na=control:trackID=0\r\na=rtpmap:96 H264/90000\r\n" +
                  "a=fmtp:96 packetization-mode=1;profile-level-id=640028;sprop-parameter-sets=Z2QAKKwbGoB4AiflwFuAgICgAAADACAAAAMDwQ==,aO44gA==\r\n" +
                  "m=audio 0 RTP/AVP 8\r\na=control:trackID=1\r\na=rtpmap:8 PCMA/8000\r\n";
        var baseUrl = "rtsp://192.168.1.111:554/cam/realmonitor?channel=1&subtype=0/";
        var track = RtspClient.ParseSdpText(sdp, baseUrl);
        Eq("H264", track.Codec, "sdp codec");
        Eq("rtsp://192.168.1.111:554/cam/realmonitor?channel=1&subtype=0/trackID=0", track.Control, "sdp control");
        Eq(7, track.Sps![0] & 0x1f, "sprop sps");
        Eq(8, track.Pps![0] & 0x1f, "sprop pps");
        Eq("H265", RtspClient.ParseSdpText("v=0\r\nm=video 0 RTP/AVP 98\r\na=rtpmap:98 H265/90000\r\na=control:trackID=0\r\n", "rtsp://h/").Codec, "sdp h265");

        var a = new RtpH264Assembler();
        byte[] sps = { 0x67, 0x64, 0x00, 0x28 }, pps = { 0x68, 0xee, 0x38 };
        var stap = new byte[] { 0x18, 0, (byte)sps.Length }.Concat(sps).Concat(new byte[] { 0, (byte)pps.Length }).Concat(pps).ToArray();
        Eq(0, a.Push(Rtp(1000, false, stap)).Count, "stap no unit");
        True(a.Sps!.SequenceEqual(sps) && a.Pps!.SequenceEqual(pps), "stap sps/pps");
        Eq(0, a.Push(Rtp(1000, false, new byte[] { 0x7c, 0x85, 1, 2 })).Count, "fu start");
        var units = a.Push(Rtp(1000, true, new byte[] { 0x7c, 0x45, 3, 4 }));
        Eq(1, units.Count, "fu end unit");
        True(units[0].Keyframe, "fu keyframe");
        True(RtpH264Assembler.Avcc(units[0].Nals).SequenceEqual(new byte[] { 0, 0, 0, 5, 0x65, 1, 2, 3, 4 }), "avcc");
    }

    static void OnvifChecks()
    {
        Eq("tuOSpGlFlIXsozq4HFNeeGeFLEI=", OnvifDiscovery.PasswordDigest(Convert.FromBase64String("LKqI6G/AikKCQrN0zqZFlg=="), "2010-09-16T07:50:45Z", "userpassword"), "wsse digest");
        Eq("rtsp://192.168.1.111:554/cam/realmonitor?channel=1&subtype=0&unicast=true&proto=Onvif",
            OnvifDiscovery.StreamUri("<tt:Uri>rtsp://192.168.1.111:554/cam/realmonitor?channel=1&amp;subtype=0&amp;unicast=true&amp;proto=Onvif</tt:Uri>"), "stream uri unescape");
        Eq("http://h/onvif/media_service", OnvifDiscovery.MediaXAddr("<tt:Analytics><tt:XAddr>http://h/onvif/analytics_service</tt:XAddr></tt:Analytics><tt:Media><tt:XAddr>http://h/onvif/media_service</tt:XAddr></tt:Media>"), "media xaddr");
        var xml = "<trt:Profiles token=\"MediaProfile000\" fixed=\"true\"><tt:VideoEncoderConfiguration token=\"V0\"><tt:Encoding>H265</tt:Encoding><tt:Resolution><tt:Width>1920</tt:Width><tt:Height>1080</tt:Height></tt:Resolution></tt:VideoEncoderConfiguration></trt:Profiles>" +
                  "<trt:Profiles token=\"MediaProfile001\" fixed=\"true\"><tt:VideoEncoderConfiguration token=\"V1\"><tt:Encoding>H264</tt:Encoding><tt:Resolution><tt:Width>704</tt:Width><tt:Height>576</tt:Height></tt:Resolution></tt:VideoEncoderConfiguration></trt:Profiles>";
        var profiles = OnvifDiscovery.Profiles(xml);
        Eq(2, profiles.Count, "profiles count");
        Eq("MediaProfile001", OnvifDiscovery.Order(profiles)[0].Token, "h264 preferred");
        var all265 = new List<OnvifProfile> { new("main", "H265", 0, 0), new("sub", "H265", 0, 0), new("third", "H265", 0, 0) };
        Eq("sub,main,third", string.Join(",", OnvifDiscovery.Order(all265).Select(p => p.Token)), "sub first when no h264");
        True(OnvifDiscovery.IsNotAuthorized("<env:Value>ter:NotAuthorized</env:Value>"), "not authorized");
        Eq(new DateTime(2010, 9, 16, 7, 50, 45, DateTimeKind.Utc), OnvifDiscovery.CameraUtc("<tt:UTCDateTime><tt:Time><tt:Hour>7</tt:Hour><tt:Minute>50</tt:Minute><tt:Second>45</tt:Second></tt:Time><tt:Date><tt:Year>2010</tt:Year><tt:Month>9</tt:Month><tt:Day>16</tt:Day></tt:Date></tt:UTCDateTime>"), "camera clock");
        var cam = OnvifDiscovery.CameraFromReply("<d:ProbeMatch><d:XAddrs>http://[fe80::1]/onvif/device_service http://192.168.1.111/onvif/device_service</d:XAddrs></d:ProbeMatch>", "192.168.1.111");
        Eq("http://192.168.1.111/onvif/device_service", cam?.Url, "discovery ipv4 xaddr");
        True(OnvifDiscovery.ProbeMessage().Contains("xmlns:dn="), "probe declares dn namespace");
        Eq("rtsp://192.168.1.111:554/live", OnvifDiscovery.SameHost("rtsp://0.0.0.0:554/live", "http://192.168.1.111/onvif/device_service"), "bogus host");
    }

    static void MockCameraChecks()
    {
        // Mock Dahua-style camera: 401 Digest+Basic, then 200 with SDP only for the right digest.
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        var seen = new List<string>();
        var server = Task.Run(() =>
        {
            using var s = listener.AcceptTcpClient();
            var reader = new StreamReader(s.GetStream(), Encoding.ASCII);
            var w = s.GetStream();
            for (var n = 0; n < 2; n++)
            {
                var lines = new List<string>();
                string? l;
                while (!string.IsNullOrEmpty(l = reader.ReadLine())) lines.Add(l);
                lock (seen) seen.Add(string.Join("\n", lines));
                var cseq = lines.First(x => x.StartsWith("CSeq")).Split(':')[1].Trim();
                var auth = lines.FirstOrDefault(x => x.StartsWith("Authorization:"));
                var uri = $"rtsp://127.0.0.1:{port}/cam/realmonitor?channel=1&subtype=0";
                var expected = RtspClient.Md5(RtspClient.Md5("admin:Login to abc:p@ss") + ":n1:" + RtspClient.Md5("DESCRIBE:" + uri));
                string reply;
                if (auth != null && auth.Contains($"response=\"{expected}\"") && auth.Contains($"uri=\"{uri}\""))
                {
                    var sdp = "v=0\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\na=control:trackID=0\r\n";
                    reply = $"RTSP/1.0 200 OK\r\nCSeq: {cseq}\r\nContent-Base: {uri}/\r\nContent-Length: {sdp.Length}\r\n\r\n{sdp}";
                }
                else
                {
                    reply = $"RTSP/1.0 401 Unauthorized\r\nCSeq: {cseq}\r\nWWW-Authenticate: Digest realm=\"Login to abc\", nonce=\"n1\", stale=\"FALSE\"\r\nWWW-Authenticate: Basic realm=\"Login to abc\"\r\n\r\n";
                }
                var bytes = Encoding.ASCII.GetBytes(reply);
                w.Write(bytes);
            }
        });
        var error = RtspClient.Probe($"rtsp://127.0.0.1:{port}/cam/realmonitor?channel=1&subtype=0", "admin", "p@ss");
        server.Wait(5000);
        listener.Stop();
        Eq(null, error?.Message, "mock digest login");
        lock (seen) True(seen.Count == 2 && seen.All(x => !x.Contains("p@ss")), "password never on the wire");

        var closed = new TcpListener(IPAddress.Loopback, 0);
        closed.Start();
        var closedPort = ((IPEndPoint)closed.LocalEndpoint).Port;
        closed.Stop();
        Eq(CameraFailure.Unreachable, RtspClient.Probe($"rtsp://127.0.0.1:{closedPort}/live", "", "")?.Kind, "unreachable kind");
    }

    static byte[] Rtp(int ts, bool marker, byte[] payload)
    {
        var h = new byte[12];
        h[0] = 0x80;
        h[1] = (byte)((marker ? 0x80 : 0) | 96);
        h[4] = (byte)(ts >> 24); h[5] = (byte)(ts >> 16); h[6] = (byte)(ts >> 8); h[7] = (byte)ts;
        return h.Concat(payload).ToArray();
    }

    /// <summary>live &lt;address&gt; &lt;user&gt; ; password from env OVERHEAD_PW. Read-only: ONVIF queries + RTSP DESCRIBE.</summary>
    static int Live(string[] args)
    {
        var target = CameraAddress.Resolve(args[1], args.Length > 2 ? args[2] : "", Environment.GetEnvironmentVariable("OVERHEAD_PW") ?? "");
        Console.WriteLine($"resolved kind={target.Kind} url={target.Url} {target.Error}");
        if (target.Kind == CameraKind.Onvif)
        {
            try
            {
                foreach (var u in OnvifDiscovery.Streams(target.Url, target.User, target.Password)) Console.WriteLine("onvif stream: " + u);
            }
            catch (CameraException e) { Console.WriteLine($"onvif error {e.Kind}: {e.Message}"); return 2; }
        }
        if (args.Contains("--transport"))
        {
            using var raw = new RtspClient(target.Url, target.User, target.Password);
            raw.Connect();
            var t = raw.Describe(anyCodec: true);
            raw.Setup(t);
            raw.Play(t);
            int packets = 0, video = 0, len = 0;
            var stopAt = DateTime.UtcNow.AddSeconds(5);
            while (DateTime.UtcNow < stopAt)
            {
                var pk = raw.ReadPacket();
                if (pk == null) break;
                packets++;
                if (pk.Value.Channel == raw.VideoChannel) { video++; len += pk.Value.Payload.Length; }
            }
            Console.WriteLine($"transport check codec={t.Codec} channel={raw.VideoChannel} timeout={raw.SessionTimeoutSec}s: {packets} interleaved packets in 5s, {video} video, {len} bytes");
            return 0;
        }
        var (url, problem) = CameraSetup.Check(target);        Console.WriteLine(problem == null ? "OK, would save: " + url : "REJECTED: " + problem);
        if (problem == null && args.Contains("--play"))
        {
            var parts = (url, target.User, target.Password);
            using var client = new RtspClient(parts.url, parts.User, parts.Password);
            client.Connect();
            var track = client.Describe();
            client.Setup(track);
            client.Play(track);
            var a = new RtpH264Assembler { Sps = track.Sps, Pps = track.Pps };
            int units = 0, keys = 0, bytes = 0;
            var until = DateTime.UtcNow.AddSeconds(6);
            while (DateTime.UtcNow < until)
            {
                var p = client.ReadPacket();
                if (p == null) break;
                if (p.Value.Channel != client.VideoChannel) continue;
                foreach (var u in a.Push(p.Value.Payload)) { units++; if (u.Keyframe) keys++; bytes += RtpH264Assembler.Avcc(u.Nals).Length; }
            }
            Console.WriteLine($"played 6s: {units} frames, {keys} keyframes, {bytes} bytes, sps={(a.Sps != null)} pps={(a.Pps != null)} codec={(a.Sps != null ? Avc(a.Sps) : "?")}");
        }
        return problem == null ? 0 : 1;
    }

    static string Avc(byte[] sps) => sps.Length < 4 ? "?" : $"avc1.{sps[1]:X2}{sps[2]:X2}{sps[3]:X2}";
}
