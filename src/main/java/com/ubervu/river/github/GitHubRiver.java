package com.ubervu.river.github;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonStreamParser;
import org.apache.commons.codec.digest.DigestUtils;
import org.elasticsearch.action.bulk.BulkProcessor;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.deletebyquery.DeleteByQueryResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.Base64;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.indices.IndexAlreadyExistsException;
import org.elasticsearch.river.AbstractRiverComponent;
import org.elasticsearch.river.River;
import org.elasticsearch.river.RiverName;
import org.elasticsearch.river.RiverSettings;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.elasticsearch.index.query.QueryBuilders.termQuery;


public class GitHubRiver extends AbstractRiverComponent implements River {

    private final Client client;
    private final String index;
    private final String repositories_str;
    private final String[] repositories;
    private final String owner;
    private final int interval;
    private String password;
    private String username;
    private DataStream dataStream;

    @SuppressWarnings({"unchecked"})
    @Inject
    public GitHubRiver(RiverName riverName, RiverSettings settings, Client client) {
        super(riverName, settings);
        this.client = client;

        if (!settings.settings().containsKey("github")) {
            throw new IllegalArgumentException("Need river settings - owner and repository.");
        }

        // get settings
        Map<String, Object> githubSettings = (Map<String, Object>) settings.settings().get("github");
        owner = XContentMapValues.nodeStringValue(githubSettings.get("owner"), null);
        repositories_str = XContentMapValues.nodeStringValue(githubSettings.get("repositories"), "");
        repositories = repositories_str.split(",");
        index = String.format("github-%s", owner);
        interval = XContentMapValues.nodeIntegerValue(githubSettings.get("interval"), 3600);

        // auth (optional)
        username = null;
        password = null;
        if (githubSettings.containsKey("authentication")) {
            Map<String, Object> auth = (Map<String, Object>) githubSettings.get("authentication");
            username = XContentMapValues.nodeStringValue(auth.get("username"), null);
            password = XContentMapValues.nodeStringValue(auth.get("password"), null);
        }

        logger.info("Created GitHub river.");
    }

    @Override
    public void start() {
        // create the index explicitly so we can use the whitespace tokenizer
        //   because there are usernames like "user-name" and we want those
        //   to be treated as just one term
        try {
            Settings indexSettings = ImmutableSettings.settingsBuilder().put("analysis.analyzer.default.tokenizer", "whitespace").build();
            client.admin().indices().prepareCreate(index).setSettings(indexSettings).execute().actionGet();
            logger.info("Created index.");
        } catch (IndexAlreadyExistsException e) {
            ;
        } catch (Exception e) {
            logger.error("Exception creating index.", e);
        }
        dataStream = new DataStream();
        dataStream.start();
        logger.info("Started GitHub river.");
    }

    @Override
    public void close() {
        dataStream.setRunning(false);
        logger.info("Stopped GitHub river.");
    }

    private class DataStream extends Thread {
        private volatile boolean isRunning;

        @Inject
        public DataStream() {
            super("DataStream thread");
            isRunning = true;
        }

        private void indexResponse(URLConnection conn, String type) {
            InputStream input = null;
            try {
                input = conn.getInputStream();
            } catch (java.io.FileNotFoundException e) {
                logger.info("404 - Nothing to see here", e);
                return;
            } catch (IOException e) {
                logger.info("API rate reached, will try later", e);
                try {
                    Thread.sleep(1000); // needs milliseconds
                } catch (InterruptedException ee) {}
                return;
            }
            JsonStreamParser jsp = new JsonStreamParser(new InputStreamReader(input));

            JsonArray array = (JsonArray) jsp.next();

            BulkProcessor bp = BulkProcessor.builder(client, new BulkProcessor.Listener() {
                @Override
                public void beforeBulk(long executionId, BulkRequest request) {
                }

                @Override
                public void afterBulk(long executionId, BulkRequest request, BulkResponse response) {
                }

                @Override
                public void afterBulk(long executionId, BulkRequest request, Throwable failure) {
                }
            }).build();

            IndexRequest req = null;
            for (JsonElement e: array) {
                if (type.equals("event")) {
                    req = indexEvent(e);
                } else if (type.equals("issue")) {
                    req = indexOther(e, "IssueData", true);
                } else if (type.equals("pullreq")) {
                    req = indexOther(e, "PullRequestData");
                } else if (type.equals("milestone")) {
                    req = indexOther(e, "MilestoneData");
                } else if (type.equals("label")) {
                    req = indexOther(e, "LabelData");
                } else if (type.equals("collaborator")) {
                    req = indexOther(e, "CollaboratorData");
                }
                bp.add(req);
            }
            bp.close();

            try {
                input.close();
            } catch (IOException e) {}
        }

        private IndexRequest indexEvent(JsonElement e) {
            JsonObject obj = e.getAsJsonObject();
            String type = obj.get("type").getAsString();
            String id = obj.get("id").getAsString();
            IndexRequest req = new IndexRequest(index)
                    .type(type)
                    .id(id).create(false) // we want to overwrite old items
                    .source(e.toString());
            return req;
        }

        private IndexRequest indexOther(JsonElement e, String type, boolean overwrite) {
            JsonObject obj = e.getAsJsonObject();

            // handle objects that don't have IDs (i.e. labels)
            // set the ID to the MD5 hash of the string representation
            String id;
            if (obj.has("id")) {
                id = obj.get("id").getAsString();
            } else {
                id = DigestUtils.md5Hex(e.toString());
            }

            IndexRequest req = new IndexRequest(index)
                    .type(type)
                    .id(id).create(!overwrite)
                    .source(e.toString());
            return req;
        }

        private IndexRequest indexOther(JsonElement e, String type) {
            return indexOther(e, type, false);
        }

        private HashMap<String, String> parseHeader(String header) {
            // inspired from https://github.com/uberVU/elasticboard/blob/4ccdfd8c8e772c1dda49a29a7487d14b8d820762/data_processor/github.py#L73
            Pattern p = Pattern.compile("\\<([a-z/0-9:\\.\\?_&=]+page=([0-9]+))\\>;\\s*rel=\\\"([a-z]+)\\\".*");
            Matcher m = p.matcher(header);

            if (!m.matches()) {
                return null;
            }

            HashMap<String, String> data = new HashMap<String, String>();
            data.put("url", m.group(1));
            data.put("page", m.group(2));
            data.put("rel", m.group(3));

            return data;
        }

        private boolean morePagesAvailable(URLConnection response) {
            String link = response.getHeaderField("link");
            if (link == null || link.length() == 0) {
                return false;
            }

            HashMap<String, String> headerData = parseHeader(response.getHeaderField("link"));
            if (headerData == null) {
                return false;
            }

            String rel = headerData.get("rel");
            return rel.equals("next");
        }

        private String nextPageURL(URLConnection response) {
            HashMap<String, String> headerData = parseHeader(response.getHeaderField("link"));
            if (headerData == null) {
                return null;
            }
            return headerData.get("url");
        }

        private void addAuthHeader(URLConnection request) {
            if (username == null || password == null) {
                return;
            }
            String auth = String.format("%s:%s", username, password);
            String encoded = Base64.encodeBytes(auth.getBytes());
            request.setRequestProperty("Authorization", "Basic " + encoded);
        }

        private void getData(String fmt, String type, String repo) {
            try {
                URL url = new URL(String.format(fmt, owner, repo));
                URLConnection response = url.openConnection();
                addAuthHeader(response);
                indexResponse(response, type);

                while (morePagesAvailable(response)) {
                    url = new URL(nextPageURL(response));
                    response = url.openConnection();
                    addAuthHeader(response);
                    indexResponse(response, type);
                }
            } catch (Exception e) {
                logger.error("Exception in getData", e);
            }
        }

        private void deleteByType(String type) {
            DeleteByQueryResponse response = client.prepareDeleteByQuery(index)
                    .setQuery(termQuery("_type", type))
                    .execute()
                    .actionGet();
        }

        @Override
        public void run() {
            while (isRunning) {
                deleteByType("MilestoneData");
                deleteByType("PullRequestData");
                deleteByType("CollaboratorData");
                deleteByType("LabelData");
                for (String repo : repositories) {
                    getData("https://api.github.com/repos/%s/%s/events?per_page=1000", "event", repo);
                    try {
                        Thread.sleep(1000); // needs milliseconds
                    } catch (InterruptedException e) {}
                    getData("https://api.github.com/repos/%s/%s/issues?per_page=1000", "issue", repo);
                    try {
                        Thread.sleep(1000); // needs milliseconds
                    } catch (InterruptedException e) {}
                    getData("https://api.github.com/repos/%s/%s/issues?state=closed&per_page=1000", "issue", repo);
                    try {
                        Thread.sleep(1000); // needs milliseconds
                    } catch (InterruptedException e) {}

                    // delete pull req data - we are only storing open pull reqs
                    // and when a pull request is closed we have no way of knowing;
                    // this is why we have to delete them and reindex "fresh" ones
                    getData("https://api.github.com/repos/%s/%s/pulls", "pullreq", repo);
                    try {
                        Thread.sleep(1000); // needs milliseconds
                    } catch (InterruptedException e) {}

                    // same for milestones
                    getData("https://api.github.com/repos/%s/%s/milestones?per_page=1000", "milestone", repo);
                    try {
                        Thread.sleep(1000); // needs milliseconds
                    } catch (InterruptedException e) {}

                    // collaborators
                    getData("https://api.github.com/repos/%s/%s/collaborators?per_page=1000", "collaborator", repo);
                    try {
                        Thread.sleep(1000); // needs milliseconds
                    } catch (InterruptedException e) {}

                    // and for labels - they have IDs based on the MD5 of the contents, so
                    // if a property changes, we get a "new" document
                    getData("https://api.github.com/repos/%s/%s/labels?per_page=1000", "label", repo);
                    try {
                        Thread.sleep(1000); // needs milliseconds
                    } catch (InterruptedException e) {}
                }
                try {
                    Thread.sleep(interval * 1000); // needs milliseconds
                } catch (InterruptedException e) {}
            }
        }

        public void setRunning(boolean running) {
            isRunning = running;
        }
    }
}
