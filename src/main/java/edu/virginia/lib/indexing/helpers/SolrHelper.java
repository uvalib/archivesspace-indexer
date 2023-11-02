package edu.virginia.lib.indexing.helpers;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpSolrServer;
import org.apache.solr.client.solrj.impl.XMLResponseParser;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import edu.virginia.lib.indexing.tools.IndexRecords;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static methods to interact with a Solr server's HTTP API.
 */
public class SolrHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger(SolrHelper.class);
    
    private static SolrServer solrserver = null;
    
    private static SolrServer getSolrServer(String solrUrl) {
        if (solrserver == null) {
            solrserver = new HttpSolrServer(solrUrl);
        }
        return solrserver;
    }

//    public static Iterator<SolrDocument> getRecordsForQuery(String solrUrl, String query) throws SolrServerException {
//        return getRecordsForQuery(solrUrl, query, null, null);
//    }
    
    public static Iterator<SolrDocument> getRecordsForQuery(String solrUrl, String query, String fieldList, String label) throws SolrServerException {
        SolrServer solr = getSolrServer(solrUrl);
        ((HttpSolrServer) solr).setParser(new XMLResponseParser());
        int start = 0;
        final ModifiableSolrParams p = new ModifiableSolrParams();
        p.set("q", new String[] { query });
        p.set("rows", 5000);
        p.set("start", start);
        if (fieldList != null) {
            p.set("fl", fieldList);
        }
            
        return new Iterator<SolrDocument>() {

            int index = 0;
            int start = 0;
            QueryResponse response = null;

            public boolean hasNext() {
                if (response == null || response.getResults().size() <= index) {
                    p.set("rows", 5000);
                    p.set("start", start);
                    try {
                        response = solr.query(p);
                        int numRetrieved = response.getResults().size();
                        long totalNumber = response.getResults().getNumFound();
                        start += numRetrieved;
                        if (label != null && (numRetrieved > 0 || start == 0)) {
                            if (start < totalNumber) 
                            {
                                LOGGER.debug(label+" records retrieved from solr : "+ start);
                            }
                            else
                            {
                                LOGGER.info(label+" records retrieved from solr : "+ start);
                            }
                        }
                        index = 0;
                    } catch (SolrServerException e) {
                        throw new RuntimeException(e);
                    }
                }
                return response.getResults().size() > index;
            }

            public SolrDocument next() {
                if (!hasNext()) {
                    throw new IllegalStateException();
                }
                return response.getResults().get(index ++);
            }

            public void remove() {
                throw new UnsupportedOperationException();
            }

        };
    }

    public static void postFileToSolr(final String solrUrl, final File solrDoc, boolean commit) throws IOException {
        System.out.println("Writing doc to solr " + solrUrl + "...");
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(solrUrl + (commit ? "?commit=true" : ""));
            MultipartEntityBuilder builder = MultipartEntityBuilder.create();
            builder.addBinaryBody(solrDoc.getName(), solrDoc, ContentType.create("text/xml", "UTF-8"), solrDoc.getName());
            post.setEntity(builder.build());
            try (CloseableHttpResponse response = client.execute(post)) {
                if (response.getStatusLine().getStatusCode() != 200) {
                    throw new RuntimeException("Unable to write documents to solr at " + solrUrl + ". (" + response.getStatusLine().toString() + ")");
                }
            }
        }
        System.out.println("DONE!");
    }

    public static File getSolrOutputFile(final File outputDir, final String refId) {
        return new File(outputDir, getIdFromRef(refId).replace(':', '_') + ".xml");
    }

    /**
     * Gets a solr-ready identifier from the reference id for the resource.
     */
    public static String getIdFromRef(final String refId) {
        final String id = refId.replace("/repositories/", "as:").replace("/accessions/", "a").replace("/resources/", "r");
        if (!Pattern.compile("as:\\d+[ar]\\d+").matcher(id).matches()) {
            throw new RuntimeException("refId " + refId + " maps to improper pid " + id);
        }
        return id;
    }

    public static String getRefIdForFile(final File solrFile) {
        Matcher m = Pattern.compile("as:(\\d+)([ar])(\\d+)\\.xml").matcher(solrFile.getName());
        if (m.matches()) {
            return "/repositories/" + m.group(1) + (m.group(2).equals("r") ? "/resources/" : "/accessions/") + m.group(3);
        } else{
            throw new RuntimeException("Invalid filename: " + solrFile.getName());
        }
    }

    public static boolean isUniqueVirgoId(final String id) {
        if (id.contains(" ") || id.contains("/")) {
            return false;
        }
        if (id.startsWith("VIU") || id.startsWith("MSS") || id.startsWith("VACVUCN") || id.startsWith("RG_") || id.startsWith("MS_")) {
            return true;
        }
        return false;
    }

    public static List<String> getSolrXmlFieldValues(final String fieldName, final Document doc) throws XPathExpressionException {
        XPath xpath = XPathFactory.newInstance().newXPath();
        XPathExpression ex = xpath.compile("/add/doc/field[@name='" + fieldName + "']/text()");
        final List<String> values = new ArrayList<>();
        NodeList nl = (NodeList) ex.evaluate(doc, XPathConstants.NODESET);
        for (int i = 0; i < nl.getLength(); i ++) {
            Node n = nl.item(i);
            values.add(n.getNodeValue());
        }
        return values;
    }
}
