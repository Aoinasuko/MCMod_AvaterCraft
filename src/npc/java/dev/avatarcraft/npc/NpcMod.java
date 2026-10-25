package dev.avatarcraft.npc;

import dev.avatarcraft.npc.ai.NpcService;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.registries.*;

@Mod(NpcMod.ID)
public final class NpcMod {
  public static final String ID = "avatarcraftnpc";
  public static final DeferredRegister<EntityType<?>> ENTITIES =
      DeferredRegister.create(Registries.ENTITY_TYPE, ID);
  public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ID);
  public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ID);
  public static final DeferredRegister<net.minecraft.world.level.block.entity.BlockEntityType<?>>
      BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ID);
  public static final DeferredBlock<AvatarAnchorBlock> AVATAR_ANCHOR =
      BLOCKS.registerBlock(
          "avatar_anchor",
          AvatarAnchorBlock::new,
          () ->
              net.minecraft.world.level.block.state.BlockBehaviour.Properties.of()
                  .mapColor(net.minecraft.world.level.material.MapColor.METAL)
                  .strength(3, 6)
                  .sound(net.minecraft.world.level.block.SoundType.METAL)
                  .requiresCorrectToolForDrops()
                  .pushReaction(net.minecraft.world.level.material.PushReaction.IMMOVEABLE));
  public static final DeferredItem<net.minecraft.world.item.BlockItem> ANCHOR_ITEM =
      ITEMS.registerSimpleBlockItem(AVATAR_ANCHOR);
  public static final DeferredHolder<
          net.minecraft.world.level.block.entity.BlockEntityType<?>,
          net.minecraft.world.level.block.entity.BlockEntityType<AvatarAnchorBlockEntity>>
      ANCHOR_ENTITY =
          BLOCK_ENTITIES.register(
              "avatar_anchor",
              () ->
                  new net.minecraft.world.level.block.entity.BlockEntityType<>(
                      AvatarAnchorBlockEntity::new, java.util.Set.of(AVATAR_ANCHOR.get())));
  public static final DeferredRegister<MenuType<?>> MENUS =
      DeferredRegister.create(Registries.MENU, ID);
  public static final DeferredHolder<EntityType<?>, EntityType<CompanionEntity>> COMPANION =
      ENTITIES.register(
          "companion",
          () ->
              EntityType.Builder.of(CompanionEntity::new, MobCategory.CREATURE)
                  .sized(.6f, 1.8f)
                  .eyeHeight(1.62f)
                  .clientTrackingRange(10)
                  .build(ResourceKey.create(Registries.ENTITY_TYPE, id("companion"))));
  public static final DeferredItem<SummoningItem> SOULSTONE =
      ITEMS.registerItem("soulstone", SummoningItem::new, p -> p.stacksTo(16));
  public static final DeferredItem<MemoryJournalItem> MEMORY_JOURNAL =
      ITEMS.registerItem("memory_journal", MemoryJournalItem::new, p -> p.stacksTo(1));
  public static final DeferredItem<RecallMapItem> RECALL_MAP =
      ITEMS.registerItem("recall_map", RecallMapItem::new, p -> p.stacksTo(1));
  public static final DeferredHolder<MenuType<?>, MenuType<CompanionMenu>> COMPANION_MENU =
      MENUS.register("companion", () -> IMenuTypeExtension.create(CompanionMenu::new));

  public static Identifier id(String path) {
    return Identifier.fromNamespaceAndPath(ID, path);
  }

  public NpcMod(IEventBus bus, net.neoforged.fml.ModContainer container) {
    bus.addListener(
        net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent.class,
        event -> {
          event.register(NpcChunkLoading.CONTROLLER);
          event.register(NpcRecall.CONTROLLER);
        });
    container.registerConfig(
        net.neoforged.fml.config.ModConfig.Type.SYNCED,
        NpcSettings.SPEC,
        "avatarcraftnpc-server.toml");
    NeoForge.EVENT_BUS.addListener(
        net.neoforged.neoforge.event.entity.EntityJoinLevelEvent.class,
        event -> {
          if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level
              && event.getEntity() instanceof CompanionEntity npc)
            NpcRoster.get(level.getServer()).update(npc);
        });
    NeoForge.EVENT_BUS.addListener(
        net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class,
        event -> {
          NpcRecall.tick(event.getServer());
          if (event.getServer().getTickCount() % 20 == 0)
            AnchorEditing.expire(event.getServer().getTickCount());
        });
    NeoForge.EVENT_BUS.addListener(
        net.neoforged.neoforge.event.entity.ProjectileImpactEvent.class,
        dev.avatarcraft.npc.ai.NpcCombat::projectile);
    container.registerConfig(
        net.neoforged.fml.config.ModConfig.Type.LOCAL,
        dev.avatarcraft.npc.ai.NpcAiSettings.SPEC,
        "avatarcraftnpc-ai.toml");
    NeoForge.EVENT_BUS.addListener(
        net.neoforged.bus.api.EventPriority.LOWEST,
        net.neoforged.neoforge.event.ServerChatEvent.class,
        event -> {
          var player = event.getPlayer();
          String text = event.getRawText();
          player.level().getServer().execute(() -> NpcChatRouter.receive(player, text));
        });
    if (Boolean.getBoolean("avatarcraft.npcGameTest")
        && !net.neoforged.fml.loading.FMLEnvironment.isProduction())
      bus.addListener(
          net.neoforged.neoforge.event.RegisterGameTestsEvent.class,
          dev.avatarcraft.npc.ai.NpcGameTests::register);
    NeoForge.EVENT_BUS.addListener(
        ServerStoppingEvent.class,
        event -> {
          NpcService.stop(event.getServer());
          NpcNetwork.clear();
          NpcRecall.clear(event.getServer());
        });
    bus.addListener(
        net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent.class,
        NpcNetwork::register);
    ENTITIES.register(bus);
    BLOCKS.register(bus);
    BLOCK_ENTITIES.register(bus);
    ITEMS.register(bus);
    MENUS.register(bus);
    bus.addListener(
        EntityAttributeCreationEvent.class,
        event ->
            event.put(
                COMPANION.get(),
                Player.createAttributes()
                    .add(Attributes.FOLLOW_RANGE, 32)
                    .add(Attributes.ATTACK_KNOCKBACK)
                    .build()));
    bus.addListener(
        BuildCreativeModeTabContentsEvent.class,
        event -> {
          if (event.getTabKey().equals(CreativeModeTabs.TOOLS_AND_UTILITIES)) {
            event.accept(SOULSTONE);
            event.accept(MEMORY_JOURNAL);
            event.accept(RECALL_MAP);
            event.accept(ANCHOR_ITEM);
          }
        });
  }
}
