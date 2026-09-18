package ai.abstraction.submissions.clockwork_v3;

import ai.abstraction.AbstractionLayerAI;
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

public class ClockworkBotV3 extends AbstractionLayerAI {

    private UnitTypeTable utt;
    private UnitType workerType;
    private UnitType baseType;
    private long harvesterId = -1;
    private int nextRoleIndex;
    private double attackMomentum;
    private boolean defensivePosture;
    private final Map<Long, Integer> workerRoles = new HashMap<>();

    public ClockworkBotV3(UnitTypeTable a_utt) {
        super(new AStarPathFinding());
        reset(a_utt);
    }

    @Override
    public void reset() {
        super.reset();
        harvesterId = -1;
        nextRoleIndex = 0;
        attackMomentum = 0.0;
        defensivePosture = false;
        workerRoles.clear();
    }

    public void reset(UnitTypeTable a_utt) {
        utt = a_utt;
        workerType = utt.getUnitType("Worker");
        baseType = utt.getUnitType("Base");
    }

    public AI clone() {
        return new ClockworkBotV3(utt);
    }
    @Override
    public PlayerAction getAction(int player, GameState gs) {
        PhysicalGameState pgs = gs.getPhysicalGameState();
        Player owner = gs.getPlayer(player);
        List<Unit> workers = unitsOfType(pgs, player, workerType);
        int ownCombat = countCombat(pgs, player);
        int enemyCombat = countCombat(pgs, 1 - player);
        int enemyWorkers = countType(pgs, 1 - player, workerType);
        boolean hasNumericalAdvantage = ownCombat > enemyCombat;
        int workerLead = workers.size() - enemyWorkers;
        boolean enemyWorkerRush = enemyWorkers >= 4 && enemyCombat == 0;

        for (Unit unit : pgs.getUnits()) {
            if (unit.getPlayer() != player || gs.getActionAssignment(unit) != null) {
                continue;
            }
            if (unit.getType() == baseType) {
                if (owner.getResources() >= workerType.cost) {
                    train(unit, workerType);
                }
            }
        }

        workers = unitsOfType(pgs, player, workerType);
        Unit harvester = chooseHarvester(workers, gs, player);
        if (harvester != null && owner.getResources() == 0
                && harvester.getResources() == 0) {
            harvester = null;
            harvesterId = -1;
        }
        Unit base = nearestOwnedStockpile(workers.isEmpty() ? null : workers.get(0), pgs, player);
        Unit baseThreat = closestThreatToBase(base, pgs, player, defenseRadius(pgs));
        Unit interceptThreat = closestWorkerThreatToBase(base, pgs, player,
            workerDefenseRadius(pgs));
        if (interceptThreat == null) {
            interceptThreat = closestThreatToBase(base, pgs, player, workerDefenseRadius(pgs));
        }
        Unit focusTarget = enemyWorkerRush ? closestWorkerThreatToBase(base, pgs, player,
                workerDefenseRadius(pgs)) : null;
        assignHarvest(harvester, pgs, player);
        boolean underThreat = enemyWithinDefenseRange(base, pgs, player);
        boolean enemyCollapsed = countType(pgs, 1 - player, baseType) == 0
            && enemyWorkers <= 2;
        double economyLead = workerLead
            + (2 * countType(pgs, player, baseType))
            - (2 * countType(pgs, 1 - player, baseType));
        double attackScore = (0.9 * workerLead)
            + (0.5 * economyLead)
            + (owner.getResources() >= workerType.cost * 2 ? 1.5 : 0.0)
            + (enemyCollapsed ? 3.0 : 0.0)
                + (underThreat ? -1.5 : 0.0)
                + (enemyWorkerRush && workerLead < 0 ? -1.0 : 0.0);
        attackMomentum = (0.70 * attackMomentum) + (0.30 * attackScore);
        if (underThreat || attackMomentum <= -1.0) {
            defensivePosture = true;
        } else if (!enemyWorkerRush && attackMomentum >= 1.0) {
            defensivePosture = false;
        }
        boolean defensiveOpening = defensivePosture;
        Unit interceptDefender = defensiveOpening
            ? nearestDefender(interceptThreat, workers, harvester) : null;
        Unit forwardDefender = defensiveOpening
            ? nearestDefender(base, workers, harvester, interceptDefender) : null;

        for (Unit worker : workers) {
            if (worker == harvester) {
                continue;
            }
            if (baseThreat != null) {
                attack(worker, baseThreat);
            } else if (worker == interceptDefender) {
                attack(worker, interceptThreat);
            } else if (worker == forwardDefender) {
                moveToForwardDefense(worker, base, pgs, player);
            } else {
                Unit target = focusTarget != null
                        ? focusTarget : bestAttackTarget(worker, pgs, player);
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
                if (baseThreat != null) {
                    attack(unit, baseThreat);
                } else if (hasNumericalAdvantage) {
                    Unit target = bestAttackTarget(unit, pgs, player);
                    if (target != null) {
                        attack(unit, target);
                    } else {
                        idle(unit);
                    }
                } else {
                    moveToForwardDefense(unit, base, pgs, player);
                }
            }
        }
        return translateActions(player, gs);
    }

    private void assignHarvest(Unit harvester, PhysicalGameState pgs, int player) {
        if (harvester == null) {
            return;
        }
        Unit base = nearestOwnedStockpile(harvester, pgs, player);
        if (base == null) {
            return;
        }
        Unit resource = nearestResource(base, pgs);
        if (resource != null) {
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
        int xDistance = Math.abs(enemy.getX() - base.getX());
        int yDistance = Math.abs(enemy.getY() - base.getY());
        int lineDistance = Math.min(workerDefenseRadius(pgs) - 1,
                Math.max(xDistance, yDistance));
        int x = base.getX() + Integer.signum(enemy.getX() - base.getX()) * lineDistance;
        int y = base.getY() + Integer.signum(enemy.getY() - base.getY()) * lineDistance;
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

    private Unit closestWorkerThreatToBase(Unit base, PhysicalGameState pgs, int player,
            int radius) {
        if (base == null) {
            return null;
        }
        Unit threat = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Unit unit : pgs.getUnits()) {
            if (unit.getPlayer() >= 0 && unit.getPlayer() != player
                    && unit.getType().canHarvest) {
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

    private Unit nearestEnemy(Unit from, PhysicalGameState pgs, int player) {
        Unit nearest = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Unit unit : pgs.getUnits()) {
            if (unit.getPlayer() < 0 || unit.getPlayer() == player) {
                continue;
            }
            int currentDistance = distance(from, unit);
            if (currentDistance < bestDistance) {
                nearest = unit;
                bestDistance = currentDistance;
            }
        }
        return nearest;
    }

    private Unit bestAttackTarget(Unit from, PhysicalGameState pgs, int player) {
        Unit best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Unit unit : pgs.getUnits()) {
            if (unit.getPlayer() < 0 || unit.getPlayer() == player) {
                continue;
            }
            int score = distance(from, unit) * 10 + unit.getHitPoints() * 2;
            if (unit.getType() == workerType) {
                score -= 2;
            }
            if (unit.getType().canAttack) {
                score += 5;
            }
            if (unit.getType() == baseType) {
                score -= 6;
            }
            if (score < bestScore) {
                best = unit;
                bestScore = score;
            }
        }
        return best;
    }

    private Unit nearestDefender(Unit target, List<Unit> workers, Unit harvester) {
        return nearestDefender(target, workers, harvester, null);
    }

    private Unit nearestDefender(Unit target, List<Unit> workers, Unit harvester,
            Unit excluded) {
        if (target == null) {
            return null;
        }
        Unit nearest = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Unit worker : workers) {
            if (worker == harvester || worker == excluded) {
                continue;
            }
            int currentDistance = distance(worker, target);
            if (currentDistance < bestDistance) {
                nearest = worker;
                bestDistance = currentDistance;
            }
        }
        return nearest;
    }

    private int defenseRadius(PhysicalGameState pgs) {
        return 3;
    }

    private int workerDefenseRadius(PhysicalGameState pgs) {
        return Math.max(defenseRadius(pgs) + 3,
                Math.max(pgs.getWidth(), pgs.getHeight()) / 2);
    }

    private boolean enemyWithinDefenseRange(Unit base, PhysicalGameState pgs, int player) {
        if (base == null) {
            return false;
        }
        int threshold = Math.max(pgs.getWidth(), pgs.getHeight()) / 2;
        for (Unit unit : pgs.getUnits()) {
            if (unit.getPlayer() >= 0 && unit.getPlayer() != player
                    && distance(base, unit) <= threshold) {
                return true;
            }
        }
        return false;
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
