package edu.virginia.lib.indexing;

import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonValue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.virginia.lib.indexing.tools.IndexRecords;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Created by md5wz on 12/18/17.
 */
public class ASpaceAccession extends ASpaceObject {

    private static final Logger LOGGER = LoggerFactory.getLogger(ASpaceAccession.class);

    public ASpaceAccession(ArchivesSpaceClient aspaceClient, final String accessionId) throws IOException {
        super(aspaceClient, accessionId);
    }

    /**
     * @return an empty list because ASpaceAccession objects cannot have children.
     */
    public List<ASpaceArchivalObject> getChildren() throws IOException {
        return Collections.emptyList();
    }

    @Override
    protected Pattern getRefIdPattern() {
        return Pattern.compile("/?repositories/\\d+/accessions/\\d+");
    }

    public boolean isShadowed() throws IOException {
        boolean published = isPublished();
        boolean hasPublishedCollectionRecord = hasPublishedCollectionRecord();
        LOGGER.debug("isPublished = "+published);
        LOGGER.debug("hasPublishedCollectionRecord = "+hasPublishedCollectionRecord);
        boolean visible = published && ! hasPublishedCollectionRecord;
        LOGGER.debug("isShadowed = "+ !(visible));
        return !(visible);
    }

    public boolean isPublished() {
        JsonObject record = getRecord();
        boolean published = record.getBoolean("publish");
        boolean hasTopContainers = !getTopContainers().isEmpty();
        boolean hasPublishedDigitalObjects = !getDigitalObjects().isEmpty();
        LOGGER.debug("publish value = "+published);
        LOGGER.debug("hasTopContainers = "+hasTopContainers);
        LOGGER.debug("isPublished = "+ (published && (hasTopContainers || hasPublishedDigitalObjects)));
  //      return  published && (hasTopContainers);//|| hasPublishedDigitalObjects);
        return  published && (hasTopContainers || hasPublishedDigitalObjects);
    }

    public boolean hasPublishedCollectionRecord() throws IOException {
        final JsonArray relatedResources = getRecord().getJsonArray("related_resources");
        if (relatedResources.size() == 0) {
            return false;
        }
        String uri = getRecord().getString("uri");
        for (JsonValue v : relatedResources) {
            ASpaceCollection col = new ASpaceCollection(c, ((JsonObject) v).getString("ref"));
            try
            {
                if (col.isPublished()) {
                    return true;
                }
            }
            catch (RuntimeException e)
            {
                if (e.getMessage().startsWith("Unable to get") && IndexRecords.knownBadRefs.contains(" "+col.refId+" "))
                {
                    LOGGER.info("Known Bad Resource "+ col.refId+ " Referenced as Related Resource from "+ this.refId);
                    return false;
                }
                else
                {
                    LOGGER.error("Non-existant Resource "+ col.refId+ " Referenced as Related Resource from "+ this.refId);
                    return false;
                }
            }
        }
        return false;
    }

}
