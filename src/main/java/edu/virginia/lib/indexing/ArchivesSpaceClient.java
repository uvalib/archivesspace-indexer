package edu.virginia.lib.indexing;


import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonStructure;
import javax.json.JsonValue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ArchivesSpaceClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(ArchivesSpaceClient.class);

    private String baseUrl;

    private CloseableHttpClient httpClient;

    private String sessionToken;

    public ArchivesSpaceClient(final String baseUrl, final String username, final String password) throws IOException {
        this.baseUrl = baseUrl;
        httpClient = HttpClients.createDefault();
        authenticate(username, password);
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
        LOGGER.debug("FETCHING " + refId);
        return (JsonObject) makeGetRequest(baseUrl + refId);
    }

    private JsonStructure makeGetRequest(final String url) throws IOException {
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
