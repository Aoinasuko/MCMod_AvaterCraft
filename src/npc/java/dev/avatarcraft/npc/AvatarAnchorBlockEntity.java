package dev.avatarcraft.npc;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.*;

public final class AvatarAnchorBlockEntity extends BlockEntity {
  private String anchorId = "";

  public AvatarAnchorBlockEntity(BlockPos pos, BlockState state) {
    super(NpcMod.ANCHOR_ENTITY.get(), pos, state);
  }

  public String anchorId() {
    return anchorId;
  }

  public void setAnchorId(String id) {
    if (!(level instanceof ServerLevel server))
      throw new IllegalStateException("Server anchor edit required");
    id = AnchorIndex.normalize(id);
    var data = NpcAnchors.get(server.getServer());
    if (id.isEmpty()) NpcAnchors.remove(server, worldPosition);
    else
      data.index.put(
          new AnchorIndex.Entry(
              id,
              server.dimension().identifier().toString(),
              worldPosition.getX(),
              worldPosition.getY(),
              worldPosition.getZ()));
    anchorId = id;
    data.setDirty();
    setChanged();
  }

  @Override
  public void onLoad() {
    super.onLoad();
    if (level instanceof ServerLevel server && !anchorId.isEmpty()) {
      try {
        setAnchorId(anchorId);
      } catch (IllegalArgumentException duplicate) {
        anchorId = "";
        setChanged();
      }
    }
  }

  @Override
  protected void saveAdditional(ValueOutput output) {
    super.saveAdditional(output);
    output.putString("AnchorId", anchorId);
  }

  @Override
  protected void loadAdditional(ValueInput input) {
    super.loadAdditional(input);
    try {
      anchorId = AnchorIndex.normalize(input.getStringOr("AnchorId", ""));
    } catch (IllegalArgumentException invalid) {
      anchorId = "";
    }
  }
}
