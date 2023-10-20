package edu.virginia.lib.indexing;

import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonValue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import static edu.virginia.lib.indexing.helpers.UvaHelper.extractManifestUrl;

/**
 * A class representing a digita_object in ArchivesSpace.  This class includes helper functions to pull
 * data from the underlying json model.
 */
public class ASpaceDigitalObject extends ASpaceObject {

    private static final Logger LOGGER = LoggerFactory.getLogger(ASpaceDigitalObject.class);

    public ASpaceDigitalObject(ArchivesSpaceClient client, String refId) throws IOException {
        super(client, refId);
    }

    /**
     * @return an empty list because ASpaceDigitalObject objects cannot have children.
     */
    public List<ASpaceArchivalObject> getChildren() throws IOException {
        return Collections.emptyList();
    }

    @Override
    protected Pattern getRefIdPattern() {
        return Pattern.compile("/?repositories/\\d+/digital_objects/\\d+");
    }

    @Override
    public boolean isShadowed() throws IOException {
        return !isPublished();
    }

    @Override
    public boolean isPublished() {
        return getRecord().getBoolean("publish");
    }

    public String getIIIFURL() {
        for (JsonValue v : getRecord().getJsonArray("file_versions")) {
            JsonObject ver = (JsonObject) v;
            try {
                if (ver.getBoolean("publish") && ver.getString("use_statement").startsWith("image-service")) {
                    return extractManifestUrl(ver.getString("file_uri"));
                }
            } catch (Throwable t) {
                LOGGER.warn("Skipping digital content, likely because no use_statement.");
                return null;
            }
        }
        return null;
    }
    
    public String getURLLabel() 
    {
        if (getRecord().getBoolean("publish") && !getRecord().getBoolean("suppressed"))
        {
            JsonArray ver = getRecord().getJsonArray("file_versions");
            JsonObject fileVer = ver != null && ver.size() > 0 ? (JsonObject) ver.get(0) : null;
            String digitalObjectType = getRecord().getString("digital_object_type", null); // "still_image"
            if (digitalObjectType != null && digitalObjectType.startsWith("still_image"))
            {
                return "View Digital Image";
            }
            else if (fileVer != null && fileVer.getString("use_statement", "").startsWith("image-service"))
            {
                String title = getRecord().getString("title", null);
                if ( title != null)
                    return title;
                else 
                    return "View Digital Image";
            }
            else 
            {
                return "Access Digital Object";
            }
        }
        return null;
    }

}
