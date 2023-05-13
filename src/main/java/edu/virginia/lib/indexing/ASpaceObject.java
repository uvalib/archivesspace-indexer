package edu.virginia.lib.indexing;

import edu.virginia.lib.indexing.helpers.JsonHelper;
import edu.virginia.lib.indexing.helpers.SolrHelper;
import edu.virginia.lib.indexing.helpers.StringNaturalCompare;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClients;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrDocument;
import org.marc4j.MarcStreamWriter;
import org.marc4j.MarcXmlWriter;
import org.marc4j.marc.DataField;
import org.marc4j.marc.MarcFactory;
import org.marc4j.marc.Record;
import org.marc4j.marc.Subfield;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonString;
import javax.json.JsonValue;
import javax.json.stream.JsonParsingException;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static edu.virginia.lib.indexing.helpers.JsonHelper.hasValue;
import static edu.virginia.lib.indexing.helpers.SolrHelper.getIdFromRef;
import static edu.virginia.lib.indexing.helpers.SolrHelper.getSolrOutputFile;
import static edu.virginia.lib.indexing.helpers.UvaHelper.normalizeLocation;

/**
 * An abstract base class that encapsulates logic to pull data from the ArchivesSpace REST API.
 */
public abstract class ASpaceObject {

    final static public String RIGHTS_WRAPPER_URL = "http://rightswrapper2.lib.virginia.edu:8090/rights-wrapper/";

    private static final Logger LOGGER = LoggerFactory.getLogger(ASpaceObject.class);
    
    final static boolean DEBUG = false;
    
    protected ArchivesSpaceClient c;

    protected String refId;

    private JsonObject record;
    
    protected JsonObject tree;

    protected List<ASpaceTopContainer> containers;

    protected List<ASpaceDigitalObject> digitalObjects;

//    private List<ASpaceArchivalObject> children;

    public ASpaceObject(ArchivesSpaceClient aspaceClient, final String refId) throws IOException {
        if (!getRefIdPattern().matcher(refId).matches()) {
            throw new IllegalArgumentException(refId + " is not an " + this.getClass().getSimpleName());
        }
        this.c = aspaceClient;
        this.refId = refId;
    }

    protected JsonObject getRecord() {
        if (record == null) {
            try {
                record = c.resolveReference(refId);
//                if (DEBUG) System.out.println(this.record);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return record;
    }

    /**
     * Only for special cases where the entire JSON has already been loaded and no reference to the
     * client is needed.  Any subclass calling this constructor must override every method whose
     * default implementation expects 'c' t be non-null.
     */
    protected ASpaceObject(JsonObject record, JsonObject tree) {
        this.record = record;
        this.tree = tree;
    }

    public static ASpaceObject parseObject(final ArchivesSpaceClient client, final String refId) throws IOException {
        if (refId.contains("/accessions/")) {
            return new ASpaceAccession(client, refId);
        } else if (refId.contains("/resources/")) {
            return new ASpaceCollection(client, refId);
        } else if (refId.contains("/top_containers/")) {
            return new ASpaceTopContainer(client, refId);
        } else {
            throw new RuntimeException("Unable to guess resource type from refID! (" + refId + ")");
        }
    }

    protected abstract Pattern getRefIdPattern();

    public abstract boolean isShadowed() throws IOException;

    public abstract boolean isPublished();

//    /**
//     * Gets all children (nested components) for this ASpaceObject.  Subclasses that don't/can't
//     * have nested components should return an empty list.
//     */
//    public List<ASpaceArchivalObject> getChildren() throws IOException {
//        if (children == null) {
//            children = new ArrayList<>();
//            final JsonObject treeObj = getTree();
//            if (treeObj != null) {
//                final JsonArray jsonChildren = tree.getJsonArray("children");
//                if (jsonChildren != null) {
//                    for (JsonValue c : jsonChildren) {
//                        final JsonObject child = (JsonObject) c;
//                        children.add(new ASpaceArchivalObject(this.c, child.getString("record_uri"), child));
//                    }
//                }
//            }
//        }
//        return children;
//    }

    public List<ASpaceDigitalObject> getDigitalObjects()  {
        if (digitalObjects == null) {
            LinkedHashSet<String> digitalObjectsSet = new LinkedHashSet<>();
            digitalObjects = new ArrayList<>();
            Iterator<SolrDocument> docs;
            try {
                docs = SolrHelper.getRecordsForQuery(c.getSolrUrl(), getDigitalObjectQuery(), "uri,digital_object_uris");
                while (docs.hasNext()) {
                    SolrDocument d = docs.next();
                    Collection<Object> uriobjs =  d.getFieldValues("digital_object_uris");
                    if (uriobjs !=  null && uriobjs.size() > 0) {
                        for (Object uriobj : uriobjs) {
                            digitalObjectsSet.add(uriobj.toString());
                        }
                    }
                }
                for (String ref : digitalObjectsSet) {
                    this.digitalObjects.add(new ASpaceDigitalObject(c, ref));
                }
            }
            catch (SolrServerException e) {
                throw new RuntimeException(e);
            }
            catch (IOException e)  {
                throw new RuntimeException(e);
            }
        }
        return digitalObjects;
    }

    protected String getDigitalObjectQuery() {
//      primary_type:"archival_object"  AND 
//      ancestors:"/repositories/3/resources/488" AND 
//      repository:"/repositories/3" AND 
//      digital_object_uris:* AND 
//      publish:"true"
        String uri = getRecord().getString("uri");
        String query = "";
        if (uri.contains("resources")) {
            JsonValue repositoryJV = getRecord().get("repository");
            String repository = null;
            if (repositoryJV != null && repositoryJV.getValueType() == JsonValue.ValueType.OBJECT) {
                repository = ((JsonObject)repositoryJV).getString("ref");
            }
            query = "primary_type:\"archival_object\" AND  ancestors:\"" + uri + "\" AND repository:\"" + repository + "\"" +
                            " AND digital_object_uris:* AND publish:\"true\"";
        }
        else if (uri.contains("accessions")) {
            query = "id:\""+uri+"\" AND digital_object_uris:* AND publish:\"true\"";
        }
        return(query);
    }

    public List<ASpaceTopContainer> getTopContainers() {
        if (containers == null) {
            containers = new ArrayList<>();
            Iterator<SolrDocument> updated;
            try {
                updated = SolrHelper.getRecordsForQuery(c.getSolrUrl(), getTopContainerQuery(), "uri,barcode_u_sstr,display_string,location_uri_u_sstr,collection_identifier_stored_u_sstr");
                while (updated.hasNext()) {
                    SolrDocument d = updated.next();
                    Object uriobj =  d.getFirstValue("uri");
                    Object barcode = d.getFirstValue("barcode_u_sstr");
                    String barcodeStr = (barcode != null) ? barcode.toString() : "";
                    Object containerCallNumber = d.getFirstValue("display_string");
                    String containerCallNumberStr = containerCallNumber != null ? containerCallNumber.toString() : null;
                    Object currentLocationRef = d.getFirstValue("location_uri_u_sstr");
                    String currentLocationRefStr = currentLocationRef != null ? currentLocationRef.toString() : null;
                    this.containers.add(new ASpaceTopContainer(c, uriobj.toString(), barcodeStr, containerCallNumberStr, currentLocationRefStr));
                }
            }
            catch (SolrServerException e) {
                throw new RuntimeException(e);
            }
            catch (IOException e)  {
                throw new RuntimeException(e);
            }
        }
        return containers;
    }

    private String getTopContainerQuery()
    {
//      primary_type:"top_container"  AND  
//      collection_uri_u_sstr:"/repositories/3/resources/488"
        String uri = getRecord().getString("uri");
        String query = "primary_type:\"top_container\" AND  collection_uri_u_sstr:\"" + uri + "\"";
        return(query);
    }

//    private void parseInstances() {
//        if (containers == null || digitalObjects == null) {
//            containers = new ArrayList<>();
//            digitalObjects = new ArrayList<>();
//            try {
//                Set<String> containers = new HashSet<>();
//                Set<String> dos = new HashSet<>();
//                collectInstanceRefs(containers, dos);
//                for (String ref : containers) {
//                    this.containers.add(new ASpaceTopContainer(c, ref));
//                }
//                for (String ref : dos) {
//                    this.digitalObjects.add(new ASpaceDigitalObject(c, ref));
//                }
//            } catch (IOException e) {
//                throw new RuntimeException(e);
//            }
//        }
//    }

//    /**
//     * Adds all the container refs and digital object refs for this node to the passed
//     * sets and recurses to the published children.
//     */
//    protected void collectInstanceRefs(final Set<String> containerRefs, Set<String> doRefs) throws IOException {
//        final JsonValue instances = getRecord().get("instances");
//        if (instances != null && instances.getValueType() == JsonValue.ValueType.ARRAY) {
//            for (JsonValue i : (JsonArray) instances) {
//                final JsonObject instance = (JsonObject) i;
//                if (!instance.getString("instance_type").equals("digital_object")) {
//                    containerRefs.add(instance.getJsonObject("sub_container").getJsonObject("top_container").getString("ref"));
//                } else {
//                    doRefs.add(instance.getJsonObject("digital_object").getString("ref"));
//                }
//            }
//        }
//
//        // recurse to children
//        for (ASpaceArchivalObject child : getChildren()) {
//            if (child.isPublished()) {
//                child.collectInstanceRefs(containerRefs, doRefs);
//            }
//        }
//    }

    public int getLockVersion() {
        return getRecord().getInt("lock_version");
    }

    /**
     * Gets a solr-ready identifier for the resource that comes from the ASpace ID.
     */
    public String getId() {
        return getCallNumber().replace("-", "_").replace("/", "").replace(" ", "").toUpperCase();
    }

    public String getTitle() {
        return getRecord().getString("title");
    }

    public String getCallNumber() {
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < 6; i++) {
            if (getRecord().get("id_" + i) != null) {
                if (sb.length() > 0) {
                    sb.append("-");
                }
                sb.append(getRecord().getString("id_" + i).trim());
            }
        }
        return sb.toString();
    }

    public File generateSolrAddDoc(final File outputDir, final String dbHost, final String dbUser, final String dbPassword) throws IOException, XMLStreamException, SQLException {
        final String shortRefId = getIdFromRef(getRecord().getString("uri"));
        final String callNumber = getCallNumber();
        final String title = getRecord().getString("title");

        XMLOutputFactory xmlOutputFactory = XMLOutputFactory.newFactory();
        final File outputFile = getSolrOutputFile(outputDir, getRecord().getString("uri"));
        outputFile.getParentFile().mkdirs();
        XMLStreamWriter xmlOut = xmlOutputFactory.createXMLStreamWriter(new FileOutputStream(outputFile));
        xmlOut.writeStartDocument("UTF-8", "1.0");
        xmlOut.writeCharacters("\n");
        xmlOut.writeStartElement("add");
        xmlOut.writeCharacters("  ");
        xmlOut.writeStartElement("doc");
        xmlOut.writeCharacters("\n");

        addField(xmlOut, "id", shortRefId);
        String hid = getId();
        // Despite it's name, "alternate_id_facet" currently must only be an alternate id that represents a
        // distinct digital object for which there's an IIIF manifest, rights_wrapper_url, etc.
        //
        //if (isUniqueVirgoId(hid)) {
        //    addField(xmlOut, "alternate_id_facet", hid);
        //}
        addField(xmlOut, "aspace_version_facet", String.valueOf(getLockVersion()));
        addField(xmlOut, "call_number_facet", callNumber);
        addField(xmlOut, "main_title_display", title);
        addField(xmlOut, "title_text", title);
        addField(xmlOut, "source_facet", "ArchivesSpace");
        addField(xmlOut, "format_facet", "Manuscript/Archive");
        final boolean shadowed = isShadowed();
        addField(xmlOut, "shadowed_location_facet", shadowed ? "HIDDEN" : "VISIBLE");
        
        if (!shadowed) {

            // TODO: get this from the data
            //addRightsFields("http://rightsstatements.org/vocab/InC-EDU/1.0/", xmlOut, id, tracksysDbHost, tracksysDbUsername, tracksysDbPassword);

            // TODO: do something with finding aid status

            final String library = getLibrary(getRecord());
            addField(xmlOut, "library_facet", library);

            // TODO location_facet

            // subjects
            final JsonValue subjects = getRecord().get("subjects");
            if (subjects != null && subjects.getValueType() == JsonValue.ValueType.ARRAY) {
                for (JsonValue sub : (JsonArray) subjects) {
                    final String ref = ((JsonObject) sub).getString("ref");
                    final JsonObject subject = c.resolveReference(ref);
                    // TODO: break up these subjects
                    if (subject.getBoolean("publish")) {
                        addField(xmlOut, "subject_facet", subject.getString("title"));
                        addField(xmlOut, "subject_text", subject.getString("title"));
                    }
                }
            }

            // extents
            final JsonValue extents = getRecord().get("extents");
            if (extents != null && extents.getValueType() == JsonValue.ValueType.ARRAY) {
                for (JsonValue extent : (JsonArray) extents) {
                    JsonObject e = (JsonObject) extent;

                    StringBuffer extentString = new StringBuffer();
                    extentString.append(e.getString("number"));
                    extentString.append(" ");
                    final String type = e.getString("extent_type");
                    extentString.append(type.replace("_", " "));
                    if (e.get("container_summary") != null) {
                        extentString.append(" (" + e.getString("container_summary") + ")");
                    }
                    addField(xmlOut, "extent_display", extentString.toString());
                }
            }

            // dates
            boolean sortDateSet = false;
            final JsonValue dates = getRecord().get("dates");
            if (dates != null && dates.getValueType() == JsonValue.ValueType.ARRAY) {
                for (JsonValue date : (JsonArray) dates) {
                    try {
                        final JsonObject dateObj = (JsonObject) date;
                        if (hasValue(dateObj, "expression")) {
                            final String dateStr = ((JsonObject) date).getString("expression");
                            int year = -1;
                            if (dateStr.matches("\\d\\d\\d\\d")) {
                                year = Integer.parseInt(dateStr);
                            } else if (dateStr.matches("\\d\\d\\d\\d-\\d\\d\\d\\d")) {
                                year = Integer.parseInt(dateStr.substring(5));
                            }
                            if (year != 0) {
                                if (!sortDateSet) {
                                    addField(xmlOut, "date_multisort_i", String.valueOf(year));
                                    sortDateSet = true;
                                }
                                final int yearsAgo = Calendar.getInstance().get(Calendar.YEAR) - year;
                                if (yearsAgo > 50) {
                                    addField(xmlOut, "published_date_facet", "More than 50 years ago");
                                }
                                if (yearsAgo <= 50) {
                                    addField(xmlOut, "published_date_facet", "Last 50 years");
                                }
                                if (yearsAgo <= 10) {
                                    addField(xmlOut, "published_date_facet", "Last 10 years");
                                }
                                if (yearsAgo <= 3) {
                                    addField(xmlOut, "published_date_facet", "Last 3 years");
                                }
                                if (yearsAgo <= 1) {
                                    addField(xmlOut, "published_date_facet", "Last 12 months");
                                }
                            } else {
                                throw new RuntimeException("Cannot parse date! (" + dateStr + ")");
                            }
                            addField(xmlOut, "date_display", dateStr);
                        } else if (hasValue(dateObj, "begin") && hasValue(dateObj, "end")) {
                            final String begin = ((JsonObject) date).getString("begin");
                            final String end = ((JsonObject) date).getString("end");
                            if (begin != null && end != null) {
                                addField(xmlOut, "date_display", begin + "-" + end);
                            }
                        }
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }

            // access restrctions note
            final JsonValue restrictions = getRecord().get("access_restrictions_note");
            if (restrictions != null) {
                addField(xmlOut, "access_restrictions_display", restrictions.toString());
            }
            
            // linked agents
            final JsonValue agents = getRecord().get("linked_agents");
            if (agents != null && agents.getValueType() == JsonValue.ValueType.ARRAY) {
                for (JsonValue agentLink : (JsonArray) agents) {
                    final String ref = ((JsonObject) agentLink).getString("ref");
                    final String role = ((JsonObject) agentLink).getString("role");
                    final JsonObject agent = c.resolveReference(ref);
                    try {
                        if (agent.getBoolean("publish")) {
                            if (role.equals("creator")) {
                                final String name = agent.getString("title");
                                addField(xmlOut, "author_facet", name);
                                addField(xmlOut, "author_text", name);
                            }
                        }
                    } catch (NullPointerException e) {
                        // TODO: do something better than skipping it
                    }
                }
            }

            // Top Containers
            JsonArrayBuilder containersBuilder = Json.createArrayBuilder();
            List<ASpaceTopContainer> containers = new ArrayList<>(getTopContainers());
            // System.err.println("Pre-Sort");
            // for (ASpaceTopContainer container : containers) {
            //     System.err.println(container.getContainerCallNumber(getCallNumber()));
            // }
            Collections.sort(containers, new Comparator<ASpaceTopContainer>() {
                @Override
                public int compare(ASpaceTopContainer o1, ASpaceTopContainer o2) {
                	StringNaturalCompare comp = new StringNaturalCompare(); 
                	return comp.compare(o1.getContainerCallNumber(""), o2.getContainerCallNumber(""));
                }
            });
            // System.err.println("Post-Sort");
            // for (ASpaceTopContainer container : containers) {
            //     System.err.println(container.getContainerCallNumber(getCallNumber()));
            // }

            for (ASpaceTopContainer container : containers) {
                JsonObjectBuilder b = Json.createObjectBuilder();
                b.add("library", library);
                b.add("location", container.getLocation());
                b.add("call_number", container.getContainerCallNumber(getCallNumber()));
                b.add("barcode", container.getBarcode());
                b.add("special_collections_location", container.getCurrentLocation());
                containersBuilder.add(b.build());
            }
            addField(xmlOut, "special_collections_holding_display", containersBuilder.build().toString());


            // Digital Objects
            int manifestsIncluded = 0;
            if (getDigitalObjects().size() <= 5) {
                for (ASpaceDigitalObject digitalObject : getDigitalObjects()) {
                    if (digitalObject.getIIIFURL() != null) {
                        try {
                            addDigitalImages(digitalObject.getIIIFURL(), xmlOut, manifestsIncluded == 0, dbHost, dbUser, dbPassword);
                            manifestsIncluded++;
                        } catch (IOException ex) {
                            System.err.println("Unable to fetch manifest: " + digitalObject.getIIIFURL());
                        }
                    }
                }
            }
            if (manifestsIncluded > 0) {
                addField(xmlOut, "feature_facet", "iiif");
                addField(xmlOut, "format_facet", "Online");
            } else {
                addField(xmlOut, "thumbnail_url_display", "http://iiif.lib.virginia.edu/iiif/static:6/full/!115,125/0/default.jpg");
            }

            // Despite it's name, "alternate_id_facet" currently must only be an alternate id that represents a
            // distinct digital object for which there's an IIIF manifest, rights_wrapper_url, etc.
            //
            // related accessions (use for alternate ids)
            //final JsonValue accessions = getRecord().get("related_accessions");
            //if (accessions != null && accessions.getValueType() == JsonValue.ValueType.ARRAY) {
            //    for (JsonValue a : (JsonArray) accessions) {
            //        final String ref = ((JsonObject) a).getString("ref");
            //        final ASpaceAccession accession = new ASpaceAccession(c, ref);
            //        addField(xmlOut, "alternate_id_facet", accession.getId());
            //    }
            //}

            // notes (right now, we only include the scope notes)
            final JsonValue notes = getRecord().get("notes");
            if (notes != null && notes.getValueType() == JsonValue.ValueType.ARRAY) {
                for (JsonValue n : (JsonArray) notes) {
                    JsonObject note = (JsonObject) n;
                    if (note.getBoolean("publish")) {
                        JsonArray subnotes = note.getJsonArray("subnotes");
                        if (subnotes != null) {
                            StringBuffer noteText = new StringBuffer();
                            for (int i = 0; i < subnotes.size(); i++) {
                                JsonObject subnote = subnotes.getJsonObject(i);
                                if (subnote.getBoolean("publish") && subnote.get("content") != null) {
                                    if (noteText.length() > 0) {
                                        noteText.append("\n");
                                    }
                                    noteText.append(subnote.getString("content"));
                                }
                            }
                            if (noteText.length() > 0) {
                                if (note.getString("type").equals("scopecontent")) {
                                    addField(xmlOut, "note_display", noteText.toString());
                                }
                                addField(xmlOut, "note_text", noteText.toString());
                            }
                        }
                    }
                }
            }
        }

        if (getRecord().get("content_description") != null) {
            final String noteText = getRecord().getString("content_description");
            addField(xmlOut, "note_text", noteText.toString());
            addField(xmlOut, "note_display", noteText.toString());
        }



        addField(xmlOut, "online_url_display", "https://archives.lib.virginia.edu" + getRecord().getString("uri"));

        // A feature_facet is needed for proper display in Virgo.
        addField(xmlOut, "feature_facet", "suppress_endnote_export");
        addField(xmlOut, "feature_facet", "suppress_refworks_export");
        addField(xmlOut, "feature_facet", "suppress_ris_export");

        xmlOut.writeCharacters("  ");
        xmlOut.writeEndElement(); // doc
        xmlOut.writeCharacters("\n");
        xmlOut.writeEndElement(); // add

        xmlOut.close();

        return outputFile;

    }

    public File generateV4SolrAddDoc(final File outputDir, final String dbHost, final String dbUser, final String dbPassword) throws IOException, XMLStreamException, SQLException {
        final String shortRefId = getIdFromRef(getRecord().getString("uri"));
        final String callNumber = getCallNumber().replaceFirst("ms ","MS_");
        final String title = getRecord().getString("title");

        XMLOutputFactory xmlOutputFactory = XMLOutputFactory.newFactory();
        final File outputFile = getSolrOutputFile(outputDir, getRecord().getString("uri"));
        outputFile.getParentFile().mkdirs();
        XMLStreamWriter xmlOut = xmlOutputFactory.createXMLStreamWriter(new FileOutputStream(outputFile));
        String path = outputFile.getAbsolutePath();
        xmlOut.writeStartDocument("UTF-8", "1.0");
        xmlOut.writeCharacters("\n");
        xmlOut.writeStartElement("add");
        xmlOut.writeCharacters("  ");
        xmlOut.writeStartElement("doc");
        xmlOut.writeCharacters("\n");

        addField(xmlOut, "id", shortRefId);
        String hid = getId();
        // Despite it's name, "alternate_id_facet" currently must only be an alternate id that represents a
        // distinct digital object for which there's an IIIF manifest, rights_wrapper_url, etc.
        //
        //if (isUniqueVirgoId(hid)) {
        //    addField(xmlOut, "alternate_id_facet", hid);
        //}
        addField(xmlOut, "aspace_version_f", String.valueOf(getLockVersion()));
        addField(xmlOut, "call_number_tsearch_stored", callNumber);
        addField(xmlOut, "mss_work_key_sort", callNumber);
        addField(xmlOut, "work_title3_key_ssort", callNumber);
        addField(xmlOut, "work_title2_key_ssort", callNumber);

        addField(xmlOut, "title_tsearch_stored", title);
        addField(xmlOut, "full_title_tsearchf_stored", title);
        addField(xmlOut, "source_f_stored", "ArchivesSpace");
        addField(xmlOut, "pool_f", "archival");
        addField(xmlOut, "data_source_f_stored", "archivespace");
        addField(xmlOut, "circulating_f", "false");
        addField(xmlOut, "format_f_stored", "Manuscript/Archive");
        addField(xmlOut, "uva_availability_f_stored", "Online");
        addField(xmlOut, "anon_availability_f_stored", "Online");

        final boolean shadowed = isShadowed();
        addField(xmlOut, "shadowed_location_f", shadowed ? "HIDDEN" : "VISIBLE");
        LOGGER.info(shortRefId+" = "+(shadowed ? "HIDDEN" : "VISIBLE"));
        if (!shadowed) {

            // TODO: get this from the data
            //addRightsFields("http://rightsstatements.org/vocab/InC-EDU/1.0/", xmlOut, id, tracksysDbHost, tracksysDbUsername, tracksysDbPassword);

            // TODO: do something with finding aid status

            String library = getLibrary(getRecord());
            if (library.equals("Law School")) {
            	library = "Law";
            }
            addField(xmlOut, "library_f_stored", library);
            if (library.equals("Special Collections")) {
                addField(xmlOut, "source_f_stored", library);            	
            }

            // TODO location_facet

            // subjects
            final JsonValue subjects = getRecord().get("subjects");
            if (subjects != null && subjects.getValueType() == JsonValue.ValueType.ARRAY) {
                for (JsonValue sub : (JsonArray) subjects) {
                    final String ref = ((JsonObject) sub).getString("ref");
                    final JsonObject subject = c.resolveReference(ref);
                    // TODO: break up these subjects
                    if (subject.getBoolean("publish")) {
                        addField(xmlOut, "subject_tsearchf_stored", subject.getString("title"));
                    }
                }
            }

            // extents
            final JsonValue extents = getRecord().get("extents");
            if (extents != null && extents.getValueType() == JsonValue.ValueType.ARRAY) {
                for (JsonValue extent : (JsonArray) extents) {
                    JsonObject e = (JsonObject) extent;

                    StringBuffer extentString = new StringBuffer();
                    extentString.append(e.getString("number"));
                    extentString.append(" ");
                    final String type = e.getString("extent_type");
                    extentString.append(type.replace("_", " "));
                    if (e.get("container_summary") != null) {
                        extentString.append(" (" + e.getString("container_summary") + ")");
                    }
                    addField(xmlOut, "extent_tsearch_stored", extentString.toString());
                }
            }

            // dates
            boolean sortDateSet = false;
            final JsonValue dates = getRecord().get("dates");
            boolean dateDone = false;
            if (dates != null && dates.getValueType() == JsonValue.ValueType.ARRAY) {
                for (JsonValue date : (JsonArray) dates) {
                    try {
                        if (dateDone) continue;
                        final JsonObject dateObj = (JsonObject) date;
                        if (hasValue(dateObj, "expression")) {
                            final String dateStr = ((JsonObject) date).getString("expression");
                            int year = -1;
                            if (dateStr.matches("\\d\\d\\d\\d")) {
                                year = Integer.parseInt(dateStr);
                                addField(xmlOut, "published_date", String.valueOf(year)+"-01-01T00:00:00Z" );
                                addField(xmlOut, "published_daterange", String.valueOf(year));
                            } 
                            else if (dateStr.matches("\\d\\d\\d\\d-\\d\\d\\d\\d")) {
                                int yearEnd = Integer.parseInt(dateStr.substring(5));
                                int yearBegin = Integer.parseInt(dateStr.substring(0, 4));
                                addField(xmlOut, "published_date", String.valueOf(yearEnd)+"-01-01T00:00:00Z" );
                                if (yearEnd > yearBegin) addField(xmlOut, "published_daterange", "["+yearBegin+ " TO " +yearEnd+"]");
                            }
                            addField(xmlOut, "published_display_a", dateStr);
                            dateDone = true;
                        } 
                        else if (hasValue(dateObj, "begin") && hasValue(dateObj, "end")) {
                            final String begin = ((JsonObject) date).getString("begin");
                            final String end = ((JsonObject) date).getString("end");
                            if (begin.matches("\\d\\d\\d\\d-\\d\\d-\\d\\d") && end.matches("\\d\\d\\d\\d-\\d\\d-\\d\\d")) {
                            	addField(xmlOut, "published_display_a", begin + " - " + end);
                                addField(xmlOut, "published_date", end+"T00:00:00Z" );
                                if (end.compareTo(begin) > 0) addField(xmlOut, "published_daterange", "["+begin+ " TO " +end+"]");
                                else if (end.compareTo(begin) == 0)  addField(xmlOut, "published_daterange", ""+end);
                                dateDone = true;
                            }
                            else if (begin.matches("\\d\\d\\d\\d-\\d\\d") && end.matches("\\d\\d\\d\\d-\\d\\d")) {
                            	addField(xmlOut, "published_display_a", begin + " - " + end);
                                addField(xmlOut, "published_date", end+"-01T00:00:00Z" );
                                if (end.compareTo(begin) > 0) addField(xmlOut, "published_daterange", "["+begin+ " TO " +end+"]");
                                else if (end.compareTo(begin) == 0)  addField(xmlOut, "published_daterange", ""+end);
                                dateDone = true;
                            }
                            else {
                            	if (begin != null && end != null) {
                            		addField(xmlOut, "published_display_a", begin.replace("-",  "/") + "-" + end.replace("-",  "/"));
                            	}
                                int yearBegin = Integer.parseInt(begin.replaceAll("([0-9]*).*", "$1"));
                                int yearEnd = Integer.parseInt(end.replaceAll("([0-9]*).*", "$1"));
                                addField(xmlOut, "published_date", String.valueOf(yearEnd)+"-01-01T00:00:00Z" );
                                if (yearEnd > yearBegin) addField(xmlOut, "published_daterange", "["+yearBegin+ " TO " +yearEnd+"]");
                                else if (yearEnd == yearBegin) addField(xmlOut, "published_daterange", ""+yearBegin);
                                dateDone = true;
                            }
                        }
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }

            // access restrictions note
            final JsonValue restrictions = getRecord().get("access_restrictions_note");
            if (restrictions != null) {
                String restrictionsStr = restrictions.toString().replaceAll("\r\n", "").replaceFirst("^\"", "").replaceFirst("\"$","");
                addField(xmlOut, "access_note_tsearch_stored", restrictionsStr);
            }
            
            // linked agents
            final JsonValue agents = getRecord().get("linked_agents");
            if (agents != null && agents.getValueType() == JsonValue.ValueType.ARRAY) {
                for (JsonValue agentLink : (JsonArray) agents) {
                    final String ref = ((JsonObject) agentLink).getString("ref");
                    final String role = ((JsonObject) agentLink).getString("role");
                    final JsonObject agent = c.resolveReference(ref);
                    try {
                        if (agent.getBoolean("publish")) {
                            if (role.equals("creator")) {
                                final String name = agent.getString("title");
                                addField(xmlOut, "author_tsearchf_stored", name);
                            }
                        }
                    } catch (NullPointerException e) {
                        // TODO: do something better than skipping it
                    }
                }
            }

            // Top Containers
            addAvailabilityInfo(xmlOut, library);

            // Digital Objects
            int manifestsIncluded = 0;
            if (getDigitalObjects().size() <= 5) {
                for (ASpaceDigitalObject digitalObject : getDigitalObjects()) {
                    if (digitalObject.getIIIFURL() != null) {
                        try {
                        	addDigitalImagesV4(digitalObject.getIIIFURL(), xmlOut, manifestsIncluded == 0, dbHost, dbUser, dbPassword);
                            manifestsIncluded++;
                        } catch (IOException ex) {
                            System.err.println("Unable to fetch manifest: " + digitalObject.getIIIFURL());
                        }
                    }
                }
            }
            if (manifestsIncluded > 0) {
//                addField(xmlOut, "feature_facet", "iiif");
                addField(xmlOut, "format_f_stored", "Online");
            } else {
                addField(xmlOut, "thumbnail_url_a", "http://iiif.lib.virginia.edu/iiif/static:6/full/!115,125/0/default.jpg");
            }

            // Despite it's name, "alternate_id_facet" currently must only be an alternate id that represents a
            // distinct digital object for which there's an IIIF manifest, rights_wrapper_url, etc.
            //
            // related accessions (use for alternate ids)
            //final JsonValue accessions = getRecord().get("related_accessions");
            //if (accessions != null && accessions.getValueType() == JsonValue.ValueType.ARRAY) {
            //    for (JsonValue a : (JsonArray) accessions) {
            //        final String ref = ((JsonObject) a).getString("ref");
            //        final ASpaceAccession accession = new ASpaceAccession(c, ref);
            //        addField(xmlOut, "alternate_id_facet", accession.getId());
            //    }
            //}

            // notes (right now, we only include the scope notes)
            final JsonValue notes = getRecord().get("notes");
            if (notes != null && notes.getValueType() == JsonValue.ValueType.ARRAY) {
                for (JsonValue n : (JsonArray) notes) {
                    JsonObject note = (JsonObject) n;
                    if (note.getBoolean("publish")) 
                    {
                        JsonArray subnotes = note.getJsonArray("subnotes");
                        String noteText;
                        if (subnotes != null) 
                        {
                            StringBuffer noteTextBuffer = new StringBuffer();
                            for (int i = 0; i < subnotes.size(); i++) 
                            {
                                JsonObject subnote = subnotes.getJsonObject(i);
                                if (subnote.getBoolean("publish") && subnote.get("content") != null) 
                                {
                                    if (noteTextBuffer.length() > 0) 
                                    {
                                    	noteTextBuffer.append("\n");
                                    }
                                    noteTextBuffer.append(subnote.getString("content"));
                                }
                            }
                            noteText = noteTextBuffer.toString();
                        }
                        else
                        {
                        	noteText = note.getJsonArray("content").getJsonString(0).toString();
                        }
                        if (noteText.length() > 0)
                        {
                            String noteType = note.getJsonString("type") != null ? note.getJsonString("type").getString() : "";
                        	if (noteType.equals("abstract"))
                            {
                                addField(xmlOut, "subject_abstract_a", noteText);
                                addField(xmlOut, "subject_summary_tsearch", noteText);
                            }
                            else if (noteType.equals("scopecontent") || noteType.equals("arrangement"))
                            {
                                addField(xmlOut, "note_tsearch_stored", noteText);
                            }
                            else if (noteType.equals("accessrestrict"))
                            {
                                addField(xmlOut, "access_note_a", noteText);
                            }
                            else if (noteType.equals("bioghist"))
                            {
                            	addField(xmlOut, "biographical_note_tsearch_stored", noteText);
                            }
                            else if (noteType.equals("prefercite"))
                            {
                            	addField(xmlOut, "citation_note_a", noteText);
                            }
                            else if (noteType.equals("acqinfo"))
                            {
                                addField(xmlOut, "note_tsearch_stored", noteText);
                            }
                            else if (noteType.equals("custodhist"))
                            {
                                addField(xmlOut, "note_tsearch_stored", noteText);
                            }
                            else if (noteType.equals("physloc"))
                            {
                                addField(xmlOut, "note_tsearch_stored", noteText);
                            }
                            else  // physdesc    odd   processinfo   relatedmaterial   userestrict    separatedmaterial
                            {
                            	noteType = "unknown";
                            }
                        }
                    }
                }
            }
        }

        if (getRecord().get("content_description") != null) {
            final String noteText = getRecord().getString("content_description");
            addField(xmlOut, "note_tsearch_stored", noteText.toString());
        }

        addField(xmlOut, "url_supp_a", "https://archives.lib.virginia.edu" + getRecord().getString("uri"));
        addField(xmlOut, "url_label_supp_a", "GUIDE TO THE COLLECTION AVAILABLE ONLINE");

        // A feature_facet is needed for proper display in Virgo.
//        addField(xmlOut, "feature_facet", "suppress_endnote_export");
//        addField(xmlOut, "feature_facet", "suppress_refworks_export");
//        addField(xmlOut, "feature_facet", "suppress_ris_export");

        xmlOut.writeCharacters("  ");
        xmlOut.writeEndElement(); // doc
        xmlOut.writeCharacters("\n");
        xmlOut.writeEndElement(); // add

        xmlOut.close();

        return outputFile;

    }

    void addAvailabilityInfo(XMLStreamWriter xmlOut, String library) throws IOException, XMLStreamException {
        // Top Containers
        JsonArrayBuilder containersBuilder = Json.createArrayBuilder();
        List<ASpaceTopContainer> containers = new ArrayList<>(getTopContainers());
        // System.err.println("Pre-Sort");
        // for (ASpaceTopContainer container : containers) {
        //     System.err.println(container.getContainerCallNumber(getCallNumber()));
        // }
        Collections.sort(containers, new Comparator<ASpaceTopContainer>() {
            @Override
            public int compare(ASpaceTopContainer o1, ASpaceTopContainer o2) {
                StringNaturalCompare comp = new StringNaturalCompare(); 
                return comp.compare(o1.getContainerCallNumber(""), o2.getContainerCallNumber(""));
            }
        });
        // System.err.println("Post-Sort");
        // for (ASpaceTopContainer container : containers) {
        //     System.err.println(container.getContainerCallNumber(getCallNumber()));
        // }
    
        for (ASpaceTopContainer container : containers) {
            JsonObjectBuilder b = Json.createObjectBuilder();
            b.add("library", library);
            String currentLocation = container.getCurrentLocation();
            String location = container.getLocation();
            if (location.equals("STACKS") && currentLocation.startsWith("SC-Ivy")) {
                location = "SC-IVY";
            }
            b.add("current_location", location);
            b.add("call_number", container.getContainerCallNumber(getCallNumber()));
            b.add("barcode", container.getBarcode());
            b.add("special_collections_location", currentLocation);
            containersBuilder.add(b.build());
        }
        addField(xmlOut, "sc_availability_large_single", containersBuilder.build().toString());
    }

    private JsonArray dedupeContainerArray(JsonArray containers) {
        HashSet<String> callNumbers = new HashSet<String>();
        JsonArrayBuilder b = Json.createArrayBuilder();
        for (JsonValue v : containers) {
            JsonObject container = (JsonObject) v;
            final String callNumber = container.getString("call_number");
            if (!callNumbers.contains(callNumber)) {
                callNumbers.add(callNumber);
                b.add(v);
            }
        }
        return b.build();
    }

    private static void addDigitalImages(final String manifestUrl, final XMLStreamWriter xmlOut, boolean thumbnail, final String dbHost, final String dbUser, final String dbPassword) throws IOException, XMLStreamException, SQLException {
        HttpGet httpGet = new HttpGet(manifestUrl);
        try (CloseableHttpResponse response = HttpClients.createDefault().execute(httpGet)) {
            if (response.getStatusLine().getStatusCode() != 200) {
                throw new RuntimeException("Unable to get IIIF manifest at " + manifestUrl + " (" + response.getStatusLine().toString() + ")");
            }
            JsonObject iiifManifest = Json.createReader(response.getEntity().getContent()).readObject();
            final String manifestId = iiifManifest.getString("@id");
            String shortManifestId = manifestId.substring(manifestId.lastIndexOf('/') + 1);
            if (shortManifestId.equals("iiif-manifest.json")) {
                // hack for Shepherd until it's in the tracking system
                shortManifestId = "MSS16152";
            }

            final JsonString rsJsonUri = iiifManifest.getJsonString("license");
            if (rsJsonUri != null)
            {
            	final String rsUri = rsJsonUri.getString();
                addRightsFields(rsUri, xmlOut, shortManifestId, dbHost, dbUser, dbPassword);
            }
            addField(xmlOut, "alternate_id_f_stored", shortManifestId);
            if (iiifManifest.getJsonString("label") != null)
            {
            	addField(xmlOut, "individual_call_number_display", iiifManifest.getString("label"));
            }
            if (thumbnail) {
                String thumbnailUrl = iiifManifest.getJsonArray("sequences").getJsonObject(0).getJsonArray("canvases").getJsonObject(0).getString("thumbnail");
                Matcher resizeMatcher = Pattern.compile("(https://.*/full/)[^/]*(/.*)").matcher(thumbnailUrl);
                if (resizeMatcher.matches()) {
                    thumbnailUrl = resizeMatcher.group(1) + "!115,125" + resizeMatcher.group(2);
                    addField(xmlOut, "thumbnail_url_display", thumbnailUrl);

                    // TODO: maybe use this as the thumbnail, maybe don't...
                } else {
                    throw new RuntimeException("Unexpected thumbnail URL! (" + thumbnailUrl + ")");
                }

                // TODO: you can pull out the rights statement and apply it to the record
            }

            addField(xmlOut, "iiif_presentation_metadata_display", iiifManifest.toString());
        } catch (JsonParsingException e) {
            throw new RuntimeException("Unable to parse IIIF manifest at " + manifestUrl);
        }
    }
    
    private static void addDigitalImagesV4(final String manifestUrl, final XMLStreamWriter xmlOut, boolean thumbnail, final String dbHost, final String dbUser, final String dbPassword) throws IOException, XMLStreamException, SQLException {
        HttpGet httpGet = new HttpGet(manifestUrl);
        try (CloseableHttpResponse response = HttpClients.createDefault().execute(httpGet)) {
            if (response.getStatusLine().getStatusCode() != 200) {
                throw new RuntimeException("Unable to get IIIF manifest at " + manifestUrl + " (" + response.getStatusLine().toString() + ")");
            }
            JsonObject iiifManifest = Json.createReader(response.getEntity().getContent()).readObject();
            final String manifestId = iiifManifest.getString("@id");
            String shortManifestId = manifestId.substring(manifestId.lastIndexOf('/') + 1);
            if (shortManifestId.equals("iiif-manifest.json")) {
                // hack for Shepherd until it's in the tracking system
                shortManifestId = "MSS16152";
            }

            final JsonString rsJsonUri = iiifManifest.getJsonString("license");
            if (rsJsonUri != null)
            {
            	final String rsUri = rsJsonUri.getString();
                addRightsFields(rsUri, xmlOut, shortManifestId, dbHost, dbUser, dbPassword);
            }
            addField(xmlOut, "alternate_id_f_stored", shortManifestId);
            if (iiifManifest.getJsonString("label") != null)
            {
            	addField(xmlOut, "individual_call_number_a", iiifManifest.getString("label"));
            }
            if (thumbnail) {
                String thumbnailUrl = iiifManifest.getJsonArray("sequences").getJsonObject(0).getJsonArray("canvases").getJsonObject(0).getString("thumbnail");
                Matcher resizeMatcher = Pattern.compile("(https://.*/full/)[^/]*(/.*)").matcher(thumbnailUrl);
                if (resizeMatcher.matches()) {
                    thumbnailUrl = resizeMatcher.group(1) + "!115,125" + resizeMatcher.group(2);
                    addField(xmlOut, "thumbnail_url_a", thumbnailUrl);

                    // TODO: maybe use this as the thumbnail, maybe don't...
                } else {
                    throw new RuntimeException("Unexpected thumbnail URL! (" + thumbnailUrl + ")");
                }

                // TODO: you can pull out the rights statement and apply it to the record
            }

            //addField(xmlOut, "iiif_presentation_metadata_display", iiifManifest.toString());
        } catch (JsonParsingException e) {
            throw new RuntimeException("Unable to parse IIIF manifest at " + manifestUrl);
        }
    }

    private String getLibrary(JsonObject c) throws IOException {
        final String repositoryRef = c.getJsonObject("repository").getString("ref");

        JsonObject repo = this.c.resolveReference(repositoryRef);
        final String name = repo.getString("name");
        return normalizeLocation(name);
    }

    private static void addRightsFields(final String uri, XMLStreamWriter w, final String pid, final String tracksysDbHost, final String tracksysDbUsername, final String tracksysDbPassword) throws SQLException, XMLStreamException {
        DriverManager.registerDriver(new com.mysql.jdbc.Driver());
        String connectionUrl = "jdbc:mysql://" + tracksysDbHost + "/tracksys_production?user=" + tracksysDbUsername + "&password=" + tracksysDbPassword;
        Connection conn = DriverManager.getConnection(connectionUrl);
        try {
            final String query = "SELECT name, uri, statement, commercial_use, educational_use, modifications from use_rights where uri=?";
            final PreparedStatement s = conn.prepareStatement(query);
            s.setString(1, uri);
            final ResultSet rs = s.executeQuery();
            try {
                if (rs.next()) {
                    addField(w, "feature_facet", "rights_wrapper");
                    addField(w, "rights_wrapper_url_display", RIGHTS_WRAPPER_URL + "?pid=" + pid + "&pagePid=");
                    addField(w, "rs_uri_display", uri);
                    // TODO: add citation below... preferably generated from ASPACE using a DOI
                    addField(w, "rights_wrapper_display", rs.getString("statement"));
                    if (rs.getInt("commercial_use") == 1) {
                        addField(w, "use_facet", "Commercial Use Permitted");
                    }
                    if (rs.getInt("educational_use") == 1) {
                        addField(w, "use_facet", "Educational Use Permitted");
                    }
                    if (rs.getInt("modifications") == 1) {
                        addField(w, "use_facet", "Modifications Permitted");
                    }
                } else {
                    throw new RuntimeException("Unable to find rights statement " + uri + " in tracksys db.");
                }
            } finally {
                rs.close();
            }
        } finally {
            conn.close();
        }
    }


    static void addField(XMLStreamWriter w, final String name, final String value) throws XMLStreamException {
        w.writeCharacters("    ");
        w.writeStartElement("field");
        w.writeAttribute("name", name);
        w.writeCharacters(value);
        w.writeEndElement();
        w.writeCharacters("\n");

    }

    public void printOutRawData() {
        JsonHelper.writeOutJson(getRecord());
    }

    public void printOutRawData(final OutputStream os) {
        JsonHelper.writeOutJson(getRecord(), os);
    }

    protected JsonObject getTree() throws IOException {
        if (tree == null) {
            JsonObject t = getRecord().getJsonObject("tree");
            if (t == null) {
                return null;
            }
            return tree = c.resolveReference(t.getString("ref"));
        } else {
            return tree;
        }
    }

    /**
     * Writes the object's corresponding MARC record into the given file.
     */
    public void writeMarcCirculationRecord(final File file, final boolean xml) throws IOException {

        if (xml) {
            try (FileOutputStream o = new FileOutputStream(file)) {
                MarcXmlWriter w = new MarcXmlWriter(o, true);
                writeCirculationRecord(w, null);
                w.close();
            }
        } else {
            try (FileOutputStream o = new FileOutputStream(file)) {
                MarcStreamWriter w = new MarcStreamWriter(o);
                writeCirculationRecord(null, w);
                w.close();
            }
        }
    }

    /**
     * Writes the object's corresponding MARC record to the given streams.
     */
    public void writeCirculationRecord(final MarcXmlWriter xmlWriter, final MarcStreamWriter marcWriter) throws IOException {
        //make MARC record with 245 and 590 fields
        MarcFactory factory = MarcFactory.newInstance();
        Record r = factory.newRecord();
        DataField df;
        Subfield sf;


        r.addVariableField(factory.newControlField("001", getIdFromRef(getRecord().getString("uri"))));

        String title = getRecord().getString("title");
        char nonIndexChars = '0';
        if (title.startsWith("A "))
            nonIndexChars = '2';
        else if (title.startsWith("The "))
            nonIndexChars = '4';
        
        df = factory.newDataField("245", '0', nonIndexChars);
        sf = factory.newSubfield('a', title);
        df.addSubfield(sf);
        r.addVariableField(df);

        df = factory.newDataField("590", '1', ' ');
        sf = factory.newSubfield('a', "From ArchivesSpace: " + getRecord().getString("uri"));
        df.addSubfield(sf);
        r.addVariableField(df);


        //generate a 999 field for each top_container
        for (ASpaceTopContainer topContainer : getTopContainers()) {
            df = factory.newDataField("949", ' ', ' ');
            df.addSubfield(factory.newSubfield('a', topContainer.getContainerCallNumber(getCallNumber())));
            df.addSubfield(factory.newSubfield('h', "SC-STACKS-MANUSCRIPT"));
            df.addSubfield(factory.newSubfield('i', topContainer.getBarcode()));
            r.addVariableField(df);
        }

        if (marcWriter != null) {
            marcWriter.write(r);
        }
        if (xmlWriter != null) {
            xmlWriter.write(r);
        }
    }

}
