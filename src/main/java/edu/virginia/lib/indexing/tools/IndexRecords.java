package edu.virginia.lib.indexing.tools;

import edu.virginia.lib.indexing.ASpaceCollection;
import edu.virginia.lib.indexing.ASpaceObject;
import edu.virginia.lib.indexing.ArchivesSpaceClient;
import edu.virginia.lib.indexing.helpers.SolrHelper;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrDocument;
import org.marc4j.MarcStreamWriter;
import org.marc4j.MarcXmlWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import javax.json.JsonObject;

/**
 * Created by md5wz on 1/12/18.
 */
public class IndexRecords {

    private static final Logger LOGGER = LoggerFactory.getLogger(IndexRecords.class);
    public static String debugUse = null;
    
    public final static String knownBadRefs = " /repositories/3/accessions/1274  /repositories/7/resources/915 /repositories/3/resources/1534 /repositories/3/resources/1628 /repositories/7/resources/116 ";
    public static void main(String [] args) throws Exception {
        Properties p = new Properties();
        int argOffset = 0;
        String filename = "config.properties";
        if (args.length > 0 && args[0].endsWith(".properties"))
        {
            filename = args[0];
            argOffset++;
        }
        try (FileInputStream fis = new FileInputStream(filename)) {
            p.load(fis);
        }
        ArchivesSpaceClient c = new ArchivesSpaceClient(
                p.getProperty("archivesSpaceUrl"),
                p.getProperty("username"),
                p.getProperty("password"),
                p.getProperty("archivesSpaceSolrUrl"));

        final String host = p.getProperty("tracksysDbHost");
        final String user = p.getProperty("tracksysDbUsername");
        final String pass = p.getProperty("tracksysDbPassword");
        final String v3Orv4 = p.getProperty("outputRecordType", "v4");
        debugUse = p.getProperty("debugUse", null);

        final int intervalInMinutes = Integer.valueOf(p.getProperty("interval"));

        final File output = new File(p.getProperty("indexOutputDir"));
        final File marcOutput = new File(p.getProperty("marcOutputDir"));
        final File marcXmlOutput = new File(p.getProperty("marcXmlOutputDir"));
//        final File logs = new File(p.getProperty("logOutputDir"));

        final String solrUrl = p.getProperty("archivesSpaceSolrUrl");

//        final File report = new File(logs, new SimpleDateFormat("yyyy-MM-dd-").format(new Date()) + "updated.txt");
//        final PrintWriter published = new PrintWriter(new OutputStreamWriter(new FileOutputStream(report, true)));

        final long start = System.currentTimeMillis();
        LOGGER.info("Started at " + new Date());

        int reindexed = 0;
        List<String> errorRefs = new ArrayList<>();
        List<String> expectedErrorRefs = new ArrayList<>();
        final Set<String> refsToUpdate = new LinkedHashSet<>();
        final Set<String> allValidUpdatableRefs = new LinkedHashSet<>();
        
        if (args.length == argOffset) {
            List<String> repos = findUpdatedRepositories(solrUrl, -1);  // Get list of all possible Valid Updateable Refs
            int totalNum = 0;
            for (String repoRef : repos) {
                allValidUpdatableRefs.addAll(c.listAccessionIds(repoRef));
                allValidUpdatableRefs.addAll(c.listResourceIds(repoRef));
                int numAdded = allValidUpdatableRefs.size() - totalNum;
                totalNum = allValidUpdatableRefs.size();
                LOGGER.info(numAdded + " contained accessions and resources could be updated if repository " + repoRef + " were updated.");
            }
            totalNum = 0;
            repos = findUpdatedRepositories(solrUrl, intervalInMinutes);
            for (String repoRef : repos) {
                refsToUpdate.addAll(c.listAccessionIds(repoRef));
                refsToUpdate.addAll(c.listResourceIds(repoRef));
                int numAdded = refsToUpdate.size() - totalNum;
                totalNum = refsToUpdate.size();
                LOGGER.info(numAdded + " contained accessions and resources will be updated because repository " + repoRef + " was updated.");
            }
            final Set<String> updatedRefs = findUpdatedRecordsToReindex(c, solrUrl, intervalInMinutes, allValidUpdatableRefs);
            LOGGER.info(updatedRefs.size() + " accessions and resources had individual updates");
            for (String ref : updatedRefs) {
                if (allValidUpdatableRefs.contains(ref)) {
                    refsToUpdate.add(ref);
                }
                else {
                    LOGGER.warn("Possible bad ref :" + ref);
                }
            }
            LOGGER.info(refsToUpdate.size() + " records to regenerate.");
        } else {
            LOGGER.info("Reindexing items provided on the command line.");
            for (int i = argOffset; i < args.length; i++) {
                refsToUpdate.add(args[i]);
            }
        }

        final File marcRecords = new File(marcOutput, new SimpleDateFormat("yyyy-MM-dd").format(new Date()) + "-updates.mrc");
        MarcStreamWriter marcStream = new MarcStreamWriter(new FileOutputStream(marcRecords));
        final File marcXmlRecords = new File(marcXmlOutput, new SimpleDateFormat("yyyy-MM-dd").format(new Date()) + "-updates.xml");
        MarcXmlWriter xmlWriter = new MarcXmlWriter(new FileOutputStream(marcXmlRecords));
        for (String ref : refsToUpdate) {
            try {
                ASpaceObject o = ASpaceObject.parseObject(c, ref);
                if (v3Orv4.contentEquals("v4")) {
                	o.generateV4SolrAddDoc(output, host, user, pass);
                }
//                else {
//                    o.generateSolrAddDoc(output, host, user, pass);
//                }
                if (isSpecialCollections(ref)) {
                    o.writeCirculationRecord(xmlWriter, marcStream);
                }
                LOGGER.info(ref + ": " + o.getId());
                LOGGER.info("--------------------------------------------------------------");
                reindexed ++;
            } catch (Throwable t) {
                LOGGER.error("", t);
                if (knownBadRefs.contains(" "+ref+ " ") && t.toString().contains("404 Not Found")) {
                	LOGGER.info(ref + ": skipped due to EXPECTED runtime error " + t.toString());
                	expectedErrorRefs.add(ref);
                }
                else { 
                    LOGGER.error(ref + ": skipped due to runtime error " + t.toString());
                    errorRefs.add(ref);
                }
            }
        }
        marcStream.close();
        xmlWriter.close();
        LOGGER.info("Completed at " + new Date());
        final long elapsedSeconds = ((System.currentTimeMillis() - start) / 1000);
        LOGGER.info((elapsedSeconds / 60) + " minutes elapsed");

        if (errorRefs.isEmpty() && expectedErrorRefs.isEmpty()) {
            LOGGER.info("Updated index and marc records for the " + reindexed + " resources/accessions in ArchivesSpace that changed in the last " + intervalInMinutes + " minutes.");
        } 
        else {
            LOGGER.warn(errorRefs.size() + " records resulted in errors, ");
            LOGGER.warn(expectedErrorRefs.size() + " records resulted in EXPECTED errors, ");
            LOGGER.warn(reindexed + " other index/marc records updated in responses to changes in the last " + intervalInMinutes + " minutes.");
            if (!errorRefs.isEmpty()) {
            	System.exit(1);
            }
        }
    }

    private static boolean isSpecialCollections(String ref) {
        return ref.startsWith("/repositories/3");
    }

    final static String TYPES = "types";

    private static String getQuery(final int minutesAgo) {
        if (minutesAgo == -1) {
            LOGGER.info("hours ago = -1  reindexing all items.");
            return "user_mtime:[* TO NOW]";
        }
        else {
            return "user_mtime:[NOW-" + minutesAgo + "MINUTE TO NOW]";
        }
    }

    // http://archivesspace01.lib.virginia.edu:8090/collection1/select?q=user_mtime:[NOW-100DAY%20TO%20NOW]&wt=xml&indent=true&facet=true&facet.field=types
    // &fl=id,types,ancestors,linked_instance_uris,related_accession_uris,collection_uri_u_sstr
    private static Set<String> findUpdatedRecordsToReindex(ArchivesSpaceClient c, final String solrUrl, int minutesAgo, Set<String> allValidUpdatableRefs) throws SolrServerException {
        final Set<String> refIds = new HashSet<>();
        Iterator<SolrDocument> updated = SolrHelper.getRecordsForQuery(solrUrl, getQuery(minutesAgo) + " AND (types:resource OR types:archival_object OR types:top_container)", 
                                                                       "types,id,related_accession_uris,ancestors,collection_uri_u_sstr", "modified objects");
        while (updated.hasNext()) {
            SolrDocument d = updated.next();
            String id = (String) d.getFirstValue("id");
            if (hasFieldValue(d, TYPES, "resource")) {
                // all directly updated resource records
                if (allValidUpdatableRefs.contains(id)) {
                    refIds.add(id);
                }
                else {
                    LOGGER.warn("SolrDocument with ID: "+id+" references top level item that doesn't exist");
                    errorCheck(c, id);
                }
                // add all affected related accessions (they might have to be hidden or something)
                final Collection<Object> values = d.getFieldValues("related_accession_uris");
                if (values != null) {
                    for (Object refo : values) {
                        String ref = (String)refo;
                        if (allValidUpdatableRefs.contains(ref)) {
                            refIds.add(ref);
                        }
                        else {
                            LOGGER.warn("SolrDocument with ID: "+id+" references accession uri: " + ref + " that doesn't exist");
                            errorCheck(c, id);
                        }
                    }
                }
            } else if (hasFieldValue(d, TYPES, "archival_object")) {
                // plus all resource records that are ancestors of updated archival objects
                for (Object a : d.getFieldValues("ancestors")) {
                    String ancestor = (String) a;
                    if (ASpaceCollection.isCorrectIdFormat(ancestor)) {
                        if (allValidUpdatableRefs.contains(ancestor)) {
                            refIds.add(ancestor);
                        }
                        else {
                            LOGGER.warn("SolrDocument with ID: "+ id +" references accession uri: " + ancestor + " that doesn't exist");
                            errorCheck(c, id);
                        }
                    }
                }
            } else if (hasFieldValue(d, TYPES, "top_container")) {
                // plus all records that may have an updated or added top_container (this may include accession records)
                final Collection<Object> values = d.getFieldValues("collection_uri_u_sstr");
                if (values != null) {
                    for (Object refo : values) {
                        String ref = (String)refo;
                        if (allValidUpdatableRefs.contains(ref)) {
                            refIds.add(ref);
                        }
                        else {
                            LOGGER.warn("SolrDocument with ID: "+id+" references collection_uri: " + ref + " that doesn't exist");
                            errorCheck(c, id);
                        }
                    }
                }
            }
        }
        return refIds;
    }

    private static void errorCheck(ArchivesSpaceClient c, String refId) {
        JsonObject joAPI = null;
        JsonObject joSolr = null;
        try{ 
            joAPI = (JsonObject)c.makeGetRequestId(refId);
        }
        catch (IOException ioe) {
            LOGGER.warn("IOException retrieving " + refId + " via API");
        }
        catch (RuntimeException re) {
            LOGGER.warn("RuntimeException 404 Not Found retrieving " + refId + " via API");
        }
        try{ 
            joSolr = (JsonObject)c.makeSolrGetRequest(refId);
        }
        catch (IOException ioe) {
            LOGGER.warn("IOException retrieving " + refId + " via Solr");
        }
        if (joSolr != null && joAPI == null) {
            LOGGER.warn("Item in Solr index, but not in API : "+ refId);
        }
    }
    
    private static List<String> findUpdatedRepositories(final String solrUrl, int minutesAgo) throws SolrServerException {
        final List<String> refIds = new ArrayList<>();
        Iterator<SolrDocument> updated = SolrHelper.getRecordsForQuery(solrUrl, getQuery(minutesAgo) + " AND " + TYPES + ":repository", "id", "repository");
        while (updated.hasNext()) {
            SolrDocument d = updated.next();
            refIds.add((String) d.getFirstValue("id"));
        }
        return refIds;
    }

    private static boolean hasFieldValue(SolrDocument d, final String field, final String value) {
        for (Object val : d.getFieldValues(field)) {
            if (val instanceof String && val.equals(value)) {
                return true;
            }
        }
        return false;
    }

}
