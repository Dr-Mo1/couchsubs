// SPDX-License-Identifier: AGPL-3.0-only
namespace CouchSubs.Core.Tests;

public class ControllerPromptTests
{
    internal const string Hint = "Hold L2 or R2 and aim around until you locate a signal";
    internal const string Dialogue = "Didn't we pay for a guide? I don't feel very guided right now.";
    internal static readonly string[] Variants =
    [
        Hint,
        "Hold 2 or and aim around until you locate a signal",
        "Hold 2 or 2 and aim around until you locate a signal",
        "Hold or and aim around until you locate a signal",
        "Hold 2 or 2 and aim around until you locate a signal",
        "Hold or and aim around until you locate a signal",
    ];

    [Theory]
    [InlineData("L1 L2 L3 R1 R2 R3")]
    [InlineData("△ ○ □ ✕")]
    [InlineData("Triangle Circle Square Cross Options Touchpad")]
    [InlineData("2")]
    [InlineData("")]
    public void ButtonTokensAndEmptySlotsHaveOneIdentity(string buttons)
    {
        var text = $"Hold {buttons} or and aim around until you locate a signal";
        Assert.True(ControllerPrompt.IsPrompt(text));
        Assert.True(ControllerPrompt.Same(Hint, text));
        Assert.True(ControllerPrompt.Same(Hint, text.Replace("locate", "locete")));
    }

    [Theory]
    [InlineData("Hold")]
    [InlineData("Press")]
    [InlineData("Tap")]
    [InlineData("Use")]
    [InlineData("Toggle")]
    [InlineData("Move")]
    public void InstructionsAreRemovedOnTheirFirstRead(string verb)
    {
        var rows = new StickyRows();
        var text = verb + " or and aim around until you locate a signal";
        Assert.Empty(rows.Filter(new Reading([new TextLine(text)]), 0).Lines);
        Assert.Equal(text, Assert.Single(rows.Removed).Text);
    }

    [Fact]
    public void NearbyButtonAndVerbAreAPromptButNamedSpeechAndNumbersRemainDialogue()
    {
        Assert.True(ControllerPrompt.IsPrompt("To aim, hold L2 and look around"));
        Assert.False(ControllerPrompt.IsPrompt("Lucia: Hold L2 and look around."));
        Assert.False(ControllerPrompt.IsPrompt("Hold the gate"));
        Assert.False(ControllerPrompt.IsPrompt("Hold your fire until I return."));
        Assert.False(ControllerPrompt.Same("We need 2 cars.", "We need 3 cars."));
    }

    [Fact]
    public void StaticTrackerKeepsOneIdentityThroughAllReportedRereads()
    {
        var rows = new StickyRows();
        foreach (var text in Variants)
        {
            Assert.Empty(rows.Filter(new Reading([new TextLine(text)]), 0).Lines);
            Assert.Single(rows.Removed);
            Assert.Equal(text == Hint ? 1 : 0, rows.NewlySticky.Count);
        }
    }

    [Theory]
    [InlineData("ar")]
    [InlineData("tr")]
    public async Task ReportedHintSequenceNeverSupersedesThePendingDialogue(string target)
    {
        long clock = 0;
        var reader = new MutableReader();
        var translator = new PendingTranslation();
        var sender = new FakeSender();
        var logs = new List<string>();
        var regions = new List<CropRegion?>();
        var pipeline = new SubtitlePipeline(reader, translator, sender, clock: () => clock)
        {
            TargetLanguage = target, PackWait = 0, CleanWait = 0, Log = logs.Add,
            PackLookup = text => text.StartsWith("Hold") ? new PackMatch("hint translation", [])
                : text.StartsWith("Swim closer") ? new PackMatch("swim translation", []) : null,
        };
        pipeline.MissionChanged += regions.Add;
        const string swim = "Swim closer while maintaining the signal";
        async Task Read(string text, int milliseconds = 500, bool withObjective = false)
        {
            var top = text == Dialogue ? 0.60 : text == swim ? 0.72 : 0.84;
            var lines = new List<TextLine> { new(text, new TextBox(0.15, top, 0.85, top + 0.06), 0.99) };
            if (withObjective) lines.Add(new TextLine(swim, new TextBox(0.2, 0.72, 0.8, 0.78), 0.99));
            reader.Current = new Reading(lines);
            await pipeline.ProcessAsync(Frame.Blank(64, 64), new CropRegion(0, 1, 0, 1));
            clock += milliseconds;
        }
        // #220-225: one hint with button OCR variants, plus the separate swim objective.
        await Read(Variants[0]);
        await Read(Variants[1]);
        await Read(swim);
        await Read(swim);
        foreach (var text in Variants.Skip(2))
            for (var i = 0; i < 4; i++) await Read(text, withObjective: true);
        // #226 is still in flight when packed #227 is read twice.
        await Read(Dialogue, withObjective: true);
        await Read(Dialogue, withObjective: true);
        await translator.Started.Task.WaitAsync(TimeSpan.FromSeconds(5));
        await Read(Variants[^1]);
        await Read(Variants[^1]);
        translator.Result.SetResult("مادفعناش لمرشد؟ مش حاسس إن حد بيرشدنا دلوقتي.");
        await pipeline.WaitForTranslationsAsync();
        Assert.Contains(Dialogue, translator.Requested);
        Assert.True(sender.Sent.Any(text => text.Contains("مادفعناش")), string.Join('\n', logs));
        Assert.DoesNotContain(sender.Sent, text => text.Contains("hint translation"));
        Assert.DoesNotContain(logs, text => text.Contains("daha yeni satır gösterildi"));
        Assert.Contains(logs, text => text.Contains("sabit satır:") && text.Contains(swim));
        Assert.NotNull(regions.First());
    }

    private sealed class MutableReader : ITextReader
    {
        public Reading Current { get; set; } = Reading.Empty;
        public string Read(Frame image) => Current.Text;
        public Reading ReadLines(Frame image, bool deep) => Current;
    }

    private sealed class PendingTranslation : ITextTranslator
    {
        public List<string> Requested { get; } = [];
        public TaskCompletionSource Started { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public TaskCompletionSource<string> Result { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public Task<string> TranslateAsync(string text, CancellationToken cancellationToken = default)
        {
            Requested.Add(text);
            Started.TrySetResult();
            return Result.Task.WaitAsync(cancellationToken);
        }
    }
}
