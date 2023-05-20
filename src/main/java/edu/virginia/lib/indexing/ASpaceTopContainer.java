package edu.virginia.lib.indexing;

import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonValue;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A class representing a top_container in ArchivesSpace.  This class includes helper functions to pull
 * data from the underlying json model.
 *
 * Created by md5wz on 6/14/18.
 */
public class ASpaceTopContainer extends ASpaceObject {    
    private String containerCallNumber;
    private String barcode;
    private String location;
    private String locationRef;    

    public ASpaceTopContainer(ArchivesSpaceClient client, String refId) throws IOException {
        super(client, refId);
        barcode = null;
        containerCallNumber = null;
        locationRef = null;
    }

    public ASpaceTopContainer(ArchivesSpaceClient client, String refId, String barcodeStr, String containerCallnumber, String currentLocationRef) throws IOException {
        super(client, refId);
        barcode = barcodeStr;
        this.containerCallNumber = containerCallnumber;
        locationRef = currentLocationRef != null ? currentLocationRef : "";
    }

    @Override
    protected JsonObject getRecord() {
        return super.getRecord();
    }

    /**
     * @return an empty list because ASpaceTopContainer objects cannot have children.
     */
    public List<ASpaceArchivalObject> getChildren() throws IOException {
        return Collections.emptyList();
    }

    @Override
    protected Pattern getRefIdPattern() {
        return Pattern.compile("/?repositories/\\d+/top_containers/\\d+");
    }

    @Override
    public boolean isShadowed() throws IOException {
        return !isPublished();
    }

    @Override
    public boolean isPublished() {
        return getRecord().getBoolean("is_linked_to_published_record");
    }

    /**
     * Gets the call number for the container, which is based off of the
     * supplied call number of the collection or accession to which the
     * container belongs.
     */
    public String getContainerCallNumber(final String owningCallNumber) {
        if (containerCallNumber == null) {
            containerCallNumber = getRecord().getString("display_string");
        }
        return owningCallNumber + " " + containerCallNumber;
    }
  
    /**
     * Gets the current location.
     * @throws IOException 
     */
    public String getCurrentLocation() throws IOException {
        if (location == null) {
            if (locationRef == null) {
                JsonArray loc = getRecord().getJsonArray("container_locations");
                for (JsonValue v : loc) {
                    JsonObject l = (JsonObject) v;
                    if (l.getString("status").equals("current")) {
                        locationRef = l.getString("ref");
                    }
                }
            }
            if (locationRef != null && !locationRef.equals("")) {
                location = getLocationTitle(locationRef);
            }
            if (location == null) {
                location = "";
            }
        }
        return location;
    }

    private String getLocationTitle(String locationRef) throws IOException {
        location = c.resolveReference(locationRef).getString("title");
        return(location);
    }

    /**
     * Gets a barcode if one exists, otherwise returns a compatible identifier
     * derived from the top container reference id.
     */
    public String getBarcode() {
        if (this.barcode != null && !this.barcode.equals("")) {
            return this.barcode;
        }
        if (this.barcode == null) {
            JsonValue barcodeJV = getRecord().get("barcode");
            if (barcodeJV != null) {
                barcode = getRecord().getString("barcode");
            }
            if (barcode != null) {
                barcode = barcode.trim();
            }
        }
        if (this.barcode == null || this.barcode.equals("")) {
            Matcher m = Pattern.compile("/repositories/(\\d+)/top_containers/(\\d+)").matcher(this.refId);
            if (m.matches()) {
                barcode = "AS:" + m.group(1) + "C" + m.group(2);
            } else {
                barcode = "UNKNOWN";
            }
        }
        return (barcode);
    }

    public String getLocation() {
//        JsonValue room = getRecord().get("room");
//        if (room == null) {
            return "STACKS";
//        } else {
//            return room.toString();
//        }
    }
}
