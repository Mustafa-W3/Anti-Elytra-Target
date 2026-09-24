package com.antielytratarget.check.elytra;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.check.AbstractCheck;
import com.antielytratarget.check.CheckData;
import com.antielytratarget.netty.PlayerPacketData;
import com.antielytratarget.player.AETPlayer;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

@CheckData(name = "RotationPrediction", configName = "rotation_prediction",
        decay = 0.10,
        description = "Detects repeated entity-target snaps that no plausible mouse lattice can explain")
public class RotationPredictionCheck extends AbstractCheck {

    private static final int HIT_QUEUE_LIMIT = 24;
    private static final int RAW_SNAP_LIMIT = 12;
    private static final int MOTION_FRAME_LIMIT = 96;
    private static final long MIN_ORBIT_FRAME_GAP_NANOS = 40_000_000L;

    private double minTargetDistance = 0.75;
    private double maxTargetDistance = 10.0;
    private double orbitMinTargetDistance = 0.35;
    private double orbitMaxTargetDistance = 8.5;
    private double minPreviousError = 18.0;
    private double maxLandingError = 0.20;
    private double maxHitError = 0.50;
    private double minGeodesicRotation = 12.0;
    private double maxGeodesicRotation = 160.0;
    private double minConvergenceRatio = 0.985;
    private double maxCorrectionResidual = 0.35;
    private double rapidHitMinYaw = 75.0;

    private long minSampleGapMs = 25L;
    private long maxSampleGapMs = 100L;
    private long maxCandidateAgeMs = 125L;
    private long rapidHitWindowMs = 250L;
    private long evidenceWindowMs = 6_000L;
    private long alertCooldownMs = 5_000L;

    private boolean orbitDetectionEnabled = true;
    private int validationSamples = 5;
    private int evidenceHitWindow = 16;
    private int requiredConfirmedCandidates = 3;
    private int rapidHitRequired = 3;

    private final RotationPredictionModel.LatticeTracker lattice =
            new RotationPredictionModel.LatticeTracker();
    private final ConcurrentLinkedQueue<HitSample> hitQueue = new ConcurrentLinkedQueue<>();
    private final Deque<RawSnap> rawSnaps = new ArrayDeque<>();
    private final Deque<RapidRotation> rapidRotations = new ArrayDeque<>();
    private final Deque<RotationPredictionOrbitModel.Frame> motionFrames = new ArrayDeque<>();
    private final Deque<RotationPredictionOrbitModel.TargetPose> targetPoses = new ArrayDeque<>();
    private final Deque<CandidateValidation> validations = new ArrayDeque<>();
    private final Deque<ConfirmedCandidate> confirmedCandidates = new ArrayDeque<>();

    private UUID trackedWorld;
    private long lastPacketSequence;
    private long lastPacketNanos;
    private long suppressUntilNanos;
    private long lastAlertNanos;
    private long hitSequence;
    private UUID orbitEvidenceTarget;
    private UUID targetPoseId;
    private long lastOrbitEvidenceFrameNanos;
    private long lastTargetHitNanos;
    private WeakReference<LivingEntity> trackedTargetEntity = new WeakReference<>(null);

    public RotationPredictionCheck(AntiElytraTargetPlugin plugin, AETPlayer aetPlayer) {
        super(plugin, aetPlayer);
    }

    @Override
    protected void loadConfig() {
        minTargetDistance = clamp(cfg("min_target_distance", 0.75), 0.0, 4.0);
        maxTargetDistance = clamp(cfg("max_target_distance", 10.0),
                minTargetDistance + 0.5, 24.0);

orbitMinTargetDistance = clamp(cfg("target_lock_min_center_distance", 0.35), 0.25, 4.0);
        orbitMaxTargetDistance = clamp(cfg("orbit_max_target_distance", 8.5),
                orbitMinTargetDistance + 0.5, 8.5);
        minPreviousError = clamp(cfg("min_previous_error", 18.0), 8.0, 90.0);
        maxLandingError = clamp(cfg("max_landing_error", 0.20), 0.02, 1.0);
        maxHitError = clamp(cfg("max_hit_error", 0.50), maxLandingError, 3.0);
        minGeodesicRotation = clamp(cfg("min_geodesic_rotation", 12.0), 5.0, 90.0);
        maxGeodesicRotation = clamp(cfg("max_geodesic_rotation", 160.0),
                minGeodesicRotation + 5.0, 180.0);
        minConvergenceRatio = clamp(cfg("min_convergence_ratio", 0.985), 0.90, 1.05);
        maxCorrectionResidual = clamp(cfg("max_correction_residual", 0.35), 0.05, 2.0);
        rapidHitMinYaw = clamp(cfg("rapid_hit_min_yaw", 75.0), 45.0, 180.0);

        minSampleGapMs = clampLong(cfgInt("min_sample_gap_ms", 25), 5L, 75L);
        maxSampleGapMs = clampLong(cfgInt("max_sample_gap_ms", 100),
                minSampleGapMs + 10L, 250L);
        maxCandidateAgeMs = clampLong(cfgInt("max_candidate_age_ms", 125), 50L, 500L);
        rapidHitWindowMs = clampLong(cfgInt("rapid_hit_window_ms", 250), 75L, 500L);
        evidenceWindowMs = clampLong(cfgInt("evidence_window_ms", 6_000), 1_000L, 20_000L);
        alertCooldownMs = clampLong(cfgInt("alert_cooldown_ms", 5_000), 1_000L, 30_000L);
        orbitDetectionEnabled = cfgBool("orbit_detection_enabled", true);
        validationSamples = clampInt(cfgInt("validation_samples", 5), 3, 10);
        evidenceHitWindow = clampInt(cfgInt("evidence_hit_window", 16), 3, 32);
        requiredConfirmedCandidates = clampInt(cfgInt("required_confirmed_candidates", 3),
                2, evidenceHitWindow);
        rapidHitRequired = clampInt(cfgInt("rapid_hit_required", 3), 2, 6);
        reset();
    }

public boolean checkOnHit(Player attacker, LivingEntity victim, double angleToVictim) {
        if (attacker == null || victim == null) return false;

        Location targetLocation = victim.getLocation();
        BoundingBox box = victim.getBoundingBox();
        double centerX = (box.getMinX() + box.getMaxX()) * 0.5;
        double centerY = (box.getMinY() + box.getMaxY()) * 0.5;
        double centerZ = (box.getMinZ() + box.getMaxZ()) * 0.5;
        double eyeX = targetLocation.getX();
        double eyeY = targetLocation.getY() + victim.getEyeHeight();
        double eyeZ = targetLocation.getZ();

        Location attackerEye = attacker.getEyeLocation();
        double eyeAnchorAngle = RotationPredictionModel.targetError(
                attackerEye.getYaw(), attackerEye.getPitch(),
                eyeX - attackerEye.getX(), eyeY - attackerEye.getY(), eyeZ - attackerEye.getZ());
        double hitAngle = Math.min(angleToVictim, eyeAnchorAngle);
        org.bukkit.util.Vector targetVelocity = victim.getVelocity();

        TargetSnapshot target = new TargetSnapshot(
                victim.getUniqueId(), victim.getWorld().getUID(),
                victim.getName(), victim.getType().name(),
                centerX, centerY, centerZ,
                eyeX, eyeY, eyeZ,
                box.getMinX(), box.getMinY(), box.getMinZ(),
                box.getMaxX(), box.getMaxY(), box.getMaxZ(),
                targetVelocity.getX(), targetVelocity.getY(), targetVelocity.getZ());
        if (!target.finite()) return false;

        long hitNanos = System.nanoTime();
        trackedTargetEntity = new WeakReference<>(victim);
        lastTargetHitNanos = hitNanos;
        hitQueue.add(new HitSample(target, hitAngle, hitNanos));
        while (hitQueue.size() > HIT_QUEUE_LIMIT) hitQueue.poll();
        return false;
    }

    public void tick(Player player, Location eventFrom, Location eventTo, boolean discontinuity) {
        if (player == null || eventFrom == null || eventTo == null) return;
        if (discontinuity) {
            hardSuppressFor(player, 1_000L);
            return;
        }
        if (!canCheck(player) || !player.isGliding() || player.isInsideVehicle()) {
            hardResetOperational(player);
            return;
        }

        long nowNanos = System.nanoTime();
        if (isSuppressed(nowNanos)) {
            hardResetOperational(player);
            return;
        }
        if (eventFrom.getWorld() == null || eventTo.getWorld() == null
                || !eventFrom.getWorld().equals(eventTo.getWorld())) {
            hardSuppressFor(player, 1_000L);
            return;
        }
        if (eventFrom.getYaw() == eventTo.getYaw()
                && eventFrom.getPitch() == eventTo.getPitch()) return;

        UUID worldId = eventTo.getWorld().getUID();
        if (trackedWorld != null && !trackedWorld.equals(worldId)) {
            hardSuppressFor(player, 1_000L);
            return;
        }

        PlayerPacketData packetData = packetData(player);
        if (packetData == null) {
            hardResetOperational(player);
            return;
        }
        PlayerPacketData.RotationSample packet = packetData.findRotationSampleAfter(
                lastPacketSequence,
                eventFrom.getYaw(), eventFrom.getPitch(),
                eventTo.getYaw(), eventTo.getPitch());
        if (packet == null) {
            reseed(worldId, packetData.latestRotationSequence());
            return;
        }
        lastPacketSequence = packet.sequence();

        long packetAgeNanos = nowNanos - packet.receivedNanos();
        if (packetAgeNanos < 0L
                || packetAgeNanos > millisToNanos(maxSampleGapMs + 100L)) {
            reseed(worldId, packet.sequence());
            return;
        }

if (Math.abs(packet.previousPitch()) > 89.0f
                || Math.abs(packet.pitch()) > 89.0f) {
            reseed(worldId, packet.sequence());
            return;
        }

recordRapidRotation(worldId, packet);

        if (lastPacketNanos == 0L) {
            lastPacketNanos = packet.receivedNanos();
            trackedWorld = worldId;
            recordMotionFrame(player, eventTo, packet);
            consumeHits(worldId, nowNanos);
            maybeAlert(player, nowNanos);
            drainExpired(nowNanos);
            return;
        }
        long gapNanos = packet.receivedNanos() - lastPacketNanos;
        long gapMillis = gapNanos / 1_000_000L;
        if (gapNanos <= 0L || gapMillis < minSampleGapMs) {

drainExpired(nowNanos);
            return;
        }
        if (gapMillis > maxSampleGapMs) {
            reseed(worldId, packet.sequence());
            return;
        }
        lastPacketNanos = packet.receivedNanos();

        RotationPredictionModel.Observation observation = lattice.observe(
                packet.previousYaw(), packet.previousPitch(), packet.yaw(), packet.pitch());
        recordMotionFrame(player, eventTo, packet);
        recordRawSnap(player, eventTo, worldId, packet);
        consumeHits(worldId, nowNanos);
        processValidations(observation, nowNanos);
        maybeAlert(player, nowNanos);
        drainExpired(nowNanos);
    }

    public void suppressFor(long milliseconds) {
        suppressUntilNanos = Math.max(suppressUntilNanos,
                System.nanoTime() + millisToNanos(Math.max(0L, milliseconds)));
        hardResetOperational(null);
    }

    public void hardSuppressFor(Player player, long milliseconds) {
        suppressUntilNanos = Math.max(suppressUntilNanos,
                System.nanoTime() + millisToNanos(Math.max(0L, milliseconds)));
        hardResetOperational(player);
    }

    public void reset() {
        suppressUntilNanos = 0L;
        lastAlertNanos = 0L;
        hardResetOperational(null);
    }

    private void recordMotionFrame(Player player,
                                   Location playerLocation,
                                   PlayerPacketData.RotationSample packet) {
        if (!orbitDetectionEnabled) return;
        RotationPredictionOrbitModel.Frame previous = motionFrames.peekLast();
        if (previous != null
                && packet.receivedNanos() - previous.createdNanos()
                < MIN_ORBIT_FRAME_GAP_NANOS) {
            return;
        }
        motionFrames.addLast(new RotationPredictionOrbitModel.Frame(
                packet.receivedNanos(),
                playerLocation.getX(), playerLocation.getY() + player.getEyeHeight(),
                playerLocation.getZ(), packet.yaw(), packet.pitch()));
        while (motionFrames.size() > MOTION_FRAME_LIMIT) motionFrames.removeFirst();
        recordTrackedTargetPose(packet.receivedNanos());
    }

    private void recordRawSnap(Player player,
                               Location playerLocation,
                               UUID worldId,
                               PlayerPacketData.RotationSample packet) {
        double rotation = RotationPredictionModel.geodesicRotation(
                packet.previousYaw(), packet.previousPitch(), packet.yaw(), packet.pitch());
        if (rotation < minGeodesicRotation || rotation > maxGeodesicRotation) return;

        RotationPredictionModel.SnapEvidence evidence = lattice.findMismatch(
                packet.previousYaw(), packet.previousPitch(), packet.yaw(), packet.pitch());
        if (evidence == null) return;

        rawSnaps.addLast(new RawSnap(
                worldId, packet.receivedNanos(),
                playerLocation.getX(), playerLocation.getY() + player.getEyeHeight(),
                playerLocation.getZ(),
                packet.previousYaw(), packet.previousPitch(),
                packet.yaw(), packet.pitch(), rotation, evidence));
        while (rawSnaps.size() > RAW_SNAP_LIMIT) rawSnaps.removeFirst();
    }

    private void recordRapidRotation(UUID worldId,
                                     PlayerPacketData.RotationSample packet) {
        double yawTurn = RotationPredictionModel.qualifyingRapidYaw(
                packet.previousYaw(), packet.yaw(), rapidHitMinYaw);
        if (yawTurn <= 0.0) return;
        rapidRotations.addLast(new RapidRotation(
                worldId, packet.receivedNanos(), yawTurn));
        while (rapidRotations.size() > 16) rapidRotations.removeFirst();
    }

    private void consumeHits(UUID worldId, long nowNanos) {
        HitSample hit;
        while ((hit = hitQueue.poll()) != null) {
            long queueAge = nowNanos - hit.createdNanos();
            if (queueAge < 0L || queueAge > millisToNanos(maxCandidateAgeMs + 250L)) continue;
            if (!worldId.equals(hit.target().world()) || !Double.isFinite(hit.angle())) continue;

            hitSequence++;
            pruneConfirmed(nowNanos);

            RapidRotation rapidRotation = findMatchingRapidRotation(hit);
            if (rapidRotation != null) {
                confirmedCandidates.addLast(new ConfirmedCandidate(
                        hit.target(), hitSequence, hit.createdNanos(),
                        rapidRotation.yawTurn(), hit.angle(), "RAPID_HIT_ROTATION"));
                while (confirmedCandidates.size() > 32) {
                    confirmedCandidates.removeFirst();
                }
                if (plugin.isDebugEnabled()) {
                    plugin.debug("[RotationPrediction] rapid-hit target="
                            + hit.target().displayName()
                            + " yaw=" + String.format("%.1f", rapidRotation.yawTurn())
                            + " ageMs=" + String.format("%.1f",
                            (hit.createdNanos() - rapidRotation.createdNanos()) / 1_000_000.0));
                }
            }

            if (orbitDetectionEnabled) {
                recordTargetPose(hit);
                RotationPredictionOrbitModel.Result orbit = RotationPredictionOrbitModel.analyze(
                        new java.util.ArrayList<>(motionFrames),
                        new RotationPredictionOrbitModel.Target(
                                hit.target().centerX(), hit.target().centerY(), hit.target().centerZ(),
                                hit.target().eyeX(), hit.target().eyeY(), hit.target().eyeZ(),
                                hit.target().velocityX(), hit.target().velocityY(), hit.target().velocityZ(),
                                new java.util.ArrayList<>(targetPoses)),
                        hit.createdNanos(), orbitMinTargetDistance, orbitMaxTargetDistance);
                if (orbit.samples() > 0 && plugin.isDebugEnabled()) {
                    plugin.debug("[RotationPrediction] orbit-probe target="
                            + hit.target().displayName()
                            + " mode=" + orbit.mode()
                            + " samples=" + orbit.samples()
                            + " net=" + String.format("%.1f", orbit.netOrbit())
                            + " yaw=" + String.format("%.1f", orbit.totalYaw())
                            + " p90=" + String.format("%.3f", orbit.p90AimError())
                            + " yawRate=" + String.format("%.1f", orbit.yawRate())
                            + " rapid=" + String.format("%.3f", orbit.rapidTurnShare())
                            + " sync=" + String.format("%.3f", orbit.syncMean())
                            + " tangent=" + String.format("%.3f", orbit.tangentShare())
                            + " path=" + String.format("%.2f", orbit.path()));
                }
                if (orbit.candidate()) {
                    String mode = "ORBIT_BEHAVIOR";
                    boolean sameSeries = hit.target().id().equals(orbitEvidenceTarget);
                    if (!sameSeries) {
                        confirmedCandidates.removeIf(candidate ->
                                "ORBIT_BEHAVIOR".equals(candidate.mode()));
                        orbitEvidenceTarget = hit.target().id();
                        lastOrbitEvidenceFrameNanos = 0L;
                    }

                    long newFrames = motionFrames.stream()
                            .filter(frame -> frame.createdNanos() > lastOrbitEvidenceFrameNanos
                                    && frame.createdNanos() <= orbit.lastFrameNanos())
                            .count();
                    if (lastOrbitEvidenceFrameNanos > 0L && newFrames < 8L) {

} else {
                        confirmedCandidates.addLast(new ConfirmedCandidate(
                                hit.target(), hitSequence, hit.createdNanos(),
                                orbit.evidenceRotation(), orbit.p90AimError(), mode));
                        lastOrbitEvidenceFrameNanos = orbit.lastFrameNanos();
                        while (confirmedCandidates.size() > 32) {
                            confirmedCandidates.removeFirst();
                        }
                        continue;
                    }
                }
            }

            MatchedSnap matched = findMatchingSnap(hit);
            if (matched == null) continue;
            if (matched.snap().evidence().epoch() != lattice.epoch()) continue;

            validations.addLast(new CandidateValidation(
                    hit.target(), hitSequence, hit.createdNanos(),
                    matched.snap().rotation(), matched.landingError(),
                    matched.snap().evidence().calibration()));
            while (validations.size() > evidenceHitWindow) validations.removeFirst();
        }
    }

    private RapidRotation findMatchingRapidRotation(HitSample hit) {
        Iterator<RapidRotation> iterator = rapidRotations.descendingIterator();
        while (iterator.hasNext()) {
            RapidRotation rotation = iterator.next();
            long age = hit.createdNanos() - rotation.createdNanos();
            if (age < 0L) continue;
            if (age > millisToNanos(rapidHitWindowMs)) break;
            if (!rotation.world().equals(hit.target().world())) continue;
            iterator.remove();
            return rotation;
        }
        return null;
    }

    private void recordTargetPose(HitSample hit) {
        UUID targetId = hit.target().id();
        prepareTargetTrajectory(targetId);
        addTargetPose(new RotationPredictionOrbitModel.TargetPose(
                hit.createdNanos(),
                hit.target().centerX(), hit.target().centerY(), hit.target().centerZ(),
                hit.target().eyeX(), hit.target().eyeY(), hit.target().eyeZ(),
                hit.target().velocityX(), hit.target().velocityY(), hit.target().velocityZ()));
    }

    private void recordTrackedTargetPose(long frameNanos) {
        if (lastTargetHitNanos == 0L
                || frameNanos - lastTargetHitNanos > 4_000_000_000L) return;
        LivingEntity target = trackedTargetEntity.get();
        if (target == null || !target.isValid() || target.isDead()) return;
        if (trackedWorld != null && !target.getWorld().getUID().equals(trackedWorld)) return;

        try {
            BoundingBox box = target.getBoundingBox();
            Location location = target.getLocation();
            org.bukkit.util.Vector velocity = target.getVelocity();
            UUID targetId = target.getUniqueId();
            prepareTargetTrajectory(targetId);
            addTargetPose(new RotationPredictionOrbitModel.TargetPose(
                    frameNanos,
                    (box.getMinX() + box.getMaxX()) * 0.5,
                    (box.getMinY() + box.getMaxY()) * 0.5,
                    (box.getMinZ() + box.getMaxZ()) * 0.5,
                    location.getX(), location.getY() + target.getEyeHeight(), location.getZ(),
                    velocity.getX(), velocity.getY(), velocity.getZ()));
        } catch (RuntimeException ignored) {

}
    }

    private void prepareTargetTrajectory(UUID targetId) {
        if (!targetId.equals(targetPoseId)) {
            targetPoses.clear();
            targetPoseId = targetId;
            if (!targetId.equals(orbitEvidenceTarget)) {
                confirmedCandidates.removeIf(candidate ->
                        "ORBIT_BEHAVIOR".equals(candidate.mode()));
                orbitEvidenceTarget = null;
                lastOrbitEvidenceFrameNanos = 0L;
            }
        }
    }

    private void addTargetPose(RotationPredictionOrbitModel.TargetPose pose) {
        if (pose == null || !pose.finite()) return;
        RotationPredictionOrbitModel.TargetPose last = targetPoses.peekLast();
        if (last == null || last.createdNanos() < pose.createdNanos()) {
            targetPoses.addLast(pose);
        } else {
            java.util.ArrayList<RotationPredictionOrbitModel.TargetPose> ordered =
                    new java.util.ArrayList<>(targetPoses);
            ordered.removeIf(existing -> existing.createdNanos() == pose.createdNanos());
            ordered.add(pose);
            ordered.sort(java.util.Comparator.comparingLong(
                    RotationPredictionOrbitModel.TargetPose::createdNanos));
            targetPoses.clear();
            targetPoses.addAll(ordered);
        }
        while (targetPoses.size() > MOTION_FRAME_LIMIT) targetPoses.removeFirst();
        long oldest = targetPoses.peekLast().createdNanos() - 4_000_000_000L;
        while (!targetPoses.isEmpty()
                && targetPoses.peekFirst().createdNanos() < oldest) {
            targetPoses.removeFirst();
        }
    }

    private MatchedSnap findMatchingSnap(HitSample hit) {
        Iterator<RawSnap> iterator = rawSnaps.descendingIterator();
        while (iterator.hasNext()) {
            RawSnap snap = iterator.next();
            long age = hit.createdNanos() - snap.createdNanos();
            if (age < 0L) continue;
            if (age > millisToNanos(maxCandidateAgeMs)) break;
            if (!snap.world().equals(hit.target().world())) continue;

            RotationPredictionTargetModel.Match geometry = RotationPredictionTargetModel.match(
                    new RotationPredictionTargetModel.Frame(
                            snap.eyeX(), snap.eyeY(), snap.eyeZ(),
                            snap.fromYaw(), snap.fromPitch(),
                            snap.toYaw(), snap.toPitch(), snap.rotation()),
                    new RotationPredictionTargetModel.TargetBox(
                            hit.target().minX(), hit.target().minY(), hit.target().minZ(),
                            hit.target().maxX(), hit.target().maxY(), hit.target().maxZ(),
                            hit.target().eyeX(), hit.target().eyeY(), hit.target().eyeZ()),
                    minTargetDistance, maxTargetDistance,
                    minPreviousError, maxLandingError,
                    minGeodesicRotation, maxGeodesicRotation,
                    minConvergenceRatio, maxCorrectionResidual);
            if (geometry == null) continue;
            if (!geometry.rayIntersectsBox() && hit.angle() > maxHitError) continue;

            iterator.remove();
            return new MatchedSnap(snap, geometry.currentError());
        }
        return null;
    }

    private void processValidations(RotationPredictionModel.Observation observation,
                                    long nowNanos) {
        if (!observation.usable()) return;

        Iterator<CandidateValidation> iterator = validations.iterator();
        while (iterator.hasNext()) {
            CandidateValidation validation = iterator.next();
            if (validation.calibration.epoch() != lattice.epoch()) {
                iterator.remove();
                continue;
            }

            validation.samples++;
            for (int divisor = validation.calibration.minDivisor();
                 divisor <= validation.calibration.maxDivisor(); divisor++) {
                if (validation.calibration.admits(observation, divisor)) {
                    validation.alignedByDivisor[
                            divisor - validation.calibration.minDivisor()]++;
                }
            }
            if (validation.samples < validationSamples) continue;

            iterator.remove();
            boolean consistent = false;
            for (byte aligned : validation.alignedByDivisor) {
                if ((aligned & 0xFF) == validationSamples) {
                    consistent = true;
                    break;
                }
            }
            if (consistent) {
                confirmedCandidates.addLast(new ConfirmedCandidate(
                        validation.target, validation.hitSequence, validation.createdNanos,
                        validation.rotation, validation.landingError, "SNAP_LATTICE"));
            }
        }

        pruneConfirmed(nowNanos);
    }

    private void maybeAlert(Player player, long nowNanos) {
        pruneConfirmed(nowNanos);
        String alertMode = null;
        int alertCount = 0;
        for (ConfirmedCandidate candidate : confirmedCandidates) {
            int modeCount = 0;
            for (ConfirmedCandidate other : confirmedCandidates) {
                if (candidate.mode().equals(other.mode())) modeCount++;
            }
            if (modeCount > alertCount) {
                alertCount = modeCount;
                alertMode = candidate.mode();
            }
        }
        int requiredForMode = "RAPID_HIT_ROTATION".equals(alertMode)
                ? rapidHitRequired : requiredConfirmedCandidates;
        if (alertCount < requiredForMode || alertMode == null) return;
        if (lastAlertNanos > 0L
                && nowNanos - lastAlertNanos < millisToNanos(alertCooldownMs)) return;

        double maxRotation = 0.0;
        double maxLanding = 0.0;
        double quantum = 0.0;
        TargetSnapshot latestTarget = null;
        boolean debugEnabled = plugin.isDebugEnabled();
        StringBuilder modes = debugEnabled ? new StringBuilder() : null;
        for (ConfirmedCandidate candidate : confirmedCandidates) {
            if (!alertMode.equals(candidate.mode())) continue;
            maxRotation = Math.max(maxRotation, candidate.rotation());
            maxLanding = Math.max(maxLanding, candidate.landingError());
            latestTarget = candidate.target();
            if (debugEnabled && modes.indexOf(candidate.mode()) < 0) {
                if (!modes.isEmpty()) modes.append('+');
                modes.append(candidate.mode());
            }
        }
        if (debugEnabled) {
            RotationPredictionModel.Calibration current = lattice.calibration();
            if (current != null) quantum = current.center();
        }

        String targetName = latestTarget != null ? latestTarget.displayName() : "N/A";
        if (debugEnabled) {
            plugin.debug("[RotationPrediction] " + player.getName()
                    + " target=" + targetName
                    + " mode=" + modes
                    + " confirmed=" + alertCount
                    + " rotation=" + String.format("%.2f", maxRotation)
                    + " landing=" + String.format("%.4f", maxLanding)
                    + " quantum=" + String.format("%.6f", quantum));
        }
        flagAndAlertNamedTarget(1.0, player, targetName, maxRotation);
        lastAlertNanos = nowNanos;
        confirmedCandidates.clear();
        orbitEvidenceTarget = null;
        lastOrbitEvidenceFrameNanos = 0L;
    }

    private void pruneConfirmed(long nowNanos) {
        long maxAge = millisToNanos(evidenceWindowMs);
        Iterator<ConfirmedCandidate> iterator = confirmedCandidates.iterator();
        while (iterator.hasNext()) {
            ConfirmedCandidate candidate = iterator.next();
            boolean expiredByTime = nowNanos - candidate.createdNanos() > maxAge;
            boolean expiredByHits = "SNAP_LATTICE".equals(candidate.mode())
                    && hitSequence - candidate.hitSequence() >= evidenceHitWindow;
            if (expiredByTime || expiredByHits) iterator.remove();
        }
    }

    private boolean isSuppressed(long nowNanos) {
        if (nowNanos < suppressUntilNanos) return true;
        return aetPlayer.mxCinematic || aetPlayer.isPostToggleOff() || aetPlayer.isPostToggleOn();
    }

    private PlayerPacketData packetData(Player player) {
        if (plugin.getNettyManager() == null) return null;
        return plugin.getNettyManager().getData(player.getUniqueId());
    }

    private void reseed(UUID world, long consumedSequence) {
        trackedWorld = world;
        lastPacketSequence = consumedSequence;
        lastPacketNanos = 0L;
        rawSnaps.clear();
        validations.clear();
        confirmedCandidates.removeIf(candidate -> "SNAP_LATTICE".equals(candidate.mode()));

lattice.reset();
    }

    private void hardResetOperational(Player player) {
        trackedWorld = null;
        lastPacketNanos = 0L;
        hitSequence = 0L;
        hitQueue.clear();
        rawSnaps.clear();
        rapidRotations.clear();
        motionFrames.clear();
        targetPoses.clear();
        targetPoseId = null;
        validations.clear();
        confirmedCandidates.clear();
        orbitEvidenceTarget = null;
        lastOrbitEvidenceFrameNanos = 0L;
        lastTargetHitNanos = 0L;
        trackedTargetEntity = new WeakReference<>(null);
        lattice.reset();
        if (player != null) {
            PlayerPacketData data = packetData(player);
            lastPacketSequence = data != null ? data.latestRotationSequence() : 0L;
        } else {
            lastPacketSequence = 0L;
        }
    }

    private void drainExpired(long nowNanos) {
        long hitMaxAge = millisToNanos(maxCandidateAgeMs + 250L);
        while (true) {
            HitSample sample = hitQueue.peek();
            if (sample == null || nowNanos - sample.createdNanos() <= hitMaxAge) break;
            hitQueue.poll();
        }
        long snapMaxAge = millisToNanos(maxCandidateAgeMs);
        while (!rawSnaps.isEmpty()) {
            RawSnap snap = rawSnaps.peekFirst();
            if (snap == null || nowNanos - snap.createdNanos() <= snapMaxAge) break;
            rawSnaps.removeFirst();
        }
        long rapidMaxAge = millisToNanos(rapidHitWindowMs);
        while (!rapidRotations.isEmpty()) {
            RapidRotation rotation = rapidRotations.peekFirst();
            if (rotation == null
                    || nowNanos - rotation.createdNanos() <= rapidMaxAge) break;
            rapidRotations.removeFirst();
        }

long motionMaxAge = 4_000_000_000L;
        while (!motionFrames.isEmpty()) {
            RotationPredictionOrbitModel.Frame frame = motionFrames.peekFirst();
            if (frame == null || nowNanos - frame.createdNanos() <= motionMaxAge) break;
            motionFrames.removeFirst();
        }
    }

    private static long millisToNanos(long milliseconds) {
        if (milliseconds <= 0L) return 0L;
        if (milliseconds >= Long.MAX_VALUE / 1_000_000L) return Long.MAX_VALUE;
        return milliseconds * 1_000_000L;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static long clampLong(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int clampInt(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private record TargetSnapshot(UUID id, UUID world,
                                  String name, String type,
                                  double centerX, double centerY, double centerZ,
                                  double eyeX, double eyeY, double eyeZ,
                                  double minX, double minY, double minZ,
                                  double maxX, double maxY, double maxZ,
                                  double velocityX, double velocityY, double velocityZ) {
        boolean finite() {
            return id != null && world != null
                    && Double.isFinite(centerX) && Double.isFinite(centerY) && Double.isFinite(centerZ)
                    && Double.isFinite(eyeX) && Double.isFinite(eyeY) && Double.isFinite(eyeZ)
                    && Double.isFinite(minX) && Double.isFinite(minY) && Double.isFinite(minZ)
                    && Double.isFinite(maxX) && Double.isFinite(maxY) && Double.isFinite(maxZ)
                    && Double.isFinite(velocityX) && Double.isFinite(velocityY)
                    && Double.isFinite(velocityZ)
                    && minX <= maxX && minY <= maxY && minZ <= maxZ;
        }

        String displayName() {
            if (name != null && !name.isBlank()) return name;
            return type != null && !type.isBlank() ? type : "LivingEntity";
        }
    }

    private record HitSample(TargetSnapshot target, double angle, long createdNanos) {
    }

    private record RawSnap(UUID world, long createdNanos,
                           double eyeX, double eyeY, double eyeZ,
                           float fromYaw, float fromPitch,
                           float toYaw, float toPitch,
                           double rotation,
                           RotationPredictionModel.SnapEvidence evidence) {
    }

    private record RapidRotation(UUID world, long createdNanos, double yawTurn) {
    }

    private record MatchedSnap(RawSnap snap, double landingError) {
    }

    private static final class CandidateValidation {
        private final TargetSnapshot target;
        private final long hitSequence;
        private final long createdNanos;
        private final double rotation;
        private final double landingError;
        private final RotationPredictionModel.Calibration calibration;
        private final byte[] alignedByDivisor;
        private int samples;

        private CandidateValidation(TargetSnapshot target,
                                    long hitSequence,
                                    long createdNanos,
                                    double rotation,
                                    double landingError,
                                    RotationPredictionModel.Calibration calibration) {
            this.target = target;
            this.hitSequence = hitSequence;
            this.createdNanos = createdNanos;
            this.rotation = rotation;
            this.landingError = landingError;
            this.calibration = calibration;
            this.alignedByDivisor = new byte[
                    calibration.maxDivisor() - calibration.minDivisor() + 1];
        }
    }

    private record ConfirmedCandidate(TargetSnapshot target,
                                      long hitSequence,
                                      long createdNanos,
                                      double rotation,
                                      double landingError,
                                      String mode) {
    }
}
