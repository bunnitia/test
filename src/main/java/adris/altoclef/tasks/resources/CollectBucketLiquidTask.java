package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.DefaultGoToDimensionTask;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.misc.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.WorldUtil;
import adris.altoclef.util.csharpisbetter.ActionListener;
import adris.altoclef.util.csharpisbetter.Timer;
import adris.altoclef.util.csharpisbetter.Util;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import baritone.api.utils.input.Input;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.RaycastContext;

import java.util.HashSet;
import java.util.function.Function;

public class CollectBucketLiquidTask extends ResourceTask {

    private int _count;

    private Item _target;
    private Block _toCollect;

    private String _liquidName;

    //private IProgressChecker<Double> _checker = new LinearProgressChecker(5, 0.1);

    private TimeoutWanderTask _wanderTask = new TimeoutWanderTask(15f);

    private final HashSet<BlockPos> _blacklist = new HashSet<>();

    private BlockPos _targetLiquid;

    private final Timer _tryImmediatePickupTimer = new Timer(3);
    private final Timer _pickedUpTimer = new Timer(0.5);

    private MovementProgressChecker _progressChecker = new MovementProgressChecker();

    public CollectBucketLiquidTask(String liquidName, Item filledBucket, int targetCount, Block toCollect) {
        super(filledBucket, targetCount);
        _liquidName = liquidName;
        _target = filledBucket;
        _count = targetCount;
        _toCollect = toCollect;
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @SuppressWarnings("ConstantConditions")
    @Override
    protected void onResourceStart(AltoClef mod) {
        // Track fluids
        mod.getBehaviour().push();
        mod.getBehaviour().setRayTracingFluidHandling(RaycastContext.FluidHandling.SOURCE_ONLY);
        mod.getBehaviour().setSearchAnywhereFlag(true); // If we don't set this, lava will never be found.
        mod.getBlockTracker().trackBlock(_toCollect);

        // Avoid breaking / placing blocks at our liquid
        mod.getBehaviour().avoidBlockBreaking((pos) -> MinecraftClient.getInstance().world.getBlockState(pos).getBlock() == _toCollect);
        mod.getBehaviour().avoidBlockPlacing((pos) -> MinecraftClient.getInstance().world.getBlockState(pos).getBlock() == _toCollect);

        //_blacklist.clear();

        _wanderTask.resetWander();

        _progressChecker.reset();
    }



    @Override
    protected Task onResourceTick(AltoClef mod) {

        // Run one update to prevent the false fail bug?
        _progressChecker.check(mod);

        // If we're standing inside a liquid, go pick it up.
        if (_tryImmediatePickupTimer.elapsed()) {
            Block standingInside = mod.getWorld().getBlockState(mod.getPlayer().getBlockPos()).getBlock();
            if (standingInside == _toCollect) {
                setDebugState("Trying to collect (we are in it)");
                mod.getInputControls().forceLook(0, 90);
                //mod.getClientBaritone().getLookBehavior().updateTarget(new Rotation(0, 90), true);
                //Debug.logMessage("Looking at " + _toCollect + ", picking up right away.");
                _tryImmediatePickupTimer.reset();
                if (!mod.getInventoryTracker().equipItem(Items.BUCKET)) {
                    Debug.logWarning("Failed to equip bucket.");
                } else {
                    mod.getInputControls().tryPress(Input.CLICK_RIGHT);
                    mod.getExtraBaritoneSettings().setInteractionPaused(true);
                    _pickedUpTimer.reset();
                    _progressChecker.reset();
                    return null;
                }
            }
        }

        if (!_pickedUpTimer.elapsed()) {
            mod.getExtraBaritoneSettings().setInteractionPaused(false);
            _progressChecker.reset();
            // Wait for force pickup
            return null;
        }

        if (_wanderTask.isActive() && !_wanderTask.isFinished(mod)) {
            setDebugState("Failed to receive: Wandering.");
            _progressChecker.reset();
            return _wanderTask;
        }

        // Get buckets if we need em
        int bucketsNeeded = _count - mod.getInventoryTracker().getItemCount(Items.BUCKET) - mod.getInventoryTracker().getItemCount(_target);
        if (bucketsNeeded > 0) {
            setDebugState("Getting bucket...");
            return TaskCatalogue.getItemTask("bucket", bucketsNeeded);
        }

        Function<Vec3d, BlockPos> getNearestLiquid = ppos -> mod.getBlockTracker().getNearestTracking(mod.getPlayer().getPos(), (blockPos -> {
            if (_blacklist.contains(blockPos)) return true;
            if (mod.getBlockTracker().unreachable(blockPos)) return true; // I think there was a bug here? Doesn't hurt to include though.
            assert MinecraftClient.getInstance().world != null;

            // Lava, we break the block above. If it's bedrock, ignore.
            if (_toCollect == Blocks.LAVA && mod.getWorld().getBlockState(blockPos.up()).getBlock() == Blocks.BEDROCK) {
                return true;
            }

            return !WorldUtil.isSourceBlock(mod, blockPos, false);
        }), _toCollect);

        // Find nearest water and right click it
        BlockPos nearestLiquid = getNearestLiquid.apply(mod.getPlayer().getPos());
        _targetLiquid = nearestLiquid;
        if (nearestLiquid != null) {
            // We want to MINIMIZE this distance to liquid.
            setDebugState("Trying to collect...");
            //Debug.logMessage("TEST: " + RayTraceUtils.fluidHandling);

            return new DoToClosestBlockTask(() -> mod.getPlayer().getPos(), (BlockPos blockpos) -> {

                // Clear above if lava because we can't enter.
                if (_toCollect == Blocks.LAVA) {
                    if (WorldUtil.isSolid(mod, blockpos.up())) {
                        if (!_progressChecker.check(mod)) {
                            Debug.logMessage("Failed to break, blacklisting & wandering");
                            mod.getBlockTracker().requestBlockUnreachable(blockpos);
                            _blacklist.add(blockpos);
                            return _wanderTask;
                        }
                        return new DestroyBlockTask(blockpos.up());
                    }
                }

                InteractWithBlockTask task = new InteractWithBlockTask(new ItemTarget(Items.BUCKET, 1), blockpos, _toCollect != Blocks.LAVA, new Vec3i(0, 1, 0));
                // noinspection rawtypes
                task.TimedOut.addListener(
                        new ActionListener() {
                            @Override
                            public void invoke(Object value) {
                                Debug.logInternal("CURRENT BLACKLIST: " + Util.arrayToString(_blacklist.toArray()));
                                Debug.logMessage("Blacklisted " + blockpos);
                                mod.getBlockTracker().requestBlockUnreachable(blockpos);
                                _blacklist.add(blockpos);

                            }
                        });
                return task;
            }, getNearestLiquid, _toCollect);
            //return task;
        }

        // Dimension
        if (_toCollect == Blocks.WATER && mod.getCurrentDimension() == Dimension.NETHER) {
            return new DefaultGoToDimensionTask(Dimension.OVERWORLD);
        }

        // Oof, no liquid found.
        setDebugState("Searching for liquid by wandering around aimlessly");

        return new TimeoutWanderTask(Float.POSITIVE_INFINITY);
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(_toCollect);
        mod.getBehaviour().pop();
        //mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
        mod.getExtraBaritoneSettings().setInteractionPaused(false);
    }

    @Override
    protected boolean isEqualResource(ResourceTask obj) {
        if (obj instanceof CollectBucketLiquidTask) {
            CollectBucketLiquidTask task = (CollectBucketLiquidTask) obj;
            if (task._count != _count) return false;
            return task._toCollect == _toCollect;
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return "Collect " + _count + " " + _liquidName + " buckets";
    }

    public static class CollectWaterBucketTask extends CollectBucketLiquidTask {
        public CollectWaterBucketTask(int targetCount) {
            super("water", Items.WATER_BUCKET, targetCount, Blocks.WATER);
        }
    }
    public static class CollectLavaBucketTask extends CollectBucketLiquidTask {
        public CollectLavaBucketTask(int targetCount) {
            super("lava", Items.LAVA_BUCKET, targetCount, Blocks.LAVA);
        }
    }

}
