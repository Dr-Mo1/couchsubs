// SPDX-License-Identifier: AGPL-3.0-only

namespace CouchSubs.Core;

/// <summary>Arabic fork: request-scoped speaker metadata, also visible to awaited fallback translators.</summary>
public static class TranslationDialogue
{
    private static readonly AsyncLocal<string?> Speaker = new();

    public static string? CurrentSpeaker
    {
        get => Speaker.Value;
        set => Speaker.Value = value;
    }
}
