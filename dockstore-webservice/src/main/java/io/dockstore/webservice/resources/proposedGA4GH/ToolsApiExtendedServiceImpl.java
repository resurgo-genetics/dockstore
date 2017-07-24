/*
 *    Copyright 2016 OICR
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.dockstore.webservice.resources.proposedGA4GH;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.Resources;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.dockstore.webservice.CustomWebApplicationException;
import io.dockstore.webservice.DockstoreWebserviceConfiguration;
import io.dockstore.webservice.core.Entry;
import io.dockstore.webservice.core.Tool;
import io.dockstore.webservice.core.Workflow;
import io.dockstore.webservice.jdbi.ToolDAO;
import io.dockstore.webservice.jdbi.WorkflowDAO;
import io.dropwizard.jackson.Jackson;
import io.swagger.api.NotFoundException;
import io.swagger.api.impl.ToolsImplCommon;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpStatus;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.entity.NStringEntity;
import org.elasticsearch.client.RestClient;

/**
 * Created by kcao on 01/03/17.
 *
 * Implementations of methods to return responses containing organization related information
 */
public class ToolsApiExtendedServiceImpl extends ToolsExtendedApiService {

    private static ToolDAO toolDAO = null;
    private static WorkflowDAO workflowDAO = null;
    private static DockstoreWebserviceConfiguration config = null;


    public static void setToolDAO(ToolDAO toolDAO) {
        ToolsApiExtendedServiceImpl.toolDAO = toolDAO;
    }
    public static void setWorkflowDAO(WorkflowDAO workflowDAO) {
        ToolsApiExtendedServiceImpl.workflowDAO = workflowDAO;
    }

    public static void setConfig(DockstoreWebserviceConfiguration config) {
        ToolsApiExtendedServiceImpl.config = config;
    }

    /**
     * Avoid using this one, this is quite slow
     * @return
     */
    private List<Entry> getPublished() {
        final List<Entry> published = new ArrayList<>();
        published.addAll(toolDAO.findAllPublished());
        published.addAll(workflowDAO.findAllPublished());
        published.sort(Comparator.comparing(Entry::getGitUrl));
        return published;
    }

    /**
     * More optimized
     * @param organization
     * @return
     */
    private List<Entry> getPublishedByOrganization(String organization) {
        final List<Entry> published = new ArrayList<>();
        published.addAll(workflowDAO.findPublishedByOrganization(organization));
        published.addAll(toolDAO.findPublishedByNamespace(organization));
        published.sort(Comparator.comparing(Entry::getGitUrl));
        return published;
    }

    @Override
    public Response toolsOrgGet(String organization, SecurityContext securityContext) throws NotFoundException {
        return Response.ok().entity(getPublishedByOrganization(organization)).build();
    }

    private List<io.swagger.model.Tool> workflowOrgGetList(String organization) {
        List<Workflow> published = workflowDAO.findPublishedByOrganization(organization);
        return published.stream().map(c -> ToolsImplCommon.convertContainer2Tool(c, config).getLeft()).collect(Collectors.toList());
    }

    private List<io.swagger.model.Tool> entriesOrgGetList(String organization) {
        List<Tool> published = toolDAO.findPublishedByNamespace(organization);
        return published.stream().map(c -> ToolsImplCommon.convertContainer2Tool(c, config).getLeft()).collect(Collectors.toList());
    }

    @Override
    public Response workflowsOrgGet(String organization, SecurityContext securityContext) throws NotFoundException {
        return Response.ok(workflowOrgGetList(organization)).build();
    }

    @Override
    public Response entriesOrgGet(String organization, SecurityContext securityContext) throws NotFoundException {
        return Response.ok(entriesOrgGetList(organization)).build();
    }

    @Override
    public Response organizationsGet(SecurityContext securityContext) {
        List<String> organizations = new ArrayList<>();
        for (Entry c : getPublished()) {
            String org;
            if (c instanceof Workflow) {
                org = ((Workflow) c).getOrganization().toLowerCase();
            } else {
                org = ((Tool) c).getNamespace().toLowerCase();
            }
            if (!organizations.contains(org)) {
                organizations.add(org);
            }
        }
        return Response.ok(organizations).build();
    }

    @Override
    public Response toolsIndexGet(SecurityContext securityContext) throws NotFoundException {
        List<Entry> published = getPublished();
        ObjectMapper mapper = Jackson.newObjectMapper();
        Gson gson = new GsonBuilder().create();

        if (!config.getEsConfiguration().getHostname().isEmpty() && !published.isEmpty()) {
            StringBuilder builder = new StringBuilder();
            try (RestClient restClient = RestClient
                    .builder(new HttpHost(config.getEsConfiguration().getHostname(), config.getEsConfiguration().getPort(), "http"))
                    .build()) {
                published.forEach(entry -> {
                    Map<String, Map<String, String>> index = new HashMap<>();
                    Map<String, String> internal = new HashMap<>();
                    internal.put("_id", String.valueOf(entry.getId()));
                    internal.put("_type", entry instanceof Tool ? "tool" : "workflow");
                    index.put("index", internal);
                    builder.append(gson.toJson(index));
                    builder.append('\n');
                    try {
                        builder.append(mapper.writeValueAsString(entry));
                    } catch (JsonProcessingException e) {
                        e.printStackTrace();
                    }
                    builder.append('\n');
                });

                //index a document
                try {
                    restClient.performRequest("DELETE", "/entry");
                } catch (Exception e) {
                    e.printStackTrace();
                }

                URL url = Resources.getResource("queries/mapping_tool.json");
                String text = Resources.toString(url, StandardCharsets.UTF_8);
                HttpEntity mappingEntity = new NStringEntity(text, ContentType.APPLICATION_JSON);
                org.elasticsearch.client.Response put = restClient.performRequest("PUT", "/entry", Collections.emptyMap(), mappingEntity);

//                URL url2 = Resources.getResource("queries/mapping_workflow.json");
//                String text2 = Resources.toString(url2, StandardCharsets.UTF_8);
//                HttpEntity mappingEntity2 = new NStringEntity(text2, ContentType.APPLICATION_JSON);
//                restClient.performRequest("PUT", "/entry/_mapping/workflow", Collections.emptyMap(), mappingEntity2);


                HttpEntity bulkEntity = new NStringEntity(builder.toString(), ContentType.APPLICATION_JSON);
                org.elasticsearch.client.Response post = restClient.performRequest("POST", "/entry/_bulk", Collections.emptyMap(), bulkEntity);
                if (post.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                    throw new CustomWebApplicationException("Could not submit index to elastic search",
                            HttpStatus.SC_INTERNAL_SERVER_ERROR);
                }
            } catch (IOException e) {
                throw new CustomWebApplicationException("io exception", HttpStatus.SC_INTERNAL_SERVER_ERROR);
            }
            return Response.ok().entity(published.size()).build();
        }
        return Response.ok().entity(0).build();
    }

    @Override
    public Response toolsIndexSearch(String query, MultivaluedMap<String, String> queryParameters, SecurityContext securityContext) {
        if (!config.getEsConfiguration().getHostname().isEmpty()) {
            try (RestClient restClient = RestClient
                    .builder(new HttpHost(config.getEsConfiguration().getHostname(), config.getEsConfiguration().getPort(), "http"))
                    .build()) {
                HttpEntity entity = query == null ? null : new NStringEntity(query, ContentType.APPLICATION_JSON);
                Map<String, String> parameters = new HashMap<>();
                // TODO: note that this is lossy if there are repeated parameters
                // but it looks like the elastic search http client classes don't handle it
                queryParameters.forEach((key, value) -> parameters.put(key, value.get(0)));
                org.elasticsearch.client.Response get = restClient.performRequest("GET", "/entry/_search", parameters, entity);
                System.out.println("GETT: " + get);
                if (get.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                    throw new CustomWebApplicationException("Could not submit index to elastic search",
                            HttpStatus.SC_INTERNAL_SERVER_ERROR);
                }
                return Response.ok().entity(get.getEntity().getContent()).build();
            } catch (IOException e) {
                throw new CustomWebApplicationException("io exception", HttpStatus.SC_INTERNAL_SERVER_ERROR);
            }
        }
        return Response.ok().entity(0).build();
    }
}
