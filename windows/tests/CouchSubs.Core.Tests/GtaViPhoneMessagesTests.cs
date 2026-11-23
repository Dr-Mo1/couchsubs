// SPDX-License-Identifier: AGPL-3.0-only

namespace CouchSubs.Core.Tests;

public class GtaViPhoneMessagesTests
{
    internal static TextLine Row(string text, double top, double left = .73, double right = .95) =>
        new(text, new TextBox(left, top, right, top + .018));

    internal static Reading Chat() => new([
        Row("Messages encrypted", .24), Row("New message", .28), Row("Automated Machine", .32),
        Row("Congratulations.", .38),
        Row("By scanning that code you have already", .404),
        Row("proven yourself to be in the top 1%", .428),
        Row("of human observational skills.", .452),
        Row("Now, if you are ready to go further", .476),
        Row("and join the No Flushers fighting", .500),
        Row("to protect DNA and do whatever it takes", .524),
        Row("to uncover the truth, reply with", .548),
        Row("\"TRUTH\".", .572),
        Row("Standard rates will apply.", .65), Row("The truth isn't free.", .674),
        Row("Typing...", .75),
    ]);

    [Fact]
    public void EachBubbleIsOneParagraphIncludingSentenceBoundariesAndQuotedReply()
    {
        var chat = Assert.IsType<GtaViPhoneMessages.Chat>(GtaViPhoneMessages.Find(Chat()));
        Assert.Equal(2, chat.Bubbles.Count);
        var first = Assert.Single(chat.Bubbles[0].Paragraphs);
        Assert.StartsWith("Congratulations. By scanning", first);
        Assert.Contains("skills. Now,", first);
        Assert.EndsWith("reply with \"TRUTH\".", first);
        Assert.Equal("Standard rates will apply. The truth isn't free.", Assert.Single(chat.Bubbles[1].Paragraphs));
        Assert.DoesNotContain(chat.Bubbles, group => group.Paragraphs.Any(p => p.Contains("Automated") || p.Contains("Typing")));
    }

    [Fact]
    public async Task HotkeyTranslatesWholeBubblesAndArabicBlockKeepsThem()
    {
        var translator = new BubbleTranslator();
        var items = await ScreenTranslation.TranslateAsync(Chat(), translator);
        Assert.Equal(2, translator.Requested.Count);
        Assert.StartsWith("Congratulations. By scanning", translator.Requested[0]);
        Assert.EndsWith("reply with \"TRUTH\".", translator.Requested[0]);
        Assert.Equal(2, items.Count);
        Assert.All(items, item => Assert.True(item.IsPhoneMessage));
        Assert.Equal("الرسالة كاملة رقم 1\nالرسالة كاملة رقم 2", ScreenTranslation.SubtitleBlock(items));
    }

    private sealed class BubbleTranslator : ITextTranslator
    {
        public List<string> Requested { get; } = [];
        public Task<string> TranslateAsync(string text, CancellationToken cancellationToken = default)
        {
            Requested.Add(text);
            return Task.FromResult($"الرسالة كاملة رقم {Requested.Count}");
        }
    }

    [Fact]
    public async Task HotkeyProtectsTheCompleteMessageFromEarlierSubtitleFragments()
    {
        var echo = new SubtitleEcho();
        echo.Record(new Reading([Row("to uncover the truth, reply with", .548)]), new CropRegion(0, 1, 0, 1));
        var reading = await ScreenTranslation.ReadAsync(Frame.Blank(64, 64), new LinesReader(Chat()), echo);
        Assert.Equal(Chat().Lines.Count, reading.Lines.Count);
    }

    [Theory]
    [InlineData("Messages encrypted")]
    [InlineData("New message")]
    public void OneHeaderAloneIsInsufficient(string removed)
    {
        Assert.Null(GtaViPhoneMessages.Find(new Reading(Chat().Lines.Where(line => line.Text != removed))));
    }

    [Fact]
    public void AHeaderInAnotherColumnDoesNotTriggerDetection()
    {
        Assert.Null(GtaViPhoneMessages.Find(new Reading(Chat().Lines.Select(line => line.Text == "New message"
            ? line with { Box = new TextBox(.1, .28, .3, .298) } : line))));
    }

    [Fact]
    public void MissingPunctuationDoesNotTurnTheFirstMessageWordIntoAContact()
    {
        var reading = new Reading(Chat().Lines.Select(line => line.Text == "Congratulations."
            ? line with { Text = "Congratulations" } : line));
        Assert.StartsWith("Congratulations By scanning", GtaViPhoneMessages.Find(reading)!.Bubbles[0].Paragraphs[0]);
    }

    [Fact]
    public void CroppedBubblesAreRemovedButDialogueIsPreserved()
    {
        var chat = GtaViPhoneMessages.Find(Chat())!;
        var dialogue = Row("LUCIA: We have to leave.", .8, .73, .94);
        var wide = Row("We should leave before they come back.", .85, .3, .9);
        var reading = new Reading(Chat().Lines.Concat([dialogue, wide]));
        var filtered = chat.WithoutMessages(reading);
        Assert.Equal([dialogue, wide], filtered.Lines);
        var area = new TextBox(.14, .62, .86, .97);
        var partial = new Reading([new TextLine("Standard rates will", new TextBox(.82, .08, .99, .14))]);
        Assert.Empty(chat.WithoutMessages(partial, area).Lines);
    }

    private sealed class PhoneReader : ITextReader
    {
        public int ScreenReads;
        public string Read(Frame image) => "";
        public Reading ReadScreen(Frame image)
        {
            ScreenReads++;
            return new Reading(Chat().Lines.Select(line => line with
            {
                Box = line.Box is { } b ? new TextBox((b.Left - .5) * 2, b.Top, (b.Right - .5) * 2, b.Bottom) : null,
            }));
        }
        public Reading ReadLines(Frame image, bool deep) => new([
            Row("Standard rates will", .65), Row("The truth isn't", .674),
            Row("LUCIA: We have to leave.", .85, .25, .65),
        ]);
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task LivePipelineSkipsOnlyPhoneBubblesWhenGtaViGateIsEnabled(bool enabled)
    {
        var reader = new PhoneReader();
        var translator = new FakeTranslator();
        var pipeline = new SubtitlePipeline(reader, translator, new FakeSender())
        {
            ManualPhoneMessages = () => enabled, CleanWait = 0, PackWait = 0,
        };
        await pipeline.ProcessAsync(Frame.Blank(64, 64), new CropRegion(0, 1, 0, 1));
        await pipeline.WaitForTranslationsAsync();
        if (enabled)
        {
            Assert.Equal(["We have to leave."], translator.Requested);
            Assert.NotNull(pipeline.PhoneChat);
            Assert.Equal(1, reader.ScreenReads);
            pipeline.Reset();
            Assert.Null(pipeline.PhoneChat);
        }
        else
        {
            Assert.Equal(0, reader.ScreenReads);
            Assert.Null(pipeline.PhoneChat);
        }
    }

    [Fact]
    public void DarkTextPassKeepsDarkInkOnLightBubble()
    {
        var frame = Frame.Blank(20, 20);
        Array.Fill(frame.Pixels, (byte)245);
        var p = 10 * frame.Stride + 10 * 4;
        frame.Pixels[p] = frame.Pixels[p + 1] = frame.Pixels[p + 2] = 10;
        Assert.Equal(0, TextImage.DarkTextOnly(frame).Pixels[p]);
        Assert.Equal(255, TextImage.WhiteTextOnly(frame).Pixels[p]);
    }

    [Fact]
    public void DebugFramesArePngAndOnlyNewestTwentyAreKept()
    {
        var directory = Path.Combine(Path.GetTempPath(), "c25-" + Guid.NewGuid());
        try
        {
            var at = new DateTime(2026, 11, 23, 15, 35, 0);
            for (var i = 0; i < 23; i++)
                Assert.NotNull(ScreenDebugFrames.Save(Frame.Blank(4, 4), "test", directory, at.AddSeconds(i)));
            Assert.Equal(20, Directory.GetFiles(directory).Length);
            Assert.False(File.Exists(Path.Combine(directory, "screen-20261123-153500.png")));
            var bytes = File.ReadAllBytes(Path.Combine(directory, "screen-20261123-153522.png"));
            Assert.Equal(new byte[] { 137, 80, 78, 71, 13, 10, 26, 10 }, bytes[..8]);
            Assert.Null(ScreenDebugFrames.Save(Frame.Blank(0, 0), "invalid", directory));
        }
        finally { if (Directory.Exists(directory)) Directory.Delete(directory, true); }
    }
}
