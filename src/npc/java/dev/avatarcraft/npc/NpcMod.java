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
  public static final DeferredHolder<MenuType<?>, MenuType<CompanionMenu>> COMPANION_MENU =
      MENUS.register("companion", () -> IMenuTypeExtension.create(CompanionMenu::new));

  public static Identifier id(String path) {
    return Identifier.fromNamespaceAndPath(ID, path);
  }

  public NpcMod(IEventBus bus, net.neoforged.fml.ModContainer container) {
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
        });
    bus.addListener(
        net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent.class,
        NpcNetwork::register);
    ENTITIES.register(bus);
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
          }
        });
  }
}
