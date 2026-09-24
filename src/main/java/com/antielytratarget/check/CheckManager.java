package com.antielytratarget.check;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.check.aim.*;
import com.antielytratarget.check.combat.*;
import com.antielytratarget.check.elytra.*;
import com.antielytratarget.check.misc.*;
import com.antielytratarget.player.AETPlayer;

import java.util.*;

public class CheckManager {

    private final AntiElytraTargetPlugin plugin;
    private final AETPlayer aetPlayer;

private final List<AbstractCheck> combatChecks = new ArrayList<>();
    private final List<AbstractCheck> aimChecks = new ArrayList<>();
    private final List<AbstractCheck> elytraChecks = new ArrayList<>();
    private final List<AbstractCheck> miscChecks = new ArrayList<>();
    private final List<AbstractCheck> allChecks = new ArrayList<>();
    private final Map<Class<? extends AbstractCheck>, AbstractCheck> checksByClass = new HashMap<>();
    private AbstractCheck[] allChecksArray = new AbstractCheck[0];
    private PredictionCompleteCheck[] predictionCompleteByIndex = new PredictionCompleteCheck[0];

    public CheckManager(AntiElytraTargetPlugin plugin, AETPlayer aetPlayer) {
        this.plugin = plugin;
        this.aetPlayer = aetPlayer;

combatChecks.add(new AngleDeviationCheck(plugin, aetPlayer));
        combatChecks.add(new AimSnapCheck(plugin, aetPlayer));
        combatChecks.add(new AttackRateCheck(plugin, aetPlayer));
        combatChecks.add(new TimingConsistencyCheck(plugin, aetPlayer));
        combatChecks.add(new ElytraCombatRateCheck(plugin, aetPlayer));

aimChecks.add(new SmoothAimCheck(plugin, aetPlayer));
        aimChecks.add(new GCDCheck(plugin, aetPlayer));
        aimChecks.add(new LookVectorLockCheck(plugin, aetPlayer));
        aimChecks.add(new PitchLockCheck(plugin, aetPlayer));
        aimChecks.add(new RotationConsistencyCheck(plugin, aetPlayer));

aimChecks.add(new AimComplexCheck(plugin, aetPlayer));
        aimChecks.add(new AimMXHeuristicCheck(plugin, aetPlayer));

        elytraChecks.add(new VelocityAlignCheck(plugin, aetPlayer));
        elytraChecks.add(new StrafeSyncCheck(plugin, aetPlayer));
        elytraChecks.add(new AccelerationLockCheck(plugin, aetPlayer));
        elytraChecks.add(new ElytraPhysicsCheck(plugin, aetPlayer));

elytraChecks.add(new RotationPredictionCheck(plugin, aetPlayer));

miscChecks.add(new FireworkPatternCheck(plugin, aetPlayer));
        miscChecks.add(new OffhandFireworkSwitchCheck(plugin, aetPlayer));
        miscChecks.add(new IncapableSwapCheck(plugin, aetPlayer));
        miscChecks.add(new HeadPitchDivergenceCheck(plugin, aetPlayer));
        miscChecks.add(new UseItemRotationMismatchCheck(plugin, aetPlayer));
        miscChecks.add(new PacketOrderECheck(plugin, aetPlayer));

        allChecks.addAll(combatChecks);
        allChecks.addAll(aimChecks);
        allChecks.addAll(elytraChecks);
        allChecks.addAll(miscChecks);

        indexChecks();
        reloadAll();
    }

    private void indexChecks() {
        checksByClass.clear();
        allChecksArray = allChecks.toArray(new AbstractCheck[0]);
        predictionCompleteByIndex = new PredictionCompleteCheck[allChecksArray.length];
        for (int i = 0; i < allChecksArray.length; i++) {
            AbstractCheck check = allChecksArray[i];
            checksByClass.put(check.getClass(), check);
            if (check instanceof PredictionCompleteCheck predictionCompleteCheck) {
                predictionCompleteByIndex[i] = predictionCompleteCheck;
            }
        }
    }

    public void reloadAll() {
        for (AbstractCheck check : allChecks) {
            check.onReload();
        }
    }

public void tickReward() {
        for (int i = 0; i < allChecksArray.length; i++) {
            PredictionCompleteCheck predictionCompleteCheck = predictionCompleteByIndex[i];
            if (predictionCompleteCheck != null) {
                predictionCompleteCheck.onPredictionComplete();
            }
            AbstractCheck check = allChecksArray[i];
            check.reward();
        }
    }

    @SuppressWarnings("unchecked")
    public <T extends AbstractCheck> T getCheck(Class<T> clazz) {
        AbstractCheck direct = checksByClass.get(clazz);
        if (direct != null) return (T) direct;

        for (AbstractCheck c : allChecksArray) {
            if (clazz.isInstance(c)) return (T) c;
        }
        return null;
    }

    public List<AbstractCheck> getCombatChecks() { return combatChecks; }
    public List<AbstractCheck> getAimChecks() { return aimChecks; }
    public List<AbstractCheck> getElytraChecks() { return elytraChecks; }
    public List<AbstractCheck> getMiscChecks() { return miscChecks; }
    public List<AbstractCheck> getAllChecks() { return allChecks; }
}
