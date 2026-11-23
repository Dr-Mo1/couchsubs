// SPDX-License-Identifier: AGPL-3.0-only

using System.Text.Json;

namespace CouchSubs.Shell.Tests;

public class PlayerCharacterSettingsTests
{
    [Fact]
    public void OldSettingsDefaultToAutomaticPlayerCharacter()
    {
        Assert.Equal("auto", new UserSettings().PlayerCharacter);
        Assert.Equal("auto", JsonSerializer.Deserialize<UserSettings>("{}")!.PlayerCharacter);
    }

    [Theory]
    [InlineData("auto")]
    [InlineData("jason")]
    [InlineData("lucia")]
    public void PlayerCharacterSurvivesSettingsJson(string player)
    {
        var json = JsonSerializer.Serialize(new UserSettings { PlayerCharacter = player });
        Assert.Equal(player, JsonSerializer.Deserialize<UserSettings>(json)!.PlayerCharacter);
    }
}
