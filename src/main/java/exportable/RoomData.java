package exportable;

import gearth.protocol.HPacket;
import javafx.event.ActionEvent;
import javafx.scene.control.*;
import javafx.scene.paint.Color;
import javafx.stage.StageStyle;
import org.json.JSONObject;
import parsers.Inventory;
import utils.Executor;
import utils.Logger;

import java.util.List;
import java.util.Map;

@ExportableInfo(
    Name = "Room Settings",
    JsonTag = "roomData"
)
public class RoomData extends Exportable {
    public String password;
    public final String name, description, ownerName;
    public final int id, ownerId, lockType, maxUsers, tradingMode, category, freeflow, chatSize, scrollMethod, chatDistance, floodMode, muteRights, kickRights, banRights, wallThickness, floorThickness;
    public final boolean wallsHidden, allowPets;

    private static final class SafeReader {
        private final HPacket packet;
        private int failures = 0;

        SafeReader(HPacket packet) {
            this.packet = packet;
        }

        int readInt(int fallback) {
            if(packet == null) return fallback;
            try {
                return packet.readInteger();
            } catch (Throwable t) {
                failures++;
                return fallback;
            }
        }

        String readString(String fallback) {
            if(packet == null) return fallback;
            try {
                return packet.readString();
            } catch (Throwable t) {
                failures++;
                return fallback;
            }
        }

        boolean readBoolean(boolean fallback) {
            if(packet == null) return fallback;
            try {
                return packet.readBoolean();
            } catch (Throwable t) {
                failures++;
                return fallback;
            }
        }
    }

    public RoomData(HPacket roomPacket, HPacket visualizationPacket) {
        roomPacket.setReadIndex(7);
        SafeReader room = new SafeReader(roomPacket);

        this.id = room.readInt(0);
        this.name = room.readString("Unknown");
        this.ownerId = room.readInt(0);
        this.ownerName = room.readString("");
        this.lockType = room.readInt(0);

        room.readInt(0);

        this.maxUsers = room.readInt(0);
        this.description = room.readString("");
        this.tradingMode = room.readInt(0);

        room.readInt(0);
        room.readInt(0);

        this.category = room.readInt(0);

        int tagCount = room.readInt(0);
        for(int i = 0; i < tagCount && i < 64; i ++) {
            room.readString("");
        }

        final int THUMBNAIL_BITMASK = 1;
        final int GROUPDATA_BITMASK = 2;
        final int ROOMAD_BITMASK = 4;
        final int SHOWOWNER_BITMASK  = 8;
        final int ALLOWPETS_BITMASK = 16;
        final int DISPLAYROOMENTRYAD_BITMASK = 32;

        int multiUse = room.readInt(0);

        // skip Official room pic if present
        if((multiUse & THUMBNAIL_BITMASK) > 0) {
            room.readString("");
        }

        // Skip group info if present
        if((multiUse & GROUPDATA_BITMASK) > 0) {
            room.readInt(0);
            room.readString("");
            room.readString("");
        }

        // Skip event info if present
        if((multiUse & ROOMAD_BITMASK) > 0) {
            room.readString("");
            room.readString("");
            room.readInt(0);
        }

        this.allowPets = (multiUse & ALLOWPETS_BITMASK) > 0;

        room.readInt(0);

        this.muteRights = room.readInt(0);
        this.kickRights = room.readInt(0);
        this.banRights = room.readInt(0);

        room.readBoolean(false);

        this.freeflow = room.readInt(0);
        this.chatSize = room.readInt(0);
        this.scrollMethod = room.readInt(0);
        this.chatDistance = room.readInt(50);
        this.floodMode = room.readInt(0);

        if(visualizationPacket != null) {
            visualizationPacket.resetReadIndex();
        }
        SafeReader visualization = new SafeReader(visualizationPacket);
        this.wallsHidden = visualization.readBoolean(false);
        this.wallThickness = visualization.readInt(0);
        this.floorThickness = visualization.readInt(0);

        if(room.failures + visualization.failures > 0) {
            Logger.log(Color.ORANGE, "Room settings: " + (room.failures + visualization.failures)
                    + " field(s) missing in this client's packets, defaults used");
        }
    }

    public RoomData(JSONObject roomDataImport) {
        this.id = roomDataImport.getInt("id");
        this.name = roomDataImport.getString("name");
        this.ownerId = roomDataImport.getInt("ownerId");
        this.ownerName = roomDataImport.getString("ownerName");
        this.lockType = roomDataImport.getInt("lockType");
        if(this.lockType == 2) {
            this.password = roomDataImport.getString("password");
        }
        this.maxUsers = roomDataImport.getInt("maxUsers");
        this.description = roomDataImport.getString("description");
        this.tradingMode = roomDataImport.getInt("tradingMode");
        this.category = roomDataImport.getInt("category");

        this.allowPets = roomDataImport.getBoolean("allowPets");

        this.muteRights = roomDataImport.getInt("muteRights");
        this.kickRights = roomDataImport.getInt("kickRights");
        this.banRights = roomDataImport.getInt("banRights");
        this.freeflow = roomDataImport.getInt("freeflow");
        this.chatSize = roomDataImport.getInt("chatSize");
        this.scrollMethod = roomDataImport.getInt("scrollMethod");
        this.chatDistance = roomDataImport.getInt("chatDistance");
        this.floodMode = roomDataImport.getInt("floodMode");
        this.wallsHidden = roomDataImport.getBoolean("wallsHidden");
        this.wallThickness = roomDataImport.getInt("wallThickness");
        this.floorThickness = roomDataImport.getInt("floorThickness");
    }

    @Override
    public Object export(ProgressListener progressListener) {
        JSONObject export = (JSONObject) super.export(progressListener);
        if(export.getInt("lockType") == 2 && this.password == null) {
            TextInputDialog dialog = new TextInputDialog();
            dialog.setHeaderText("Locktype is set to password, enter a new room password below or select a different locktype!");
            dialog.setContentText("Password:");
            dialog.setTitle("Locktype");
            dialog.initStyle(StageStyle.UNDECORATED);

            ButtonType openButton = new ButtonType("Open", ButtonBar.ButtonData.CANCEL_CLOSE);
            ButtonType doorbellButton = new ButtonType("Doorbell", ButtonBar.ButtonData.CANCEL_CLOSE);
            ButtonType invisibleButton = new ButtonType("Invisible", ButtonBar.ButtonData.CANCEL_CLOSE);
            ButtonType setPasswordButton = new ButtonType("SetPassWord", ButtonBar.ButtonData.RIGHT);

            dialog.getDialogPane().getButtonTypes().clear();
            dialog.getDialogPane().getButtonTypes()
                    .addAll(openButton,
                            doorbellButton,
                            invisibleButton,
                            setPasswordButton);

            dialog.getDialogPane().lookupButton(setPasswordButton).setDisable(true);

            dialog.getEditor().textProperty().addListener((observable, oldValue, newValue) -> dialog.getDialogPane().lookupButton(setPasswordButton).setDisable(newValue.isEmpty()));

            dialog.getDialogPane().lookupButton(openButton).addEventHandler(ActionEvent.ACTION, event -> export.put("lockType", 0));
            dialog.getDialogPane().lookupButton(doorbellButton).addEventHandler(ActionEvent.ACTION, event -> export.put("lockType", 1));
            dialog.getDialogPane().lookupButton(invisibleButton).addEventHandler(ActionEvent.ACTION, event -> export.put("lockType", 3));
            dialog.getDialogPane().lookupButton(setPasswordButton).addEventHandler(ActionEvent.ACTION, event -> export.put("password", dialog.getEditor().getText()));

            dialog.showAndWait();
        }
        return export;
    }

    @Override
    public void doImport(Executor executor, List<Exportable> importingStates, Map<String, Exportable> currentStates, Inventory inventory, ProgressListener progressListener) {
        Logger.log(Color.TEAL, "Update room settings");
        executor.sendToServer("SaveRoomSettings",
                ((RoomData) currentStates.get("RoomData")).id, name, description, lockType, password != null ? password : "",
                maxUsers, category, /*tagCount*/ 0, tradingMode, allowPets, /*allowFoodConsume*/ false, /*allowWalkThrough*/ true,
                wallsHidden, wallThickness, floorThickness, muteRights, kickRights, banRights,
                freeflow, chatSize, scrollMethod, chatDistance, floodMode, /*showRoomByFurniInNav*/ false);
    }
}
