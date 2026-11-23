// SPDX-License-Identifier: AGPL-3.0-only

namespace CouchSubs.Core.Tests;

public partial class RegionWatcherTests
{
    [Theory]
    [InlineData("Grand Theft Auto VI", false)]
    [InlineData("Grand Theft Auto VI", true)]
    [InlineData("Grand Theft Auto V", false)]
    [InlineData("Another game", false)]
    public async Task EncryptedChatIsManualOnlyInGtaVi(string game, bool phonePanel)
    {
        var reader = new Reader { Lines = [.. GtaViPhoneMessagesTests.Chat().Lines] };
        var translator = new Translator();
        var server = new Server();
        var watcher = new RegionWatcher(reader, translator, server) { Game = () => game };
        using var lifetime = new CancellationTokenSource();
        var area = new WatchedArea("chat", "Chat", new CropRegion(0, 1, 0, 1))
        {
            PanelLifetime = phonePanel ? lifetime.Token : null,
        };
        await watcher.PassAsync(Shade(32), [area]);
        await watcher.PassAsync(Shade(32), [area]);
        if (GameName.Key(game) == "gta 6")
        {
            Assert.Empty(translator.Calls);
            Assert.Empty(server.Sent);
        }
        else Assert.NotEmpty(translator.Calls);
    }
}
