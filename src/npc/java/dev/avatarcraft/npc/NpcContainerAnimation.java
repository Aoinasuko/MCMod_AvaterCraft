package dev.avatarcraft.npc;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import com.google.gson.JsonObject;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.*;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.*;

/** Native ContainerUser lifecycle; commits inventory changes only after a visible opening. */
public final class NpcContainerAnimation implements AutoCloseable {
  public static final int OPEN_TICKS = 15;
  private final CompanionEntity npc;
  private ServerLevel level;
  private BlockPos target;
  private Container container;
  private EnderChestBlockEntity ender;
  private final Map<BlockPos, BlockEntity> parts = new LinkedHashMap<>();
  private long until;
  private BooleanSupplier valid;
  private Supplier<JsonObject> operation;
  private CompletableFuture<JsonObject> pending;

  public NpcContainerAnimation(CompanionEntity npc) {
    this.npc = npc;
  }

  public boolean busy() {
    return pending != null;
  }

  public boolean hasOpen(BlockPos pos) {
    return pending != null && npc.isAlive() && npc.level() == level && parts.containsKey(pos);
  }

  public CompletionStage<JsonObject> use(
      BlockPos pos, Container container, BooleanSupplier valid, Supplier<JsonObject> operation) {
    var block = npc.level().getBlockEntity(pos);
    if (!(block instanceof ChestBlockEntity
        || block instanceof BarrelBlockEntity
        || block instanceof ShulkerBoxBlockEntity
        || block instanceof EnderChestBlockEntity))
      return CompletableFuture.completedFuture(operation.get());
    if (busy())
      return CompletableFuture.completedFuture(
          object("ok", false, "error", "Another container is still open"));
    if (!valid.getAsBoolean())
      return CompletableFuture.completedFuture(
          object("ok", false, "error", "Container is inaccessible"));
    this.level = (ServerLevel) npc.level();
    this.target = pos.immutable();
    this.container = container;
    this.ender = block instanceof EnderChestBlockEntity e ? e : null;
    this.valid = valid;
    this.operation = operation;
    parts.put(target, block);
    if (container instanceof CompoundContainer
        && block.getBlockState().getBlock() instanceof ChestBlock) {
      var second = target.relative(ChestBlock.getConnectedDirection(block.getBlockState()));
      var other = level.getBlockEntity(second);
      if (other != null) parts.put(second, other);
    }
    until = level.getGameTime() + OPEN_TICKS;
    pending = new CompletableFuture<>();
    var result = pending;
    npc.getNavigation().stop();
    npc.setSprinting(false);
    npc.getLookControl().setLookAt(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5);
    try {
      if (ender != null) ender.startOpen(npc);
      else container.startOpen(npc);
      npc.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
    } catch (RuntimeException e) {
      finish(object("ok", false, "error", "Container opening failed"));
    }
    return result;
  }

  public void tick() {
    if (pending == null) return;
    boolean available =
        npc.isAlive()
            && npc.level() == level
            && npc.ownerId() != null
            && level.getServer().getPlayerList().getPlayer(npc.ownerId()) != null;
    for (var part : parts.entrySet())
      available &=
          !part.getValue().isRemoved() && level.getBlockEntity(part.getKey()) == part.getValue();
    if (!available || !valid.getAsBoolean()) {
      finish(object("ok", false, "error", "Container became unavailable before transfer"));
      return;
    }
    if (level.getGameTime() < until) return;
    JsonObject outcome;
    try {
      outcome = operation.get();
    } catch (RuntimeException e) {
      outcome = object("ok", false, "error", "Container operation failed");
    }
    finish(outcome);
  }

  private void finish(JsonObject outcome) {
    var result = pending;
    if (result == null) return;
    pending = null;
    try {
      // A native five-tick recheck may already have removed a viewer pushed far away.
      // Reconcile the remaining real viewers instead of decrementing a stale count below zero.
      for (var part : parts.values()) {
        if (part.isRemoved()) continue;
        if (part instanceof ChestBlockEntity chest) chest.recheckOpen();
        else if (part instanceof BarrelBlockEntity barrel) barrel.recheckOpen();
        else if (part instanceof EnderChestBlockEntity chest) chest.recheckOpen();
        else if (part instanceof ShulkerBoxBlockEntity box) box.stopOpen(npc);
      }
    } finally {
      parts.clear();
      container = null;
      ender = null;
      operation = null;
      valid = null;
      result.complete(outcome);
    }
  }

  @Override
  public void close() {
    finish(object("ok", false, "error", "Container interaction cancelled"));
  }
}
