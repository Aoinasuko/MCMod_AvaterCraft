package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;
import com.google.gson.*;
import dev.avatarcraft.npc.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.ChestBlock;

/** An instruction's immutable goal, independently checked against actual world state. */
public final class NpcMission {
  private final CompanionEntity npc;
  private JsonObject state=new JsonObject();
  public NpcMission(CompanionEntity npc){this.npc=npc;}

  public void reset(){
    state=object("instruction",npc.instruction(),"sinceTick",npc.level().getGameTime(),
        "dimension",npc.level().dimension().identifier().toString());
    var crops=new JsonArray();var seen=new HashSet<Long>();
    if(!npc.instruction().isBlank()){
      captureCrops(npc.blockPosition(),16,crops,seen);
      var context=npc.commandContext();
      if(context.has("speaker"))captureCrops(block(context.getAsJsonObject("speaker").getAsJsonArray("blockPosition")),16,crops,seen);
      for(var anchor:NpcAnchors.get(npc.level().getServer()).relevant(npc.level().getServer(),npc.instruction())){
        var a=anchor.getAsJsonObject();
        if(a.get("dimension").getAsString().equals(state.get("dimension").getAsString()))
          captureCrops(new BlockPos(a.get("x").getAsInt(),a.get("y").getAsInt(),a.get("z").getAsInt()),8,crops,seen);
      }
    }
    state.add("initialCrops",crops);
    state.add("initialProducts",object("minecraft:wheat",inventory("minecraft:wheat"),"minecraft:beetroot",inventory("minecraft:beetroot")));
  }
  private void captureCrops(BlockPos center,int radius,JsonArray crops,Set<Long> seen){
    if(crops.size()>=256)return;
    for(var p:BlockPos.betweenClosed(center.offset(-radius,-4,-radius),center.offset(radius,4,radius))){
      if(crops.size()>=256)break;
      if(!npc.level().hasChunkAt(p)||!seen.add(p.asLong()))continue;
      var s=npc.level().getBlockState(p);
      if(s.getBlock() instanceof CropBlock crop&&crop.isMaxAge(s))
        crops.add(object("position",position(p),"block",BuiltInRegistries.BLOCK.getKey(crop).toString()));
    }
  }
  public boolean active(){return state.has("goal")&&!state.getAsJsonObject("goal").get("type").getAsString().equals("plan");}
  public void adopt(JsonObject goal){
    if(active()||goal.get("type").getAsString().equals("plan"))return;
    if(!state.has("sinceTick"))reset();
    state.add("goal",goal.deepCopy());
    resolve();
    if(!goal.get("type").getAsString().equals("farm")){state.remove("initialCrops");state.remove("initialProducts");}
  }
  private void resolve(){
    if(!active()||state.has("position"))return;
    var g=state.getAsJsonObject("goal");String kind=g.get("type").getAsString();
    if(kind.equals("defeat"))return;
    var a=g.getAsJsonArray("position");
    if(a.size()==3)state.add("position",a.deepCopy());
    else {
      String name=g.get("location").getAsString();
      var place=npc.locations.get(name);
      if(place!=null&&place.dimension().equals(state.get("dimension").getAsString()))
        state.add("position",position(new BlockPos(place.x(),place.y(),place.z())));
      else if(name.equals(NpcCommandContext.DELIVERY)){
        var p=NpcCommandContext.delivery(npc);
        if(p!=null)state.add("position",position(new BlockPos(p.x(),p.y(),p.z())));
      } else {
        var p=NpcAnchors.get(npc.level().getServer()).resolve(npc.level().getServer(),name);
        if(p!=null&&p.dimension().equals(state.get("dimension").getAsString()))
          state.add("position",position(new BlockPos(p.x(),p.y(),p.z())));
      }
    }
    if(kind.equals("farm")&&state.has("position")){
      var crops=new JsonArray();var center=block(state.getAsJsonArray("position"));int r=g.get("radius").getAsInt();
      if(state.has("initialCrops"))for(var e:state.getAsJsonArray("initialCrops")){
        var p=block(e.getAsJsonObject().getAsJsonArray("position"));
        if(Math.abs(p.getX()-center.getX())<=r&&Math.abs(p.getZ()-center.getZ())<=r&&Math.abs(p.getY()-center.getY())<=4)crops.add(e.deepCopy());
      }
      if(crops.isEmpty())captureCrops(center,r,crops,new HashSet<>());
      state.add("crops",crops);
      var products=new JsonObject();
      for(var e:crops){String crop=e.getAsJsonObject().get("block").getAsString();
        String item=crop.equals("minecraft:wheat")?"minecraft:wheat":crop.equals("minecraft:beetroots")?"minecraft:beetroot":"";
        int baseline=state.has("initialProducts")&&state.getAsJsonObject("initialProducts").has(item)?state.getAsJsonObject("initialProducts").get(item).getAsInt():inventory(item);
        if(!item.isEmpty())products.addProperty(item,(products.has(item)?products.get(item).getAsInt():baseline)+1);
      }
      state.add("harvestProductMinimum",products);
      state.remove("initialCrops");state.remove("initialProducts");
    }
  }
  public boolean verified(){return !active()||observation().get("verified").getAsBoolean();}

  public JsonObject observation(){
    if(state.has("verifiedAtCompletion"))return state.getAsJsonObject("verifiedAtCompletion").deepCopy();
    resolve();var out=state.deepCopy();out.addProperty("verified",false);
    if(!active()){out.addProperty("established",false);return out;}
    out.addProperty("established",true);
    var g=state.getAsJsonObject("goal");String kind=g.get("type").getAsString();int expected=g.get("count").getAsInt(),actual=0;
    if(!state.get("dimension").getAsString().equals(npc.level().dimension().identifier().toString()))return out;
    if(kind.equals("defeat")){
      var data=NpcWorkData.get((ServerLevel)npc.level());
      for(var kill:data.kills.values()){
        if(!kill.get("owner").getAsString().equals(npc.ownerId().toString())
            ||!kill.get("dimension").getAsString().equals(state.get("dimension").getAsString())
            ||kill.get("tick").getAsLong()<state.get("sinceTick").getAsLong()
            ||!kill.get("type").getAsString().equals(g.get("target").getAsString()))continue;
        String actor=kill.get("actor").getAsString();
        boolean member=actor.equals(npc.getUUID().toString())||data.jobs.stream().anyMatch(j->j.leader.equals(npc.getUUID())
            &&j.worker.toString().equals(actor)&&j.parentInstruction.equals(state.get("instruction").getAsString()));
        member|=data.groups.stream().anyMatch(team->team.leader.equals(npc.getUUID())&&team.instruction.equals(state.get("instruction").getAsString())
            &&team.members.stream().anyMatch(id->id.toString().equals(actor)));
        if(member)actual++;
      }
    }else if(kind.equals("deposit")&&state.has("position")){
      var p=block(state.getAsJsonArray("position"));
      if(npc.level().hasChunkAt(p)&&npc.level().getBlockEntity(p) instanceof Container c){
        var s=npc.level().getBlockState(p);
        if(s.getBlock() instanceof ChestBlock chest){var combined=ChestBlock.getContainer(chest,s,npc.level(),p,false);if(combined!=null)c=combined;}
        for(int slot=0;slot<c.getContainerSize();slot++)if(matches(c.getItem(slot),g.get("target").getAsString()))actual+=c.getItem(slot).getCount();
      }
    }else if(kind.equals("farm")&&state.has("crops")){
      expected=state.getAsJsonArray("crops").size();var missing=new JsonArray();
      for(var e:state.getAsJsonArray("crops")){
        var cell=e.getAsJsonObject();var p=block(cell.getAsJsonArray("position"));
        var s=npc.level().getBlockState(p);
        if(npc.level().hasChunkAt(p)&&s.getBlock() instanceof CropBlock crop
            &&BuiltInRegistries.BLOCK.getKey(crop).toString().equals(cell.get("block").getAsString())&&!crop.isMaxAge(s))actual++;
        else missing.add(object("position",cell.get("position"),"block",BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString()));
      }
      out.add("unfinished_crops",missing);
    }
    out.addProperty("actual",actual);out.addProperty("required",expected);out.addProperty("remaining",Math.max(0,expected-actual));
    boolean productsReady=true;
    if(kind.equals("farm")&&state.has("harvestProductMinimum"))for(var e:state.getAsJsonObject("harvestProductMinimum").entrySet())
      productsReady &= inventory(e.getKey())>=e.getValue().getAsInt();
    out.addProperty("products_collected",productsReady);
    out.addProperty("verified",expected>0&&actual>=expected&&productsReady);
    return out;
  }
  public String save(){return state.toString();}
  public void completed(){if(active()&&verified())state.add("verifiedAtCompletion",observation());}
  public void restore(String json){
    try{
      if(json.length()>65536)throw new IllegalArgumentException();
      var parsed=JsonParser.parseString(json).getAsJsonObject();
      if(!parsed.get("instruction").getAsString().equals(npc.instruction()))throw new IllegalArgumentException();
      if(parsed.has("goal"))new TaskPlan(1,"validate goal","",List.of(new TaskPlan.Step("wait","","",1,-1,-1,"main",1,List.of())),parsed.getAsJsonObject("goal"));
      state=parsed;
    }catch(RuntimeException invalid){reset();}
  }
  static boolean matches(ItemStack item,String target){
    if(item.isEmpty()||target.isEmpty())return false;
    return target.startsWith("#")?item.is(TagKey.create(net.minecraft.core.registries.Registries.ITEM,Identifier.parse(target.substring(1))))
        :BuiltInRegistries.ITEM.getKey(item.getItem()).toString().equals(target);
  }
  private int inventory(String item){int n=0;for(int i=0;i<42;i++)if(matches(npc.inventory.getItem(i),item))n+=npc.inventory.getItem(i).getCount();return n;}
  static JsonArray position(BlockPos p){return new Gson().toJsonTree(List.of(p.getX(),p.getY(),p.getZ())).getAsJsonArray();}
  static BlockPos block(JsonArray a){return BlockPos.containing(a.get(0).getAsDouble(),a.get(1).getAsDouble(),a.get(2).getAsDouble());}
  public static void killed(LivingEntity victim,net.minecraft.world.entity.Entity source){
    if(!(source instanceof CompanionEntity actor)||!(victim.level() instanceof ServerLevel level)||actor.ownerId()==null)return;
    var data=NpcWorkData.get(level);String id=victim.getUUID().toString();
    data.kills.putIfAbsent(id,object("entity",id,"actor",actor.getUUID().toString(),"owner",actor.ownerId().toString(),
        "type",BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()).toString(),"dimension",level.dimension().identifier().toString(),"tick",level.getGameTime()));
    while(data.kills.size()>1024)data.kills.remove(data.kills.keySet().iterator().next());data.setDirty();
  }
}
