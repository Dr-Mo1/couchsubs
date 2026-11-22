// SPDX-License-Identifier: AGPL-3.0-only

using CouchSubs.Core;
using CouchSubs.Shell;

namespace CouchSubs.Shell.Tests;

public class MenuPausedSenderTests
{
    private sealed class Sink : ISubtitleSender
    {
        public List<string> Sent { get; } = [];
        public int Clears;
        public (string Source, string Target)? Identity;
        public void SendSubtitle(string text, int durationMs = 15000, TextBox? box = null, bool paragraphs = false, bool replaces = false) => Sent.Add(text);
        public void Clear() => Clears++;
        public void SendPresentedSubtitle(string text, string source, string target, int durationMs, TextBox? box, bool replaces)
        {
            Identity = (source, target);
            Sent.Add(text);
        }
    }

    [Fact]
    public void SubtitlesWaitWhileAMenuIsOnScreen()
    {
        var sink = new Sink();
        var menu = false;
        var sender = new MenuPausedSender(sink, () => menu);
        sender.SendSubtitle("Bir");
        menu = true;
        sender.SendSubtitle("RESTORE DEFAULT");
        sender.Clear();
        menu = false;
        sender.SendSubtitle("İki");
        Assert.Equal(["Bir", "İki"], sink.Sent);
        Assert.Equal(1, sink.Clears);
    }

    [Fact]
    public void LocalizedSpeakerIdentitySurvivesTheMenuWrapper()
    {
        var sink = new Sink();
        var menu = true;
        var sender = new MenuPausedSender(sink, () => menu);
        sender.SendPresentedSubtitle("لوسيا: تعال.", "Lucia: Come.", "ar", 4000, null, false);
        Assert.Empty(sink.Sent);
        menu = false;
        sender.SendPresentedSubtitle("لوسيا: تعال.", "Lucia: Come.", "ar", 4000, null, false);
        Assert.Equal(("Lucia: Come.", "ar"), sink.Identity);
        Assert.Equal("لوسيا: تعال.", Assert.Single(sink.Sent));
    }
}
