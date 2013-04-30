package au.com.codeka.warworlds.server.ctrl;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import au.com.codeka.common.protobuf.Messages;
import au.com.codeka.warworlds.server.RequestException;
import au.com.codeka.warworlds.server.data.SqlStmt;
import au.com.codeka.warworlds.server.data.Transaction;
import au.com.codeka.warworlds.server.model.ScoutReport;

public class ScoutReportController {
    private DataBase db;

    public ScoutReportController() {
        db = new DataBase();
    }
    public ScoutReportController(Transaction trans) {
        db = new DataBase(trans);
    }

    public void saveScoutReport(ScoutReport scoutReport) throws RequestException {
        try {
            db.saveScoutReport(scoutReport);
        } catch(Exception e) {
            throw new RequestException(e);
        }
    }

    public List<ScoutReport> getScoutReports(int starID, int empireID) throws RequestException {
        try {
            return db.getScoutReports(starID, empireID);
        } catch(Exception e) {
            throw new RequestException(e);
        }
    }

    private static class DataBase extends BaseDataBase {
        public DataBase() {
            super();
        }
        public DataBase(Transaction trans) {
            super(trans);
        }

        public void saveScoutReport(ScoutReport scoutReport) throws Exception {
            String sql = "INSERT INTO scout_reports (star_id, empire_id, date, report) VALUES (?, ?, ?, ?)";
            try (SqlStmt stmt = prepare(sql, Statement.RETURN_GENERATED_KEYS)) {
                stmt.setInt(1, Integer.parseInt(scoutReport.getStarKey()));
                stmt.setInt(2, Integer.parseInt(scoutReport.getEmpireKey()));
                stmt.setDateTime(3, scoutReport.getReportDate());

                Messages.ScoutReport.Builder scout_report_pb = Messages.ScoutReport.newBuilder();
                scoutReport.toProtocolBuffer(scout_report_pb);
                stmt.setBlob(4, scout_report_pb.build().toByteArray());

                stmt.update();
                scoutReport.setID(stmt.getAutoGeneratedID());
            }
        }

        public List<ScoutReport> getScoutReports(int starID, int empireID) throws Exception {
            String sql = "SELECT * FROM scout_reports WHERE star_id = ? and empire_id = ? ORDER BY date DESC";
            try (SqlStmt stmt = prepare(sql)) {
                stmt.setInt(1, starID);
                stmt.setInt(2, empireID);
                ResultSet rs = stmt.select();

                ArrayList<ScoutReport> reports = new ArrayList<ScoutReport>();
                while (rs.next()) {
                    reports.add(new ScoutReport(rs));
                }
                return reports;
            }
        }
    }
}
