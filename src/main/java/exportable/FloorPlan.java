package exportable;

import gearth.protocol.HMessage;
import gearth.protocol.HPacket;
import javafx.scene.paint.Color;
import javafx.util.Pair;
import org.json.JSONObject;
import parsers.Inventory;
import utils.Executor;
import utils.Logger;

import java.util.List;
import java.util.Map;

@ExportableInfo(
    Name = "Floorplan",
    JsonTag = "floorPlan"
)
public class FloorPlan extends Exportable {
    public String floorPlan;
    public int doorX;
    public int doorY;
    public int doorDir;

    public FloorPlan(HPacket floorPlanPacket, HPacket doorPacket) {
        this.floorPlan = floorPlanPacket.readString(11);
        this.doorX = doorPacket.readInteger(6);
        this.doorY = doorPacket.readInteger(10);
        this.doorDir = doorPacket.readInteger(14);

        if(this.floorPlan == null || this.floorPlan.isEmpty()) {
            throw new IllegalStateException("floorplan packet has an unexpected layout");
        }
    }

    public FloorPlan(JSONObject floorImport) {
        this.floorPlan = floorImport.getString("floorPlan");
        this.doorX = floorImport.getInt("doorX");
        this.doorY = floorImport.getInt("doorY");
        this.doorDir = floorImport.getInt("doorDir");
    }

    public Pair<Integer, Integer> getOpenSpot(int minSize) {
        if(floorPlan == null || floorPlan.isEmpty()) {
            return new Pair<>(1, 1);
        }

        int size = Math.max(1, minSize);
        String[] splitFloorPlan = floorPlan.split("\r");
        for(int y = 0; y + size <= splitFloorPlan.length; y++) {
            for(int x = 0; x + size <= splitFloorPlan[y].length(); x++) {
                if(splitFloorPlan[y].charAt(x) != 'x') {
                    boolean possible = true;
                    char height = splitFloorPlan[y].charAt(x);
                    for(int i = 0; i < size && possible; i++) {
                        String row = splitFloorPlan[y + i];
                        for(int j = 0; j < size; j++) {
                            if(x + j >= row.length() || row.charAt(x + j) != height) {
                                possible = false;
                                break;
                            }
                        }
                    }
                    if(possible) return new Pair<>(x, y);
                }
            }
        }
        return new Pair<>(1, 1);
    }

    @Override
    public void doImport(Executor executor, List<Exportable> importingStates, Map<String, Exportable> currentStates, Inventory inventory, ProgressListener progressListener) {
        progressListener.setProgress(0d);
        Logger.log(Color.TEAL, "Updating floorplan");
        executor.sendToServer("UpdateFloorProperties", floorPlan, doorX, doorY, doorDir, 0, 0);
        progressListener.setProgress(0.5d);
        executor.awaitPacket(new Executor.AwaitingPacket("GetGuestRoomResult", HMessage.Direction.TOCLIENT, 15000)
                .addConditions(HPacket::readBoolean));
        progressListener.setProgress(1d);
    }
}
