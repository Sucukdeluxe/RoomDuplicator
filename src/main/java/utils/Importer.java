package utils;

import exportable.*;
import extension.RoomDuplicator;
import gearth.protocol.HPacket;
import javafx.scene.control.Alert;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import org.json.JSONException;
import org.json.JSONObject;
import parsers.Inventory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

public class Importer {
    private final RoomDuplicator extension;
    private final Executor executor;
    private JSONObject importingJson;

    public Importer(RoomDuplicator extension) {
        this.extension = extension;
        this.executor = new Executor(extension);
    }

    public void chooseImportFile() {
        try {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Open Import File");
            fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("RoomJSON (*.roomJson)", "*.roomJson"));
            fileChooser.setInitialDirectory(new File(Cacher.dir));

            File selectedFile = fileChooser.showOpenDialog(extension.getPrimaryStage());

            if (selectedFile != null) {
                String input = new String(Files.readAllBytes(selectedFile.toPath()));
                try {
                    importingJson = new JSONObject(input);
                    extension.importPath.setText(selectedFile.getPath());
                    updateRadioButtons();
                } catch (JSONException e) {
                    Alert errorAlert = new Alert(Alert.AlertType.ERROR);
                    errorAlert.setHeaderText("Input not valid");
                    errorAlert.setContentText("The imported file is not a valid RoomJSON");
                    errorAlert.showAndWait();

                    e.printStackTrace();
                }
            }
        } catch(IOException e) {
            e.printStackTrace();
        }
    }

    private void updateRadioButtons() {
        extension.importRoomSettings.setDisable(!importingJson.has(RoomData.class.getAnnotation(ExportableInfo.class).JsonTag()));
        extension.importRoomSettings.setSelected(importingJson.has(RoomData.class.getAnnotation(ExportableInfo.class).JsonTag()));

        extension.importFloorplan.setDisable(!importingJson.has(FloorPlan.class.getAnnotation(ExportableInfo.class).JsonTag()));
        extension.importFloorplan.setSelected(importingJson.has(FloorPlan.class.getAnnotation(ExportableInfo.class).JsonTag()));

        extension.importFloorItems.setDisable(!importingJson.has(FloorItems.class.getAnnotation(ExportableInfo.class).JsonTag()));
        extension.importFloorItems.setSelected(importingJson.has(FloorItems.class.getAnnotation(ExportableInfo.class).JsonTag()));

        extension.importWallItems.setDisable(!importingJson.has(WallItems.class.getAnnotation(ExportableInfo.class).JsonTag()));
        extension.importWallItems.setSelected(importingJson.has(WallItems.class.getAnnotation(ExportableInfo.class).JsonTag()));

        extension.importButton.setDisable(false);
    }

    public void runImport() {
        // TODO

        Map<String, HPacket> packets = Utils.requestRoomEntryPackets(executor);
        if(packets.values().stream().noneMatch(Objects::nonNull) || packets.get("GetGuestRoomResult") == null || packets.get("RoomVisualizationSettings") == null) {
            Logger.log(Color.RED, "No room data captured yet - walk out and back in to the room, then import again!");
            return;
        }

        Map<String, Exportable> currentStates = Utils.getLiveExportablesFromPackets(extension, packets);

        List<Exportable> exportables;
        try {
            exportables = getExportablesFromJson(importingJson);
        } catch (Throwable t) {
            t.printStackTrace();
            Logger.log(Color.RED, "Import file could not be read: " + t);
            return;
        }

        if(exportables.isEmpty()) {
            Logger.log(Color.RED, "Nothing selected to import!");
            return;
        }

        Set<Class<?>> selected = exportables.stream().map(Exportable::getClass).collect(Collectors.toSet());
        boolean needsEjectall = selected.contains(FloorPlan.class) || selected.contains(FloorItems.class);
        boolean needsInventory = selected.contains(FloorItems.class) || selected.contains(WallItems.class);

        if((needsEjectall && currentStates.get("FloorPlan") == null)
                || (selected.contains(FloorItems.class) && currentStates.get("FloorItems") == null)
                || (selected.contains(WallItems.class) && currentStates.get("WallItems") == null)) {
            Logger.log(Color.RED, "Target room not fully captured - walk out and back in to the room, then import again!");
            return;
        }

        Inventory inv = Utils.requestInventory(executor);
        if(inv == null && needsInventory) {
            Logger.log(Color.RED, "Could not read your inventory - import stopped, the room was left untouched");
            return;
        }

        if(needsEjectall) {
            if(!Utils.requestEjectall(executor)) {
                Logger.log(Color.RED, "Ejectall rejected, import stopped!");
                return;
            }

            Inventory afterEjectall = Utils.requestInventory(executor);
            if(afterEjectall != null) {
                inv = afterEjectall;
            } else if(needsInventory) {
                Logger.log(Color.ORANGE, "Could not re-read the inventory after clearing the room - continuing with the earlier snapshot");
            }
        }

        for(Exportable exportable : exportables) {
            String name = exportable.getClass().getAnnotation(ExportableInfo.class).Name();
            try {
                exportable.doImport(executor, exportables, currentStates, inv, this::setProgress);
                Logger.log(Color.SEAGREEN, name + " imported!");
            } catch (Throwable t) {
                t.printStackTrace();
                Logger.log(Color.RED, name + " failed: " + t);
            }
        }
    }

    private List<Exportable> getExportablesFromJson(JSONObject importingJson) {
        List<Exportable> exportables = new ArrayList<>();

        if(importingJson.has(RoomData.class.getAnnotation(ExportableInfo.class).JsonTag()) && extension.importRoomSettings.isSelected()) {
            exportables.add(new RoomData(importingJson.getJSONObject(RoomData.class.getAnnotation(ExportableInfo.class).JsonTag())));
        }
        if(importingJson.has(FloorPlan.class.getAnnotation(ExportableInfo.class).JsonTag()) && extension.importFloorplan.isSelected()) {
            exportables.add(new FloorPlan(importingJson.getJSONObject(FloorPlan.class.getAnnotation(ExportableInfo.class).JsonTag())));
        }
        if(importingJson.has(WallItems.class.getAnnotation(ExportableInfo.class).JsonTag()) && extension.importWallItems.isSelected()) {
            exportables.add(new WallItems(importingJson.getJSONArray(WallItems.class.getAnnotation(ExportableInfo.class).JsonTag())));
        }
        if(importingJson.has(FloorItems.class.getAnnotation(ExportableInfo.class).JsonTag()) && extension.importFloorItems.isSelected()) {
            exportables.add(new FloorItems(importingJson.getJSONArray(FloorItems.class.getAnnotation(ExportableInfo.class).JsonTag())));
        }

        return exportables;
    }

    public void setProgress(double p) {
        javafx.application.Platform.runLater(() -> this.extension.importProgress.setProgress(p));
    }
}
