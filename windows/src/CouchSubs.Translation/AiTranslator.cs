// SPDX-License-Identifier: AGPL-3.0-only

using System.Collections.Concurrent;
using System.Net;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using CouchSubs.Core;
using CouchSubs.Core.Dubbing;

namespace CouchSubs.Translation;

/// <summary>Bağlam olarak giden, daha önce gösterilmiş satır ve (biliniyorsa) çevirisi.</summary>
public sealed record AiContextLine(string Source, string? Translation = null, string? Speaker = null)
{
    public string? PlayerCharacter { get; init; }
}

/// <summary>Arabic fork: the lines shown so far; one log is shared by every key and model of a chain, so each sees the scene.</summary>
public sealed class AiContextLog
{
    internal List<AiContextLine> Lines { get; } = [];
    internal string? Game { get; set; }
}

/// <summary>Yapay zekâ hizmeti çeviremedi; yalnız durum taşır, anahtar ve hizmetin yanıt metni hiçbir yere yazılmaz.</summary>
public sealed class AiException(OnlineKeyState state) : Exception($"AI translation failed: {state}")
{
    public OnlineKeyState State { get; } = state;
}

/// <summary>
/// Üç hizmet için istek kurma ve yanıt okuma (Mac'teki CouchSubsCore/AITranslation.swift ile aynı). Cümleler bir JSON
/// dizisi olarak gider, aynı sırada JSON dizisi beklenir; böylece cümle cümle önbelleğe yazılır ve dublaj konuşmacıyı
/// bilir (DeepL ile aynı düzen).
/// </summary>
public static class AiRequest
{
    /// <summary>Bir altyazı satırına yeten çıktı sınırı; düşünen modellere de pay bırakır.</summary>
    public const int MaxOutputTokens = 1024;
    /// <summary>"Ekranı çevir"in tek isteğinde çıktı sınırı: ekrandaki bütün yazılar birlikte döner.</summary>
    public const int ScreenOutputTokens = 8192;

    public const string SystemPrompt =
        "You translate English video game subtitles into natural, spoken Turkish, the way people really talk. " +
        "Characters address each other informally (use \"sen\", not \"siz\") unless the line is clearly formal. " +
        "Keep character names, place names and speaker labels such as \"ATREUS:\" exactly as they are. " +
        "Keep line breaks. Keep the meaning and tone; do not add or leave out anything. " +
        // #296: OCR kaçan/yanlış harfler ve önceki altyazının devamı olan satırlar.
        "The text is read from the screen by OCR and may have misread or missing letters (\"Y u ave to ask?\", " +
        "\"Doesn't 00k good.\"); translate the line the speaker clearly meant. " +
        "A subtitle may continue the previous one (it starts in lowercase or with a word like \"and\"); " +
        "translate it so that it reads as that continuation. " +
        // #827: menü ve görev etiketi kısa, tekil emir kipi ("Para Kazan", "Para Kazanın" değil).
        "Short menu items, objectives and button labels without a subject (\"Make Money\", \"Find Donnie's Package\") " +
        "are commands to the player: translate them as short singular informal imperatives (\"Para Kazan\", " +
        "\"Donnie'nin Paketini Bul\"), never polite or plural forms such as \"Para Kazanın\". " +
        "Reply with only the translation, no explanations, notes or quotes.";

    /// <summary>Arabic fork: the system prompt for target "ar" (Modern Standard Arabic, swearing in raw Egyptian).</summary>
    public const string ArabicSystemPrompt =
        // Arabic fork: DocMo switched from Egyptian colloquial to Modern Standard Arabic with a light Egyptian flavour,
        // the swearing staying raw Egyptian (2026-11-22). gemini-flash-lite drifts back to full Egyptian without the word lists.
        // Arabic fork: DocMo's mix (2026-11-22): calm talk in Modern Standard Arabic, heated action lines in Egyptian street Arabic.
        "You translate English video game subtitles into Arabic subtitles that mix two registers, the way an Egyptian gamer " +
        "wants them. Calm talk, story dialogue, explanations and any longer sentence are in Modern Standard Arabic (الفصحى), " +
        "the way the best Arabic film and game subtitles read: simple, flowing and quick to read at a glance, never stiff or " +
        "old-fashioned (\"ماذا تفعل هنا؟\", not \"ما الذي تقوم بفعله هنا؟\"); in those lines use ماذا/لماذا/أين/متى/الآن/هذا/هذه/" +
        "أريد/لا/ليس/الذي and سـ/سوف, never the Egyptian إيه/ليه/فين/إمتى/دلوقتي/ده/دي/عايز/مش/اللي/كده/بتاع or the Egyptian " +
        "verb prefixes بـ/هـ. Short heated lines in the action (a shout, a warning, a quick order or reaction, an insult; only " +
        "a few words) are in Egyptian street Arabic instead, the way Egyptians really shout them (\"أحيه، شافونا! اجري يا سطى!\"). " +
        "Words of address are Egyptian (\"يا سطى\" or \"يا صاحبي\" to a man, \"يا بت\" to a woman or a girl, \"يا عم\", \"يا باشا\"), " +
        "but only where the English has one (man, bro, dude, homie, girl, boss); never add one to a line that has none. " +
        "Never use Levantine or Gulf words such as \"زلمة\", \"شو\", \"هلق\", \"هيك\", \"وايد\" or \"شلون\". " +
        "Translate the meaning, not the words: give the overall meaning of the whole line in natural Arabic, the way a good " +
        "subtitler condenses it (drop fillers such as \"like\", \"I mean\" or \"you know\" when they add nothing), and say idioms, " +
        "slang and jokes the way a good Arabic subtitler would (\"lay low\" = \"سنختفي لفترة\", \"it's been a minute\" = " +
        "\"مرّ وقت طويل\"), never word for word; " +
        "but never change what happens, who does what, names, numbers, or whether someone is a man or a woman. " +
        // Arabic fork: DocMo wants the swearing raw, sexual words included (2026-11-20), but GTA swears in almost every line,
        // so only the swearing that carries meaning is kept and filler swearing is dropped (2026-11-22).
        "Swearing: the characters swear in almost every line, so do not translate every swear word. Keep the swearing that " +
        "carries meaning (an insult aimed at someone, an angry or shocked exclamation, a threat) and drop filler swearing that " +
        "only adds force (\"fucking\", \"the fuck\", \"damn\" or \"shit\" inside a sentence); at most one swear word per line. " +
        "The swearing you keep stays exactly as strong and explicit as the English, sexual words included, in raw Egyptian " +
        "street words, the way Egyptians really curse: never soften or censor it, never turn it into polite words like \"تبًا\", " +
        "\"اللعنة\" or \"أيها الوغد\", never clinical words like \"عضو ذكري\" (say زب، كس، نيك، متناك، شرموطة), and never add an " +
        "insult the English does not have. \"Shit\", \"fuck\" or \"damn\" as an exclamation is \"أحيه\" or \"أحا\", never \"يا نهار " +
        "أسود\", \"تبًا\" or \"اللعنة\", and never an insult at someone such as \"يا متناك\" or \"يا ابن المتناكة\". \"Hey!\" is " +
        "\"اسمع!\" or \"هيه!\" and \"Yo\" is \"اسمع\", never an insult. Condensing never drops a sentence or a fact, only filler. " +
        // Arabic fork: situation-based reactions (reports/reaction-palette.md, DocMo 2026-11-22: "someone caught cheating says it differently").
        "Reactions: choose them from the scene, not from the English word. أحيه/أحا only for sharp shock, awe or rage; mild " +
        "surprise is \"إيه ده؟\"; busted or spotted is \"أحيه، شافونا!\" and then a short order (\"اجري!\", \"اتخبّى!\", \"وطّي!\"); a " +
        "betrayal is short, cold and hurt first (\"بعتنا؟\" / \"بعتني؟\", matching who was sold out), rage after, never أحا; someone " +
        "caught cheating pleads (\"استنى " +
        "أفهّمك\"); fear and pain are short broken bits (\"هنموت!\", \"إلحقني!\", \"آخ!\"); sarcasm has a mocking word (\"برافو " +
        "عليك\", \"يا فنان\"); admiration is one modern word (\"تحفة\", \"فخم\"); never \"يا لهوي\", \"يا خرابي\" or \"يا ساتر\". " +
        "Orders and warnings take the right gender (\"حاسب\"/\"حاسبي\", \"اهدى\"/\"اهدي\"). Police radio and dispatch stay Modern Standard. " +
        // DocMo 2026-11-22: a woman cursing his male character came out as «يا شرموطة»; an insult follows its target.
        "Insults and words of address agree with the person they are aimed at, never with the speaker: a woman cursing a man " +
        "still uses a man's insult, and the reverse. \"Bitch\", \"asshole\" or \"fucker\" have no gender in English, so choose " +
        "by the target: \"bitch\" at a man is \"يا خول\" or \"يا معرص\", at a woman \"يا شرموطة\" or \"يا وسخة\"; \"that bitch\" " +
        "about someone follows that person. When the player character is given (Jason is a man, Lucia is a woman), a " +
        "stranger's line to \"you\" (a pedestrian, a driver, a cop) uses that gender. Keep the strength of the English: " +
        // DocMo 2026-11-23: strong insults stay strong (his own list); an unknown target gets the man's form, a mild one «يا زفت».
        "\"motherfucker\" or \"son of a bitch\" at a man is a strong insult, varied: \"يا معرص\", \"يا خول\", \"يا متناك\" or " +
        "\"يا ابن الشرموطة\"; at a woman \"يا شرموطة\", \"يا بنت الشرموطة\" or \"يا وسخة\"; a man's friends joking with " +
        "him say \"يلا يا علق\" or \"يا متناك\". When nothing shows who is addressed, a strong insult takes the man's form, " +
        "the Arabic default, and a mild one uses a word that fits both: \"يا زفت\", \"يا زبالة\", \"يا عرّة\" or \"يا جزمة\". " +
        "Examples: \"Damn, they saw us! Run!\" = \"أحيه، شافونا! اجري يا سطى!\"; \"Run, girl!\" = \"اجري يا بت!\"; " +
        "\"What're you doing? Stop!\" = \"بتعمل إيه؟ اقف!\"; \"Get the fuck out of my car, motherfucker!\" = " +
        "\"انزل من العربية يا ابن المتناكة!\"; \"Fuck, I forgot the bag.\" = \"أحيه، نسيت الشنطة.\"; \"Thanks, man.\" = \"تسلم يا سطى.\"; " +
        "\"What are you doing here at this hour, Jason?\" = \"ماذا تفعل هنا في هذه الساعة يا جيسون؟\"; \"I told you the money " +
        "isn't here, we need another way.\" = \"قلت لك إن المال ليس هنا، نحتاج إلى طريقة أخرى.\"; \"Nobody's gonna try shit in " +
        "the fucking hospital, you know?\" = \"لن يجرؤ أحد على فعل شيء في المستشفى، أتفهم؟\" " +
        "When a slang list or a Turkish reference comes with the lines, use it. " +
        "Keep speaker labels such as \"ATREUS:\" exactly as they are. Write character names and place names in Arabic " +
        "letters by how they sound; never translate their meaning. In Grand Theft Auto VI spell them like this: " +
        "Lucia لوسيا, Jason جيسون, Vice City فايس سيتي, Leonida ليونيدا, Leonida Keys ليونيدا كيز, Grassrivers جراس ريفرز, " +
        "Port Gellhorn بورت جيلهورن, Ambrosia أمبروزيا, Mount Kalaga جبل كالاجا, Los Santos لوس سانتوس, " +
        "Liberty City ليبرتي سيتي, San Andreas سان أندرياس; keep agency and brand names in Latin letters as written " +
        "(VCPD, LSPD, FIB, IAA, Ammu-Nation, Lifeinvader, Burger Shot, Cluckin' Bell, Pay 'n' Spray, Sprunk, eCola, Rockstar). " +
        "Keep line breaks. Keep the meaning and tone; do not add anything, and leave out only filler words and filler swearing. " +
        // Arabic fork: subtitle craft from knowledge/translation-rulebook.md + knowledge/game-localization-practice.md
        // (Netflix Arabic TTSG limits, IGDA, Diaz Cintas & Remael), 2026-11-22.
        "Subtitle craft: at most 2 lines of about 42 characters and about 20 characters per second, so a fast line is " +
        "condensed (a quick shout becomes 2-4 words): drop fillers, repeats and tags first, never a fact, name or number. " +
        "Expand terse radio or tactical words when a literal word fails (\"Copy\" = \"عُلم\", \"Clear\" = \"الطريق آمن\"). " +
        "Number: English \"you\" hides it; \"you two\", \"both of you\" or two people addressed = dual in Modern Standard lines " +
        "(أنتما) but plural in Egyptian street lines (إنتو), which have no dual; \"y'all\" or \"you guys\" = plural; with no cue, " +
        "singular. Unknown gender: masculine or a neutral phrasing until the scene shows it. Humor, puns and American " +
        "references: recreate the effect for an Egyptian viewer, never word for word. Punctuation: Arabic ، ؟ ؛ with no space " +
        "before them; no \"?!\"; \"...\" only for a real pause, not for a sentence split over subtitles. Numbers in Western " +
        "digits; money as \"500 دولار\". Expand opaque acronyms (ASAP = فورًا) and give a Latin agency an Arabic noun when " +
        "needed (شرطة VCPD). Keep each character's way of speaking steady through a scene. " +
        // Arabic fork: DocMo 2026-11-22: "we swear by Allah only" (the audio test rendered "on God" as "على الإله").
        "Oaths and religious words: \"I swear to God\", \"on God\", \"swear on my mother\" or \"hand to God\" = \"والله\"; \"Jesus\", " +
        "\"Christ\" or \"God\" used as an exclamation = \"يا الله\" or \"يا إلهي\" (\"أحيه\" in a heated line), never a literal Jesus; " +
        "\"God damn it\" = \"يخرب بيت كده\" or \"أحا\"; never a curse on God or religion. " +
        "The text is read from the screen by OCR and may have misread or missing letters (\"Y u ave to ask?\", " +
        "\"Doesn't 00k good.\"); translate the line the speaker clearly meant. " +
        "A subtitle may continue the previous one (it starts in lowercase or with a word like \"and\"); " +
        "translate it so that it reads as that continuation. " +
        "Reply with only the translation, no explanations, notes or quotes.";

    /// <summary>Arabic fork: the target language as named in the prompt ("tr" and anything else stays Turkish).</summary>
    public static string LanguageName(string target) => target == "ar" ? "Arabic" : "Turkish";

    /// <summary>Kullanıcının ek talimatında en çok bu kadar karakter gider (fazlası kesilir).</summary>
    public const int MaxInstructionLength = 1000;

    /// <summary>Ayarlardaki "Ek talimat": baştaki/sondaki boşluk atılır, sınırdan uzunsa kesilir; boşsa null.</summary>
    public static string? Instruction(string? text)
    {
        var trimmed = text?.Trim() ?? "";
        if (trimmed.Length > MaxInstructionLength) trimmed = trimmed[..MaxInstructionLength].TrimEnd();
        return trimmed.Length == 0 ? null : trimmed;
    }

    /// <summary>
    /// Sistem talimatı: kullanıcının ek talimatı varsa sona eklenir (#306; ör. küfrü yumuşatmadan çevir). Boşsa
    /// <see cref="SystemPrompt"/> olduğu gibi gider.
    /// </summary>
    public static string SystemPromptWith(string? instruction, string target = "tr")
    {
        var prompt = target == "ar" ? ArabicSystemPrompt : SystemPrompt;
        return Instruction(instruction) is { } extra ? prompt + "\n\n" + extra : prompt;
    }

    /// <summary>
    /// Ek talimatın önbellek etiketi: talimat değişince eski çeviriler dönmesin diye anahtarın başına girer. Talimat
    /// yoksa null; o zaman anahtar eskisi gibidir ve önceki çeviriler geçerli kalır (Mac ve telefonla aynı).
    /// </summary>
    public static string? InstructionTag(string? instruction) => Instruction(instruction) is { } extra
        ? Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(Encoding.UTF8.GetBytes(extra)))[..16].ToLowerInvariant()
        : null;

    /// <summary>Arabic fork: a short, irreversible tag naming a key in logs and budgets (the key itself is never logged).</summary>
    public static string KeyTag(string key) =>
        Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(Encoding.UTF8.GetBytes(key.Trim())))[..8].ToLowerInvariant();

    /// <summary>Arabic fork: the quota ids Google named in a 429 ("GenerateRequestsPerMinute...=15"), for altyazi.log.</summary>
    public static string QuotaSummary(string body)
    {
        try
        {
            var violations = (ErrorOf(body)?["details"] as JsonArray ?? new JsonArray())
                .SelectMany(d => d?["violations"] as IEnumerable<JsonNode?> ?? []).OfType<JsonNode>()
                .Select(v => $"{v["quotaId"]}={v["quotaValue"]}").ToList();
            if (violations.Count > 0) return string.Join(",", violations);
            var message = ErrorOf(body)?["message"]?.ToString() ?? "";
            return "no-details: " + (message.Length > 100 ? message[..100] : message);
        }
        catch (Exception error) when (error is JsonException or InvalidOperationException) { return "unreadable"; }
    }

    /// <summary>Arabic fork: "english phrase = مصري" lines (# comments); keys lowercased like <see cref="SlangIn"/> reads lines.</summary>
    public static IReadOnlyList<KeyValuePair<string, string>> ParseGlossary(string text) =>
        [.. text.Split('\n').Select(line => line.Trim()).Where(line => line.Length > 0 && !line.StartsWith('#'))
            .Select(line => line.Split('=', 2)).Where(pair => pair.Length == 2)
            .Select(pair => KeyValuePair.Create(SlangText(pair[0]), pair[1].Trim()))
            .Where(pair => pair.Key.Length > 0 && pair.Value.Length > 0)
            .DistinctBy(pair => pair.Key)];

    /// <summary>Arabic fork: the glossary entries whose English phrase occurs (as whole words) in these lines; at most 12.</summary>
    public static IReadOnlyList<KeyValuePair<string, string>> SlangIn(IReadOnlyList<KeyValuePair<string, string>>? glossary, IEnumerable<string> lines)
    {
        if (glossary is not { Count: > 0 }) return [];
        var text = " " + SlangText(string.Join(" ", lines)) + " ";
        var hits = glossary.Where(entry => text.Contains(" " + entry.Key + " ", StringComparison.Ordinal)).ToList();
        // "come on down" wins over "come on": a hit inside a longer hit is left out.
        return [.. hits.Where(hit => !hits.Any(other => other.Key.Length > hit.Key.Length
                                                        && (" " + other.Key + " ").Contains(" " + hit.Key + " ", StringComparison.Ordinal))).Take(12)];
    }

    /// <summary>
    /// Arabic fork: reviewed line fixes, "english line = السطر المصري" per line (# comments), keyed by the pack canon so
    /// case, punctuation and accents do not matter. A fixed line is shown as written, without asking the engine.
    /// </summary>
    public static IReadOnlyDictionary<string, string> ParseCorrections(string text)
    {
        var fixes = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var line in text.Split('\n').Select(line => line.Trim()).Where(line => line.Length > 0 && !line.StartsWith('#')))
        {
            var at = line.IndexOf(" = ", StringComparison.Ordinal);
            if (at <= 0) continue;
            // "\n" written in the file is a row break (tools/live_review.py keeps two-row lines as two rows).
            var (english, arabic) = (PackKey.Canon(line[..at]), line[(at + 3)..].Trim().Replace("\\n", "\n", StringComparison.Ordinal));
            if (english.Length > 0 && arabic.Length > 0) fixes[english] = arabic;
        }
        return fixes;
    }

    /// <summary>Arabic fork: Persian letters the models sometimes write (پ ی ک گ) as Arabic ones; ڤ (foreign v) stays.</summary>
    public static string ArabicLetters(string text) =>
        text.Replace('\u067E', '\u0628').Replace('\u06CC', '\u064A').Replace('\u06A9', '\u0643').Replace('\u06AF', '\u062C');

    private static string SlangText(string text) =>
        System.Text.RegularExpressions.Regex.Replace(text.ToLowerInvariant().Replace('\u2019', '\''), "[^a-z0-9']+", " ").Trim();

    /// <summary>
    /// Arabic fork: the "error" object of a failed reply. Google's OpenAI-compatible endpoint (used through the Local engine)
    /// wraps it in an array: [{"error": {...}}]; indexing that array by name used to throw past the error handling.
    /// </summary>
    internal static JsonNode? ErrorOf(string body) => JsonNode.Parse(body) switch
    {
        JsonArray { Count: > 0 } list => list[0] is JsonObject first ? first["error"] : null,
        JsonObject root => root["error"],
        _ => null,
    };

    private static readonly JsonSerializerOptions Compact = new() { Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping };

    /// <summary>
    /// Kullanıcı iletisi: önceki satırlar (yalnız bağlam) ve çevrilecek cümleler. <paramref name="names"/>: konuşan
    /// etiketlerinden öğrenilip cümlelerde geçen adlar; model "Price"ı "Fiyat" diye çevirmesin diye adıyla söylenir (#287).
    /// </summary>
    public static string UserMessage(IReadOnlyList<string> sentences, IReadOnlyList<AiContextLine> context, string? game = null,
                                     IReadOnlyList<string>? names = null, string target = "tr",
                                     IReadOnlyList<KeyValuePair<string, string>>? slang = null, string? reference = null,
                                     string? speaker = null, string? playerCharacter = null)
    {
        var language = LanguageName(target);
        var parts = new List<string>();
        if (!string.IsNullOrEmpty(game)) parts.Add($"Game: {game}");
        if (target == "ar")
        {
            AddDialogueHints(parts, sentences, context, speaker);
            if (PlayerCharacterInference.Normalize(playerCharacter) is { } player)
                parts.Add($"Player character: {player} (a {(player == "Jason" ? "man" : "woman")})");
        }
        if (names is { Count: > 0 })
            parts.Add(target == "ar"
                ? $"Character names (write them in Arabic letters by how they sound, do not translate their meaning): {string.Join(", ", names)}"
                : $"Character names (keep them exactly as written, do not translate them): {string.Join(", ", names)}");
        // Arabic fork: glossary entries found in these lines, and the game pack's Turkish line (made from the clean English).
        if (slang is { Count: > 0 })
            parts.Add("Slang and idioms in these lines; say them with these expressions (the meaning, not the words): " +
                      string.Join("; ", slang.Select(entry => $"\"{entry.Key}\" = \"{entry.Value}\"")));
        if (!string.IsNullOrWhiteSpace(reference))
            parts.Add($"Turkish translation of what is probably the same line, from the game's translation pack: \"{reference}\". " +
                      "Use it to fix letters the OCR misread and to understand the meaning; ignore it if it does not match. " +
                      "Still translate from the English, and take he/she and the speaker's gender from the English and the scene.");
        if (context.Count > 0)
        {
            var lines = new List<string> { "Previous subtitles, for context only (do not translate them again):" };
            foreach (var line in context)
            {
                if (target == "ar" && line.Speaker is { } previousSpeaker) lines.Add($"Speaker: {previousSpeaker}");
                lines.Add($"English: {line.Source}");
                if (line.Translation is { } translation) lines.Add($"{language}: {translation}");
            }
            parts.Add(string.Join("\n", lines));
        }
        var list = new JsonArray([.. sentences.Select(s => (JsonNode?)JsonValue.Create(s))]).ToJsonString(Compact);
        parts.Add($"Translate each string in this JSON array into {language}. Reply with only a JSON array of {sentences.Count} " +
                  $"{language} strings in the same order.\n{list}");
        return string.Join("\n\n", parts);
    }

    // Arabic fork: use only explicit speaker labels and direct address; a name mentioned in a story is not the listener.
    private static readonly IReadOnlyDictionary<string, string> CharacterGenders = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
    {
        ["Jason"] = "man", ["Lucia"] = "woman", ["Cal"] = "man", ["Brian"] = "man",
        ["Boobie"] = "man", ["Raul"] = "man", ["Dre'Quan"] = "man", ["Real Dimez"] = "women",
    };

    private static void AddDialogueHints(List<string> parts, IReadOnlyList<string> sentences,
                                         IReadOnlyList<AiContextLine> context, string? speaker)
    {
        if (!string.IsNullOrWhiteSpace(speaker))
        {
            parts.Add($"Speaker: {speaker}");
            if (CharacterGenders.TryGetValue(speaker.Replace('’', '\''), out var gender))
                parts.Add(gender == "women" ? "Speakers are women." : $"Speaker is a {gender}.");
        }
        var text = string.Join(" ", sentences).Trim();
        foreach (var (name, gender) in CharacterGenders)
        {
            if (string.Equals(name, speaker, StringComparison.OrdinalIgnoreCase)) continue;
            var escaped = System.Text.RegularExpressions.Regex.Escape(name);
            if (System.Text.RegularExpressions.Regex.IsMatch(text.Replace('’', '\''),
                    $@"^(?:Hey,?\s+)?{escaped}\s*,|,\s*{escaped}\s*[.!?]*$",
                    System.Text.RegularExpressions.RegexOptions.IgnoreCase | System.Text.RegularExpressions.RegexOptions.CultureInvariant))
                parts.Add(gender == "women" ? $"Listeners ({name}) are women." : $"Listener ({name}) is a {gender}.");
        }
        parts.Add("Understand each whole sentence before translating; when it spans strings, preserve its overall meaning and keep the requested array count and order.");
        if (context.LastOrDefault() is not { Translation: { } arabic } previous || string.IsNullOrWhiteSpace(arabic)) return;
        if (speaker is not null && previous.Speaker is not null &&
            !string.Equals(speaker, previous.Speaker, StringComparison.OrdinalIgnoreCase)) return;
        var ending = previous.Source.TrimEnd().TrimEnd('"', '\'', '”', '’', ')', ']').TrimEnd();
        var first = text.TrimStart('"', '\'', '“', '‘', '(', '[');
        var word = new string(first.TakeWhile(char.IsLetter).ToArray());
        if (ending.Length == 0 || first.Length == 0) return;
        if (!".!?…".Contains(ending[^1]) || char.IsLower(first[0]) ||
            new[] { "and", "but", "so", "or" }.Contains(word, StringComparer.OrdinalIgnoreCase))
            parts.Add($"This subtitle continues the previous one: \"{previous.Source}\" (shown as \"{arabic}\"); " +
                      "translate it so the two read as one natural Arabic sentence. Translate only the current subtitle, without repeating the previous one.");
    }

    /// <summary>Yerel modelin sıcaklığı: 0, aynı satır her seferinde aynı çevrilir (#306).</summary>
    public const double LocalTemperature = 0;

    /// <summary>
    /// Ollama modeli son istekten sonra bu kadar bellekte tutar (varsayılan 5 dk). Oyunda uzun süre altyazı çıkmayınca
    /// model bellekten atılıyor, ilk satır modelin diskten yeniden yüklenmesini bekliyordu (#306).
    /// </summary>
    public const string OllamaKeepAlive = "24h";

    /// <summary>
    /// Hizmete giden HTTP isteği. <paramref name="server"/>: yerel modelin sunucusu (verilmezse Ollama'nın varsayılan adresi).
    /// <paramref name="thinkingOff"/>: Ollama'da düşünmeyi kapatan alanlar gitsin mi (bu alanları reddeden eski sürümde false).
    /// <paramref name="instruction"/>: Ayarlardaki "Ek talimat"; sistem talimatının sonuna eklenir, boşsa hiçbir şey değişmez.
    /// </summary>
    public static HttpRequestMessage Build(AiProvider provider, string key, AiModel model, IReadOnlyList<string> sentences,
                                           IReadOnlyList<AiContextLine> context, string? game = null, LocalAiServer? server = null,
                                           int maxOutputTokens = MaxOutputTokens, bool thinkingOff = true,
                                           IReadOnlyList<string>? names = null, string? instruction = null, string target = "tr",
                                           IReadOnlyList<KeyValuePair<string, string>>? slang = null, string? reference = null,
                                           string? speaker = null, string? playerCharacter = null)
    {
        key = key.Trim();
        var user = UserMessage(sentences, context, game, names, target, slang, reference, speaker, playerCharacter);
        var system = SystemPromptWith(instruction, target);
        HttpRequestMessage request;
        JsonObject body;
        switch (provider)
        {
            case AiProvider.Gemini:
                // https://ai.google.dev/api/generate-content (aistudio.google.com/api-reference/generate-content):
                // POST /v1beta/models/{model}:generateContent, anahtar "x-goog-api-key" başlığında.
                // Gemini 3.6 Flash ve sonrası temperature'ı yok sayıyor; düşünme düzeyi listedeki modelle gelir.
                request = new HttpRequestMessage(HttpMethod.Post,
                    $"https://generativelanguage.googleapis.com/v1beta/models/{Uri.EscapeDataString(model.Id)}:generateContent");
                request.Headers.Add("x-goog-api-key", key);
                body = new JsonObject
                {
                    ["systemInstruction"] = new JsonObject { ["parts"] = new JsonArray(new JsonObject { ["text"] = system }) },
                    ["contents"] = new JsonArray(new JsonObject
                    {
                        ["role"] = "user",
                        ["parts"] = new JsonArray(new JsonObject { ["text"] = user }),
                    }),
                    ["generationConfig"] = new JsonObject { ["maxOutputTokens"] = maxOutputTokens },
                };
                if (model.Thinking is { } level) body["generationConfig"]!["thinkingConfig"] = new JsonObject { ["thinkingLevel"] = level };
                // Arabic fork: game dialogue (swearing, threats, slurs) is translated as it is; a blocked reply has no text.
                if (target == "ar") body["safetySettings"] = SafetyOff();
                break;
            case AiProvider.OpenAi:
                // https://platform.openai.com/docs/api-reference/chat/create (developers.openai.com/api/reference):
                // POST /v1/chat/completions, "Authorization: Bearer"; "developer" iletisi yönergedir.
                request = new HttpRequestMessage(HttpMethod.Post, "https://api.openai.com/v1/chat/completions");
                request.Headers.TryAddWithoutValidation("Authorization", $"Bearer {key}");
                body = new JsonObject
                {
                    ["model"] = model.Id,
                    ["messages"] = new JsonArray(
                        new JsonObject { ["role"] = "developer", ["content"] = system },
                        new JsonObject { ["role"] = "user", ["content"] = user }),
                    ["max_completion_tokens"] = maxOutputTokens,
                };
                if (model.Effort is { } effort) body["reasoning_effort"] = effort;
                break;
            case AiProvider.Local:
                // Ollama ve LM Studio'nun OpenAI uyumlu Chat Completions'ı: POST {adres}/chat/completions. Yalnız iki
                // sunucunun da tanıdığı alanlar gider ("developer" rolü, reasoning_effort, max_completion_tokens gitmez).
                // Anahtar isteğe bağlıdır; boşsa Authorization başlığı hiç eklenmez.
                var local = server ?? LocalAiServer.Create(LocalAiServer.OllamaUrl, model.Id)!;
                request = new HttpRequestMessage(HttpMethod.Post, local.ChatUrl);
                if (key.Length > 0) request.Headers.TryAddWithoutValidation("Authorization", $"Bearer {key}");
                body = new JsonObject
                {
                    ["model"] = model.Id,
                    ["messages"] = new JsonArray(
                        new JsonObject { ["role"] = "system", ["content"] = system },
                        new JsonObject { ["role"] = "user", ["content"] = user }),
                    ["temperature"] = LocalTemperature,
                    ["max_tokens"] = maxOutputTokens,
                    ["stream"] = false,
                };
                // Ollama'da düşünme kapalı (#295): yerel API "think": false okur, OpenAI uyumlu adres ise
                // reasoning_effort "none" (github.com/ollama/ollama openai/openai.go). Eski sürüm 400 verirse çevirmen
                // alanlarsız yeniden dener ve bunu oturum boyunca hatırlar.
                if (thinkingOff && local.DisablesThinking)
                {
                    body["think"] = false;
                    body["reasoning_effort"] = "none";
                }
                // Ollama'nın OpenAI uyumlu adresi keep_alive'ı okur ("Ollama extension", github.com/ollama/ollama
                // openai/openai.go ChatCompletionRequest.KeepAlive); bu alanı bilmeyen eski sürüm bilinmeyen alanı yok
                // sayar. Yalnız Ollama'ya gider: LM Studio ve başka sunucular bu alanı tanımaz (#306).
                if (local.Kind == LocalAiServerKind.Ollama) body["keep_alive"] = OllamaKeepAlive;
                break;
            default:
                // https://docs.anthropic.com/en/api/messages (platform.claude.com/docs/en/api/messages): POST /v1/messages,
                // "x-api-key" ve "anthropic-version". Yeni modellerde temperature gönderilmez; effort ve düşünme listedeki
                // modelle gelir (platform.claude.com/docs/en/build-with-claude/effort).
                request = new HttpRequestMessage(HttpMethod.Post, "https://api.anthropic.com/v1/messages");
                request.Headers.Add("x-api-key", key);
                request.Headers.Add("anthropic-version", "2023-06-01");
                body = new JsonObject
                {
                    ["model"] = model.Id,
                    ["max_tokens"] = maxOutputTokens,
                    ["system"] = system,
                    ["messages"] = new JsonArray(new JsonObject { ["role"] = "user", ["content"] = user }),
                };
                if (model.Effort is { } claudeEffort) body["output_config"] = new JsonObject { ["effort"] = claudeEffort };
                if (model.Thinking is { } thinking) body["thinking"] = new JsonObject { ["type"] = thinking };
                break;
        }
        request.Content = new StringContent(body.ToJsonString(Compact), Encoding.UTF8, "application/json");
        return request;
    }

    private static JsonArray SafetyOff() => new([.. new[] { "HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH",
        "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT" }
        .Select(category => (JsonNode)new JsonObject { ["category"] = category, ["threshold"] = "BLOCK_NONE" })]);

    /// <summary>
    /// Arabic fork: why the model stopped early (Gemini finishReason other than STOP, "length"/"content_filter",
    /// Claude "max_tokens"/"refusal"), or null for a normal end. A reply cut there must not be cached as a translation.
    /// </summary>
    internal static string? EarlyStop(AiProvider provider, string json)
    {
        try
        {
            var root = JsonNode.Parse(json);
            var reason = provider switch
            {
                AiProvider.Gemini => root?["candidates"]?.AsArray().FirstOrDefault()?["finishReason"]?.GetValue<string>(),
                AiProvider.OpenAi or AiProvider.Local => root?["choices"]?.AsArray().FirstOrDefault()?["finish_reason"]?.GetValue<string>(),
                _ => root?["stop_reason"]?.GetValue<string>(),
            };
            return reason is "MAX_TOKENS" or "SAFETY" or "RECITATION" or "PROHIBITED_CONTENT" or "BLOCKLIST" or "OTHER"
                or "length" or "content_filter" or "max_tokens" or "refusal" ? reason : null;
        }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException)
        {
            return null;
        }
    }

    /// <summary>Yanıttaki düz metin (düşünme bölümleri atlanır).</summary>
    public static string Text(AiProvider provider, string json)
    {
        JsonNode? root;
        try { root = JsonNode.Parse(json); }
        catch (JsonException) { throw new AiException(OnlineKeyState.Unreachable); }
        string? text = null;
        try
        {
            switch (provider)
            {
                case AiProvider.Gemini:
                    // candidates[0].content.parts[].text; "thought": true olan parçalar düşünmedir.
                    var parts = root?["candidates"]?.AsArray().FirstOrDefault()?["content"]?["parts"]?.AsArray();
                    text = parts is null ? null : string.Concat(parts
                        .Where(p => p?["thought"]?.GetValueKind() is not JsonValueKind.True)
                        .Select(p => p?["text"]?.GetValue<string>() ?? ""));
                    break;
                case AiProvider.OpenAi or AiProvider.Local:
                    text = root?["choices"]?.AsArray().FirstOrDefault()?["message"]?["content"]?.GetValue<string>();
                    break;
                default:
                    var blocks = root?["content"]?.AsArray();
                    text = blocks is null ? null : string.Concat(blocks
                        .Where(b => b?["type"]?.GetValue<string>() == "text")
                        .Select(b => b?["text"]?.GetValue<string>() ?? ""));
                    break;
            }
        }
        catch (Exception error) when (error is InvalidOperationException or FormatException)
        {
            throw new AiException(OnlineKeyState.Unreachable);
        }
        if (string.IsNullOrWhiteSpace(text)) throw new AiException(OnlineKeyState.Unreachable);
        return text;
    }

    /// <summary>Yanıttaki JSON dizisi; tek cümlede model diziyi unutup düz metin dönerse o metin alınır.</summary>
    public static string[] Translations(string text, int count) => [.. ReplyItems(text, count).Select(Unwrapped)];

    /// <summary>
    /// Arabic fork: a model once nested its answer, <c>["json ["يا جيسون!", "نسيت السواقة ولا إيه؟"]"]</c>, and the raw
    /// <c>json [...]</c> text reached the TV (2026-11-21). An item that is itself a quoted array (with or without a leading
    /// "json") becomes its strings, one per row. Any other text is left as it is.
    /// </summary>
    internal static string Unwrapped(string item)
    {
        var text = System.Text.RegularExpressions.Regex.Replace(item.Trim(), @"^(?:```)?json\s*", "",
                                                                System.Text.RegularExpressions.RegexOptions.IgnoreCase);
        if (!(text.StartsWith('[') && text.EndsWith(']') && text.Contains('"'))) return item;
        try
        {
            if (JsonNode.Parse(text) is JsonArray array && array.Count > 0 && array.All(n => n?.GetValueKind() == JsonValueKind.String))
                return string.Join("\n", array.Select(n => n!.GetValue<string>().Trim()));
        }
        catch (JsonException) { /* unescaped quotes inside: take the quoted parts */ }
        var parts = System.Text.RegularExpressions.Regex.Matches(text, "\"([^\"]+)\"").Select(m => m.Groups[1].Value.Trim())
            .Where(part => part.Length > 0 && part != ",").ToList();
        return parts.Count > 0 ? string.Join("\n", parts) : item;
    }

    private static string[] ReplyItems(string text, int count)
    {
        var trimmed = text.Trim();
        // Düşünen yerel modeller (ör. Qwen3, DeepSeek-R1) yanıtın başına <think>…</think> koyar; yalnız sonrası alınır.
        var thought = trimmed.LastIndexOf("</think>", StringComparison.Ordinal);
        if (thought >= 0) trimmed = trimmed[(thought + "</think>".Length)..].Trim();
        trimmed = StripControlTokens(trimmed);
        // ```json … ``` çitini at.
        if (trimmed.StartsWith("```", StringComparison.Ordinal))
            trimmed = string.Join("\n", trimmed.Split('\n').Skip(1).Where(l => !l.StartsWith("```", StringComparison.Ordinal))).Trim();
        // Arabic fork: the fence's language tag without the fence ("json [...]").
        if (System.Text.RegularExpressions.Regex.Match(trimmed, @"^json\s*(?=\[)", System.Text.RegularExpressions.RegexOptions.IgnoreCase) is { Success: true } tag)
            trimmed = trimmed[tag.Length..];
        int start = trimmed.IndexOf('['), end = trimmed.LastIndexOf(']');
        if (start >= 0 && end > start)
        {
            try
            {
                if (JsonNode.Parse(WithoutTrailingComma(trimmed[start..(end + 1)])) is JsonArray array && array.All(n => n?.GetValueKind() == JsonValueKind.String))
                {
                    if (array.Count != count) throw new AiException(OnlineKeyState.Unreachable);
                    return [.. array.Select(n => n!.GetValue<string>().Trim())];
                }
            }
            catch (JsonException) { /* dizi değil; düz metin olabilir */ }
        }
        if (UnquotedList(trimmed, count) is { } list) return list;
        if (count != 1) throw new AiException(OnlineKeyState.Unreachable);
        return [trimmed.Trim('"', '“', '”')];
    }

    /// <summary>
    /// Yerel modellerin metne sızan bitiş/rol işaretleri (<c>&lt;/s&gt;</c>, <c>&lt;|im_end|&gt;</c>, <c>&lt;|eot_id|&gt;</c>,
    /// <c>&lt;end_of_turn&gt;</c> …) ekranda görünmesin; yalnız bu kalıplar silinir, altyazıdaki başka yazılara dokunulmaz.
    /// </summary>
    internal static string StripControlTokens(string text) =>
        System.Text.RegularExpressions.Regex.Replace(text,
            @"</?s>|<\|[A-Za-z0-9_]{1,32}\|>|</?(?:start|end)_of_turn>|</?(?:eos|bos|pad)>", "").Trim();

    /// <summary><c>["Bir.", "İki.",]</c> gibi sondaki fazla virgül JSON'u bozmasın.</summary>
    internal static string WithoutTrailingComma(string text) =>
        System.Text.RegularExpressions.Regex.Replace(text, @",\s*\]$", "]");

    /// <summary>
    /// Tırnaksız dizi: bazı yerel modeller JSON istense de <c>[Hareket etmemiz lazım.]</c> ya da
    /// <c>[Satır bir., Satır iki.]</c> döndürür. Yalnız bütün yanıt köşeli parantez içindeyse ve öğe sayısı tam tutuyorsa
    /// alınır; tutmazsa null (hat her zamanki geri dönüşe gider). Cümle içi virgülde yanlış bölmemek için sıra: satır sonu,
    /// cümle sonu işaretinden (. ! ? … ") sonra gelen virgül, en son düz virgül.
    /// </summary>
    internal static string[]? UnquotedList(string text, int count)
    {
        if (count < 1 || text.Length < 2 || !text.StartsWith('[') || !text.EndsWith(']')) return null;
        var inner = text[1..^1];
        IEnumerable<string[]> candidates = count == 1 ? [[inner]] :
        [
            [.. inner.Split('\n').Where(line => line.Trim().Length > 0)],
            SentenceSplit(inner),
            inner.Split(','),
        ];
        foreach (var parts in candidates)
        {
            var items = parts.Select(CleanItem).ToArray();
            if (items.Length == count && items.All(item => item.Length > 0)) return items;
        }
        return null;
    }

    private static string CleanItem(string item)
    {
        var value = item.Trim();
        if (value.EndsWith(',')) value = value[..^1];
        return value.Trim().Trim('"', '“', '”').Trim();
    }

    /// <summary>Cümle sonu işaretinden (. ! ? … " ”) hemen sonra gelen virgüllerden böler; işaret öğede kalır.</summary>
    private static string[] SentenceSplit(string text)
    {
        var parts = new List<string>();
        var start = 0;
        var last = '\0';
        for (var i = 0; i < text.Length; i++)
        {
            var c = text[i];
            if (c == ',' && last is '.' or '!' or '?' or '…' or '"' or '”')
            {
                parts.Add(text[start..i]);
                start = i + 1;
            }
            if (!char.IsWhiteSpace(c)) last = c;
        }
        parts.Add(text[start..]);
        return [.. parts];
    }

    /// <summary>Başarısız HTTP yanıtının anlamı. Gövde yalnız tanınan hata kodları için okunur, hiçbir yere yazılmaz.</summary>
    public static OnlineKeyState Failure(HttpStatusCode status, string body)
    {
        var message = body.ToLowerInvariant();
        return (int)status switch
        {
            401 or 403 => OnlineKeyState.Invalid,
            402 or 429 => OnlineKeyState.QuotaExceeded,
            400 when message.Contains("api_key_invalid") || message.Contains("api key not valid") => OnlineKeyState.Invalid,
            400 when message.Contains("credit balance") || message.Contains("insufficient_quota") => OnlineKeyState.QuotaExceeded,
            400 or 404 => OnlineKeyState.ModelRejected,
            // Hizmete ulaşıldı ama şu an yoğun ya da arızalı (Gemini 503 "overloaded", Claude 529); internet çalışıyor.
            408 or (>= 500 and <= 599) => OnlineKeyState.Busy,
            _ => OnlineKeyState.Unreachable,
        };
    }

    /// <summary>
    /// 429 yalnız dakikalık sınırdan geldiyse onun ayrıntısı; gün sınırı, bakiye ya da tanınmayan 429 için null (bunlar
    /// <see cref="OnlineKeyState.QuotaExceeded"/> olarak kullanıcıya gösterilir). Gemini'nin hata gövdesinde error.details[]
    /// içinde google.rpc.QuotaFailure (violations[].quotaId, ör. "GenerateRequestsPerMinutePerProjectPerModel-FreeTier",
    /// quotaValue "15") ve google.rpc.RetryInfo (retryDelay "37s") bulunur.
    /// </summary>
    public static AiRateLimit? RateLimit(HttpStatusCode status, string body)
    {
        if ((int)status != 429) return null;
        JsonArray? details;
        try { details = ErrorOf(body)?["details"] as JsonArray; }
        catch (JsonException) { return null; }
        if (details is null) return null;
        try
        {
            var violations = details.SelectMany(d => d?["violations"] as IEnumerable<JsonNode?> ?? []).OfType<JsonNode>().ToList();
            var ids = violations.Select(v => v["quotaId"]?.GetValueKind() == JsonValueKind.String ? v["quotaId"]!.GetValue<string>() : null)
                .OfType<string>().ToList();
            // Günlük sınır doldu: dakikalık beklemeyle geçmez, kullanıcı bilmeli.
            if (ids.Any(id => id.Contains("PerDay", StringComparison.Ordinal)) || !ids.Any(id => id.Contains("PerMinute", StringComparison.Ordinal)))
                return null;
            TimeSpan? retry = null;
            var delay = details.Select(d => d?["retryDelay"]).FirstOrDefault(n => n?.GetValueKind() == JsonValueKind.String)?.GetValue<string>();
            if (delay is not null && double.TryParse(delay.TrimEnd('s'), System.Globalization.NumberStyles.Float,
                                                     System.Globalization.CultureInfo.InvariantCulture, out var seconds))
                retry = TimeSpan.FromSeconds(seconds);
            // İstek sınırı (token sınırı değil).
            int? perMinute = null;
            var requests = violations.FirstOrDefault(v => v["quotaId"]?.GetValueKind() == JsonValueKind.String
                                                          && v["quotaId"]!.GetValue<string>().Contains("Requests", StringComparison.Ordinal));
            var value = requests?["quotaValue"];
            if (value?.GetValueKind() == JsonValueKind.String && int.TryParse(value.GetValue<string>(), out var parsed) && parsed > 0) perMinute = parsed;
            else if (value?.GetValueKind() == JsonValueKind.Number && value.GetValue<int>() > 0) perMinute = value.GetValue<int>();
            return new AiRateLimit(retry, perMinute);
        }
        catch (Exception error) when (error is InvalidOperationException or FormatException)
        {
            return null;
        }
    }
}

/// <summary>
/// Hizmetin bildirdiği dakikalık istek sınırı aşımı (429). Gün sınırından farklı olarak kısa sürer; kullanıcıya hata
/// gösterilmez, birkaç saniye o satırlar yerel çeviriyle çevrilir.
/// </summary>
/// <param name="RetryAfter">Hizmetin önerdiği bekleme ("retryDelay"); yoksa null.</param>
/// <param name="RequestsPerMinute">Hizmetin bildirdiği dakikalık istek sınırı ("quotaValue"); yoksa null.</param>
/// <param name="Daily">Arabic fork: the key's day limit; the key rests for <see cref="AiTranslator.DayPause"/>.</param>
/// <param name="Shared">Arabic fork: Google's no-details 429; it hits every key of the model at once, so they all rest.</param>
public sealed record AiRateLimit(TimeSpan? RetryAfter = null, int? RequestsPerMinute = null, bool Daily = false, bool Shared = false);

/// <summary>Hizmet dakikalık sınırın aşıldığını bildirdi; kullanıcıya hata olarak gösterilmez.</summary>
/// <summary>
/// Arabic fork: the reply was cut or blocked (<see cref="AiRequest.EarlyStop"/>). It is not cached; the line goes to the
/// next link and this key is not paused (the key is fine, only this line was a problem).
/// </summary>
internal sealed class AiIncompleteException(string reason) : Exception($"AI reply incomplete: {reason}")
{
    public string Reason { get; } = reason;
}

internal sealed class AiRateLimitedException(AiRateLimit limit) : Exception("AI translation rate limited")
{
    public AiRateLimit Limit { get; } = limit;
}

/// <summary>
/// İsteğe bağlı yapay zekâ çevirisi: kullanıcının kendi Gemini, OpenAI ya da Claude anahtarıyla (Mac'teki
/// AITranslationCore ile aynı). Altyazı cümle cümle, konuşmacı adları çevrilmeden gönderilir; son gösterilen 3 satır ve
/// çevirileri bağlam olarak eklenir. Hizmete ulaşılamazsa, kota biterse ya da zaman aşımı olursa o satır cihaz üstü
/// modelle çevrilir ve bir dakika hizmet denenmez. Yerel çeviriler bu motorun önbelleğine yazılmaz.
/// Bir altyazının çevrilmemiş bütün cümleleri tek istekte gider. Ücretsiz katmanın dakikalık sınırı için model listesindeki
/// <c>requestsPerMinute</c> kadar istek hakkı vardır; hak bitince o satır sessizce (hata göstermeden) yerel çeviriyle çevrilir.
/// </summary>
public sealed class AiTranslator : ITextTranslator, IOnlineTranslator
{
    /// <summary>Bağlama giden önceki satır sayısı.</summary>
    internal const int ContextLines = 3;
    /// <summary>Arabic fork: more of the scene, so lines are understood rather than translated word by word.</summary>
    internal const int ArabicContextLines = 10;
    /// <summary>Hata sonrası bu süre hizmet denenmez (bulut hizmetlerinin kotası için).</summary>
    private static readonly TimeSpan RetryAfter = TimeSpan.FromMinutes(1);
    /// <summary>
    /// Yerel modelde hata sonrası bekleme. Kota yoktur, sunucu çoğunlukla hemen düzelir (zaman aşımı, bozuk JSON, an
    /// meşgul): tek bir hata bir dakika boyunca bütün satırları yedek çeviriye düşürmesin, yalnız birkaç satırı etkilesin.
    /// Askıda kalan sunucuda da bekleme sürer: her satır <see cref="LocalTimeout"/> kadar beklemez, sıra dolmaz.
    /// </summary>
    internal static readonly TimeSpan LocalRetryAfter = TimeSpan.FromSeconds(5);
    /// <summary>Hata sonrası hizmetin denenmediği süre: yerel modelde <see cref="LocalRetryAfter"/>, bulutta bir dakika (testler kısaltır).</summary>
    internal TimeSpan ErrorPause { get; init; }
    /// <summary>Dakikalık sınır aşılınca en çok bu kadar beklenir (hizmet bekleme önermezse de bu kadar).</summary>
    public static readonly TimeSpan RateLimitPause = TimeSpan.FromSeconds(20);
    /// <summary>Arabic fork: a key that hit its day limit is tried again only after this (the chain moves on meanwhile).</summary>
    public static readonly TimeSpan DayPause = TimeSpan.FromMinutes(15);

    /// <summary>
    /// After a 429 this key rests. Arabic fork: when the limit is <see cref="AiRateLimit.Shared"/>, the next keys of the
    /// same model in the chain rest too. In the 2026-11-20 log, all 4 keys always failed together, so 3 more requests only
    /// cost ~0.8 s per model before the line reached the next one.
    /// </summary>
    private void Rest(AiRateLimit limit)
    {
        if (limit.RequestsPerMinute is { } perMinute) _budget?.Lower(perMinute);
        var until = DateTimeOffset.UtcNow + PauseFor(limit);
        LastLineWasRateLimited = true;
        var link = this;
        while (true)
        {
            link._pausedUntil = until;
            link._pauseRateLimited = true;
            if (!limit.Shared || link._fallback is not AiTranslator next || next.Model.Id != Model.Id) break;
            link = next;
        }
    }

    private static TimeSpan PauseFor(AiRateLimit limit) =>
        limit.Daily ? DayPause
        : limit.RetryAfter is { } retry && retry < RateLimitPause ? (retry < TimeSpan.FromSeconds(1) ? TimeSpan.FromSeconds(1) : retry)
        : RateLimitPause;
    /// <summary>Denemede çevrilen örnek satır.</summary>
    public const string Sample = "KRATOS: We go now. Stay close, boy.";

    /// <summary>Bulut hizmetinde beklenen en uzun süre.</summary>
    public static readonly TimeSpan Timeout = TimeSpan.FromSeconds(5);
    /// <summary>
    /// Yerel modelde beklenen en uzun süre: bilgisayardaki model buluttan yavaştır ve bir süre boşta kalınca belleğe
    /// yeniden yüklenir. Yine de sınırlıdır: çeviriler sırayla gider, bir satır bütün sırayı bekletmesin; ekrandan çoktan
    /// kalkmış satırın geç gelen çevirisini hat zaten göstermez.
    /// </summary>
    public static readonly TimeSpan LocalTimeout = TimeSpan.FromSeconds(15);
    /// <summary>"Bağlantıyı dene"de yerel model için beklenen süre: ilk istek modeli belleğe yükler, uzun sürebilir.</summary>
    public static readonly TimeSpan LocalTestTimeout = TimeSpan.FromSeconds(90);

    // Kısa zaman aşımı: altyazı birkaç saniye ekranda kalır, geç gelen çeviri işe yaramaz.
    private static readonly Lazy<HttpClient> SharedClient = new(() => new HttpClient { Timeout = AiTranslator.Timeout });
    private static readonly Lazy<HttpClient> LocalClient = new(() => new HttpClient { Timeout = LocalTimeout });
    private static readonly Lazy<HttpClient> LocalTestClient = new(() => new HttpClient { Timeout = LocalTestTimeout });
    /// <summary>
    /// Ayarlardaki "Kaydet ve dene"de bulut hizmeti için beklenen en uzun süre: deneme tek istektir, ilk bağlantı
    /// altyazıdaki 5 saniyeyi aşabilir; yine de sınırlıdır, düğme takılı kalmaz (Mac ile aynı, 15 sn).
    /// </summary>
    public static readonly TimeSpan CloudKeyTestTimeout = TimeSpan.FromSeconds(15);
    private static readonly Lazy<HttpClient> CloudTestClient = new(() => new HttpClient { Timeout = CloudKeyTestTimeout });

    /// <summary>Hizmete uygun zaman aşımıyla paylaşılan istemci.</summary>
    public static HttpClient ClientFor(AiProvider provider) => provider.IsLocal() ? LocalClient.Value : SharedClient.Value;

    /// <summary>"Ekranı çevir"de bulut hizmetinde beklenen en uzun süre: bütün ekranın çevirisi tek yanıtta gelir.</summary>
    public static readonly TimeSpan ScreenTimeout = TimeSpan.FromSeconds(30);
    /// <summary>Bir istekte giden en çok parça; kalabalık ekran birkaç isteğe bölünür.</summary>
    public const int ScreenBatch = 40;
    private static readonly Lazy<HttpClient> ScreenClient = new(() => new HttpClient { Timeout = ScreenTimeout });

    private readonly string _key;
    private readonly ITextTranslator? _fallback;
    private readonly HttpClient _http;
    /// <summary>"Ekranı çevir"in uzun yanıtlı tek isteği için daha uzun bekleyen istemci.</summary>
    private readonly HttpClient _screenHttp;
    private readonly TranslationCache _cache;
    private readonly RequestBudget? _budget;
    private readonly ConcurrentDictionary<string, IReadOnlyList<SpokenLine>> _speech = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, byte> _names = new(StringComparer.Ordinal);
    private readonly SpeakerNameGuard _speakers = new();
    private readonly AiContextLog _log;
    private readonly Func<string, string?>? _reference;
    private readonly IReadOnlyList<KeyValuePair<string, string>>? _glossary;
    private readonly IReadOnlyDictionary<string, string>? _corrections;
    private readonly Func<string?>? _game;
    /// <summary>Ayarlardaki "Ek talimat" (sınırlanmış); yoksa null.</summary>
    private readonly string? _instruction;
    /// <summary>Ek talimatın önbellek etiketi; talimat yoksa null (anahtar eskisi gibi).</summary>
    private readonly string? _instructionTag;
    /// <summary>Arabic fork: target language of the prompt ("tr" or "ar").</summary>
    private readonly string _target;
    /// <summary>Arabic fork: <see cref="AiRequest.KeyTag"/> of this translator's key, for altyazi.log.</summary>
    private readonly string _keyTag;
    private DateTimeOffset _pausedUntil = DateTimeOffset.MinValue;
    /// <summary>Bekleme hizmetin 429 yanıtından mı (dakikalık sınır); öyleyse yerel yedek olmasa da istek gönderilmez.</summary>
    private bool _pauseRateLimited;
    private OnlineKeyState? _failure;

    /// <param name="model">Kayıtlı model kimliği; listede yoksa varsayılan model.</param>
    /// <param name="cache">Kalıcı önbellek; verilmezse yalnız bellekte tutulur.</param>
    /// <param name="budget">
    /// Dakikalık istek hakkı; null ise modelin uygulama genelindeki ortak hakkı (Google sınırı proje ve model başına sayar;
    /// çeviri hattı yeniden kurulsa da hak sıfırlanmaz). Modelin sınırı yoksa hak da yoktur.
    /// </param>
    /// <param name="server">Yerel modelin sunucusu (yalnız <see cref="AiProvider.Local"/>; verilmezse Ollama'nın varsayılan adresi).</param>
    /// <param name="http">Verilmezse hizmete uygun zaman aşımıyla paylaşılan istemci (yerel model daha uzun bekler).</param>
    /// <param name="game">
    /// Oynanan oyunun adı (ör. eşleşen oyun paketinin başlığı); her istekte sorulur, null ya da boşsa istemde oyun satırı olmaz.
    /// </param>
    /// <param name="instruction">Ayarlardaki "Ek talimat": sistem talimatının sonuna eklenir; boşsa hiçbir şey değişmez.</param>
    public AiTranslator(AiProvider provider, string key, string? model, ITextTranslator? fallback, HttpClient? http = null,
                        TranslationCache? cache = null, RequestBudget? budget = null, LocalAiServer? server = null,
                        Func<string?>? game = null, string? instruction = null, bool paidTier = false, string target = "tr",
                        AiContextLog? context = null, Func<string, string?>? reference = null,
                        IReadOnlyList<KeyValuePair<string, string>>? glossary = null,
                        IReadOnlyDictionary<string, string>? corrections = null)
    {
        _game = game;
        _target = target;
        _log = context ?? new AiContextLog();
        _reference = reference;
        _glossary = glossary;
        _corrections = corrections;
        _instruction = AiRequest.Instruction(instruction);
        _instructionTag = AiRequest.InstructionTag(_instruction);
        Provider = provider;
        ErrorPause = provider.IsLocal() ? LocalRetryAfter : RetryAfter;
        Model = provider.Model(model);
        Server = provider.IsLocal() ? server ?? LocalAiServer.Create(LocalAiServer.OllamaUrl, Model.Id) : null;
        _key = key.Trim();
        _keyTag = _key.Length == 0 ? "-" : AiRequest.KeyTag(_key)[..4];
        _fallback = fallback;
        _http = http ?? ClientFor(provider);
        _screenHttp = http ?? (provider.IsLocal() ? LocalTestClient.Value : ScreenClient.Value);
        _cache = cache ?? new TranslationCache();
        _budget = budget ?? RequestBudget.Shared(provider, Model, _key, paidTier);
    }

    public AiProvider Provider { get; }
    public AiModel Model { get; }
    /// <summary>Yerel modelin sunucusu; bulut hizmetlerinde null.</summary>
    public LocalAiServer? Server { get; }

    /// <summary>Son hata; çeviri düzgün gidiyorsa null.</summary>
    public OnlineKeyState? LastError => _failure;

    /// <summary>Son satır dakikalık sınır yüzünden yerel çeviriyle çevrildi (hata sayılmaz, bildirilmez).</summary>
    public bool LastLineWasRateLimited { get; private set; }

    public event Action<OnlineKeyState?>? FailureChanged;

    // Oyun bilinmiyorsa eski kayıtlar geçerlidir; biliniyorsa yalnız o oyunun sonucu kullanılır. Ek talimat varsa
    // anahtar onun etiketiyle başlar: talimat değişince eski çeviriler dönmez, talimat yoksa anahtar eskisi gibidir.
    private string CacheKey(string text, string? game)
    {
        var key = string.IsNullOrEmpty(game) ? text : game + "\u001f" + text;
        if (_target == "ar" && PlayerCharacterInference.Normalize(TranslationDialogue.CurrentPlayerCharacter) is { } player)
            key = "player:" + player + "\u001f" + key;
        return _instructionTag is null ? key : "i:" + _instructionTag + "\u001f" + key;
    }

    /// <summary>Konuşan adını öğrenir ("OLD MAN" → "old", "man"); korunacak adlar ayrıca (#287).</summary>
    public void NoteSpeaker(string name)
    {
        foreach (var word in name.Split(' ', StringSplitOptions.RemoveEmptyEntries).Where(w => w.Length >= 2))
            _names[word.ToLowerInvariant()] = 0;
        _speakers.Learn(name);
        _fallback?.NoteSpeaker(name);
    }

    public async Task<string> TranslateAsync(string text, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(text);
        // Arama, HTTP isteği ve kayıt aynı oyunla yapılmalı; callback işlem sırasında değişebilir.
        var game = _game?.Invoke()?.Trim();
        // Bağlam, bu satırdan önce gösterilenlerdir; satırın kendisi bağlama girmez.
        var context = ContextBeforeShowing(text, game);
        // Arabic fork: a feminine reply must not reuse the same English sentence spoken by a man (or an older blind reply).
        var speaker = _target == "ar" ? TranslationDialogue.CurrentSpeaker : null;
        string Key(string source) => _target == "ar"
            ? "dialogue:1\u001f" + (speaker?.ToLowerInvariant() ?? "") + "\u001f" + CacheKey(source, game)
            : CacheKey(source, game);
        // Arabic fork: a reviewed fix for this exact line wins over the cache and the engine.
        if (_corrections is { Count: > 0 } fixes && fixes.TryGetValue(PackKey.Canon(text), out var fixedLine)) return Remember(fixedLine, text, game);
        if (_cache.TryGet(Key(text), out var cached)) return Remember(cached, text, game);
        // Arabic fork: an OCR re-read of a line shown a moment ago ("Clean iti" / "Clean it'") reuses that translation;
        // re-reads were 35% of the requests. Only translated lines count, so a failed line can still try again.
        if (_target == "ar" && RecentTranslation(text) is { } again) return Remember(again, text, game);

        var pieces = SubtitleSegmenter.Split(text);
        foreach (var name in pieces.Select(SubtitleSegmenter.LabelName).OfType<string>()) NoteSpeaker(name);
        var names = _names.Keys.ToHashSet(StringComparer.Ordinal);
        // Önbellekte duran cümleler yeniden gönderilmez.
        var translations = pieces.Select(p => p.Translate && _cache.TryGet(Key(p.Text), out var known) ? known : null).ToList();
        var missing = Enumerable.Range(0, pieces.Count).Where(i => pieces[i].Translate && translations[i] is null).ToList();
        var sources = missing.Select(i => SubtitleSegmenter.NormalizeCase(pieces[i].Text, names)).ToList();
        // Hizmetin beklemesi yalnız eksik çeviriler içindir; önbellekteki parçalar yerel motor istemez. Hata sonrası
        // beklemede yerel çeviri yoksa (inmemiş, açılamadı) satır düşmesin: hizmet yine denenir. 429 beklemesi kalır.
        if (sources.Count > 0 && DateTimeOffset.UtcNow < _pausedUntil)
        {
            // Arabic fork: a paused AiKeyChain link hands the line to the next key and never re-sends it itself; retrying
            // here re-ran the whole rest of the chain at every level (up to 15 requests per line with 4 keys).
            if (_pauseRateLimited || _fallback is AiTranslator)
            {
                NoteFallback(_pauseRateLimited ? "dakikalık hizmet sınırı" : $"yeniden deneme beklemesi ({_failure?.ToString() ?? "bekleme"})");
                return Remember(await FallbackAsync(text, cancellationToken).ConfigureAwait(false), text, game);
            }
            if (await TryFallbackAsync(text, cancellationToken).ConfigureAwait(false) is { } offline)
            {
                NoteFallback($"yeniden deneme beklemesi ({_failure?.ToString() ?? "bekleme"})");
                return Remember(offline, text, game);
            }
        }
        // Dakikalık hak bittiyse satır sessizce yerel çeviriyle çevrilir (hata değildir). Hak uygulamanın temkinli
        // tahminidir; yerel çeviri yoksa satır denenmeden düşmesin, istek yine gönderilir.
        LastLineWasRateLimited = sources.Count > 0 && _budget is { } budget && !budget.TryTake();
        if (LastLineWasRateLimited)
        {
            if (await TryFallbackAsync(text, cancellationToken).ConfigureAwait(false) is { } offline)
            {
                NoteFallback("uygulama dakikalık sınırı");
                return Remember(offline, text, game);
            }
            LastLineWasRateLimited = false;
        }

        string[] translated;
        try
        {
            var present = SpeakerNameGuard.Occurring(_speakers.Protected, string.Join("\n", sources), exact: true);
            translated = sources.Count == 0 ? [] : await RequestAsync(sources, context, game, present, cancellationToken,
                                                                          sources.Count > 0 ? _reference?.Invoke(text) : null).ConfigureAwait(false);
            if (sources.Count > 0) Report(null);
        }
        catch (AiRateLimitedException limited) when (!cancellationToken.IsCancellationRequested)
        {
            // Dakikalık sınır: kısa beklenir, ana ekranda uyarı çıkmaz; hizmet daha düşük sınır bildirdiyse ona inilir.
            Rest(limited.Limit);
            NoteFallback("dakikalık hizmet sınırı");
            return Remember(await FallbackAsync(text, cancellationToken).ConfigureAwait(false), text, game);
        }
        catch (AiIncompleteException incomplete) when (!cancellationToken.IsCancellationRequested)
        {
            NoteFallback($"yarım yanıt: {incomplete.Reason}");
            return Remember(await FallbackAsync(text, cancellationToken).ConfigureAwait(false), text, game);
        }
        catch (Exception error) when (error is HttpRequestException or TaskCanceledException or AiException
                                      && !cancellationToken.IsCancellationRequested)
        {
            var state = StateOf(error);
            Report(state);
            _pausedUntil = DateTimeOffset.UtcNow + ErrorPause;
            _pauseRateLimited = false;
            NoteFallback($"hizmet durumu: {state}");
            return Remember(await FallbackAsync(text, cancellationToken).ConfigureAwait(false), text, game);
        }
        var local = false;
        for (var i = 0; i < missing.Count; i++)
        {
            var piece = pieces[missing[i]].Text;
            // Model dizide bu cümleyi boş bıraktıysa (bozuk OCR okumasında olabiliyor, #296) yanıt yok sayılır.
            var empty = string.IsNullOrWhiteSpace(translated[i]);
            if ((empty || IsEcho(piece, translated[i])) && await TryFallbackAsync(piece, cancellationToken).ConfigureAwait(false) is { } retried
                && !string.IsNullOrWhiteSpace(retried) && !IsEcho(piece, retried))
            {
                // Yerel çeviri bu motorun önbelleğine yazılmaz.
                NoteFallback("boş veya değişmemiş yanıt");
                translations[missing[i]] = retried;
                local = true;
                continue;
            }
            if (empty)
            {
                // Yerel çeviri de yoksa cümle olduğu gibi kalır; boş satır gösterilmez, sonuç önbelleğe yazılmaz.
                local = true;
                continue;
            }
            translations[missing[i]] = translated[i];
            _cache.Store(Key(piece), translated[i]);
        }

        var builder = new StringBuilder();
        for (var i = 0; i < pieces.Count; i++) builder.Append(translations[i] ?? pieces[i].Text);
        if (_speech.Count >= 512) _speech.Clear();
        _speech[CacheKey(text, game)] = SpeechScript.Lines(pieces, translations);
        var result = builder.ToString();
        if (!local) _cache.Store(Key(text), result);
        return Remember(result, text, game);
    }

    /// <summary>
    /// Model cümleyi çevirmeden geri verdi mi (hattın "zaten hedef dilde" eşiğiyle aynı). Küçük yerel modeller İngilizce
    /// cümleyi sık sık olduğu gibi döndürüyor ("You think I'm on the run?"); hat bunu Türkçe sanıp hiç göstermiyor,
    /// önbellek de öyle saklıyordu (#245).
    /// </summary>
    internal static bool IsEcho(string source, string translation) =>
        TextMatch.IsSimilar(TextMatch.Normalize(source), TextMatch.Normalize(translation), 0.90);

    /// <summary>
    /// Cümle cihaz üstü çeviriyle denenir; yerel çeviri yoksa ya da hata verirse null. Geri verilen cümlede o da aynı
    /// kalırsa (ad, konsolun Türkçe menüsü) çevrilecek bir şey yoktur ve modelin yanıtı önbelleğe yazılır; hizmet
    /// beklerken null dönerse istek yine gönderilir.
    /// </summary>
    /// <summary>Satırın ücretsiz yedeğe düşme nedeni altyazı günlüğüne gider (Mac'teki <c>noteFallback</c> ile aynı).</summary>
    private void NoteFallback(string reason) =>
        TranslationProbe.Current?.NoteFallback($"{Provider.ToString().ToLowerInvariant()} {reason}");

    private async Task<string?> TryFallbackAsync(string text, CancellationToken cancellationToken)
    {
        if (_fallback is null) return null;
        try { return await _fallback.TranslateAsync(text, cancellationToken).ConfigureAwait(false); }
        catch (Exception error) when (error is not OperationCanceledException) { return null; }
    }

    public bool TranslatesTogether => true;

    /// <summary>
    /// "Ekranı çevir": birbirinden bağımsız parçalar (menü satırı, görev yazısı...) tek istekte, aynı sırada çevrilir.
    /// Parça başına istek gitseydi kalabalık bir ekran Gemini'nin dakikalık hakkını (15) ilk saniyelerde bitirir, kalan
    /// parçalar yerel çeviriye düşerdi; yerel model kurulu değilse ekranın hiçbir yazısı çevrilmezdi. Bu tek istek
    /// kullanıcının tuşa basmasıyla gider: ortak hak o an bitmiş olsa da gönderilir (hakkı sayılır). Hata ya da dakikalık
    /// sınırda parçalar yerel çeviriyle çevrilir; o da yoksa hata yukarı çıkar ve ekranda gösterilir (Mac ile aynı).
    /// </summary>
    public async Task<IReadOnlyList<string>> TranslateAllAsync(IReadOnlyList<string> texts, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(texts);
        var game = _game?.Invoke()?.Trim();
        var results = texts.Select(text => _cache.TryGet(CacheKey(text, game), out var known) ? known : null).ToArray();
        var missing = Enumerable.Range(0, texts.Count).Where(i => results[i] is null).ToList();
        var failed = DateTimeOffset.UtcNow < _pausedUntil;
        for (var start = 0; start < missing.Count && !failed; start += ScreenBatch)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var chunk = missing.Skip(start).Take(ScreenBatch).ToList();
            _budget?.TryTake();
            LastLineWasRateLimited = false;
            try
            {
                var sentences = chunk.Select(i => texts[i]).ToList();
                var translated = await PerformAsync(thinkingOff => AiRequest.Build(Provider, _key, Model, sentences, [], game, Server,
                    maxOutputTokens: AiRequest.ScreenOutputTokens, thinkingOff: thinkingOff, instruction: _instruction, target: _target,
                    slang: AiRequest.SlangIn(_glossary, sentences)), _screenHttp, sentences.Count, cancellationToken).ConfigureAwait(false);
                Report(null);
                for (var i = 0; i < chunk.Count; i++)
                {
                    results[chunk[i]] = translated[i];
                    _cache.Store(CacheKey(texts[chunk[i]], game), translated[i]);
                }
            }
            catch (AiRateLimitedException limited) when (!cancellationToken.IsCancellationRequested)
            {
                Rest(limited.Limit);
                failed = true;
            }
            catch (AiIncompleteException) when (!cancellationToken.IsCancellationRequested)
            {
                failed = true;
            }
            catch (Exception error) when (error is HttpRequestException or TaskCanceledException or AiException
                                          && !cancellationToken.IsCancellationRequested)
            {
                Report(StateOf(error));
                _pausedUntil = DateTimeOffset.UtcNow + ErrorPause;
                _pauseRateLimited = false;
                failed = true;
            }
        }
        // Arabic fork: the next key of an AiKeyChain translates the rest together (one request), not line by line.
        var rest = Enumerable.Range(0, texts.Count).Where(i => results[i] is null).ToList();
        if (rest.Count > 0 && _fallback is AiTranslator next)
        {
            var more = await next.TranslateAllAsync([.. rest.Select(i => texts[i])], cancellationToken).ConfigureAwait(false);
            for (var j = 0; j < rest.Count; j++) results[rest[j]] = more[j];
        }
        for (var i = 0; i < texts.Count; i++)
        {
            if (results[i] is not null) continue;
            cancellationToken.ThrowIfCancellationRequested();
            results[i] = await FallbackAsync(texts[i], cancellationToken).ConfigureAwait(false);
        }
        return [.. results.Select(result => result ?? "")];
    }

    public IReadOnlyList<SpokenLine> SpokenLines(string text)
    {
        var game = _game?.Invoke()?.Trim();
        return _speech.TryGetValue(CacheKey(text, game), out var lines) ? lines
            : SpeechScript.FromCache(text, _cache, part => CacheKey(part, game)) is { Count: > 0 } cached ? cached
            : _fallback?.SpokenLines(text) ?? [];
    }

    private string? RecentTranslation(string text)
    {
        var normalized = TextMatch.Normalize(text);
        lock (_log)
            return _log.Lines.LastOrDefault(line => line.Translation is not null && line.Source != text
                                                    && line.Speaker == TranslationDialogue.CurrentSpeaker
                                                    && line.PlayerCharacter == PlayerCharacterInference.Normalize(TranslationDialogue.CurrentPlayerCharacter)
                                                    && TextMatch.IsSimilar(TextMatch.Normalize(line.Source), normalized, 0.85))?.Translation;
    }

    private List<AiContextLine> ContextBeforeShowing(string text, string? game)
    {
        lock (_log)
        {
            if (!string.Equals(_log.Game, game, StringComparison.Ordinal))
            {
                _log.Lines.Clear();
                _log.Game = game;
            }
            // Arabic fork: a chain's links share one log, so the line may already be in it from the link before.
            var speaker = _target == "ar" ? TranslationDialogue.CurrentSpeaker : null;
            var context = _target == "ar" ? _log.Lines.Where(line => line.Source != text || line.Speaker != speaker || line.PlayerCharacter != PlayerCharacterInference.Normalize(TranslationDialogue.CurrentPlayerCharacter)).ToList() : new List<AiContextLine>(_log.Lines);
            // Aynı satır yeniden okunduysa bağlam tekrarla dolmasın.
            if (_log.Lines.Count == 0 || _log.Lines[^1].Source != text || (_target == "ar" && (_log.Lines[^1].Speaker != speaker || _log.Lines[^1].PlayerCharacter != PlayerCharacterInference.Normalize(TranslationDialogue.CurrentPlayerCharacter))))
            {
                _log.Lines.Add(new AiContextLine(text, Speaker: speaker) { PlayerCharacter = _target == "ar" ? PlayerCharacterInference.Normalize(TranslationDialogue.CurrentPlayerCharacter) : null });
                if (_log.Lines.Count > (_target == "ar" ? ArabicContextLines : ContextLines)) _log.Lines.RemoveAt(0);
            }
            return context;
        }
    }

    /// <summary>Gösterilen satırın çevirisi sonraki satırların bağlamına girer.</summary>
    private string Remember(string translation, string text, string? game)
    {
        lock (_log)
        {
            if (!string.Equals(_log.Game, game, StringComparison.Ordinal)) return translation;
            var index = _log.Lines.FindLastIndex(line => line.Source == text &&
                (_target != "ar" || (line.Speaker == TranslationDialogue.CurrentSpeaker && line.PlayerCharacter == PlayerCharacterInference.Normalize(TranslationDialogue.CurrentPlayerCharacter))));
            if (index >= 0) _log.Lines[index] = _log.Lines[index] with { Translation = translation };
        }
        return translation;
    }

    private void Report(OnlineKeyState? state)
    {
        if (_failure == state) return;
        _failure = state;
        FailureChanged?.Invoke(state);
    }

    private async Task<string> FallbackAsync(string text, CancellationToken cancellationToken)
    {
        if (_target != "ar")
            return _fallback is null ? throw new TranslatorException(TranslatorStatus.NeedsDownload)
                : await _fallback.TranslateAsync(text, cancellationToken).ConfigureAwait(false);
        // Arabic fork: the app listens to the chain's first link, so "every key is resting" travels up the chain and a later
        // success clears it; before, lines just vanished with nothing on screen.
        if (_fallback is null)
        {
            if (_pauseRateLimited) Report(OnlineKeyState.QuotaExceeded);
            throw new TranslatorException(TranslatorStatus.NeedsDownload);
        }
        try
        {
            var translated = await _fallback.TranslateAsync(text, cancellationToken).ConfigureAwait(false);
            Report(null);
            return translated;
        }
        catch (TranslatorException) when (_fallback is AiTranslator { LastError: OnlineKeyState.QuotaExceeded })
        {
            Report(OnlineKeyState.QuotaExceeded);
            throw;
        }
    }

    private Task<string[]> RequestAsync(List<string> sentences, List<AiContextLine> context, string? game,
                                        IReadOnlyList<string> names, CancellationToken cancellationToken, string? reference = null) =>
        PerformAsync(thinkingOff => AiRequest.Build(Provider, _key, Model, sentences, context, game, Server, thinkingOff: thinkingOff, names: names,
                                                       instruction: _instruction, target: _target,
                                                       slang: AiRequest.SlangIn(_glossary, sentences), reference: reference,
                                                       speaker: _target == "ar" ? TranslationDialogue.CurrentSpeaker : null,
                                                       playerCharacter: _target == "ar" ? TranslationDialogue.CurrentPlayerCharacter : null),
                     _http, sentences.Count, cancellationToken);

    /// <summary>Düşünmeyi kapatan alanları reddeden Ollama sunucuları; uygulama açık kaldıkça hatırlanır.</summary>
    private static readonly ConcurrentDictionary<string, byte> ThinkingRejected = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>Testler için: hatırlanan sunucuları unutur.</summary>
    internal static void ResetThinkingRejected() => ThinkingRejected.Clear();

    /// <summary>
    /// İsteği gönderir. Ollama düşünmeyi kapatan alanları 400 ile reddederse (eski sürüm) alanlarsız bir kez yeniden
    /// dener; işe yararsa bu sunucuya oturum boyunca alanlar gönderilmez.
    /// </summary>
    private async Task<string[]> PerformAsync(Func<bool, HttpRequestMessage> build, HttpClient http, int count, CancellationToken cancellationToken)
    {
        var thinkingOff = Server is { DisablesThinking: true } server && !ThinkingRejected.ContainsKey(server.Address);
        if (thinkingOff)
        {
            using var request = build(true);
            using var response = await http.SendAsync(request, cancellationToken).ConfigureAwait(false);
            var body = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
            if (response.StatusCode != HttpStatusCode.BadRequest) return Read(response, body, count);
        }
        using (var request = build(false))
        {
            using var response = await http.SendAsync(request, cancellationToken).ConfigureAwait(false);
            var body = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
            var result = Read(response, body, count);
            if (thinkingOff) ThinkingRejected[Server!.Address] = 0;
            return result;
        }
    }

    /// <summary>
    /// İstek hatasının kullanıcıya gösterilen türü. Zaman aşımı (HttpClient'ın <see cref="TaskCanceledException"/>'ı;
    /// kullanıcının iptali buraya gelmez) yoğunluk sayılır: hizmete ulaşıldı ama yanıt zamanında gelmedi.
    /// </summary>
    internal static OnlineKeyState StateOf(Exception error) => error switch
    {
        AiException ai => ai.State,
        TaskCanceledException => OnlineKeyState.Busy,
        _ => OnlineKeyState.Unreachable,
    };

    private string[] Read(HttpResponseMessage response, string body, int count)
    {
        if (!response.IsSuccessStatusCode)
        {
            // Arabic fork: which limit Google hit, and on which key (by tag), so the session report can tell.
            SubtitleLog.Write((int)response.StatusCode == 429
                ? $"limit: {AiRequest.QuotaSummary(body)} key#{_keyTag} {Model.Id}"
                : $"api-hata: HTTP {(int)response.StatusCode} key#{_keyTag} {Model.Id}");
            if (AiRequest.RateLimit(response.StatusCode, body) is { } limit) throw new AiRateLimitedException(limit);
            // Arabic fork: any other 429 (day limit, Google's no-details "Resource has been exhausted") only rests this key;
            // the line goes to the next key at once and the resting key is not retried line after line.
            if ((int)response.StatusCode == 429 && _target == "ar")
            {
                var daily = body.Contains("PerDay", StringComparison.Ordinal);
                throw new AiRateLimitedException(new AiRateLimit(Daily: daily, Shared: !daily));
            }
            throw new AiException(AiRequest.Failure(response.StatusCode, body));
        }
        if (_target == "ar" && AiRequest.EarlyStop(Provider, body) is { } stop)
        {
            SubtitleLog.Write($"yarım yanıt: {stop} key#{_keyTag} {Model.Id}");
            throw new AiIncompleteException(stop);
        }
        var lines = AiRequest.Translations(AiRequest.Text(Provider, body), count);
        return _target == "ar" ? [.. lines.Select(AiRequest.ArabicLetters)] : lines;
    }

    /// <summary>
    /// Ayarlardaki "Dene" ve "Bağlantıyı dene": örnek satırı çevirir; çeviri ya da hatanın türü döner. Yerel modelde
    /// anahtar isteğe bağlıdır ve model belleğe yüklenirken uzun beklenir.
    /// </summary>
    public static async Task<AiTestResult> TestAsync(AiProvider provider, string key, string? model, HttpClient? http = null,
                                                     CancellationToken cancellationToken = default, LocalAiServer? server = null,
                                                     string? instruction = null, string target = "tr")
    {
        key = key.Trim();
        if (key.Length == 0 && !provider.IsLocal()) return new(OnlineKeyState.Invalid);
        http ??= provider.IsLocal() ? LocalTestClient.Value : CloudTestClient.Value;
        // Deneme tek istektir; çeviri hattının dakikalık hakkından yemez.
        var translator = new AiTranslator(provider, key, model, new FailingTranslator(), http, budget: new RequestBudget(int.MaxValue),
                                          server: server, instruction: instruction, target: target);
        try
        {
            return new(OnlineKeyState.Valid, await translator.TranslateAsync(Sample, cancellationToken).ConfigureAwait(false));
        }
        catch (FailedException)
        {
            // Dakikalık sınıra takıldıysa anahtar geçerlidir ama şu an sınırdadır.
            return new(translator.LastError ?? (translator.LastLineWasRateLimited ? OnlineKeyState.Busy : OnlineKeyState.Unreachable));
        }
    }

    /// <summary>Denemede yerel çeviriye düşülmez; hata olduğu gibi gösterilir.</summary>
    private sealed class FailingTranslator : ITextTranslator
    {
        public Task<string> TranslateAsync(string text, CancellationToken cancellationToken = default) => throw new FailedException();
    }

    private sealed class FailedException : Exception;
}
