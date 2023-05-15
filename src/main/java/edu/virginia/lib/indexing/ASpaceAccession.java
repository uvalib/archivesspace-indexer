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
        boolean published = getRecord().getBoolean("publish");
        boolean topContainersEmpty = getTopContainers().isEmpty();
        LOGGER.debug("publish value = "+published);
        LOGGER.debug("topContainersEmpty = "+topContainersEmpty);
        LOGGER.debug("isPublished = "+ (published && !topContainersEmpty));
        return  published && !topContainersEmpty;
    }

    public boolean hasPublishedCollectionRecord() throws IOException {
        final JsonArray relatedResources = getRecord().getJsonArray("related_resources");
        if (relatedResources.size() == 0) {
            return false;
        }
        for (JsonValue v : relatedResources) {
            ASpaceCollection col = new ASpaceCollection(c, ((JsonObject) v).getString("ref"));
            if (col.isPublished()) {
                return true;
            }
        }
        return false;
    }

}
