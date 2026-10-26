package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;
import dev.avatarcraft.npc.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

final class NpcLocationGameTests {
  static void register(RegisterGameTestsEvent event) {
    var env=event.registerEnvironment(bep_3davater_companion.id("location_qa"),new TestEnvironmentDefinition.AllOf());
    event.registerTest(bep_3davater_companion.id("location_command_snapshot_and_completion"),new FunctionGameTestInstance(
        BuiltinTestFunctions.ALWAYS_PASS,new TestData<>(env,bep_3davater_companion.id("qa_empty"),400,1,true)) {
      public void run(GameTestHelper h) {
        for(int x=0;x<14;x++) for(int z=0;z<14;z++) h.setBlock(x,0,z,Blocks.STONE);
        h.setBlock(5,1,5,Blocks.CHEST); h.setBlock(3,1,5,Blocks.CHEST);
        var owner=h.makeMockServerPlayerInLevel();
        owner.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(5,1,2))));
        aim(owner,h.absolutePos(new BlockPos(5,1,5)));
        var n=NpcNeoRecoveryGameTests.npc(h,owner,5,1,3);
        n.setInstruction("原木を採取してここのチェストに入れてほしい",NpcCommandContext.capture(n,owner));
        var intended=NpcCommandContext.delivery(n);
        var frozenNpc=NpcCommandContext.reference(n,"君の位置","npc_position");
        var frozenOwner=NpcCommandContext.reference(n,"私の位置","speaker_position");
        h.assertTrue(frozenNpc.z()!=frozenOwner.z(),"Speaker and addressee resolve independently");
        var capturedChunk=n.commandContext().getAsJsonObject("speaker").getAsJsonObject("chunk");
        int expectedX=Math.floorDiv(owner.blockPosition().getX(),16)*16;
        h.assertTrue(capturedChunk.get("minX").getAsInt()==expectedX
            &&capturedChunk.get("maxX").getAsInt()==expectedX+15,"Chunk bounds use floor division, including negative coordinates");
        h.assertTrue(intended!=null && intended.x()==h.absolutePos(new BlockPos(5,1,5)).getX(),"Ray-selected chest wins over another nearby chest");
        owner.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(12,1,12))));
        h.assertTrue(frozenOwner.equals(NpcCommandContext.reference(n,"私の位置","speaker_position")),"Moving speaker does not move the saved reference");
        var original=new TaskPlan(1,"collect then deposit","",List.of(
            NpcSurvival.step("acquire","#minecraft:logs","",64),
            NpcSurvival.step("deposit","#minecraft:logs","",64)));
        var skills=new NpcSkills(n,(a,b)->{});
        var bound=new NpcLocalWorld(n,skills).bindLocations(original);
        h.assertTrue(bound.steps().getFirst().action().equals("remember_location"),"Save destination before gathering");
        h.assertTrue(bound.steps().getLast().location().equals(NpcCommandContext.DELIVERY),"Deposit uses frozen referent");
        var p=h.absolutePos(new BlockPos(5,1,5));
        skills.execute("remember_location",object("name",NpcCommandContext.DELIVERY,"x",p.getX(),"y",p.getY(),"z",p.getZ())).toCompletableFuture().join();
        h.assertTrue(intended.equals(n.locations.get(NpcCommandContext.DELIVERY)),"Tool saves exact captured coordinates");
        owner.setPos(-0.1,64,-16.1);
        var negative=NpcCommandContext.capture(n,owner).getAsJsonObject("speaker").getAsJsonObject("chunk");
        h.assertTrue(negative.get("x").getAsInt()==-1 && negative.get("z").getAsInt()==-2
            &&negative.get("minX").getAsInt()==-16&&negative.get("maxZ").getAsInt()==-17,"Negative positions resolve to the correct inclusive chunk bounds");
        for(int i=1;i<8;i++) n.locations.remember(new NpcLocationMemory.Place("p"+i,intended.dimension(),i,64,0,"point"));
        n.completeInstruction();
        h.assertTrue(n.locations.entries().isEmpty() && n.commandContext().isEmpty(),"Verified completion clears all eight locations and command context");
        n.discard(); h.succeed();
      }
    });
    var lifecycleEnv=event.registerEnvironment(bep_3davater_companion.id("location_lifecycle_qa"),new TestEnvironmentDefinition.AllOf());
    event.registerTest(bep_3davater_companion.id("location_lifecycle_verified_completion"),new FunctionGameTestInstance(
        BuiltinTestFunctions.ALWAYS_PASS,new TestData<>(lifecycleEnv,bep_3davater_companion.id("qa_empty"),400,1,true)) {
      public void run(GameTestHelper h) {
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)h.setBlock(x,0,z,Blocks.STONE);
        var owner=h.makeMockServerPlayerInLevel();owner.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(2,1,2))));
        var n=NpcNeoRecoveryGameTests.npc(h,owner,3,1,3);
        n.setInstruction("1秒待ってください",NpcCommandContext.capture(n,owner));
        for(int i=0;i<8;i++)n.locations.remember(new NpcLocationMemory.Place("p"+i,h.getLevel().dimension().identifier().toString(),i,64,0,"point"));
        h.setBlock(6,1,6,bep_3davater_companion.AVATAR_ANCHOR.get());
        ((AvatarAnchorBlockEntity)h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(6,1,6)))).setAnchorId("完了後も残る共有場所");
        var output=net.minecraft.world.level.storage.TagValueOutput.createWithContext(
            net.minecraft.util.ProblemReporter.DISCARDING,h.getLevel().registryAccess());
        n.saveWithoutId(output);
        var restored=bep_3davater_companion.COMPANION.get().create(h.getLevel(),net.minecraft.world.entity.EntitySpawnReason.LOAD);
        restored.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING,
            h.getLevel().registryAccess(),output.buildResult()));
        h.assertTrue(restored.commandContext().equals(n.commandContext()),"Frozen references persist across entity save/load");
        h.assertTrue(restored.locations.entries().size()==8,"Unfinished mission retains its memories on save/load");
        n.mind().acceptDelegatedPlan(new TaskPlan(1,"wait then complete","",List.of(new TaskPlan.Step(
            "wait","","",1,-1,-1,"main",1,List.of()))));
        h.onEachTick(()->{
          if(!n.instruction().isBlank())return;
          h.assertTrue(n.locations.entries().isEmpty()&&n.commandContext().isEmpty(),"Mind completion clears all personal references");
          h.assertTrue(NpcAnchors.get(h.getLevel().getServer()).resolve(h.getLevel().getServer(),"完了後も残る共有場所")!=null,"Shared anchor survives mission completion");
          n.discard();h.succeed();
        });
      }
    });
  }

  static void aim(net.minecraft.server.level.ServerPlayer player, BlockPos block) {
    var delta=Vec3.atCenterOf(block).subtract(player.getEyePosition());
    player.setYRot((float)Math.toDegrees(Math.atan2(-delta.x,delta.z)));
    player.setXRot((float)Math.toDegrees(Math.atan2(-delta.y,Math.hypot(delta.x,delta.z))));
  }
}
