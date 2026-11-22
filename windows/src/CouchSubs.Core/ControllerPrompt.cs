// SPDX-License-Identifier: AGPL-3.0-only
using System.Text.RegularExpressions;

namespace CouchSubs.Core;

/// <summary>Controller hints share an identity even when OCR loses their button glyphs.</summary>
internal static class ControllerPrompt
{
    private static readonly Regex Buttons = new(@"\b(?:[LR][123]|Triangle|Circle|Square|Cross|Options|Touchpad)\b|[△▲○◯□▢✕×✖\uFFFC]",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private static readonly Regex Verbs = new(@"\b(?:Hold|Press|Tap|Use|Toggle|Move)\b",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);

    public static bool IsPrompt(string text)
    {
        if (SpeakerPrefix.Parse(text) is not null) return false;
        var verb = Verbs.Match(text);
        if (!verb.Success) return false;
        var nearbyButton = Buttons.Matches(text).Any(button =>
        {
            var start = Math.Min(verb.Index + verb.Length, button.Index + button.Length);
            var end = Math.Max(verb.Index, button.Index);
            return TextMatch.Normalize(text[start..end]).Split(' ', StringSplitOptions.RemoveEmptyEntries).Length <= 2;
        });
        if (nearbyButton) return true;
        if (verb.Index != text.Length - text.TrimStart().Length) return false;
        // Without a button, punctuated speech and "Hold the gate / Hold your fire / Hold on" are ambiguous dialogue.
        // Empty controller slots still leave "Hold or and aim ...", so the diving tutorial remains recognizable.
        return !SubtitleStabilizer.EndsSentence(text)
            && !Regex.IsMatch(text.TrimStart(), @"^hold\s+(?:the|a|an|my|your|his|her|our|their|on|up|it|me|us)\b",
                RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    }

    public static string Identity(string text)
    {
        var normalized = TextMatch.Normalize(Buttons.Replace(text, " "));
        // A lone digit is button debris only inside an instruction; dialogue numbers retain their meaning.
        return IsPrompt(text) ? Regex.Replace(normalized, @"\b\d\b", " ").SplitWords() : normalized;
    }

    private static string SplitWords(this string text) =>
        string.Join(' ', text.Split(' ', StringSplitOptions.RemoveEmptyEntries));

    public static bool Same(string a, string b)
    {
        var x = Identity(a);
        var y = Identity(b);
        return !TextMatch.AddsMeaning(x, y) && !TextMatch.AddsMeaning(y, x)
            && TextMatch.IsSimilar(x, y, 0.9);
    }
}
