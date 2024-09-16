package edu.virginia.lib.indexing;

import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonValue;

import edu.virginia.lib.indexing.helpers.KeyValues;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class ASpaceArchivalObject extends ASpaceObject {

    Map<String, KeyValues> valueMap = null;
    
    public ASpaceArchivalObject(ArchivesSpaceClient aspaceClient, final String refId, final JsonObject tree) throws IOException {
        super(aspaceClient, refId);
        if (!tree.getString("node_type").equals("archival_object")) {
            throw new IllegalArgumentException("Unexpected node_type \"" + tree.getString("node_type") + "\"");
        }
        this.tree = tree;
    }
    
    public ASpaceArchivalObject(ArchivesSpaceClient aspaceClient, final String refId, KeyValues ... keyValues ) throws IOException {
        super(aspaceClient, refId);
        this.tree = null;
        valueMap = new LinkedHashMap<>();
        for (KeyValues kv : keyValues) {
            String key = kv.getKey();
            valueMap.put(key, kv);
        }
    }

    @Override
    protected Pattern getRefIdPattern() {
        return Pattern.compile("/?repositories/\\d+/archival_objects/\\d+");
    }

    @Override
    public boolean isShadowed() throws IOException {
        throw new UnsupportedOperationException();
    }

    public boolean isPublished() {
        return getRecord().getBoolean("publish");
    }

    @Override
    public String getId() {
        return null;
    }

    @Override
    public String getCallNumber() {
        return null;
    }
    
    public List<String> getRecordValues(String key) {
        List<String> values = new ArrayList<>();
        if (this.tree != null) {
            JsonArray ja = getRecord().getJsonArray(key);
            JsonValue[] jav = ja.toArray(new JsonValue[0]);
            for (JsonValue jv : jav) {
                values.add(jv.toString());
            }
            return values;
        }
        else {
            if (this.valueMap != null && valueMap.containsKey(key)) {
                return (valueMap.get(key).getValues());
            }
        }
        return null;
    }

}
