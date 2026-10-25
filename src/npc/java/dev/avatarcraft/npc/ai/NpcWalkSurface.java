package dev.avatarcraft.npc.ai;

import dev.avatarcraft.npc.CompanionEntity;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Collision-derived floor height, shared by route validation and physical arrival checks. */
public final class NpcWalkSurface {
  public static double height(Level level, Mob mob, BlockPos feetCell) {
    var support = feetCell.below();
    if (!level.hasChunkAt(support)) return Double.NaN;
    var shape =
        level.getBlockState(support).getCollisionShape(level, support, CollisionContext.of(mob));
    double top = Double.NaN, radius = Math.min(.3, mob.getBbWidth() / 2.);
    for (var box : shape.toAabbs()) {
      if (box.maxY > 1.00001
          || box.maxY <= 0
          || box.maxX <= .5 - radius
          || box.minX >= .5 + radius
          || box.maxZ <= .5 - radius
          || box.minZ >= .5 + radius) continue;
      if (!Double.isFinite(top) || box.maxY > top) top = box.maxY;
    }
    return Double.isFinite(top) ? support.getY() + top : Double.NaN;
  }

  public static Vec3 point(Mob npc, BlockPos cell) {
    double height = height(npc.level(), npc, cell);
    return new Vec3(
        cell.getX() + .5, Double.isFinite(height) ? height : cell.getY(), cell.getZ() + .5);
  }

  public static BlockPos standingCell(CompanionEntity npc) {
    var p = npc.blockPosition();
    var shape =
        npc.level().getBlockState(p).getCollisionShape(npc.level(), p, CollisionContext.of(npc));
    return npc.onGround() && !shape.isEmpty() && shape.max(Direction.Axis.Y) <= 1.00001
        ? p.above()
        : p;
  }

  public static BlockPos canonicalTarget(CompanionEntity npc, BlockPos target) {
    var state = npc.level().getBlockState(target);
    if (state.getBlock() instanceof DoorBlock
        || state.getBlock() instanceof FenceGateBlock
        || state.hasBlockEntity()) return target;
    var shape = state.getCollisionShape(npc.level(), target, CollisionContext.of(npc));
    if (!shape.isEmpty()
        && (shape.max(Direction.Axis.Y) < .99999 || state.getBlock() instanceof StairBlock)
        && standable(npc, target.above())) return target.above();
    return target;
  }

  static boolean finalApproach(CompanionEntity npc, BlockPos cell) {
    var target = point(npc, cell);
    var delta = target.subtract(npc.position());
    if (delta.y > npc.maxUpStep() + 1e-5 || delta.y < -1.25001 || !standable(npc, cell))
      return false;
    // For a step down, walk horizontally off the support before gravity lowers the entity.
    // For a step up, use the same headroom envelope as physical auto-stepping.
    return npc.level()
        .noCollision(
            npc,
            npc.getBoundingBox()
                .move(0, Math.max(0, delta.y), 0)
                .expandTowards(delta.x, 0, delta.z));
  }

  public static boolean mayOpen(CompanionEntity npc, BlockPos p) {
    if (!(npc.level() instanceof ServerLevel level) || npc.ownerId() == null) return false;
    var owner = level.getServer().getPlayerList().getPlayer(npc.ownerId());
    return owner != null && owner.mayBuild() && level.mayInteract(owner, p);
  }

  public static boolean openable(CompanionEntity npc, BlockPos p) {
    var level = npc.level();
    var state = level.getBlockState(p);
    if (state.getBlock() instanceof DoorBlock && DoorBlock.isWoodenDoor(level, p))
      return state.getValue(DoorBlock.OPEN) || mayOpen(npc, p);
    if (state.getBlock() instanceof FenceGateBlock)
      return state.getValue(FenceGateBlock.OPEN) || mayOpen(npc, p);
    return false;
  }

  public static boolean standable(CompanionEntity npc, BlockPos cell) {
    var level = npc.level();
    if (!level.hasChunkAt(cell)
        || !level.isInWorldBounds(cell)
        || !level.getWorldBorder().isWithinBounds(cell)) return false;
    double y = height(level, npc, cell);
    if (!Double.isFinite(y)
        || !level.getFluidState(cell.below()).isEmpty()
        || hazard(level.getBlockState(cell.below()).getBlock())) return false;
    double r = npc.getBbWidth() / 2.;
    var body =
        new AABB(
            cell.getX() + .5 - r + 1e-5,
            y + 1e-5,
            cell.getZ() + .5 - r + 1e-5,
            cell.getX() + .5 + r - 1e-5,
            y + npc.getBbHeight(),
            cell.getZ() + .5 + r - 1e-5);
    return clearBody(npc, body);
  }

  static boolean stepClear(
      CompanionEntity npc, BlockPos from, BlockPos to, double fromY, double toY) {
    double r = npc.getBbWidth() / 2., y = Math.max(fromY, toY);
    var body =
        new AABB(
                from.getX() + .5 - r + 1e-5,
                y + 1e-5,
                from.getZ() + .5 - r + 1e-5,
                from.getX() + .5 + r - 1e-5,
                y + npc.getBbHeight(),
                from.getZ() + .5 + r - 1e-5)
            .expandTowards(to.getX() - from.getX(), 0, to.getZ() - from.getZ());
    return clearBody(npc, body);
  }

  private static boolean clearBody(CompanionEntity npc, AABB body) {
    var level = npc.level();
    for (var p :
        BlockPos.betweenClosed(
            BlockPos.containing(body.minX, body.minY, body.minZ),
            BlockPos.containing(body.maxX, body.maxY, body.maxZ))) {
      if (!level.hasChunkAt(p) || !level.isInWorldBounds(p) || !level.getFluidState(p).isEmpty())
        return false;
      var state = level.getBlockState(p);
      if (hazard(state.getBlock())) return false;
      if (openable(npc, p)) continue;
      for (var box : state.getCollisionShape(level, p, CollisionContext.of(npc)).toAabbs())
        if (box.move(p.getX(), p.getY(), p.getZ()).intersects(body)) return false;
    }
    return true;
  }

  private static boolean hazard(Block b) {
    return b == Blocks.MAGMA_BLOCK
        || b == Blocks.CACTUS
        || b == Blocks.FIRE
        || b == Blocks.SOUL_FIRE
        || b == Blocks.CAMPFIRE
        || b == Blocks.SOUL_CAMPFIRE
        || b == Blocks.POWDER_SNOW
        || b == Blocks.SWEET_BERRY_BUSH;
  }

  private NpcWalkSurface() {}
}
