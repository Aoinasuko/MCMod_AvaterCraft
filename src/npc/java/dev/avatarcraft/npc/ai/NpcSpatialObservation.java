package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;
import com.google.gson.*;
import dev.avatarcraft.npc.CompanionEntity;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;

/** Read-only loaded-space sensing. Geometry is not a promise that a dynamic route will succeed. */
final class NpcSpatialObservation {
  static final int TERRAIN_RADIUS=32, ENTITY_RADIUS=16;
  private static final String HEIGHTS="0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz+-=";
  private static final Map<CompanionEntity,Snapshot> CACHE=new WeakHashMap<>();
  record Snapshot(BlockPos origin,String dimension,long tick,JsonObject observation,
      Map<Long,Double> floors,Set<Long> reachable){}

  static Snapshot snapshot(CompanionEntity npc,boolean fresh){
    var old=CACHE.get(npc);var origin=NpcWalkSurface.standingCell(npc);
    String dimension=npc.level().dimension().identifier().toString();long now=npc.level().getGameTime();
    if(!fresh&&old!=null&&old.origin.equals(origin)&&old.dimension.equals(dimension)&&now>=old.tick&&now-old.tick<20)return old;
    var begin=System.nanoTime();var floors=new HashMap<Long,Double>();
    var resources=new TreeMap<String,JsonObject>();var heightRows=new JsonArray();var kindRows=new JsonArray();
    int scanned=0,unknown=0,excluded=0;var volume=new TreeMap<String,Integer>();
    for(int dz=-32;dz<=32;dz++){
      var heights=new StringBuilder();var kinds=new StringBuilder();
      for(int dx=-32;dx<=32;dx++){
        int horizontal=dx*dx+dz*dz;
        if(horizontal>1024){heights.append(' ');kinds.append(' ');continue;}
        int vertical=(int)Math.floor(Math.sqrt(1024-horizontal));
        int x=origin.getX()+dx,z=origin.getZ()+dz;
        if(npc.level().getChunkSource().getChunkNow(x>>4,z>>4)==null){
          unknown+=vertical*2+1;heights.append('?');kinds.append('?');continue;
        }
        BlockPos nearest=null;double nearestDistance=Double.POSITIVE_INFINITY;char kind='#';
        for(int dy=-vertical;dy<=vertical;dy++){
          var p=new BlockPos(x,origin.getY()+dy,z);
          if(!npc.level().isInWorldBounds(p)||!npc.level().getWorldBorder().isWithinBounds(p)){excluded++;continue;}
          var state=npc.level().getBlockState(p);scanned++;
          String material=state.getFluidState().isEmpty()?(state.getCollisionShape(npc.level(),p).isEmpty()?"open":"collision"):
              state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)?"lava":"water";
          volume.merge(material,1,Integer::sum);
          if(material.equals("lava"))kind='!';else if(material.equals("water")&&kind!='!')kind='~';
          String id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
          if(state.is(BlockTags.LOGS)||id.endsWith("_ore")||state.getBlock() instanceof CropBlock
              ||state.getBlock() instanceof ChestBlock||state.is(Blocks.CRAFTING_TABLE)||state.is(Blocks.FURNACE)){
            var group=resources.computeIfAbsent(id,k->object("block",k,"count",0,"positions",new JsonArray()));
            group.addProperty("count",group.get("count").getAsInt()+1);
            var points=group.getAsJsonArray("positions");points.add(NpcMission.position(p));
            if(points.size()>6){int far=0;double distance=-1;
              for(int i=0;i<points.size();i++){double d=NpcMission.block(points.get(i).getAsJsonArray()).distSqr(origin);if(d>distance){distance=d;far=i;}}
              points.remove(far);
            }
          }
          // Only potential floor boundaries need the more expensive full body collision check.
          if(!state.getCollisionShape(npc.level(),p).isEmpty()&&!NpcWalkSurface.openable(npc,p))continue;
          if(npc.level().getBlockState(p.below()).getCollisionShape(npc.level(),p.below()).isEmpty()&&!NpcWaterTravel.swimmable(npc,p))continue;
          if(!NpcWaterTravel.passable(npc,p))continue;
          double floor=NpcWaterTravel.height(npc,p);floors.put(p.asLong(),floor);
          double distance=Math.abs(floor-npc.getY());
          if(distance<nearestDistance){nearest=p;nearestDistance=distance;}
        }
        heights.append(nearest==null?'_':HEIGHTS.charAt(nearest.getY()-origin.getY()+32));
        kinds.append(nearest==null?kind:NpcWaterTravel.swimmable(npc,nearest)?'w':'.');
      }
      heightRows.add(heights.toString());kindRows.add(kinds.toString());
    }
    var reachable=new LinkedHashSet<Long>();var queue=new ArrayDeque<BlockPos>();
    if(floors.containsKey(origin.asLong())){reachable.add(origin.asLong());queue.add(origin);}
    else for(var p:BlockPos.betweenClosed(origin.offset(-1,-1,-1),origin.offset(1,1,1)))
      if(floors.containsKey(p.asLong())&&NpcWalkSurface.finalApproach(npc,p)&&reachable.add(p.asLong()))queue.add(p.immutable());
    boolean limited=false;
    while(!queue.isEmpty()){
      if(reachable.size()>=8192){limited=true;break;}
      var from=queue.removeFirst();double fromY=floors.get(from.asLong());
      for(var direction:Direction.Plane.HORIZONTAL)for(int dy:new int[]{0,-1,1}){
        var to=from.relative(direction).above(dy);var toY=floors.get(to.asLong());
        if(toY==null||reachable.contains(to.asLong())||toY-fromY>1.00001||fromY-toY>1.25001)continue;
        if(!NpcWaterTravel.transition(npc,from,to,fromY,toY))continue;
        reachable.add(to.asLong());queue.addLast(to);
      }
    }
    var waypoints=new JsonArray();var selected=new HashSet<Long>();
    for(int distance:new int[]{8,16,24,31})for(int sector=0;sector<8;sector++){
      double angle=sector*Math.PI/4,tx=origin.getX()+Math.cos(angle)*distance,tz=origin.getZ()+Math.sin(angle)*distance;
      BlockPos best=null;double score=Double.POSITIVE_INFINITY;
      for(long key:reachable){var p=BlockPos.of(key);double candidate=Math.pow(p.getX()-tx,2)+Math.pow(p.getZ()-tz,2)+Math.abs(p.getY()-origin.getY())*.2;
        if(candidate<score){score=candidate;best=p;}}
      if(best!=null&&best.distSqr(origin)>=9&&selected.add(best.asLong()))waypoints.add(object("position",NpcMission.position(best),
          "sector",sector,"desired_distance",distance,"reachable_in_static_geometry",true,"mode",NpcWaterTravel.swimmable(npc,best)?"swim":"walk"));
    }
    var landmarks=new JsonArray();resources.values().forEach(landmarks::add);
    var result=object("ok",true,"radius",32,"shape","3d_sphere","origin",NpcMission.position(origin),"dimension",dimension,
        "tick",now,"scanned_cells",scanned,"unknown_cells",unknown,"excluded_cells",excluded,"complete_loaded_coverage",unknown==0,
        "volume",new Gson().toJsonTree(volume),"standable_cells",floors.size(),"reachable_cells",reachable.size(),"graph_complete",!limited,
        "safe_water_depth",5,
        "waypoints",waypoints,"resources",landmarks,"height_rows",heightRows,"kind_rows",kindRows,
        "map_format","65 rows: z=-32..32, x=-32..32. Height encodes nearest standing cell dy using "+HEIGHTS+
            "; space=outside sphere, ?=unloaded, _=no standing cell. Kind .=dry standing cell, w=swimmable water depth1..5, #=blocked/no floor, ~=other water, !=lava. Other vertical levels are included in the internal graph, not this single-level projection.",
        "limits","Loaded blocks including occluded blocks only; no chunk generation or edits. Static connectivity excludes dynamic actors; paths and pickup must be verified when executed.");
    result.addProperty("scan_milliseconds",(System.nanoTime()-begin)/1e6);
    var snapshot=new Snapshot(origin,dimension,now,result,Map.copyOf(floors),Set.copyOf(reachable));CACHE.put(npc,snapshot);return snapshot;
  }

  static JsonObject terrain(CompanionEntity npc,boolean fresh,boolean maps){
    var out=snapshot(npc,fresh).observation.deepCopy();
    if(!maps){out.remove("height_rows");out.remove("kind_rows");out.remove("map_format");}
    return out;
  }

  static JsonObject entities(CompanionEntity npc){
    var all=npc.level().getEntities(npc,npc.getBoundingBox().inflate(16),e->e.isAlive()&&!e.isSpectator()&&npc.distanceToSqr(e)<=256)
        .stream().sorted(Comparator.comparingInt((Entity e)->e instanceof ItemEntity?0:e instanceof Enemy?1:2).thenComparingDouble(npc::distanceToSqr)).toList();
    var rows=new JsonArray();var counts=new TreeMap<String,Integer>();
    for(var e:all){String id=BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();counts.merge(id,1,Integer::sum);
      if(rows.size()>=128)continue;
      var row=object("entity_id",e.getId(),"uuid",e.getUUID().toString(),"type",id,"position",vector(e.position()),
          "velocity",vector(e.getDeltaMovement()),"distance",npc.distanceTo(e),"visible",npc.hasLineOfSight(e),
          "alive",e.isAlive(),"on_ground",e.onGround(),"in_water",e.isInWater());
      if(e instanceof LivingEntity living){row.addProperty("health",living.getHealth());row.addProperty("hostile",e instanceof Enemy||NpcCombat.boss(e));}
      if(e instanceof ItemEntity drop){row.addProperty("item",BuiltInRegistries.ITEM.getKey(drop.getItem().getItem()).toString());
        row.addProperty("count",drop.getItem().getCount());row.addProperty("pickup_delayed",drop.hasPickUpDelay());
        row.addProperty("settling",!drop.onGround()&&drop.getDeltaMovement().lengthSqr()>.0004);
        row.addProperty("collection_verified",false);
      }
      rows.add(row);
    }
    return object("ok",true,"radius",16,"shape","3d_sphere","origin",vector(npc.position()),"tick",npc.level().getGameTime(),
        "total",all.size(),"returned",rows.size(),"truncated",all.size()>rows.size(),"counts",new Gson().toJsonTree(counts),"entities",rows,
        "limits","Includes occluded loaded entities. Presence, visibility and movement completion do not prove pickup or death. Re-observe and compare inventory/mission_goal; pickups run every 10 ticks.");
  }
  static JsonArray vector(Vec3 p){return new Gson().toJsonTree(List.of(p.x,p.y,p.z)).getAsJsonArray();}
}
