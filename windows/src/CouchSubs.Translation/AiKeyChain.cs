// SPDX-License-Identifier: AGPL-3.0-only

using System.Collections.Concurrent;
using CouchSubs.Core;

namespace CouchSubs.Translation;

/// <summary>
/// Arabic fork: several Gemini keys (one per Google account, each with its own free per-minute limit) pasted into the
/// one key field, separated by commas or spaces. Each key becomes one <see cref="AiTranslator"/> with its own request
/// budget; a limited key hands the line to the next key through the fallback chain, and the last key falls back to the
/// given fallback. One key behaves exactly as before (shared budget, no chain).
/// </summary>
public static class AiKeyChain
{
    private static readonly char[] Separators = [',', ';', ' ', '\t', '\r', '\n'];
    private static readonly ConcurrentDictionary<string, RequestBudget> Budgets = new(StringComparer.Ordinal);

    public static string[] Keys(string keys) => keys.Split(Separators, StringSplitOptions.RemoveEmptyEntries);

    /// <summary>The key "Save and test" checks: the first one.</summary>
    public static string First(string keys) => Keys(keys) is [var first, ..] ? first : keys.Trim();

    /// <param name="make">Builds one link: (key, next link or fallback, its own budget or null for the shared one).</param>
    public static ITextTranslator Build(string keys, AiProvider provider, AiModel model, ITextTranslator? fallback,
                                        Func<string, ITextTranslator?, RequestBudget?, ITextTranslator> make)
    {
        var list = Keys(keys);
        if (list.Length <= 1) return make(First(keys), fallback, null);
        var next = fallback;
        for (var i = list.Length - 1; i >= 0; i--)
        {
            var key = list[i];
            var budget = model.RequestsPerMinute is { } perMinute
                ? Budgets.GetOrAdd(provider.EngineId(model.Id) + "\u001f" + AiRequest.KeyTag(key), _ => new RequestBudget(perMinute))
                : null;
            next = make(key, next, budget);
        }
        return next!;
    }
}
