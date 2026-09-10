package ai.abstraction.submissions.clockwork_bot;

import ai.abstraction.AbstractionLayerAI;
import ai.abstraction.pathfinding.AStarPathFinding;
import ai.core.AI;
import ai.core.ParameterSpecification;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import rts.GameState;
import rts.PhysicalGameState;
import rts.Player;
import rts.PlayerAction;
import rts.units.Unit;
import rts.units.UnitType;
import rts.units.UnitTypeTable;

public class ClockworkBot extends AbstractionLayerAI {

    private UnitTypeTable utt;
    private UnitType workerType;
    private UnitType baseType;
    private UnitType barracksType;
    private UnitType lightType;
    private UnitType rangedType;

    public ClockworkBot(UnitTypeTable a_utt) {
        super(new AStarPathFinding());
        reset(a_utt);
    }

    @Override
    public void reset() {
        super.reset();
    }

    public void reset(UnitTypeTable a_utt) {
        utt = a_utt;
        workerType = utt.getUnitType("Worker");
        baseType = utt.getUnitType("Base");
        barracksType = utt.getUnitType("Barracks");
        lightType = utt.getUnitType("Light");
        rangedType = utt.getUnitType("Ranged");
    }

    @Override
    public AI clone() {
        return new ClockworkBot(utt);
    }

    @Override
    public PlayerAction getAction(int player, GameState gs) {
        PhysicalGameState pgs = gs.getPhysicalGameState();
        Player owner = gs.getPlayer(player);
        int combatCount = countCombat(pgs, player);
        int workerCount = countType(pgs, player, workerType);
        int enemyRanged = countType(pgs, 1 - player, rangedType);
        int enemyHeavy = countType(pgs, 1 - player, utt.getUnitType("Heavy"));
        UnitType preferredCombat = chooseCombatType(pgs, enemyRanged, enemyHeavy);

        for (Unit unit : pgs.getUnits()) {
            if (unit.getPlayer() != player || gs.getActionAssignment(unit) != null) {
                continue;
            }
            if (unit.getType() == baseType) {
                if (owner.getResources() >= workerType.cost && workerCount < workerTarget(pgs)) {
                    train(unit, workerType);
                }
            } else if (unit.getType() == barracksType && owner.getResources() >= preferredCombat.cost) {
                train(unit, preferredCombat);
            }
        }

        List<Unit> workers = new LinkedList<>();
        for (Unit unit : pgs.getUnits()) {
            if (unit.getPlayer() == player && unit.getType() == workerType) {
                workers.add(unit);
            }
        }

        int barracksCount = countType(pgs, player, barracksType);
        int reservedResources = 0;
        List<Integer> reserved = new ArrayList<>();
        for (Unit worker : workers) {
            if (barracksCount == 0 && owner.getResources() >= barracksType.cost + reservedResources) {
                buildIfNotAlreadyBuilding(worker, barracksType, worker.getX(), worker.getY(), reserved, owner, pgs);
                reservedResources += barracksType.cost;
                barracksCount++;
                continue;
            }
            Unit resource = nearestResource(worker, pgs);
            Unit base = nearestOwnedStockpile(worker, pgs, player);
            if (resource != null && base != null) {
                harvest(worker, resource, base);
            }
        }

        for (Unit unit : pgs.getUnits()) {
            if (unit.getPlayer() == player && unit.getType().canAttack
                    && !unit.getType().canHarvest && gs.getActionAssignment(unit) == null) {
                Unit target = bestTarget(unit, pgs, player);
                if (target != null) {
                    attack(unit, target);
                }
            }
        }

        if (combatCount == 0) {
            for (Unit worker : workers) {
                if (getAbstractAction(worker) == null) {
                    Unit target = bestTarget(worker, pgs, player);
                    if (target != null) {
                        attack(worker, target);
                    }
                }
            }
        }
        return translateActions(player, gs);
    }

    private int workerTarget(PhysicalGameState pgs) {
        int area = pgs.getWidth() * pgs.getHeight();
        return area <= 64 ? 3 : area <= 256 ? 5 : 7;
    }

    private UnitType chooseCombatType(PhysicalGameState pgs, int enemyRanged, int enemyHeavy) {
        if (enemyRanged > 0) {
            return lightType;
        }
        if (enemyHeavy > 0 || pgs.getWidth() * pgs.getHeight() >= 256) {
            return rangedType;
        }
        return lightType;
    }

    private int countCombat(PhysicalGameState pgs, int player) {
        int count = 0;
        for (Unit unit : pgs.getUnits()) {
            if (unit.getPlayer() == player && unit.getType().canAttack && !unit.getType().canHarvest) {
                count++;
            }
        }
        return count;
    }

    private int countType(PhysicalGameState pgs, int player, UnitType type) {
        int count = 0;
        if (type == null) {
            return count;
        }
        for (Unit unit : pgs.getUnits()) {
            if (unit.getPlayer() == player && unit.getType() == type) {
                count++;
            }
        }
        return count;
    }

    private Unit nearestResource(Unit from, PhysicalGameState pgs) {
        Unit nearest = null;
        int distance = Integer.MAX_VALUE;
        for (Unit unit : pgs.getUnits()) {
            if (unit.getType().isResource) {
                int current = distance(from, unit);
                if (current < distance) {
                    nearest = unit;
                    distance = current;
                }
            }
        }
        return nearest;
    }

    private Unit nearestOwnedStockpile(Unit from, PhysicalGameState pgs, int player) {
        Unit nearest = null;
        int distance = Integer.MAX_VALUE;
        for (Unit unit : pgs.getUnits()) {
            if (unit.getPlayer() == player && unit.getType().isStockpile) {
                int current = distance(from, unit);
                if (current < distance) {
                    nearest = unit;
                    distance = current;
                }
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
            int priority = unit.getType().isStockpile ? 3 : unit.getType().canHarvest ? 2 : 1;
            int currentDistance = distance(from, unit);
            if (priority < bestPriority || (priority == bestPriority && currentDistance < bestDistance)) {
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
