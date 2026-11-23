// SPDX-License-Identifier: AGPL-3.0-only

namespace CouchSubs.Core;

/// <summary>Bounded, offline evidence for failed screen OCR.</summary>
public static class ScreenDebugFrames
{
    private static readonly object Gate = new();

    public static string? Save(Frame frame, string reason, string? directory = null, DateTime? at = null)
    {
        if (!frame.IsConsistent || frame.Width <= 0 || frame.Height <= 0) return null;
        lock (Gate)
        {
            try
            {
                directory ??= Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "CouchSubs", "debug");
                Directory.CreateDirectory(directory);
                var path = Path.Combine(directory, $"screen-{at ?? DateTime.Now:yyyyMMdd-HHmmss}.png");
                File.WriteAllBytes(path, ButtonIcons.Png(frame));
                foreach (var old in Directory.EnumerateFiles(directory, "screen-????????-??????.png")
                    .OrderByDescending(Path.GetFileName, StringComparer.Ordinal).Skip(20)) File.Delete(old);
                SubtitleLog.Write($"screen debug ({reason}): {path}");
                return path;
            }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException or System.Security.SecurityException)
            {
                SubtitleLog.Write($"screen debug failed: {error.GetType().Name}: {error.Message}");
                return null;
            }
        }
    }
}
