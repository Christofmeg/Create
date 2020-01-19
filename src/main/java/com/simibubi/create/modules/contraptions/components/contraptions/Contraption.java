package com.simibubi.create.modules.contraptions.components.contraptions;

import static com.simibubi.create.CreateConfig.parameters;
import static net.minecraft.state.properties.BlockStateProperties.AXIS;
import static net.minecraft.state.properties.BlockStateProperties.FACING;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;

import org.apache.commons.lang3.tuple.MutablePair;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.CreateConfig;
import com.simibubi.create.modules.contraptions.components.contraptions.IHaveMovementBehavior.MovementContext;
import com.simibubi.create.modules.contraptions.components.contraptions.bearing.BearingContraption;
import com.simibubi.create.modules.contraptions.components.contraptions.chassis.AbstractChassisBlock;
import com.simibubi.create.modules.contraptions.components.contraptions.chassis.ChassisTileEntity;
import com.simibubi.create.modules.contraptions.components.contraptions.chassis.LinearChassisBlock;
import com.simibubi.create.modules.contraptions.components.contraptions.chassis.RadialChassisBlock;
import com.simibubi.create.modules.contraptions.components.contraptions.mounted.MountedContraption;
import com.simibubi.create.modules.contraptions.components.contraptions.piston.PistonContraption;
import com.simibubi.create.modules.contraptions.components.saw.SawBlock;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.PistonBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.SlimeBlock;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.FloatNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.nbt.NBTUtil;
import net.minecraft.state.properties.BlockStateProperties;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Direction;
import net.minecraft.util.Direction.Axis;
import net.minecraft.util.Direction.AxisDirection;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IWorld;
import net.minecraft.world.World;
import net.minecraft.world.gen.feature.template.Template.BlockInfo;

public class Contraption {

	public Map<BlockPos, BlockInfo> blocks;
	public List<MutablePair<BlockInfo, MovementContext>> actors;
	public AxisAlignedBB constructCollisionBox;
	protected Set<BlockPos> cachedColliders;
	protected Direction cachedColliderDirection;
	protected BlockPos anchor;

	public Contraption() {
		blocks = new HashMap<>();
		actors = new ArrayList<>();
	}

	private static List<BlockInfo> getChassisClusterAt(World world, BlockPos pos) {
		List<BlockPos> search = new LinkedList<>();
		Set<BlockPos> visited = new HashSet<>();
		List<BlockInfo> chassis = new LinkedList<>();
		BlockState anchorChassis = world.getBlockState(pos);
		Axis axis = anchorChassis.get(AXIS);
		search.add(pos);

		while (!search.isEmpty()) {
			if (chassis.size() > parameters.maxChassisForTranslation.get())
				return null;

			BlockPos current = search.remove(0);
			if (visited.contains(current))
				continue;
			if (!world.isAreaLoaded(current, 1))
				return null;

			BlockState state = world.getBlockState(current);
			if (!isLinearChassis(state))
				continue;
			if (!LinearChassisBlock.sameKind(anchorChassis, state))
				continue;
			if (state.get(AXIS) != axis)
				continue;

			visited.add(current);
			chassis.add(new BlockInfo(current, world.getBlockState(current), getTileEntityNBT(world, current)));

			for (Direction offset : Direction.values()) {
				if (offset.getAxis() == axis)
					continue;
				search.add(current.offset(offset));
			}
		}
		return chassis;
	}

	public Set<BlockPos> getColliders(World world, Direction movementDirection) {
		if (blocks == null)
			return null;
		if (cachedColliders == null || cachedColliderDirection != movementDirection) {
			cachedColliders = new HashSet<>();
			cachedColliderDirection = movementDirection;

			for (BlockInfo info : blocks.values()) {
				BlockPos offsetPos = info.pos.offset(movementDirection);
				boolean hasNext = false;
				for (BlockInfo otherInfo : blocks.values()) {
					if (!otherInfo.pos.equals(offsetPos))
						continue;
					hasNext = true;
					break;
				}
				if (!hasNext)
					cachedColliders.add(info.pos);
			}

		}
		return cachedColliders;
	}

	public boolean searchMovedStructure(World world, BlockPos pos, Direction direction) {
		List<BlockPos> frontier = new ArrayList<>();
		Set<BlockPos> visited = new HashSet<>();
		anchor = pos;

		if (constructCollisionBox == null)
			constructCollisionBox = new AxisAlignedBB(BlockPos.ZERO);

		frontier.add(pos);
		if (!addToInitialFrontier(world, pos, direction, frontier))
			return false;

		for (int limit = 1000; limit > 0; limit--) {
			if (frontier.isEmpty())
				return true;
			if (!moveBlock(world, frontier.remove(0), direction, frontier, visited))
				return false;

		}
		return false;
	}

	protected boolean addToInitialFrontier(World world, BlockPos pos, Direction direction, List<BlockPos> frontier) {
		return true;
	}

	private boolean moveBlock(World world, BlockPos pos, Direction direction, List<BlockPos> frontier,
			Set<BlockPos> visited) {
		visited.add(pos);
		frontier.remove(pos);

		if (!world.isBlockPresent(pos))
			return false;
		BlockState state = world.getBlockState(pos);
		if (state.getMaterial().isReplaceable())
			return true;
		if (state.getCollisionShape(world, pos).isEmpty())
			return true;
		if (!canPush(world, pos, direction))
			return false;
		if (isLinearChassis(state) && !moveLinearChassis(world, pos, direction, frontier, visited))
			return false;
		if (isRadialChassis(state) && !moveRadialChassis(world, pos, direction, frontier, visited))
			return false;

		if (state.getBlock() instanceof SlimeBlock)
			for (Direction offset : Direction.values()) {
				BlockPos offsetPos = pos.offset(offset);
				if (offset.getAxis() == direction.getAxis()) {
					BlockState blockState = world.getBlockState(offsetPos);
					if (AllBlocks.MECHANICAL_PISTON.typeOf(blockState)
							|| AllBlocks.STICKY_MECHANICAL_PISTON.typeOf(blockState)
							|| AllBlocks.MECHANICAL_PISTON_HEAD.typeOf(blockState))
						continue;
				}
				if (!visited.contains(offsetPos))
					frontier.add(offsetPos);
			}

		add(pos, capture(world, pos));
		return true;
	}

	private boolean moveLinearChassis(World world, BlockPos pos, Direction movementDirection, List<BlockPos> frontier,
			Set<BlockPos> visited) {
		List<BlockInfo> cluster = getChassisClusterAt(world, pos);

		if (cluster == null)
			return false;
		if (cluster.isEmpty())
			return true;

		Set<BlockPos> validChassis = new HashSet<>(cluster.size());
		cluster.forEach(info -> validChassis.add(info.pos));

		BlockInfo anchorChassis = cluster.get(0);
		Axis chassisAxis = anchorChassis.state.get(AXIS);
		int chassisCoord = chassisAxis.getCoordinate(anchorChassis.pos.getX(), anchorChassis.pos.getY(),
				anchorChassis.pos.getZ());

		Function<BlockPos, BlockPos> getChassisPos = position -> new BlockPos(
				chassisAxis == Axis.X ? chassisCoord : position.getX(),
				chassisAxis == Axis.Y ? chassisCoord : position.getY(),
				chassisAxis == Axis.Z ? chassisCoord : position.getZ());

		// Collect blocks on both sides
		for (AxisDirection axisDirection : AxisDirection.values()) {

			Direction chassisDirection = Direction.getFacingFromAxis(axisDirection, chassisAxis);
			List<BlockPos> chassisFrontier = new LinkedList<>();
			Set<BlockPos> chassisVisited = new HashSet<>();
			cluster.forEach(c -> chassisFrontier.add(c.pos));
			boolean pushing = chassisDirection == movementDirection;

			Search: while (!chassisFrontier.isEmpty()) {
				BlockPos currentPos = chassisFrontier.remove(0);
				if (!world.isAreaLoaded(currentPos, 1))
					return false;
				if (!world.isBlockPresent(currentPos))
					continue;
				if (chassisVisited.contains(currentPos))
					continue;
				chassisVisited.add(currentPos);

				BlockState state = world.getBlockState(currentPos);
				BlockPos currentChassisPos = getChassisPos.apply(currentPos);
				BlockState chassisState = world.getBlockState(currentChassisPos);

				// Not attached to a chassis
				if (!isLinearChassis(chassisState) || chassisState.get(AXIS) != chassisAxis)
					continue;
				if (AllBlocks.MECHANICAL_PISTON_HEAD.typeOf(state)
						&& state.get(FACING) == chassisDirection.getOpposite())
					continue;

				int chassisRange = ((ChassisTileEntity) world.getTileEntity(currentChassisPos)).getRange();
				boolean chassisSticky = chassisState.get(((AbstractChassisBlock) chassisState.getBlock())
						.getGlueableSide(chassisState, chassisDirection));

				// Ignore replaceable Blocks and Air-like
				if (state.getMaterial().isReplaceable() || state.isAir(world, currentPos))
					continue;
				if (state.getCollisionShape(world, currentPos).isEmpty())
					continue;

				// Too many Blocks
				boolean notInRange = !currentChassisPos.withinDistance(currentPos, chassisRange + 1);
				if (pushing && notInRange)
					return false;
				if (!pushing && notInRange)
					continue;

				// Chassis not part of cluster
				if (!validChassis.contains(currentChassisPos))
					continue;

				boolean isBaseChassis = currentPos.equals(currentChassisPos);
				if (!isBaseChassis) {
					// Don't pull if chassis not sticky
					if (!chassisSticky && !pushing)
						continue;

					// Skip if pushed column ended already
					for (BlockPos posInbetween = currentPos; !posInbetween.equals(
							currentChassisPos); posInbetween = posInbetween.offset(chassisDirection.getOpposite())) {
						BlockState blockState = world.getBlockState(posInbetween);

						if (!chassisSticky && (blockState.getMaterial().isReplaceable()))
							continue Search;
						if (!pushing && chassisSticky && !canPush(world, posInbetween, movementDirection))
							continue Search;
					}
				}

				// Ignore sand and co.
				if (chassisSticky && !pushing && state.getBlock() instanceof FallingBlock)
					continue;

				// Structure is immobile
				boolean cannotPush = !canPush(world, currentPos, movementDirection);
				if (pushing && cannotPush)
					return false;
				if (!pushing && cannotPush)
					continue;

				if (isBaseChassis) {
					add(currentPos, capture(world, currentPos));
					visited.add(currentPos);
				} else {
					frontier.add(currentPos);
				}

				// Expand search
				for (Direction facing : Direction.values()) {
					if (isBaseChassis && facing == chassisDirection.getOpposite())
						continue;
					if (notSupportive(world, pos, facing))
						continue;
					chassisFrontier.add(currentPos.offset(facing));
				}
			}
		}

		return true;
	}

	private boolean moveRadialChassis(World world, BlockPos pos, Direction movementDirection, List<BlockPos> frontier,
			Set<BlockPos> visited) {
		RadialChassisBlock def = (RadialChassisBlock) AllBlocks.ROTATION_CHASSIS.block;

		List<BlockPos> chassisPositions = new ArrayList<>();
		BlockState chassisState = world.getBlockState(pos);
		Axis axis = chassisState.get(RadialChassisBlock.AXIS);
		chassisPositions.add(pos);

		// Collect chain of chassis
		for (int offset : new int[] { -1, 1 }) {
			for (int distance = 1; distance <= parameters.maxChassisForTranslation.get(); distance++) {
				Direction direction = Direction.getFacingFromAxis(AxisDirection.POSITIVE, axis);
				BlockPos currentPos = pos.offset(direction, distance * offset);
				if (!world.isBlockPresent(currentPos))
					return false;

				BlockState state = world.getBlockState(currentPos);
				if (!AllBlocks.ROTATION_CHASSIS.typeOf(state))
					break;
				if (direction.getAxis() != state.get(BlockStateProperties.AXIS))
					break;

				chassisPositions.add(currentPos);
			}
		}

		// Add attached blocks to frontier
		for (BlockPos chassisPos : chassisPositions) {
			add(chassisPos, capture(world, chassisPos));
			visited.add(chassisPos);

			BlockPos currentPos = chassisPos;
			BlockState state = world.getBlockState(currentPos);
			TileEntity tileEntity = world.getTileEntity(currentPos);

			if (!(tileEntity instanceof ChassisTileEntity))
				return false;

			int chassisRange = ((ChassisTileEntity) tileEntity).getRange();

			for (Direction facing : Direction.values()) {
				if (facing.getAxis() == axis)
					continue;
				if (!state.get(def.getGlueableSide(state, facing)))
					continue;

				BlockPos startPos = currentPos.offset(facing);
				List<BlockPos> localFrontier = new LinkedList<>();
				Set<BlockPos> localVisited = new HashSet<>();
				localFrontier.add(startPos);

				while (!localFrontier.isEmpty()) {
					BlockPos searchPos = localFrontier.remove(0);
					BlockState searchedState = world.getBlockState(searchPos);

					if (localVisited.contains(searchPos))
						continue;
					if (!searchPos.withinDistance(currentPos, chassisRange + .5f))
						continue;
					if (searchedState.getMaterial().isReplaceable() || state.isAir(world, searchPos))
						continue;
					if (searchedState.getCollisionShape(world, searchPos).isEmpty())
						continue;

					localVisited.add(searchPos);
					if (!visited.contains(searchPos))
						frontier.add(searchPos);

					for (Direction offset : Direction.values()) {
						if (offset.getAxis() == axis)
							continue;
						if (searchPos.equals(currentPos) && offset != facing)
							continue;

						localFrontier.add(searchPos.offset(offset));
					}
				}
			}
		}

		return true;
	}

	private static boolean isLinearChassis(BlockState state) {
		return LinearChassisBlock.isChassis(state);
	}

	private static boolean isRadialChassis(BlockState state) {
		return AllBlocks.ROTATION_CHASSIS.typeOf(state);
	}

	private boolean notSupportive(World world, BlockPos pos, Direction facing) {
		BlockState state = world.getBlockState(pos);
		if (AllBlocks.DRILL.typeOf(state))
			return state.get(BlockStateProperties.FACING) == facing;
		if (AllBlocks.HARVESTER.typeOf(state))
			return state.get(BlockStateProperties.HORIZONTAL_FACING) == facing;
		return false;
	}

	protected static boolean canPush(World world, BlockPos pos, Direction direction) {
		BlockState blockState = world.getBlockState(pos);
		if (isLinearChassis(blockState) || isRadialChassis(blockState))
			return true;
		if (blockState.getBlock() instanceof ShulkerBoxBlock)
			return false;
		return PistonBlock.canPush(blockState, world, pos, direction, true, direction);
	}

	protected BlockInfo capture(World world, BlockPos pos) {
		BlockState blockstate = world.getBlockState(pos);
		if (AllBlocks.SAW.typeOf(blockstate))
			blockstate = blockstate.with(SawBlock.RUNNING, true);
		CompoundNBT compoundnbt = getTileEntityNBT(world, pos);
		return new BlockInfo(pos, blockstate, compoundnbt);
	}

	public static CompoundNBT getTileEntityNBT(World world, BlockPos pos) {
		TileEntity tileentity = world.getTileEntity(pos);
		CompoundNBT compoundnbt = null;
		if (tileentity != null) {
			compoundnbt = tileentity.write(new CompoundNBT());
			compoundnbt.remove("x");
			compoundnbt.remove("y");
			compoundnbt.remove("z");
		}
		return compoundnbt;
	}

	public void add(BlockPos pos, BlockInfo block) {
		BlockPos localPos = pos.subtract(anchor);
		BlockInfo blockInfo = new BlockInfo(localPos, block.state, block.nbt);
		blocks.put(localPos, blockInfo);
		if (block.state.getBlock() instanceof IHaveMovementBehavior)
			getActors().add(MutablePair.of(blockInfo, null));
		constructCollisionBox = constructCollisionBox.union(new AxisAlignedBB(localPos));
	}

	public static Contraption fromNBT(World world, CompoundNBT nbt) {
		String type = nbt.getString("Type");
		Contraption contraption = new Contraption();
		if (type.equals("Piston"))
			contraption = new PistonContraption();
		if (type.equals("Mounted"))
			contraption = new MountedContraption();
		if (type.equals("Bearing"))
			contraption = new BearingContraption();
		contraption.readNBT(world, nbt);
		return contraption;
	}

	public void readNBT(World world, CompoundNBT nbt) {
		nbt.getList("Blocks", 10).forEach(c -> {
			CompoundNBT comp = (CompoundNBT) c;
			BlockInfo info = new BlockInfo(NBTUtil.readBlockPos(comp.getCompound("Pos")),
					NBTUtil.readBlockState(comp.getCompound("Block")),
					comp.contains("Data") ? comp.getCompound("Data") : null);
			blocks.put(info.pos, info);
		});

		nbt.getList("Actors", 10).forEach(c -> {
			CompoundNBT comp = (CompoundNBT) c;
			BlockInfo info = blocks.get(NBTUtil.readBlockPos(comp.getCompound("Pos")));
			MovementContext context = MovementContext.readNBT(world, comp);
			getActors().add(MutablePair.of(info, context));
		});

		if (nbt.contains("BoundsFront"))
			constructCollisionBox = readAABB(nbt.getList("BoundsFront", 5));

		anchor = NBTUtil.readBlockPos(nbt.getCompound("Anchor"));
	}

	public AxisAlignedBB getCollisionBoxFront() {
		return constructCollisionBox;
	}

	public CompoundNBT writeNBT() {
		CompoundNBT nbt = new CompoundNBT();

		if (this instanceof PistonContraption)
			nbt.putString("Type", "Piston");
		if (this instanceof MountedContraption)
			nbt.putString("Type", "Mounted");
		if (this instanceof BearingContraption)
			nbt.putString("Type", "Bearing");

		ListNBT blocks = new ListNBT();
		for (BlockInfo block : this.blocks.values()) {
			CompoundNBT c = new CompoundNBT();
			c.put("Block", NBTUtil.writeBlockState(block.state));
			c.put("Pos", NBTUtil.writeBlockPos(block.pos));
			if (block.nbt != null)
				c.put("Data", block.nbt);
			blocks.add(c);
		}

		ListNBT actorsNBT = new ListNBT();
		for (MutablePair<BlockInfo, MovementContext> actor : getActors()) {
			CompoundNBT compound = new CompoundNBT();
			compound.put("Pos", NBTUtil.writeBlockPos(actor.left.pos));
			actor.right.writeToNBT(compound);
			actorsNBT.add(compound);
		}
		nbt.put("Actors", actorsNBT);

		if (constructCollisionBox != null) {
			ListNBT bb = writeAABB(constructCollisionBox);
			nbt.put("BoundsFront", bb);
		}
		nbt.put("Blocks", blocks);
		nbt.put("Anchor", NBTUtil.writeBlockPos(anchor));
		return nbt;
	}

	public ListNBT writeAABB(AxisAlignedBB bb) {
		ListNBT bbtag = new ListNBT();
		bbtag.add(new FloatNBT((float) bb.minX));
		bbtag.add(new FloatNBT((float) bb.minY));
		bbtag.add(new FloatNBT((float) bb.minZ));
		bbtag.add(new FloatNBT((float) bb.maxX));
		bbtag.add(new FloatNBT((float) bb.maxY));
		bbtag.add(new FloatNBT((float) bb.maxZ));
		return bbtag;
	}

	public AxisAlignedBB readAABB(ListNBT bbtag) {
		if (bbtag == null || bbtag.isEmpty())
			return null;
		return new AxisAlignedBB(bbtag.getFloat(0), bbtag.getFloat(1), bbtag.getFloat(2), bbtag.getFloat(3),
				bbtag.getFloat(4), bbtag.getFloat(5));

	}

	public static boolean isFrozen() {
		return CreateConfig.parameters.freezePistonConstructs.get();
	}

	public void disassemble(IWorld world, BlockPos offset, float yaw, float pitch) {
		disassemble(world, offset, yaw, pitch, (pos, state) -> false);
	}

	public void removeBlocksFromWorld(IWorld world, BlockPos offset) {
		removeBlocksFromWorld(world, offset, (pos, state) -> false);
	}

	public void removeBlocksFromWorld(IWorld world, BlockPos offset, BiPredicate<BlockPos, BlockState> customRemoval) {
		for (BlockInfo block : blocks.values()) {
			BlockPos add = block.pos.add(anchor).add(offset);
			if (customRemoval.test(add, block.state))
				continue;
			world.setBlockState(add, Blocks.AIR.getDefaultState(), 67);
		}
	}

	public void disassemble(IWorld world, BlockPos offset, float yaw, float pitch,
			BiPredicate<BlockPos, BlockState> customPlacement) {
		for (BlockInfo block : blocks.values()) {
			BlockPos targetPos = block.pos.add(offset);
			BlockState state = block.state;

			if (customPlacement.test(targetPos, state))
				continue;

			for (Direction face : Direction.values())
				state = state.updatePostPlacement(face, world.getBlockState(targetPos.offset(face)), world, targetPos,
						targetPos.offset(face));
			if (AllBlocks.SAW.typeOf(state))
				state = state.with(SawBlock.RUNNING, false);

			world.destroyBlock(targetPos, world.getBlockState(targetPos).getCollisionShape(world, targetPos).isEmpty());
			world.setBlockState(targetPos, state, 3);
			TileEntity tileEntity = world.getTileEntity(targetPos);
			if (tileEntity != null && block.nbt != null) {
				block.nbt.putInt("x", targetPos.getX());
				block.nbt.putInt("y", targetPos.getY());
				block.nbt.putInt("z", targetPos.getZ());
				tileEntity.read(block.nbt);
			}
		}
	}

	public void initActors(World world) {
		for (MutablePair<BlockInfo, MovementContext> pair : actors) {
			BlockState blockState = pair.left.state;
			MovementContext context = new MovementContext(world, blockState);
			((IHaveMovementBehavior) blockState.getBlock()).startMoving(context);
			pair.setRight(context);
		}
	}

	public List<MutablePair<BlockInfo, MovementContext>> getActors() {
		return actors;
	}

	public BlockPos getAnchor() {
		return anchor;
	}

}