package mca.entity.ai;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import mca.entity.VillagerEntityMCA;
import mca.server.world.data.Building;
import mca.server.world.data.GraveyardManager;
import mca.server.world.data.Village;
import mca.server.world.data.VillageManager;
import mca.util.compat.OptionalCompat;
import mca.util.network.datasync.CDataManager;
import mca.util.network.datasync.CDataParameter;
import mca.util.network.datasync.CParameter;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.tag.BlockTags;
import net.minecraft.util.dynamic.GlobalPos;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.poi.PointOfInterestType;

/**
 * Villagers need a place to live too.
 */
public class Residency {
    private static final CDataParameter<Integer> VILLAGE = CParameter.create("village", -1);
    private static final CDataParameter<Integer> BUILDING = CParameter.create("buildings", -1);
    private static final CDataParameter<BlockPos> HANGOUT = CParameter.create("hangoutPos", BlockPos.ORIGIN);

    public static <E extends Entity> CDataManager.Builder<E> createTrackedData(CDataManager.Builder<E> builder) {
        return builder.addAll(VILLAGE, BUILDING, HANGOUT);
    }

    private final VillagerEntityMCA entity;

    public Residency(VillagerEntityMCA entity) {
        this.entity = entity;
    }

    public BlockPos getWorkplace() {
        return entity.getBrain()
                .getOptionalMemory(MemoryModuleType.JOB_SITE)
                .map(GlobalPos::getPos)
                .orElse(BlockPos.ORIGIN);
    }

    public void setWorkplace(PlayerEntity player) {
        entity.sendChatMessage(player, "interaction.setworkplace.success");
        entity.getBrain().remember(MemoryModuleType.JOB_SITE, GlobalPos.create(player.world.getRegistryKey(), player.getBlockPos()));
    }

    public BlockPos getHangout() {
        return entity.getTrackedValue(HANGOUT);
    }

    public void setHangout(PlayerEntity player) {
        entity.sendChatMessage(player, "interaction.sethangout.success");
        entity.setTrackedValue(HANGOUT, player.getBlockPos());
    }

    public void setBuildingId(int id) {
        entity.setTrackedValue(BUILDING, id);
    }

    public void setVillageId(int id) {
        entity.setTrackedValue(VILLAGE, id);
    }

    public Optional<Village> getHomeVillage() {
        return VillageManager.get((ServerWorld)entity.world).getOrEmpty(entity.getTrackedValue(VILLAGE));
    }

    public void leaveHome() {
        VillageManager villages = VillageManager.get((ServerWorld)entity.world);
        Optional<Village> village = villages.getOrEmpty(entity.getTrackedValue(VILLAGE));
        if (village.isPresent()) {
            Optional<Building> building = village.get().getBuilding(entity.getTrackedValue(BUILDING));
            if (building.isPresent()) {
                building.get().getResidents().remove(entity.getUuid());
                villages.markDirty();
            }
        }
    }

    public Optional<GlobalPos> getHome() {
        return entity.getBrain().getOptionalMemory(MemoryModuleType.HOME);
    }

    public void tick() {
        if (entity.age % 600 == 0) {
            reportBuildings();

            //poor villager has no village
            if (entity.getTrackedValue(VILLAGE) == -1) {
                Village.findNearest(entity).map(Village::getId).ifPresent(this::setVillageId);
            }

            //and no house
            if (entity.getTrackedValue(BUILDING) == -1) {
                OptionalCompat.ifPresentOrElse(getHomeVillage(), this::seekNewHome, () -> setVillageId(-1));
            }

            //no bed?
            if (entity.getTrackedValue(BUILDING) != -1) {
                Optional<GlobalPos> memory = entity.getBrain().getOptionalMemory(MemoryModuleType.HOME);
                if (!memory.isPresent()) {
                    getHomeVillage().ifPresent(v -> v.removeResident(entity));
                    setBuildingId(-1);
                }
            }
        }

        //check if his village and building still exists
        if (entity.age % 1200 == 0) {
            OptionalCompat.ifPresentOrElse(getHomeVillage(), village -> {
                if (!village.getBuilding(entity.getTrackedValue(BUILDING)).filter(building -> building.hasResident(entity.getUuid())).isPresent()) {
                    setBuildingId(-1);
                    clearBed();
                } else {
                    //fetch mood from the village storage
                    int mood = village.popMood((ServerWorld)entity.world);
                    if (mood != 0) {
                        entity.getVillagerBrain().modifyMoodValue(mood);
                    }

                    //fetch hearts
                    entity.world.getPlayers().forEach(player -> {
                        int rep = village.popHearts(player);
                        if (rep != 0) {
                            entity.getVillagerBrain().getMemoriesForPlayer(player).modHearts(rep);
                        }
                    });

                    //update the reputation
                    entity.world.getPlayers().forEach(player -> {
                        //currently, only hearts are considered, maybe additional factors can affect that
                        int hearts = entity.getVillagerBrain().getMemoriesForPlayer(player).getHearts();
                        village.setReputation(player, entity, hearts);
                    });
                }
            }, () -> {
                setBuildingId(-1);
                setVillageId(-1);
                clearBed();
            });
        }
    }

    //report potential buildings within this villagers reach
    private void reportBuildings() {
        VillageManager manager = VillageManager.get((ServerWorld)entity.world);

        //fetch all near POIs
        Stream<BlockPos> stream = ((ServerWorld)entity.world).getPointOfInterestStorage().getPositions(
                PointOfInterestType.ALWAYS_TRUE,
                (p) -> !manager.cache.contains(p),
                entity.getBlockPos(),
                48,
                PointOfInterestStorage.OccupationStatus.ANY);

        //check if it is a building
        stream.forEach(manager::reportBuilding);

        // also add tombstones
        GraveyardManager.get((ServerWorld)entity.world)
                .findAll(entity.getBoundingBox().expand(24D), true, true)
                .stream()
                .filter(p -> !manager.cache.contains(p))
                .forEach(manager::reportBuilding);
    }

    private boolean seekNewHome(Village village) {
        //choose the first building available, shuffled
        List<Building> buildings = village.getBuildings().values().stream()
                .filter(Building::hasFreeSpace)
                .collect(Collectors.toList());
        Collections.shuffle(buildings);

        for (Building b : buildings) {
            if (seekNewHomeIn(village, b)) {
                return true;
            }
        }
        return false;
    }

    private boolean seekNewHomeIn(Village village, Building building) {
        //find a free bed within the building
        Optional<BlockPos> bed = building.findOpenBed((ServerWorld)entity.world);

        //sometimes the bed is blocked by someone
        if (bed.isPresent()) {
            setBed(village, building, bed.get());
            return true;
        }
        return false;
    }

    private boolean seekNewHomeIn(Village village, Building building, BlockPos pos) {
        //find a free bed within the building
        Optional<BlockPos> bed = building.findClosestOpenBed((ServerWorld)entity.world, pos);

        //sometimes the bed is blocked by someone
        if (bed.isPresent()) {
            setBed(village, building, bed.get());
            return true;
        }
        return false;
    }

    private void setBed(Village village, Building building, BlockPos bed) {
        //get a bed
        clearBed();

        ServerWorld serverWorld = ((ServerWorld)entity.world);
        PointOfInterestStorage poiManager = serverWorld.getPointOfInterestStorage();

        //check if it is a bed and reserve a ticket
        if (entity.world.getBlockState(bed).isIn(BlockTags.BEDS)) {
            entity.getBrain().remember(MemoryModuleType.HOME, GlobalPos.create(entity.world.getRegistryKey(), bed));
            poiManager.getPosition(PointOfInterestType.HOME.getCompletionCondition(), (p) -> p.equals(bed), bed, 1);
            serverWorld.sendEntityStatus(entity, (byte)14);
        }

        //add to residents
        setBuildingId(building.getId());
        village.addResident(entity, building.getId());
    }

    private void clearBed() {
        ServerWorld serverWorld = ((ServerWorld)entity.world);
        PointOfInterestStorage poiManager = serverWorld.getPointOfInterestStorage();
        entity.getBrain().getOptionalMemory(MemoryModuleType.HOME).ifPresent(globalPos -> {
            if (poiManager.hasTypeAt(PointOfInterestType.HOME, globalPos.getPos())) {
                poiManager.releaseTicket(globalPos.getPos());
            }
        });
    }

    public void setHome(PlayerEntity player) {
        // make sure the building is up-to-date
        VillageManager manager = VillageManager.get((ServerWorld)player.world);
        manager.processBuilding(player.getBlockPos());

        //check if a bed can be found
        Optional<Village> village = manager.findNearestVillage(player);
        if (village.isPresent()) {
            Optional<Building> building = village.get().getBuildingAt(player.getBlockPos());
            if (building.isPresent()) {
                if (seekNewHomeIn(village.get(), building.get(), player.getBlockPos())) {
                    entity.sendChatMessage(player, "interaction.sethome.success");
                } else {
                    entity.sendChatMessage(player, "interaction.sethome.bedfail");
                }
            } else {
                entity.sendChatMessage(player, "interaction.sethome.fail");
            }
        }
    }

    public void goHome(PlayerEntity player) {
        OptionalCompat.ifPresentOrElse(getHome()
                        .filter(p -> p.getDimension() == entity.world.getRegistryKey())
                , home -> {
                    entity.moveTowards(home.getPos());
                    entity.sendChatMessage(player, "interaction.gohome.success");
                }, () -> entity.sendChatMessage(player, "interaction.gohome.fail.nohome"));
    }
}
