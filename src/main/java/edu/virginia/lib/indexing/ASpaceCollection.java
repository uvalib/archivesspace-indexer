package edu.virginia.lib.indexing;

import javax.json.JsonObject;
import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Created by md5wz on 11/9/17.
 */
public class ASpaceCollection extends ASpaceObject {

    private static Pattern ID_PATTERN = Pattern.compile("/?repositories/\\d+/resources/\\d+");

    /**
     * Checks whether a passed id is in the format expected for an ID.  This does not
     * guarantee that it's a resource record identifier, but only that it could be one,
     * and couldn't be another type.
     */
    public static boolean isCorrectIdFormat(final String id) {
        return ID_PATTERN.matcher(id).matches();
    }

    public ASpaceCollection(ArchivesSpaceClient c, String refId) throws IOException {
        super(c, refId);
    }

    @Override
    protected Pattern getRefIdPattern() {
        return ID_PATTERN;
    }

    @Override
    public boolean isShadowed() throws IOException {
        return !isPublished();
    }

    private String assertNotNull(final String value) {
        if (value == null) {
            throw new NullPointerException();
        } else {
            return value;
        }
    }

    public boolean isPublished() {
        JsonObject cm = getRecord().getJsonObject("collection_management");
        boolean published = getRecord().getBoolean("publish");
        boolean collectionManagementCompleted = cm != null && cm.get("processing_status") != null  && "completed".equals(cm.getString("processing_status"));
        boolean hasTopContainers = !getTopContainers().isEmpty();
        boolean hasPublishedDigitalObjects = !getDigitalObjects().isEmpty();

//      boolean result = published && collectionManagementCompleted && (hasTopContainers);
        boolean result = published && collectionManagementCompleted && (hasTopContainers || hasPublishedDigitalObjects);
        return (result);
    }

}
