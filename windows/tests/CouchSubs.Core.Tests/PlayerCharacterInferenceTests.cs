// SPDX-License-Identifier: AGPL-3.0-only

using CouchSubs.Core;

namespace CouchSubs.Core.Tests;

public class PlayerCharacterInferenceTests
{
    [Fact]
    public void SeventyPercentJasonIsEnoughAndWindowMovesToLucia()
    {
        var inference = new PlayerCharacterInference(() => 0);
        for (var i = 0; i < 7; i++) inference.Observe("JASON");
        for (var i = 0; i < 3; i++) inference.Observe("Lucia");
        Assert.Equal("Jason", inference.Resolve());
        for (var i = 0; i < 4; i++) inference.Observe("LUCIA");
        Assert.Equal("Lucia", inference.Resolve());
    }

    [Fact]
    public void AlternatingProtagonistsAndNoLabelsAreUnknown()
    {
        var inference = new PlayerCharacterInference(() => 0);
        Assert.Null(inference.Resolve());
        inference.Observe("Cal");
        Assert.Null(inference.Resolve());
        for (var i = 0; i < 10; i++) inference.Observe(i % 2 == 0 ? "Jason" : "Lucia");
        Assert.Null(inference.Resolve());
    }

    [Fact]
    public void LabelsExpireAfterFifteenMinutes()
    {
        long time = 0;
        var inference = new PlayerCharacterInference(() => time);
        for (var i = 0; i < 3; i++) inference.Observe("Jason");
        time = PlayerCharacterInference.WindowMs;
        Assert.Equal("Jason", inference.Resolve());
        time++;
        Assert.Null(inference.Resolve());
        for (var i = 0; i < 3; i++) inference.Observe("Lucia");
        Assert.Equal("Lucia", inference.Resolve());
    }

    [Fact]
    public void OneOrTwoStrayLabelsStayUnknown()
    {
        // Lucia calling while Jason roams must not flip strangers' insults to the feminine.
        var inference = new PlayerCharacterInference(() => 0);
        inference.Observe("Lucia");
        Assert.Null(inference.Resolve());
        inference.Observe("Lucia");
        Assert.Null(inference.Resolve());
        inference.Observe("Lucia");
        Assert.Equal("Lucia", inference.Resolve());
    }

    [Fact]
    public void ManualChoiceWinsWithoutDiscardingInference()
    {
        var inference = new PlayerCharacterInference(() => 0);
        for (var i = 0; i < 3; i++) inference.Observe("Jason");
        Assert.Equal("Lucia", inference.Resolve("lucia"));
        Assert.Equal("Jason", inference.Resolve("auto"));
        Assert.Equal("Jason", inference.Resolve("invalid"));
        Assert.Equal("Jason", new PlayerCharacterInference(() => 0).Resolve("jason"));
    }
}
