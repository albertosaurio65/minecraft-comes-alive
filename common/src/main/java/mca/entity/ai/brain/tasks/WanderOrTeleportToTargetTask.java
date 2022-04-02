package mca.entity.ai.brain.tasks;

import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonSyntaxException;
import mca.Config;
import mca.entity.ai.MemoryModuleTypeMCA;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.ai.NoPenaltyTargeting;
import net.minecraft.entity.ai.brain.Brain;
import net.minecraft.entity.ai.brain.MemoryModuleState;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.ai.brain.task.Task;
import net.minecraft.entity.ai.brain.task.WanderAroundTask;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.ai.pathing.LandPathNodeMaker;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.ai.pathing.PathNodeType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.tag.ServerTagManagerHolder;
import net.minecraft.tag.Tag;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.registry.Registry;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

public class WanderOrTeleportToTargetTask extends Task<MobEntity> {

    private static final int MAX_UPDATE_COUNTDOWN = 40;
    private int pathUpdateCountdownTicks;
    @Nullable
    private Path path;
    @Nullable
    private BlockPos lookTargetPos;
    private float speed;

    public WanderOrTeleportToTargetTask() {
        this(150, 250);
    }

    public WanderOrTeleportToTargetTask(int minRunTime, int maxRunTime) {
        super(ImmutableMap.of(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE, MemoryModuleState.REGISTERED, MemoryModuleType.PATH, MemoryModuleState.VALUE_ABSENT, MemoryModuleType.WALK_TARGET, MemoryModuleState.VALUE_PRESENT), minRunTime, maxRunTime);
    }

    @Override
    protected boolean shouldRun(ServerWorld serverWorld, MobEntity mobEntity) {
        if (this.pathUpdateCountdownTicks > 0) {
            --this.pathUpdateCountdownTicks;
            return false;
        } else {
            Brain<?> brain = mobEntity.getBrain();
            WalkTarget walkTarget = brain.getOptionalMemory(MemoryModuleType.WALK_TARGET).get();
            boolean bl = this.hasReached(mobEntity, walkTarget);
            boolean bl2 = brain.isMemoryInState(MemoryModuleTypeMCA.STAYING, MemoryModuleState.VALUE_ABSENT);
            if (!bl && bl2 && this.hasFinishedPath(mobEntity, walkTarget, serverWorld.getTime())) {
                this.lookTargetPos = walkTarget.getLookTarget().getBlockPos();
                return true;
            } else {
                brain.forget(MemoryModuleType.WALK_TARGET);
                if (bl) {
                    brain.forget(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
                }

                return false;
            }
        }
    }

    @Override
    protected boolean shouldKeepRunning(ServerWorld serverWorld, MobEntity mobEntity, long l) {
        if (this.path != null && this.lookTargetPos != null) {
            Optional<WalkTarget> optional = mobEntity.getBrain().getOptionalMemory(MemoryModuleType.WALK_TARGET);
            EntityNavigation entityNavigation = mobEntity.getNavigation();
            return !entityNavigation.isIdle() && optional.isPresent() && !this.hasReached(mobEntity, optional.get());
        } else {
            return false;
        }
    }

    @Override
    protected void finishRunning(ServerWorld serverWorld, MobEntity mobEntity, long l) {
        if (mobEntity.getBrain().hasMemoryModule(MemoryModuleType.WALK_TARGET) && !this.hasReached(mobEntity, (WalkTarget)mobEntity.getBrain().getOptionalMemory(MemoryModuleType.WALK_TARGET).get()) && mobEntity.getNavigation().isNearPathStartPos()) {
            this.pathUpdateCountdownTicks = serverWorld.getRandom().nextInt(MAX_UPDATE_COUNTDOWN);
        }

        mobEntity.getNavigation().stop();
        mobEntity.getBrain().forget(MemoryModuleType.WALK_TARGET);
        mobEntity.getBrain().forget(MemoryModuleType.PATH);
        this.path = null;
    }

    @Override
    protected void run(ServerWorld serverWorld, MobEntity mobEntity, long l) {
        mobEntity.getBrain().remember(MemoryModuleType.PATH, this.path);
        mobEntity.getNavigation().startMovingAlong(this.path, this.speed);
    }

    @Override
    protected void keepRunning(ServerWorld serverWorld, MobEntity mobEntity, long l) {
        Path path = mobEntity.getNavigation().getCurrentPath();
        Brain<?> brain = mobEntity.getBrain();
        if (this.path != path) {
            this.path = path;
            brain.remember(MemoryModuleType.PATH, path);
        }

        if (path != null && this.lookTargetPos != null) {
            WalkTarget walkTarget = brain.getOptionalMemory(MemoryModuleType.WALK_TARGET).get();
            if (walkTarget.getLookTarget().getBlockPos().getSquaredDistance(this.lookTargetPos) > 4.0D && this.hasFinishedPath(mobEntity, walkTarget, serverWorld.getTime())) {
                this.lookTargetPos = walkTarget.getLookTarget().getBlockPos();
                if (Config.getInstance().allowVillagerTeleporting && !lookTargetPos.isWithinDistance(mobEntity.getPos(), Config.getInstance().villagerTeleportLimit)) {
                    tryTeleport(serverWorld, mobEntity, lookTargetPos);
                } else {
                    this.run(serverWorld, mobEntity, l);
                }
            }
        }
    }

    private boolean hasFinishedPath(MobEntity entity, WalkTarget walkTarget, long time) {
        BlockPos blockPos = walkTarget.getLookTarget().getBlockPos();
        this.path = entity.getNavigation().findPathTo(blockPos, 0);
        this.speed = walkTarget.getSpeed();
        Brain<?> brain = entity.getBrain();
        if (this.hasReached(entity, walkTarget)) {
            brain.forget(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
        } else {
            boolean bl = this.path != null && this.path.reachesTarget();
            if (bl) {
                brain.forget(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
            } else if (!brain.hasMemoryModule(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE)) {
                brain.remember(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE, time);
            }

            if (this.path != null) {
                return true;
            }

            Vec3d vec3d = NoPenaltyTargeting.findTo((PathAwareEntity)entity, 10, 7, Vec3d.ofBottomCenter(blockPos), Math.PI * 0.5);
            if (vec3d != null) {
                this.path = entity.getNavigation().findPathTo(vec3d.x, vec3d.y, vec3d.z, 0);
                return this.path != null;
            }
        }

        return false;
    }

    private boolean hasReached(MobEntity entity, WalkTarget walkTarget) {
        return walkTarget.getLookTarget().getBlockPos().getManhattanDistance(entity.getBlockPos()) <= walkTarget.getCompletionRange();
    }

    private void tryTeleport(ServerWorld world, MobEntity entity, BlockPos targetPos) {
        for(int i = 0; i < 10; ++i) {
            int j = this.getRandomInt(entity, -3, 3);
            int k = this.getRandomInt(entity, -1, 1);
            int l = this.getRandomInt(entity, -3, 3);
            boolean bl = this.tryTeleportTo(world, entity, targetPos, targetPos.getX() + j, targetPos.getY() + k, targetPos.getZ() + l);
            if (bl) {
                return;
            }
        }
    }

    private boolean tryTeleportTo(ServerWorld world, MobEntity entity, BlockPos targetPos, int x, int y, int z) {
        if (Math.abs((double)x - targetPos.getX()) < 2.0D && Math.abs((double)z - targetPos.getZ()) < 2.0D) {
            return false;
        } else if (!this.canTeleportTo(world, entity, new BlockPos(x, y, z))) {
            return false;
        } else {
            entity.requestTeleport((double)x + 0.5D, (double)y, (double)z + 0.5D);
            return true;
        }
    }

    private boolean canTeleportTo(ServerWorld world, MobEntity entity, BlockPos pos) {
        PathNodeType pathNodeType = LandPathNodeMaker.getLandNodeType(world, pos.mutableCopy());
        if (pathNodeType != PathNodeType.WALKABLE) {
            return false;
        } else {
            if (!isAreaSafe(world, pos.down())) {
                return false;
            } else {
                BlockPos blockPos = pos.subtract(entity.getBlockPos());
                return world.isSpaceEmpty(entity, entity.getBoundingBox().offset(blockPos));
            }
        }
    }

    private int getRandomInt(MobEntity entity, int min, int max) {
        return entity.getRandom().nextInt(max - min + 1) + min;
    }

    private boolean isAreaSafe(ServerWorld world, Vec3d pos) {
        return isAreaSafe(world, new BlockPos(pos));
    }

    private boolean isAreaSafe(ServerWorld world, BlockPos pos) {
        // The following conditions define whether it is logically
        // safe for the entity to teleport to the specified pos within world
        final BlockState aboveState = world.getBlockState(pos);
        final Identifier aboveId = Registry.BLOCK.getId(aboveState.getBlock());
        for (String blockId : Config.getInstance().villagerPathfindingBlacklist) {
            if (blockId.equals(aboveId.toString())) {
                return false;
            } else if (blockId.charAt(0) == '#') {
                Identifier identifier = new Identifier(blockId.substring(1));
                Tag<Block> tag = ServerTagManagerHolder.getTagManager().getOrCreateTagGroup(Registry.BLOCK_KEY).getTag(identifier);
                if (tag != null) {
                    if (aboveState.isIn(tag)) {
                        return false;
                    }
                } else {
                    throw new JsonSyntaxException("Unknown block tag in villagerPathfindingBlacklist '" + identifier + "'");
                }
            }
        }
        return true;
    }
}
