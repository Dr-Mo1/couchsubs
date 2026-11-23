// SPDX-License-Identifier: AGPL-3.0-only

namespace CouchSubs.Core;

/// <summary>Arabic fork: recent protagonist labels, independent of the current speaker.</summary>
public sealed class PlayerCharacterInference(Func<long> clock)
{
    private readonly object gate = new();
    private readonly Queue<(string Name, long At)> labels = new();
    public const long WindowMs = 15 * 60 * 1000;
    // One stray label (Lucia on the phone while Jason roams) must not flip the gender: until three labels agree, the
    // character stays unknown and the prompt's gender-neutral insults apply, which are safe for both.
    public const int MinimumLabels = 3;

    public static string? Normalize(string? name) => name?.Trim().ToLowerInvariant() switch
    {
        "jason" => "Jason",
        "lucia" => "Lucia",
        _ => null,
    };

    public void Observe(string? label)
    {
        if (Normalize(label) is not { } name) return;
        lock (gate)
        {
            labels.Enqueue((name, clock()));
            while (labels.Count > 10) labels.Dequeue();
        }
    }

    public string? Resolve(string? selection = "auto")
    {
        if (Normalize(selection) is { } manual) return manual;
        lock (gate)
        {
            var cutoff = clock() - WindowMs;
            while (labels.TryPeek(out var first) && first.At < cutoff) labels.Dequeue();
            if (labels.Count < MinimumLabels) return null;
            var jason = labels.Count(line => line.Name == "Jason");
            if (jason * 10 >= labels.Count * 7) return "Jason";
            return (labels.Count - jason) * 10 >= labels.Count * 7 ? "Lucia" : null;
        }
    }
}
