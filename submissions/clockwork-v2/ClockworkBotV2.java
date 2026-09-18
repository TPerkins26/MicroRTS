package ai.abstraction.submissions.clockwork_v2;

import ai.abstraction.AbstractionLayerAI;
import ai.abstraction.Build;
import ai.abstraction.pathfinding.AStarPathFinding;
import ai.core.AI;
import ai.core.ParameterSpecification;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import rts.GameState;
import rts.PhysicalGameState;
import rts.Player;
import rts.PlayerAction;
import rts.units.Unit;
import rts.units.UnitType;
import rts.units.UnitTypeTable;

public class ClockworkBotV2 extends AbstractionLayerAI {

    private UnitTypeTable utt;
    private UnitType workerType;
    private UnitType baseType;
    private UnitType barracksType;
    private UnitType lightType;
    private long harvesterId = -1;
    private int nextRoleIndex;
    private final Map<Long, Integer> workerRoles = new HashMap<>();

    public ClockworkBotV2(UnitTypeTable a_utt) {
        super(new AStarPathFinding());
        reset(a_utt);
    }

    @Override
    public void reset() {
        super.reset();
        harvesterId = -1;
        nextRoleIndex = 0;
        workerRoles.clear();
    }

    public void reset(UnitTypeTable a_utt) {
        utt = a_utt;
        workerType = utt.getUnitType("Worker");
        baseType = utt.getUnitType("Base");
        barracksType = utt.getUnitType("Barracks");
        lightType = utt.getUnitType("Light");
    }

    @Override
    public AI clone() {
        return new ClockworkBotV2(utt);
    }

    @Override
    public PlayerAction getAction(int player, GameState gs) {
        PhysicalGameState pgs = gs.getPhysicalGameState();
        Player owner = gs.getPlayer(player);
        List<Unit> workers = unitsOfType(pgs, player, workerType);
        int barracksCount = countType(pgs, player, barracksType);
        int enemyWorkers = countType(pgs, 1 - player, workerType);
        int ownCombat = countCombat(pgs, player);

        for (Unit unit : pgs.getUnits()) {
            if (unit.getPlayer() != player || gs.getActionAssignment(unit) != null) {
                continue;
            }
            if (unit.getType() == baseType) {
                int target = barracksCount == 0 ? 1 : workerTarget(pgs);
                if (owner.getResources() >= workerType.cost && workers.size() < target) {
                    train(unit, workerType);
                }
            } else if (unit.getType() == barracksType
                    && owner.getResources() >= lightType.cost) {
                train(unit, lightType);
            }
        }

        workers = unitsOfType(pgs, player, workerType);
        Unit harvester = chooseHarvester(workers, gs, player);
        Unit base = nearestOwnedStockpile(workers.isEmpty() ? null : workers.get(0), pgs, player);
        Unit baseThreat = closestThreatToBase(base, pgs, player, defenseRadius(pgs));
        assignHarvest(harvester, pgs, player);
        List<Integer> reserved = new ArrayList<>();
        boolean buildingBarracks = false;
        for (Unit worker : workers) {
            if (worker != harvester && barracksCount == 0
                && owner.getResources() >= barracksType.cost
                    && !buildingBarracks) {
                buildIfNotAlreadyBuilding(worker, barracksType, worker.getX(), worker.getY(),
                        reserved, owner, pgs);
                buildingBarracks = true;
                barracksCount++;
            }
        }

        for (Unit worker : workers) {
            if (worker == harvester) {
                continue;
            }
            if (getAbstractAction(worker) instanceof Build) {
                continue;
            }
            int roleIndex = roleIndex(worker, workers);
            if (roleIndex % 2 == 0) {
                if (baseThreat != null) {
                    attack(worker, baseThreat);
                } else {
                    moveToForwardDefense(worker, base, pgs, player);
                }
            } else {
                Unit target = bestTarget(worker, pgs, player);
                if (target != null) {
                    attack(worker, target);
                } else {
                    idle(worker);
                }
            }
        }

        for (Unit unit : pgs.getUnits()) {
            if (unit.getPlayer() == player && unit.getType().canAttack
                    && !unit.getType().canHarvest && gs.getActionAssignment(unit) == null) {
                Unit target = baseThreat != null ? baseThreat : bestTarget(unit, pgs, player);
                if (target != null) {
                    attack(unit, target);
                }
            }
        }
        return translateActions(player, gs);
    }

    private void assignHarvest(Unit harvester, PhysicalGameState pgs, int player) {
        if (harvester == null) {
            return;
        }
        Unit resource = nearestResource(harvester, pgs);
        Unit base = nearestOwnedStockpile(harvester, pgs, player);
        if (resource != null && base != null) {
            harvest(harvester, resource, base);
        }
    }

    private int roleIndex(Unit worker, List<Unit> workers, Unit harvester) {
        Integer role = workerRoles.get(worker.getID());
        if (role == null) {
            role = nextRoleIndex++;
            workerRoles.put(worker.getID(), role);
        }
        return role;
    }

    private void moveToForwardDefense(Unit worker, Unit base, PhysicalGameState pgs, int player) {
        Unit enemy = nearestEnemyToBase(base, pgs, player);
        if (base == null || enemy == null) {
            idle(worker);
            return;
        }
        int x = base.getX() + Integer.signum(enemy.getX() - base.getX()) * 2;
        int y = base.getY() + Integer.signum(enemy.getY() - base.getY()) * 2;
        x = Math.max(0, Math.min(pgs.getWidth() - 1, x));
        y = Math.max(0, Math.min(pgs.getHeight() - 1, y));
        move(worker, x, y);
    }

    private Unit chooseHarvester(List<Unit> workers, GameState gs, int player) {
        if (workers.isEmpty()) {
            return null;
        }
        for (Unit worker : workers) {
            if (worker.getID() == harvesterId) {
                return worker;
            }
        }
        harvesterId = workers.get(0).getID();
        return workers.get(0);
    }

    private boolean nearEnemy(Unit from, PhysicalGameState pgs, int player, int range) {
        for (Unit unit : pgs.getUnits()) {
            if (unit.getPlayer() >= 0 && unit.getPlayer() != player
                    && distance(from, unit) <= range) {
                return true;
            }
        }
        return false;
    }

    private Unit closestThreatToBase(Unit base, PhysicalGameState pgs, int player, int radius) {
        if (base == null) {
            return null;
        }
        Unit threat = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Unit unit : pgs.getUnits()) {
            if (unit.getPlayer() >= 0 && unit.getPlayer() != player) {
                int baseDistance = distance(base, unit);
                if (baseDistance <= radius && baseDistance < bestDistance) {
                    threat = unit;
                    bestDistance = baseDistance;
                }
            }
        }
        return threat;
    }

    private Unit nearestEnemyToBase(Unit base, PhysicalGameState pgs, int player) {
        if (base == null) {
            return null;
        }
        Unit nearest = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Unit unit : pgs.getUnits()) {
            if (unit.getPlayer() >= 0 && unit.getPlayer() != player) {
                int currentDistance = distance(base, unit);
                if (currentDistance < bestDistance) {
                    nearest = unit;
                    bestDistance = currentDistance;
                }
            }
        }
        return nearest;
    }

    private int defenseRadius(PhysicalGameState pgs) {
        return 3;
    }

    private int workerTarget(PhysicalGameState pgs) {
        int area = pgs.getWidth() * pgs.getHeight();
        return area <= 64 ? 4 : area <= 256 ? 6 : 8;
    }

    private List<Unit> unitsOfType(PhysicalGameState pgs, int player, UnitType type) {
        List<Unit> result = new LinkedList<>();
        for (Unit unit : pgs.getUnits()) {
            if (unit.getPlayer() == player && unit.getType() == type) {
                result.add(unit);
            }
        }
        return result;
    }

    private int countCombat(PhysicalGameState pgs, int player) {
        int count = 0;
        for (Unit unit : pgs.getUnits()) {
            if (unit.getPlayer() == player && unit.getType().canAttack
                    && !unit.getType().canHarvest) {
                count++;
            }
        }
        return count;
    }

    private int countType(PhysicalGameState pgs, int player, UnitType type) {
        int count = 0;
        for (Unit unit : pgs.getUnits()) {
            if (unit.getPlayer() == player && unit.getType() == type) {
                count++;
            }
        }
        return count;
    }

    private Unit nearestResource(Unit from, PhysicalGameState pgs) {
        if (from == null) {
            return null;
        }
        Unit nearest = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Unit unit : pgs.getUnits()) {
            int currentDistance = distance(from, unit);
            if (unit.getType().isResource && currentDistance < bestDistance) {
                nearest = unit;
                bestDistance = currentDistance;
            }
        }
        return nearest;
    }

    private Unit nearestOwnedStockpile(Unit from, PhysicalGameState pgs, int player) {
        if (from == null) {
            return null;
        }
        Unit nearest = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Unit unit : pgs.getUnits()) {
            int currentDistance = distance(from, unit);
            if (unit.getPlayer() == player && unit.getType().isStockpile
                    && currentDistance < bestDistance) {
                nearest = unit;
                bestDistance = currentDistance;
            }
        }
        return nearest;
    }

    private Unit bestTarget(Unit from, PhysicalGameState pgs, int player) {
        Unit best = null;
        int bestPriority = Integer.MAX_VALUE;
        int bestDistance = Integer.MAX_VALUE;
        for (Unit unit : pgs.getUnits()) {
            if (unit.getPlayer() < 0 || unit.getPlayer() == player) {
                continue;
            }
            int priority = unit.getType().canHarvest ? 1
                    : unit.getType().isStockpile ? 3 : 2;
            int currentDistance = distance(from, unit);
            if (priority < bestPriority || (priority == bestPriority
                    && currentDistance < bestDistance)) {
                best = unit;
                bestPriority = priority;
                bestDistance = currentDistance;
            }
        }
        return best;
    }

    private int distance(Unit first, Unit second) {
        return Math.abs(first.getX() - second.getX()) + Math.abs(first.getY() - second.getY());
    }

    @Override
    public List<ParameterSpecification> getParameters() {
        return new ArrayList<>();
    }
}
