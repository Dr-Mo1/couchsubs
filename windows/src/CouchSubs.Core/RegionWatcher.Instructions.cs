// SPDX-License-Identifier: AGPL-3.0-only
namespace CouchSubs.Core;

public sealed partial class RegionWatcher
{
    public const int InstructionDisplayMs = 4000;
    internal const int RecentInstructionLimit = 20;
    private readonly object hintGate = new();
    private readonly Queue<string> recentInstructions = new();
    private Timer? hintTimer;
    private bool instructionVisible;
    private long instructionUntil;
    private int dialogueVersion;

    // Called before dialogue translation starts, without waiting for the region worker.
    public void DialogueArrived()
    {
        lock (hintGate)
        {
            dialogueVersion++;
            HideInstruction();
        }
    }

    internal void ExpireInstruction()
    {
        lock (hintGate)
            if (instructionVisible && now() >= instructionUntil) HideInstruction();
    }

    private void HideInstruction()
    {
        hintTimer?.Dispose();
        hintTimer = null;
        if (!instructionVisible) return;
        instructionVisible = false;
        server.SendRegion(MissionLine.AreaId, "", null);
    }
}
