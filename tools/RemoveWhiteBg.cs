using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;

public static class Program
{
    private static byte[] px;
    private static int w;
    private static int h;
    private static int tolerance;
    private static bool[] bg;

    private static bool IsNearWhite(int i)
    {
        int r = px[i], g = px[i + 1], b = px[i + 2];
        int min = Math.Min(Math.Min(r, g), b);
        return min >= tolerance;
    }

    private static void Seed(int x, int y, Queue<int> queue)
    {
        if (bg[y * w + x]) return;
        if (!IsNearWhite((y * w + x) * 4)) return;
        bg[y * w + x] = true;
        queue.Enqueue(y * w + x);
    }

    public static int Main(string[] args)
    {
        if (args.Length < 2)
        {
            Console.Error.WriteLine("usage: RemoveWhiteBg <input> <output> [tolerance]");
            return 2;
        }
        string src = args[0];
        string dst = args[1];
        tolerance = args.Length >= 3 ? int.Parse(args[2]) : 240;

        using (var srcBmp = new Bitmap(src))
        using (var bmp = new Bitmap(srcBmp.Width, srcBmp.Height, PixelFormat.Format32bppArgb))
        {
            using (var g = Graphics.FromImage(bmp))
            {
                g.DrawImage(srcBmp, 0, 0, bmp.Width, bmp.Height);
            }
            w = bmp.Width;
            h = bmp.Height;
            var rect = new Rectangle(0, 0, w, h);
            var data = bmp.LockBits(rect, ImageLockMode.ReadWrite, PixelFormat.Format32bppArgb);
            int stride = data.Stride;
            px = new byte[stride * h];
            Marshal.Copy(data.Scan0, px, 0, px.Length);

            bg = new bool[w * h];
            var queue = new Queue<int>();

            for (int x = 0; x < w; x++) { Seed(x, 0, queue); Seed(x, h - 1, queue); }
            for (int y = 0; y < h; y++) { Seed(0, y, queue); Seed(w - 1, y, queue); }

            int[] dx = { 1, -1, 0, 0 };
            int[] dy = { 0, 0, 1, -1 };
            while (queue.Count > 0)
            {
                int c = queue.Dequeue();
                int cx = c % w, cy = c / w;
                for (int d = 0; d < 4; d++)
                {
                    int nx = cx + dx[d], ny = cy + dy[d];
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    int ni = ny * w + nx;
                    if (bg[ni]) continue;
                    if (IsNearWhite(ni * 4))
                    {
                        bg[ni] = true;
                        queue.Enqueue(ni);
                    }
                }
            }

            int ramp = tolerance - 8;
            if (ramp < 1) ramp = 1;

            for (int y = 0; y < h; y++)
            {
                for (int x = 0; x < w; x++)
                {
                    int pi = y * w + x;
                    int i = pi * 4;
                    if (bg[pi])
                    {
                        px[i + 3] = 0;
                        continue;
                    }
                    bool touches = false;
                    for (int d = 0; d < 4 && !touches; d++)
                    {
                        int nx = x + dx[d], ny = y + dy[d];
                        if (nx >= 0 && ny >= 0 && nx < w && ny < h && bg[ny * w + nx])
                            touches = true;
                    }
                    if (!touches) continue;
                    int r = px[i], g = px[i + 1], b = px[i + 2];
                    int min = Math.Min(Math.Min(r, g), b);
                    if (min >= tolerance) { px[i + 3] = 0; continue; }
                    int a = 255 - (int)Math.Round(255.0 * (ramp - min) / ramp);
                    if (a < 0) a = 0;
                    if (a > 255) a = 255;
                    if (a < px[i + 3]) px[i + 3] = (byte)a;
                }
            }

            Marshal.Copy(px, 0, data.Scan0, px.Length);
            bmp.UnlockBits(data);
            bmp.Save(dst, ImageFormat.Png);
        }
        Console.WriteLine("saved " + dst);
        return 0;
    }
}
