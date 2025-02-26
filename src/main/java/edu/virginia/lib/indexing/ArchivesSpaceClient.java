package edu.virginia.lib.indexing;


import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.virginia.lib.indexing.helpers.SolrHelper;
import edu.virginia.lib.indexing.tools.IndexRecords;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonStructure;
import javax.json.JsonValue;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ArchivesSpaceClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(ArchivesSpaceClient.class);

    private String baseUrl;
    
    private String solrUrl;

    private CloseableHttpClient httpClient;

    private String sessionToken;
    
    private Map<String, JsonObject> refCacheMap = null;
    
    public ArchivesSpaceClient(final String baseUrl, final String username, final String password, String solrUrl) throws IOException {
        this.baseUrl = baseUrl;
        this.solrUrl = solrUrl;
        httpClient = HttpClients.createDefault();
        authenticate(username, password);
    }

    public String getSolrUrl()
    {
        return solrUrl;
    }

    public List<String> listRepositoryIds() throws IOException {
        final List<String> ids = new ArrayList<String>();
        for (JsonValue v : (JsonArray) makeGetRequest(baseUrl + "repositories")) {
            ids.add(((JsonObject) v).getString("uri"));
        }
        return ids;
    }

    public List<String> listAccessionIds(final String repoId) throws IOException {
        final List<String> ids = new ArrayList<String>();
        for (JsonValue v : (JsonArray) makeGetRequest(baseUrl + repoId + "/accessions?all_ids=1")) {
            ids.add(repoId + "/accessions/" + v.toString());
        }
        return ids;
    }

    public List<String> listResourceIds(final String repoId) throws IOException {
        final List<String> ids = new ArrayList<String>();
        for (JsonValue v : (JsonArray) makeGetRequest(baseUrl + repoId + "/resources?all_ids=1")) {
            ids.add(repoId + "/resources/" + v.toString());
        }
        return ids;
    }

    public JsonObject resolveReference(final String refId) throws IOException {
        if (IndexRecords.debugUse == null) {
            if (refCacheMap == null) {
                refCacheMap = new LinkedHashMap<>();
            }
        }
        if (refCacheMap != null && refCacheMap.containsKey(refId)) {
            LOGGER.debug("Already have " + refId);
            return refCacheMap.get(refId);
        }
        LOGGER.debug("FETCHING " + refId);
        JsonObject result = (JsonObject) makeSolrGetRequest(refId);
        if (result == null)
        {
            LOGGER.warn("Failed to fetch ref from solr " + refId);
            result = (JsonObject) makeGetRequestId(refId);
        }
        if (refCacheMap != null) {
            refCacheMap.put(refId, result);
        }
        return(result);
    }

    public JsonStructure makeSolrGetRequest(final String refId) throws IOException {
       // getQuery(minutesAgo) + " AND " + TYPES + ":repository", "id"
        String solrQuery = "id:\""+refId+"\"";
        Iterator<SolrDocument> recordIter;
        try
        {
            recordIter = SolrHelper.getRecordsForQuery(solrUrl, solrQuery, "json", null);
        }
        catch (SolrServerException e)
        {
            throw new IOException("Accessing SolrServer", e);
        }
        String json = null;
        while (recordIter.hasNext()) {
            SolrDocument d = recordIter.next();
            if (json == null) 
            {
                Object jObj = d.getFieldValue("json");
                if (jObj instanceof String)
                {
                    json = (String)jObj;
                }
            }
            else
            {
                throw new RuntimeException("Multiple records returned for id");
            }
        }
        if (json == null) 
        {
            return null;
        }
        return Json.createReader(new StringReader(json)).read();
    }

    public JsonStructure makeGetRequest(final String url) throws IOException {
        HttpGet httpGet = new HttpGet(url);
        httpGet.addHeader("X-ArchivesSpace-Session", sessionToken);
        try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
            if (response.getStatusLine().getStatusCode() == 404 && url.endsWith("/tree")) {
                LOGGER.warn("FETCHING " + url + " received a 404, returning null and continuing");
                return (null);
            }
            if (response.getStatusLine().getStatusCode() != 200) {
                throw new RuntimeException("Unable to get " + url + " " + response.getStatusLine().toString());
            }
            return Json.createReader(response.getEntity().getContent()).read();
        }
    }
    
    public JsonStructure makeGetRequestId(final String refId) throws IOException {
        return this.makeGetRequest(baseUrl + refId);
    }

    private void authenticate(final String username, final String password) throws IOException {
        HttpPost httpPost = new HttpPost(baseUrl + "users/" + username + "/login");
        httpPost.setEntity(MultipartEntityBuilder.create().addTextBody("password", password).build());
        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            if (response.getStatusLine().getStatusCode() != 200) {
                throw new RuntimeException("Unable to authenticate! (response " + response.getStatusLine().toString() + ")");
            }
            this.sessionToken = Json.createReader(response.getEntity().getContent()).readObject().getString("session");
        }
    }

}
