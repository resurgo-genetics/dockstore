package io.dockstore.webservice.resources.proposedGA4GH;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import io.dockstore.webservice.DockstoreWebserviceApplication;
import io.dropwizard.hibernate.UnitOfWork;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import io.swagger.api.NotFoundException;
import io.swagger.model.Tool;
import org.apache.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GET methods for organization related information on path: /api/ga4gh/v1/tools
 */
@Path(DockstoreWebserviceApplication.GA4GH_API_PATH + "/extended")
@Api("GA4GH")
@Produces({ "application/json", "text/plain" })
public class ToolsExtendedApi {
    private static final Logger LOG = LoggerFactory.getLogger(ToolsExtendedApi.class);
    private final ToolsExtendedApiService delegate = ToolsApiExtendedServiceFactory.getToolsExtendedApi();

    @GET
    @Path("/tools/{organization}")
    @UnitOfWork
    @Produces({ "application/json", "text/plain" })
    @ApiOperation(value = "List tools of an organization", notes = "This endpoint returns tools of an organization. ", response = Tool.class, responseContainer = "List", tags = {
            "GA4GH", })
    @ApiResponses(value = {
            @ApiResponse(code = HttpStatus.SC_OK, message = "An array of Tools of the input organization.", response = Tool.class, responseContainer = "List") })
    public Response toolsOrgGet(
            @ApiParam(value = "An organization, for example `cancercollaboratory`", required = true) @PathParam("organization") String organization,
            @Context SecurityContext securityContext) throws NotFoundException {
        return delegate.toolsOrgGet(organization, securityContext);
    }

    @POST
    @Path("/tools/entry/_search")
    @UnitOfWork
    @Produces({ "application/json" })
    @ApiOperation(value = "Search the index of tools", notes = "This endpoint searches the index for all published tools and workflows. Used by utilities that expect to talk to an elastic search endpoint", tags = { "GA4GH", }, response = String.class)
    @ApiResponses(value = { @ApiResponse(code = HttpStatus.SC_OK, message = "An elastic search result.", response = String.class) })
    public Response toolsIndexSearch(@ApiParam(value = "elastic search query", required = true) String query, @Context UriInfo uriInfo, @Context SecurityContext securityContext) throws NotFoundException {
//        LOG.info(delegate.toolsIndexSearch(query, uriInfo.getQueryParameters(), securityContext));
        System.out.println("Something happened!!!!!!!!: " + delegate.toolsIndexSearch(query, uriInfo.getQueryParameters(), securityContext));
        LOG.error("Something happened");
        return delegate.toolsIndexSearch(query, uriInfo.getQueryParameters(), securityContext);
    }


    @POST
    @Path("/tools/index")
    @UnitOfWork
    @Produces({ "text/plain" })
    @ApiOperation(value = "Update the index of tools", notes = "This endpoint updates the index for all published tools and workflows. ", response = Integer.class, tags = { "GA4GH", })
    @ApiResponses(value = { @ApiResponse(code = HttpStatus.SC_OK, message = "An array of Tools of the input organization.") })
    public Response toolsIndexGet(@Context SecurityContext securityContext) throws NotFoundException {
        return delegate.toolsIndexGet(securityContext);
    }

    @GET
    @Path("/workflows/{organization}")
    @UnitOfWork
    @Produces({ "application/json", "text/plain" })
    @ApiOperation(value = "List workflows of an organization", notes = "This endpoint returns workflows of an organization. ", response = Tool.class, responseContainer = "List", tags = {
            "GA4GH", })
    @ApiResponses(value = {
            @ApiResponse(code = HttpStatus.SC_OK, message = "An array of Tools of the input organization.", response = Tool.class, responseContainer = "List") })
    public Response workflowsOrgGet(
            @ApiParam(value = "An organization, for example `cancercollaboratory`", required = true) @PathParam("organization") String organization,
            @Context SecurityContext securityContext) throws NotFoundException {
        return delegate.workflowsOrgGet(organization, securityContext);
    }

    @GET
    @Path("/containers/{organization}")
    @UnitOfWork
    @Produces({ "application/json", "text/plain" })
    @ApiOperation(value = "List entries of an organization", notes = "This endpoint returns entries of an organization. ", response = Tool.class, responseContainer = "List", tags = {
            "GA4GH", })
    @ApiResponses(value = {
            @ApiResponse(code = HttpStatus.SC_OK, message = "An array of Tools of the input organization.", response = Tool.class, responseContainer = "List") })
    public Response entriesOrgGet(
            @ApiParam(value = "An organization, for example `cancercollaboratory`", required = true) @PathParam("organization") String organizations,
            @Context SecurityContext securityContext) throws NotFoundException {
        return delegate.entriesOrgGet(organizations, securityContext);
    }

    @GET
    @Path("/organizations")
    @UnitOfWork
    @Produces({ "application/json", "text/plain" })
    @ApiOperation(value = "List all organizations", notes = "This endpoint returns list of all organizations. ", response = String.class, responseContainer = "List", tags = {
            "GA4GH", })
    @ApiResponses(value = {
            @ApiResponse(code = HttpStatus.SC_OK, message = "An array of organizations' names.", response = String.class, responseContainer = "List") })
    public Response entriesOrgGet(
            @Context SecurityContext securityContext) {
        return delegate.organizationsGet(securityContext);
    }
}
