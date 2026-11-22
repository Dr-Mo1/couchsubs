// SPDX-License-Identifier: AGPL-3.0-only
namespace CouchSubs.Core.Tests;

public partial class RegionWatcherTests
{
    [Fact]
    public async Task InstructionTimerHidesWithoutAnotherFrame()
    {
        var reader = new Reader { Lines = [Line(ControllerPromptTests.Hint)] };
        var server = new Server();
        var watcher = new RegionWatcher(reader, new Translator(), server)
        { PackLookup = _ => new PackMatch("ترجمة التعليمة", []) };
        var mission = Hud with { Id = MissionLine.AreaId, Auto = true };
        await watcher.PassAsync(Shade(20), [mission]);
        await watcher.PassAsync(Shade(20), [mission]);
        Assert.Single(server.Sent, item => item.Text.Length > 0);
        var deadline = DateTime.UtcNow.AddMilliseconds(RegionWatcher.InstructionDisplayMs + 3000);
        bool hidden;
        do
        {
            await Task.Delay(50);
            lock (server.Sent) hidden = server.Sent[^1].Text.Length == 0;
        } while (!hidden && DateTime.UtcNow < deadline);
        Assert.True(hidden);
        watcher.Reset();
    }

    [Fact]
    public async Task InstructionMemoryKeepsOnlyTheLastTwentyIdentities()
    {
        var reader = new Reader();
        var server = new Server();
        var watcher = new RegionWatcher(reader, new Translator(), server)
        { PackLookup = _ => new PackMatch("ترجمة التعليمة", []) };
        var mission = Hud with { Id = MissionLine.AreaId, Auto = true };
        async Task Read(int number)
        {
            await watcher.PassAsync(Shade(0), []);
            reader.Lines = [Line($"Press Square to open locker number {number}")];
            await watcher.PassAsync(Shade(20), [mission]);
            await watcher.PassAsync(Shade(20), [mission]);
        }
        for (var number = 10; number <= 10 + RegionWatcher.RecentInstructionLimit; number++) await Read(number);
        Assert.Equal(21, server.Sent.Count(item => item.Text.Length > 0));
        await Read(11);
        Assert.Equal(21, server.Sent.Count(item => item.Text.Length > 0));
        await Read(10);
        Assert.Equal(22, server.Sent.Count(item => item.Text.Length > 0));
        watcher.Reset();
    }

    [Theory]
    [InlineData("ar", false)]
    [InlineData("ar", true)]
    [InlineData("tr", false)]
    [InlineData("tr", true)]
    public async Task DivingHintShowsOnceWhileGuideDialogueTranslatesNormally(string target, bool packed)
    {
        long clock = 0;
        var hintReader = new Reader();
        var dialogueReader = new Reader();
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
        var translator = new Translator { Respond = source =>
        {
            if (source == ControllerPromptTests.Dialogue) { entered.TrySetResult(); return release.Task; }
            return Task.FromResult("ترجمة التعليمة");
        } };
        var server = new Server();
        var sender = new FakeSender();
        var watcher = new RegionWatcher(hintReader, translator, server, clock: () => clock);
        if (packed) watcher.PackLookup = _ => new PackMatch("ترجمة التعليمة", []);
        var pipeline = new SubtitlePipeline(dialogueReader, translator, sender, clock: () => clock)
        { TargetLanguage = target, PackWait = 0, CleanWait = 0 };
        pipeline.DialogueArrived += watcher.DialogueArrived;
        var mission = Hud with { Id = MissionLine.AreaId, Auto = true };
        byte shade = 20;
        async Task Hint(string source)
        {
            hintReader.Lines = [Line(source)];
            for (var i = 0; i < 8; i++) await watcher.PassAsync(Shade(shade), [mission]);
            shade += 20;
        }
        await Hint(ControllerPromptTests.Hint);
        Assert.Single(server.Sent, item => item.Text.Length > 0);
        dialogueReader.Lines = [Line(ControllerPromptTests.Dialogue, 0.6)];
        for (var i = 0; i < 2; i++)
        {
            await pipeline.ProcessAsync(Frame.Blank(64, 64), new CropRegion(0, 1, 0, 1));
            clock += 500;
        }
        await entered.Task.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.Equal("", server.Sent[^1].Text);
        foreach (var variant in ControllerPromptTests.Variants) await Hint(variant);
        release.SetResult("مادفعناش لمرشد؟ مش حاسس إن حد بيرشدنا دلوقتي.");
        await pipeline.WaitForTranslationsAsync();
        Assert.Contains(sender.Sent, text => text.Contains("مادفعناش"));
        Assert.Equal(1, translator.Calls.Count(text => text == ControllerPromptTests.Dialogue));
        Assert.Single(server.Sent, item => item.Text.Length > 0);
        await Hint("Press Square to open the equipment locker");
        Assert.Equal(2, server.Sent.Count(item => item.Text.Length > 0));
        await Hint("Press Square to open the equipment locker");
        Assert.Equal(2, server.Sent.Count(item => item.Text.Length > 0));
        watcher.Reset();
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task InstructionExpiresAndRemembersVariantsAcrossRemoval(bool dialogue)
    {
        long clock = 0;
        var reader = new Reader { Lines = [Line(ControllerPromptTests.Hint)] };
        var translator = new Translator { Map = _ => "ترجمة التعليمة" };
        var server = new Server();
        var watcher = new RegionWatcher(reader, translator, server, clock: () => clock);
        var mission = Hud with { Id = MissionLine.AreaId, Auto = true };
        async Task Read(string text, byte shade)
        {
            reader.Lines = [Line(text)];
            for (var i = 0; i < 8; i++) await watcher.PassAsync(Shade(shade), [mission]);
        }
        await Read(ControllerPromptTests.Hint, 20);
        Assert.Single(server.Sent, item => item.Text.Length > 0);
        clock = dialogue ? 1000 : RegionWatcher.InstructionDisplayMs - 1;
        watcher.ExpireInstruction();
        Assert.DoesNotContain(server.Sent, item => item.Text.Length == 0);
        if (dialogue) watcher.DialogueArrived();
        else { clock++; watcher.ExpireInstruction(); }
        Assert.Equal("", server.Sent[^1].Text);
        foreach (var variant in ControllerPromptTests.Variants) await Read(variant, 80);
        await watcher.PassAsync(Shade(0), []);
        await Read(ControllerPromptTests.Variants[^1], 110);
        Assert.Single(server.Sent, item => item.Text.Length > 0);
        Assert.Single(translator.Calls);
        await Read("Press Square to open the equipment locker", 160);
        Assert.Equal(2, server.Sent.Count(item => item.Text.Length > 0));
        Assert.Equal(2, translator.Calls.Count);
        watcher.Reset();
    }

    [Fact]
    public async Task DialogueDuringHintTranslationPreventsLateHintDisplay()
    {
        var reader = new Reader { Lines = [Line(ControllerPromptTests.Hint)] };
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
        var translator = new Translator { Respond = _ => { entered.TrySetResult(); return release.Task; } };
        var server = new Server();
        var watcher = new RegionWatcher(reader, translator, server, intervalMs: 0);
        var mission = Hud with { Id = MissionLine.AreaId, Auto = true };
        watcher.Offer(Shade(20), [mission]);
        await WaitForPass(watcher);
        watcher.Offer(Shade(20), [mission]);
        await entered.Task.WaitAsync(TimeSpan.FromSeconds(5));
        watcher.DialogueArrived();
        release.SetResult("Hint translation");
        await WaitForPass(watcher);
        for (var i = 0; i < 8; i++) await watcher.PassAsync(Shade(40), [mission]);
        Assert.DoesNotContain(server.Sent, item => item.Text.Length > 0);
        Assert.Single(translator.Calls);
        watcher.Reset();
    }

    [Fact]
    public async Task MissionHintOcrVariantDoesNotInvalidateItsInFlightTranslation()
    {
        var reader = new Reader { Lines = [Line(ControllerPromptTests.Hint)] };
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
        var translator = new Translator { Respond = _ => { entered.TrySetResult(); return release.Task; } };
        var server = new Server();
        var watcher = new RegionWatcher(reader, translator, server, intervalMs: 0);
        var mission = Hud with { Id = MissionLine.AreaId, Auto = true };
        watcher.Offer(Shade(20), [mission]);
        await WaitForPass(watcher);
        watcher.Offer(Shade(20), [mission]);
        await entered.Task.WaitAsync(TimeSpan.FromSeconds(5));
        reader.Lines = [Line(ControllerPromptTests.Variants[^1])];
        watcher.Offer(Shade(100), [mission]);
        release.SetResult("Keep aiming until you find the signal.");
        await WaitForPass(watcher);
        watcher.Offer(Shade(100), [mission]);
        await WaitForPass(watcher);
        Assert.Single(translator.Calls);
        Assert.Equal("Keep aiming until you find the signal.", Assert.Single(server.Sent).Text);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task MissionHintVariantsTranslateAndDisplayOnceEvenAfterRepeatedConfirmation(bool packed)
    {
        var reader = new Reader();
        var translator = new Translator { Map = _ => "Keep aiming until you find the signal." };
        var server = new Server();
        var watcher = new RegionWatcher(reader, translator, server);
        var packCalls = 0;
        if (packed) watcher.PackLookup = _ => { packCalls++; return new PackMatch("Signal hint translation", []); };
        var mission = Hud with { Id = MissionLine.AreaId, Auto = true };
        byte shade = 20;
        foreach (var variant in ControllerPromptTests.Variants)
        {
            reader.Lines = [Line(variant)];
            shade += 20;
            for (var pass = 0; pass < 6; pass++) await watcher.PassAsync(Shade(shade), [mission]);
        }
        Assert.Equal(packed ? 0 : 1, translator.Calls.Count);
        Assert.Equal(packed ? 1 : 0, packCalls);
        Assert.Equal(MissionLine.AreaId, Assert.Single(server.Sent).Id);
    }

    [Fact]
    public void ControllerMissionHasAnAreaImmediatelyAndKeepsItThroughRereads()
    {
        var mission = new MissionLine();
        var area = new TextBox(0, 0, 1, 1);
        Assert.True(mission.Observe([Line(ControllerPromptTests.Hint)], area, 0));
        var region = mission.Region;
        Assert.NotNull(region);
        long clock = 0;
        foreach (var variant in ControllerPromptTests.Variants)
        {
            clock += 500;
            Assert.False(mission.Observe([Line(variant)], area, clock));
            Assert.Equal(region, mission.Region);
        }
        Assert.True(mission.Observe([], area, clock + MissionLine.ForgetMs + 1));
        Assert.Null(mission.Region);
    }
}
