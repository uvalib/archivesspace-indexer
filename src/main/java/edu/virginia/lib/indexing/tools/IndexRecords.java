package edu.virginia.lib.indexing.tools;

import edu.virginia.lib.indexing.ASpaceCollection;
import edu.virginia.lib.indexing.ASpaceObject;
import edu.virginia.lib.indexing.ArchivesSpaceClient;
import edu.virginia.lib.indexing.helpers.SolrHelper;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrDocument;
import org.marc4j.MarcStreamWriter;
import org.marc4j.MarcXmlWriter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Created by md5wz on 1/12/18.
 */
public class IndexRecords {

	public final static String knownBadRefs = " /repositories/7/resources/174 /repositories/3/accessions/1274 /repositories/7/resources/155 "+
				"/repositories/7/resources/167 /repositories/7/resources/168 /repositories/7/resources/124 /repositories/7/resources/125 ";
    public static void main(String [] args) throws Exception {
        Properties p = new Properties();
        try (FileInputStream fis = new FileInputStream("config.properties")) {
            p.load(fis);
        }
        ArchivesSpaceClient c = new ArchivesSpaceClient(
                p.getProperty("archivesSpaceUrl"),
                p.getProperty("username"),
                p.getProperty("password"));

        final String host = p.getProperty("tracksysDbHost");
        final String user = p.getProperty("tracksysDbUsername");
        final String pass = p.getProperty("tracksysDbPassword");
        final String v3Orv4 = p.getProperty("outputRecordType", "v3");

        final int intervalInHours = Integer.valueOf(p.getProperty("interval"));

        final File output = new File(p.getProperty("indexOutputDir"));
        final File marcOutput = new File(p.getProperty("marcOutputDir"));
        final File marcXmlOutput = new File(p.getProperty("marcXmlOutputDir"));
        final File logs = new File(p.getProperty("logOutputDir"));

        final String solrUrl = p.getProperty("archivesSpaceSolrUrl");

        final File report = new File(logs, new SimpleDateFormat("yyyy-MM-dd-").format(new Date()) + "updated.txt");
        final PrintWriter published = new PrintWriter(new OutputStreamWriter(new FileOutputStream(report, true)));

        final long start = System.currentTimeMillis();
        published.println("Started at " + new Date());

        int reindexed = 0;
        List<String> errorRefs = new ArrayList<>();
        List<String> expectedErrorRefs = new ArrayList<>();
        final Set<String> refsToUpdate = new HashSet<>();
        if (args.length == 0) {
            List<String> repos = findUpdatedRepositories(solrUrl, intervalInHours);
            for (String repoRef : repos) {
                refsToUpdate.addAll(c.listAccessionIds(repoRef));
                refsToUpdate.addAll(c.listResourceIds(repoRef));
                published.println(refsToUpdate.size() + " contained accessions and resources will be updated because repository " + repoRef + " was updated.");
            }
            final Set<String> updatedRefs = findUpdatedRecordsToReindex(solrUrl, intervalInHours);
            published.println(updatedRefs.size() + " accessions and resources had individual updates");
            refsToUpdate.addAll(updatedRefs);
            published.println(refsToUpdate.size() + " records to regenerate.");
            published.flush();
        } else {
            published.println("Reindexing items provided on the command line.");
            for (String arg : args) {
                refsToUpdate.add(arg);
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
                else {
                	o.generateSolrAddDoc(output, host, user, pass);
                }
                if (isSpecialCollections(ref)) {
                    o.writeCirculationRecord(xmlWriter, marcStream);
                }
                published.println(ref + ": " + o.getId());
                published.flush();
                reindexed ++;
            } catch (Throwable t) {
                t.printStackTrace(published);
                if (knownBadRefs.contains(" "+ref+ " ") && t.toString().contains("404 Not Found")) {
                	published.println(ref + ": skipped due to EXPECTED runtime error " + t.toString());
                	expectedErrorRefs.add(ref);
                }
                else { 
                	published.println(ref + ": skipped due to runtime error " + t.toString());
                    errorRefs.add(ref);
                }
            }
        }
        marcStream.close();
        xmlWriter.close();
        published.println("Completed at " + new Date());
        final long elapsedSeconds = ((System.currentTimeMillis() - start) / 1000);
        published.println((elapsedSeconds / 60) + " minutes elapsed");
        published.close();

        if (errorRefs.isEmpty() && expectedErrorRefs.isEmpty()) {
            System.out.println("Updated index and marc records for the " + reindexed + " resources/accessions in ArchivesSpace that changed in the last " + intervalInHours + " hours.");
        } 
        else {
            System.err.println(errorRefs.size() + " records resulted in errors, ");
            System.err.println(expectedErrorRefs.size() + " records resulted in EXPECTED errors, ");
            System.err.println(reindexed + " other index/marc records updated in responses to changes in the last " + intervalInHours + " hours.");
            if (!errorRefs.isEmpty()) {
            	System.exit(1);
            }
        }
    }

    private static boolean isSpecialCollections(String ref) {
        return ref.startsWith("/repositories/3");
    }

    final static String TYPES = "types";

    private static String getQuery(final int hoursAgo) {
        if (hoursAgo == -1) {
            System.out.println("hours ago = -1  reindexing all items.");
            return "user_mtime:[* TO NOW]";
        }
        else {
            return "user_mtime:[NOW-" + hoursAgo + "HOUR TO NOW]";
        }
    }

    // http://archivesspace01.lib.virginia.edu:8090/collection1/select?q=user_mtime:[NOW-100DAY%20TO%20NOW]&wt=xml&indent=true&facet=true&facet.field=types
    // &fl=id,types,ancestors,linked_instance_uris,related_accession_uris,collection_uri_u_sstr
    private static Set<String> findUpdatedRecordsToReindex(final String solrUrl, int hoursAgo) throws SolrServerException {
        final Set<String> refIds = new HashSet<>();
        Iterator<SolrDocument> updated = SolrHelper.getRecordsForQuery(solrUrl, getQuery(hoursAgo));
        while (updated.hasNext()) {
            SolrDocument d = updated.next();
            if (hasFieldValue(d, TYPES, "resource")) {
                // all directly updated resource records
                refIds.add((String) d.getFirstValue("id"));
                // add all affected related accessions (they might have to be hidden or something)
                final Collection<Object> values = d.getFieldValues("related_accession_uris");
                if (values != null) {
                    for (Object ref : values) {
                        refIds.add((String) ref);
                    }
                }
            } else if (hasFieldValue(d, TYPES, "archival_object")) {
                // plus all resource records that are ancestors of updated archival objects
                for (Object a : d.getFieldValues("ancestors")) {
                    String ancestor = (String) a;
                    if (ASpaceCollection.isCorrectIdFormat(ancestor)) {
                        refIds.add(ancestor);
                    }
                }
            } else if (hasFieldValue(d, TYPES, "top_container")) {
                // plus all records that may have an updated or added top_container (this may include accession records)
                final Collection<Object> values = d.getFieldValues("collection_uri_u_sstr");
                if (values != null) {
                    for (Object ref : values) {
                        refIds.add((String) ref);
                    }
                }
            }
        }
        return refIds;
    }

    private static List<String> findUpdatedRepositories(final String solrUrl, int hoursAgo) throws SolrServerException {
        final List<String> refIds = new ArrayList<>();
        Iterator<SolrDocument> updated = SolrHelper.getRecordsForQuery(solrUrl, getQuery(hoursAgo) + " AND " + TYPES + ":repository");
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
