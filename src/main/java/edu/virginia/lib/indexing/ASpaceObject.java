package edu.virginia.lib.indexing;

import edu.virginia.lib.indexing.helpers.JsonHelper;
import edu.virginia.lib.indexing.helpers.StringNaturalCompare;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClients;
import org.marc4j.MarcStreamWriter;
import org.marc4j.MarcWriter;
import org.marc4j.MarcXmlWriter;
import org.marc4j.marc.DataField;
import org.marc4j.marc.MarcFactory;
import org.marc4j.marc.Record;
import org.marc4j.marc.Subfield;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonValue;
import javax.json.stream.JsonParsingException;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.File;
import java.io.FileInputStream;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static edu.virginia.lib.indexing.helpers.JsonHelper.hasValue;
import static edu.virginia.lib.indexing.helpers.SolrHelper.getIdFromRef;
import static edu.virginia.lib.indexing.helpers.SolrHelper.getSolrOutputFile;
import static edu.virginia.lib.indexing.helpers.SolrHelper.isUniqueVirgoId;
import static edu.virginia.lib.indexing.helpers.UvaHelper.extractManifestUrl;
import static edu.virginia.lib.indexing.helpers.UvaHelper.normalizeLocation;

/**
 * An abstract base class that encapsulates logic to pull data from the ArchivesSpace REST API.
 */
public abstract class ASpaceObject {

    final static public String RIGHTS_WRAPPER_URL = "http://rightswrapper2.lib.virginia.edu:8090/rights-wrapper/";

    protected ArchivesSpaceClient c;

    protected String refId;

    private JsonObject record;
    
    protected JsonObject tree;

    protected List<ASpaceTopContainer> containers;

    protected List<ASpaceDigitalObject> digitalObjects;

    private List<ASpaceArchivalObject> children;

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

    /**
     * Gets all children (nested components) for this ASpaceObject.  Subclasses that don't/can't
     * have nested components should return an empty list.
     */
    public List<ASpaceArchivalObject> getChildren() throws IOException {
        if (children == null) {
            children = new ArrayList<>();
            final JsonObject treeObj = getTree();
            if (treeObj != null) {
                final JsonArray jsonChildren = tree.getJsonArray("children");
                if (jsonChildren != null) {
                    for (JsonValue c : jsonChildren) {
                        final JsonObject child = (JsonObject) c;
                        children.add(new ASpaceArchivalObject(this.c, child.getString("record_uri"), child));
                    }
                }
            }
        }
        return children;
    }

    public List<ASpaceDigitalObject> getDigitalObjects() {
        parseInstances();
        return digitalObjects;
    }

    public List<ASpaceTopContainer> getTopContainers() {
        parseInstances();
        return containers;
    }

    private void parseInstances() {
        if (containers == null || digitalObjects == null) {
            containers = new ArrayList<>();
            digitalObjects = new ArrayList<>();
            try {
                Set<String> containers = new HashSet<>();
                Set<String> dos = new HashSet<>();
                collectInstanceRefs(containers, dos);
                for (String ref : containers) {
                    this.containers.add(new ASpaceTopContainer(c, ref));
                }
                for (String ref : dos) {
                    this.digitalObjects.add(new ASpaceDigitalObject(c, ref));
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Adds all the container refs and digital object refs for this node to the passed
     * sets and recurses to the published children.
     */
    protected void collectInstanceRefs(final Set<String> containerRefs, Set<String> doRefs) throws IOException {
        final JsonValue instances = getRecord().get("instances");
        if (instances != null && instances.getValueType() == JsonValue.ValueType.ARRAY) {
            for (JsonValue i : (JsonArray) instances) {
                final JsonObject instance = (JsonObject) i;
                if (!instance.getString("instance_type").equals("digital_object")) {
                    containerRefs.add(instance.getJsonObject("sub_container").getJsonObject("top_container").getString("ref"));
                } else {
                    doRefs.add(instance.getJsonObject("digital_object").getString("ref"));
                }
            }
        }

        // recurse to children
        for (ASpaceArchivalObject child : getChildren()) {
            if (child.isPublished()) {
                child.collectInstanceRefs(containerRefs, doRefs);
            }
        }
    }

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

            final String rsUri = iiifManifest.getString("license");
            addRightsFields(rsUri, xmlOut, shortManifestId, dbHost, dbUser, dbPassword);

            addField(xmlOut, "alternate_id_facet", shortManifestId);
            addField(xmlOut, "individual_call_number_display", iiifManifest.getString("label"));
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
