package au.com.codeka.warworlds.server.events;

import java.sql.ResultSet;
import java.util.ArrayList;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import au.com.codeka.common.model.BaseCombatReport;
import au.com.codeka.common.model.BaseFleet;
import au.com.codeka.common.model.BaseStar;
import au.com.codeka.common.model.ShipDesign;
import au.com.codeka.common.model.ShipEffect;
import au.com.codeka.common.model.Simulation;
import au.com.codeka.common.protobuf.Messages;
import au.com.codeka.warworlds.server.Event;
import au.com.codeka.warworlds.server.RequestException;
import au.com.codeka.warworlds.server.ctrl.SituationReportController;
import au.com.codeka.warworlds.server.ctrl.StarController;
import au.com.codeka.warworlds.server.data.DB;
import au.com.codeka.warworlds.server.data.SqlStmt;
import au.com.codeka.warworlds.server.model.CombatReport;
import au.com.codeka.warworlds.server.model.Fleet;
import au.com.codeka.warworlds.server.model.ScoutReport;
import au.com.codeka.warworlds.server.model.Star;

public class FleetMoveCompleteEvent extends Event {
    private final Logger log = LoggerFactory.getLogger(FleetMoveCompleteEvent.class);

    @Override
    public String getNextEventTimeSql() {
        return "SELECT MIN(eta) FROM fleets";
    }

    @Override
    public void process() {
        String sql = "SELECT id, star_id, target_star_id FROM fleets WHERE eta < ?";
        try (SqlStmt stmt = DB.prepare(sql)) {
            stmt.setDateTime(1, DateTime.now().plusSeconds(10)); // anything in the next 10 seconds is a candidate
            ResultSet rs = stmt.select();
            while (rs.next()) {
                int fleetID = rs.getInt(1);
                int srcStarID = rs.getInt(2);
                int destStarID = rs.getInt(3);

                Star srcStar = null;
                Star destStar = null;
                for (BaseStar baseStar : new StarController().getStars(new int[] {srcStarID, destStarID})) {
                    Star star = (Star) baseStar;
                    if (star.getID() == srcStarID) {
                        srcStar = star;
                    }
                    if (star.getID() == destStarID) {
                        destStar = star;
                    }
                }

                processFleet(fleetID, srcStar, destStar);
            }
        } catch(Exception e) {
            log.error("Error processing fleet-move event!", e);
            // TODO: errors?
        }
    }

    private void processFleet(int fleetID, Star srcStar, Star destStar) throws RequestException {
        Simulation sim = new Simulation();
        sim.simulate(srcStar);
        sim.simulate(destStar);

        // remove the fleet from the source star and add it to the dest star
        Fleet fleet = null;
        for (BaseFleet baseFleet : srcStar.getFleets()) {
            fleet = (Fleet) baseFleet;
            if (fleet.getID() == fleetID) {
                srcStar.getFleets().remove(fleet);
                destStar.getFleets().add(fleet);

                fleet.idle();
                break;
            }
        }
        if (fleet == null) {
            return;
        }

        // fire off the effects to let them know we've arrived
        fireFleetArrivedEvents(destStar, fleet);

        // simulate the destination star again, in case there's any combat
        sim.simulate(destStar);

        new StarController().update(srcStar);
        new StarController().update(destStar);

        Messages.SituationReport.Builder sitrep_pb = Messages.SituationReport.newBuilder();
        sitrep_pb.setEmpireKey(fleet.getEmpireKey());
        sitrep_pb.setReportTime(DateTime.now().getMillis() / 1000);
        sitrep_pb.setStarKey(destStar.getKey());
        sitrep_pb.setPlanetIndex(-1);
        Messages.SituationReport.MoveCompleteRecord.Builder move_complete_pb = Messages.SituationReport.MoveCompleteRecord.newBuilder();
        move_complete_pb.setFleetKey(fleet.getKey());
        move_complete_pb.setFleetDesignId(fleet.getDesignID());
        move_complete_pb.setNumShips(fleet.getNumShips());
        for (ScoutReport scoutReport : destStar.getScoutReports()) {
            move_complete_pb.setScoutReportKey(scoutReport.getKey());
        }
        sitrep_pb.setMoveCompleteRecord(move_complete_pb);
        if (destStar.getCombatReport() != null && isFleetInCombatReport(fleet.getKey(), (CombatReport) destStar.getCombatReport())) {
            Messages.SituationReport.FleetUnderAttackRecord.Builder fleet_under_attack_pb = Messages.SituationReport.FleetUnderAttackRecord.newBuilder();
            fleet_under_attack_pb.setCombatReportKey(destStar.getCombatReport().getKey());
            fleet_under_attack_pb.setFleetDesignId(fleet.getDesignID());
            fleet_under_attack_pb.setFleetKey(fleet.getKey());
            fleet_under_attack_pb.setNumShips(fleet.getNumShips());
            sitrep_pb.setFleetUnderAttackRecord(fleet_under_attack_pb);
        }

        new SituationReportController().saveSituationReport(sitrep_pb.build());
    }

    private boolean isFleetInCombatReport(String fleetKey, CombatReport combatReport) {
        for (BaseCombatReport.CombatRound round : combatReport.getCombatRounds()) {
            for (BaseCombatReport.FleetSummary fleetSummary : round.getFleets()) {
                if ((fleetSummary.getFleetKey() == null && fleetKey == null) ||
                    (fleetSummary.getFleetKey() != null && fleetKey != null && fleetSummary.getFleetKey().equals(fleetKey))) {
                    return true;
                }
            }
        }
        return false;
    }

    public static void fireFleetArrivedEvents(Star star, Fleet newFleet) {
        ShipDesign fleetDesign = newFleet.getDesign();
        ArrayList<ShipEffect> effects = fleetDesign.getEffects(ShipEffect.class);
        for (ShipEffect effect : effects) {
            effect.onArrived(star, newFleet);
        }

        for (BaseFleet existingBaseFleet : star.getFleets()) {
            Fleet existingFleet = (Fleet) existingBaseFleet;
            if (existingFleet.getID() == newFleet.getID()) {
                continue;
            }

            fleetDesign = existingFleet.getDesign();
            effects = fleetDesign.getEffects(ShipEffect.class);
            for (ShipEffect effect : effects) {
                effect.onOtherArrived(star, existingFleet, newFleet);
            }

        }
    }
}
