// SPDX-License-Identifier: AGPL-3.0-only

namespace CouchSubs.Core;

/// <summary>GTA VI's encrypted Messages screen, identified before subtitle cropping loses the header.</summary>
public static class GtaViPhoneMessages
{
    public sealed record Chat(TextBox Column, IReadOnlyList<TextLine> Lines,
                              IReadOnlyList<ScreenTranslation.Group> Bubbles)
    {
        public bool Contains(TextLine line) => line.Box is { } box
            && SpeakerPrefix.Parse(line.Text) is null
            && box.Left >= Column.Left - .015 && box.Right <= Column.Right + .015
            && box.Top >= Column.Top && box.Bottom <= Column.Bottom;

        public Reading WithoutMessages(Reading reading, TextBox? area = null) => new(reading.Lines.Where(line =>
            !Contains(area is { } place ? line with { Box = line.Box?.PlacedIn(place) } : line)));
    }

    private static string Label(string text) => TextMatch.Normalize(text);

    public static Chat? Find(Reading reading)
    {
        // Tilted phone rows can have overlapping axis-aligned boxes; overlap alone is not a duplicate here.
        var lines = reading.Lines.Distinct()
            .Where(line => line.Box is { Left: >= .55 } box && box.Right - box.Left <= .36)
            .OrderBy(line => line.Box!.Value.Top).ThenBy(line => line.Box!.Value.Left).ToList();
        var encrypted = lines.FirstOrDefault(line => Label(line.Text) == "messages encrypted");
        if (encrypted?.Box is not { } header) return null;
        var newMessage = lines.FirstOrDefault(line => Label(line.Text) == "new message"
            && line.Box is { } box && Math.Abs(box.Top - header.Top) < .22
            && Math.Abs(box.Left - header.Left) < .12);
        if (newMessage?.Box is not { } next) return null;
        var left = Math.Max(.5, Math.Min(header.Left, next.Left) - .035);
        var right = Math.Min(1, left + .36);
        var column = new TextBox(left, Math.Min(header.Top, next.Top), right, .98);
        var own = lines.Where(line => SpeakerPrefix.Parse(line.Text) is null
            && line.Box is { } box && box.Left >= left && box.Right <= right
            && box.Top >= column.Top && box.Bottom <= column.Bottom).ToList();
        var body = own.Where(line => line != encrypted && line != newMessage
            && Label(line.Text) is not ("typing" or "delivered" or "read"))
            .SkipWhile(line => IsContact(line.Text)
                && line.Box!.Value.Top <= Math.Max(header.Bottom, next.Bottom) + .06).ToList();
        if (body.Count < 2) return null;
        var height = body.Select(line => line.Box!.Value.Bottom - line.Box!.Value.Top).Order().ElementAt(body.Count / 2);
        if (height <= 0 || height > .06) return null;
        var bubbles = new List<ScreenTranslation.Group>();
        var bubble = new List<TextLine>();
        void Finish()
        {
            if (bubble.Count == 0) return;
            bubbles.Add(new([string.Join(' ', bubble.Select(line => line.Text.Trim()))],
                bubble.Select(line => line.Box!.Value).Aggregate((a, b) => a.Union(b)), height)
                { IsPhoneMessage = true });
            bubble.Clear();
        }
        foreach (var line in body)
        {
            if (bubble.Count > 0)
            {
                var previous = bubble[^1].Box!.Value;
                var box = line.Box!.Value;
                if (box.Top - previous.Bottom > height * .9
                    || Math.Abs(box.Left - bubble[0].Box!.Value.Left) > .06) Finish();
            }
            bubble.Add(line);
        }
        Finish();
        return new(column, own, bubbles);
    }

    private static bool IsContact(string text)
    {
        var words = text.Split(' ', StringSplitOptions.RemoveEmptyEntries);
        return words.Length is > 0 and <= 4 && words.All(word => char.IsUpper(word[0]) && word.All(char.IsLetter));
    }

    /// <summary>Only the phone side is read; all returned boxes are in full-frame coordinates.</summary>
    public static Chat? Read(Frame frame, ITextReader reader)
    {
        var region = new CropRegion(Top: 0, Bottom: 1, Left: .5, Right: 1);
        var crop = RegionCropper.Crop(frame, region);
        var reading = reader.ReadScreen(crop.Height < 900 ? crop.Scaled(2) : crop);
        return Find(reading.PlacedIn(new TextBox(.5, 0, 1, 1)));
    }
}
