// SPDX-License-Identifier: AGPL-3.0-only

using CouchSubs.Core;

namespace CouchSubs.Core.Tests;

public class ArabicSpeakerNamesTests
{
    private sealed class SpeakerSink : ISubtitleSender
    {
        public List<(string Text, string Source, string Target)> Sent { get; } = [];
        public void SendSubtitle(string text, int durationMs = 15000, TextBox? box = null, bool paragraphs = false, bool replaces = false) =>
            throw new InvalidOperationException("Arabic source metadata was lost.");
        public void SendPresentedSubtitle(string text, string source, string target, int durationMs, TextBox? box, bool replaces) =>
            Sent.Add((text, source, target));
        public void Clear() { }
    }

    [Fact]
    public async Task PackPresentationPassesTheEnglishIdentityToTheSender()
    {
        var sink = new SpeakerSink();
        const string source = "Lucia: We need to go.";
        var pipeline = new SubtitlePipeline(new FakeReader(source, source), new FakeTranslator(), sink)
        {
            TargetLanguage = "ar",
            SkipsTurkish = false,
            PackLookup = text => text == "We need to go." ? new PackMatch("لازم نمشي.", []) : null,
        };
        for (var i = 0; i < 2; i++)
        {
            await pipeline.ProcessAsync(Frame.Blank(64, 64), new CropRegion());
            await pipeline.WaitForTranslationsAsync();
        }
        Assert.Equal(("لوسيا: لازم نمشي.", source, "ar"), Assert.Single(sink.Sent));
    }

    [Theory]
    [InlineData("Lucia", "لوسيا")]
    [InlineData("JASON", "جيسون")]
    [InlineData("brian's", "برايان")]
    [InlineData("Brian’S", "برايان")]
    [InlineData("Lucia Caminos", "لوسيا كامينوس")]
    [InlineData("Pilot Pete's", "الطيار بيت")]
    [InlineData("Arnold", "أرنولد")]
    [InlineData("Someone Unknown", "Someone Unknown")]
    [InlineData("Unknown's", "Unknown's")]
    [InlineData("Vice City", "Vice City")]
    public void OnlyKnownCharactersAreMapped(string name, string expected) =>
        Assert.Equal(expected, ArabicSpeakerNames.Display(name));

    [Theory]
    [InlineData("تعالي.", "Lucia: Come.", true, "لوسيا: تعالي.")]
    [InlineData("Lucia: تعالي.", "Lucia: Come.", true, "لوسيا: تعالي.")]
    [InlineData("لوسيا: تعالي.", "Lucia: Come.", true, "لوسيا: تعالي.")]
    [InlineData("لوسيا: تعالي.", "Lucia: Come.", false, "تعالي.")]
    [InlineData("تعالي.", "Lucia: Come.", false, "تعالي.")]
    [InlineData("Lucia: تعالي.\nJason: حاضر.", "Lucia: Come.\nJason: Okay.", true, "لوسيا: تعالي.\nجيسون: حاضر.")]
    [InlineData("لوسيا: تعالي.\nجيسون: حاضر.", "Lucia: Come.\nJason: Okay.", false, "تعالي.\nحاضر.")]
    [InlineData("وصل.", "Brian's: Arrived.", true, "برايان: وصل.")]
    [InlineData("تعال.", "Stranger: Come.", true, "Stranger: تعال.")]
    [InlineData("الهدف: تعال.", "Objective: Come.", true, "الهدف: تعال.")]
    public void ArabicPresentationUsesSourceLabels(string translation, string source, bool show, string expected) =>
        Assert.Equal(expected, SpeakerPrefix.Present(translation, source, show, "ar"));

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public void TurkishPresentationIsUnchanged(bool show)
    {
        const string source = "Lucia: Come.\nJason: Okay.";
        const string translated = "Lucia: Gel.\nJason: Tamam.";
        Assert.Equal(SpeakerPrefix.Present(translated, source, show), SpeakerPrefix.Present(translated, source, show, "tr"));
    }
}
